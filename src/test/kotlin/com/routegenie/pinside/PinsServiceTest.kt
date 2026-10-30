package com.routegenie.pinside

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.routegenie.Branding
import com.routegenie.Limits
import com.routegenie.pins.Pin
import com.routegenie.pins.PinProblem
import com.routegenie.pins.PinsCodec
import com.routegenie.pins.PinsFileProblem
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The pins file on disk: location from the branding, atomic writes, merging with what is on disk, tolerance of a missing,
 * empty, hand edited, broken, CRLF or oversized file, external edits seen through the VFS, and many writers at once.
 */
class PinsServiceTest : BasePlatformTestCase() {
    private lateinit var dir: Path
    private lateinit var service: PinsService

    private val file: Path get() = dir.resolve(Branding.DIR).resolve(PinsService.FILE_NAME)

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("pins-test")
        service = PinsService.getInstance(project)
        service.baseDirOverride = dir
        service.reload()
    }

    override fun tearDown() {
        try {
            service.baseDirOverride = null
            service.dismissed.value.toList().forEach { service.undismiss(it) }
            dir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun pin(expression: String, value: String, note: String = "") = Pin(expression, value, note)

    private fun waitFor(what: String, condition: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out: $what", condition, 30)

    fun testTheFileLivesInTheBrandedProjectFolder() {
        assertEquals(dir.resolve(Branding.DIR).resolve("pins.json"), service.filePath())
    }

    fun testAMissingFileMeansNoPinsAndNoProblem() {
        assertFalse(Files.exists(file))
        assertTrue(service.env.values.isEmpty())
        assertNull(service.snapshot.value.problem)
    }

    fun testSettingAPinWritesTheFileAndTheEnvironment() {
        assertEquals(PinResult.Saved, service.set(pin("settings.API_V1_STR", "/api/v1", "default in app/core/config.py"), requireLeadingSlash = true))
        val read = PinsCodec.read(String(Files.readAllBytes(file), Charsets.UTF_8))
        assertEquals(listOf("settings.API_V1_STR"), read.pins.map { it.expression })
        assertEquals("/api/v1", read.pins.single().value)
        assertEquals("default in app/core/config.py", read.pins.single().note)
        assertTrue("the date is stamped", read.pins.single().at.matches(Regex("""\d{4}-\d{2}-\d{2}""")))
        assertEquals(mapOf("settings.API_V1_STR" to "/api/v1"), service.env.values)
    }

    fun testNoTemporaryFileIsLeftBehind() {
        service.set(pin("settings.A", "/a"), true)
        service.set(pin("settings.B", "/b"), true)
        service.remove("settings.A")
        val names = Files.list(file.parent).use { stream -> stream.map { it.fileName.toString() }.toList() }
        assertEquals(listOf("pins.json"), names)
    }

    fun testSettingTheSameValueTwiceLeavesTheBytesAsTheyAre() {
        service.set(pin("settings.A", "/a"), true)
        val first = Files.readAllBytes(file)
        service.set(pin("settings.A", "/a"), true)
        assertEquals(first.toList(), Files.readAllBytes(file).toList())
    }

    fun testChangingAndRemovingAPin() {
        service.set(pin("settings.A", "/a"), true)
        service.set(pin("settings.B", "/b"), true)
        service.set(pin("settings.A", "/a2"), true)
        assertEquals(mapOf("settings.A" to "/a2", "settings.B" to "/b"), service.env.values)
        assertEquals(PinResult.Saved, service.remove("settings.A"))
        assertEquals(mapOf("settings.B" to "/b"), service.env.values)
        assertEquals("removing a pin that is not there is fine", PinResult.Saved, service.remove("settings.NOPE"))
    }

    fun testAPinForASecretLookingNameIsRefusedAndNothingIsWritten() {
        assertEquals(PinResult.Refused(PinProblem.EXPRESSION_SECRET), service.set(pin("settings.SECRET_KEY", "/x"), true))
        assertEquals(PinResult.Refused(PinProblem.EXPRESSION_SECRET), service.set(pin("os.environ[\"DB_PASSWORD\"]", "/x"), true))
        assertFalse(Files.exists(file))
    }

    fun testInvalidValuesAreRefusedAndNothingIsWritten() {
        assertEquals(PinResult.Refused(PinProblem.VALUE_SLASH_START), service.set(pin("settings.A", "api"), true))
        assertEquals(PinResult.Refused(PinProblem.VALUE_SLASH_END), service.set(pin("settings.A", "/api/"), true))
        assertEquals(PinResult.Refused(PinProblem.VALUE_BLANK_CHARS), service.set(pin("settings.A", "/a b"), true))
        assertEquals(PinResult.Refused(PinProblem.VALUE_DOTS), service.set(pin("settings.A", "/a/../b"), true))
        assertEquals(PinResult.Refused(PinProblem.VALUE_TOO_LONG), service.set(pin("settings.A", "/" + "a".repeat(Limits.PIN_MAX_VALUE_CHARS)), true))
        assertFalse(Files.exists(file))
        assertEquals(PinResult.Saved, service.set(pin("settings.DJ", "api/"), requireLeadingSlash = false))
    }

    fun testAnEmptyValueIsStoredAsARealPin() {
        assertEquals(PinResult.Saved, service.set(pin("settings.BASE_PATH", ""), true))
        assertEquals(mapOf("settings.BASE_PATH" to ""), service.env.values)
    }

    fun testAnExternalEditMadeSinceTheLastReadIsMergedNotLost() {
        service.set(pin("settings.A", "/a"), true)
        // A teammate's checkout adds a pin; this IDE has not noticed yet.
        Files.write(file, PinsCodec.write(listOf(pin("settings.A", "/a"), pin("settings.THEIRS", "/theirs"))).toByteArray())
        service.set(pin("settings.B", "/b"), true)
        val read = PinsCodec.read(String(Files.readAllBytes(file), Charsets.UTF_8))
        assertEquals(listOf("settings.A", "settings.B", "settings.THEIRS"), read.pins.map { it.expression })
    }

    fun testAnEmptyFileIsNoProblemAndTheNextPinIsWritten() {
        Files.createDirectories(file.parent)
        Files.write(file, ByteArray(0))
        service.reload()
        assertNull(service.snapshot.value.problem)
        assertEquals(PinResult.Saved, service.set(pin("settings.A", "/a"), true))
    }

    fun testABrokenFileIsOneMessageNoPinsAndIsNeverOverwritten() {
        Files.createDirectories(file.parent)
        val broken = "{ \"pins\": { oops"
        Files.write(file, broken.toByteArray())
        service.reload()
        val state = service.snapshot.value
        assertEquals(PinsFileProblem.BROKEN, state.problem)
        assertTrue(state.pins.isEmpty())
        assertTrue(service.env.values.isEmpty())
        val result = service.set(pin("settings.A", "/a"), true)
        assertEquals(PinResult.Failed(PinsFileProblem.BROKEN, null), result)
        assertEquals("the user's text is not replaced", broken, String(Files.readAllBytes(file), Charsets.UTF_8))
    }

    fun testFixingTheBrokenFileBringsThePinsBack() {
        Files.createDirectories(file.parent)
        Files.write(file, "nope".toByteArray())
        service.reload()
        assertEquals(PinsFileProblem.BROKEN, service.snapshot.value.problem)
        Files.write(file, PinsCodec.write(listOf(pin("settings.A", "/a"))).toByteArray())
        service.reload()
        assertNull(service.snapshot.value.problem)
        assertEquals(mapOf("settings.A" to "/a"), service.env.values)
    }

    fun testAHandEditedFileKeepsItsValidEntriesAndCountsTheOthers() {
        Files.createDirectories(file.parent)
        Files.write(file, """{"pins": {"settings.A": "/a", "settings.SECRET": {"value": "/x"}, "settings.B": {"value": "/b c"}}}""".toByteArray())
        service.reload()
        assertEquals(mapOf("settings.A" to "/a"), service.env.values)
        assertEquals(2, service.snapshot.value.skippedCount)
        assertNull(service.snapshot.value.problem)
    }

    fun testACrlfFileKeepsItsLineEndingsWhenAPinIsAdded() {
        Files.createDirectories(file.parent)
        Files.write(file, PinsCodec.write(listOf(pin("settings.A", "/a")), "\r\n").toByteArray())
        service.reload()
        service.set(pin("settings.B", "/b"), true)
        val text = String(Files.readAllBytes(file), Charsets.UTF_8)
        assertTrue(text.contains("\r\n"))
        assertFalse(text.replace("\r\n", "").contains('\n'))
        assertEquals(listOf("settings.A", "settings.B"), PinsCodec.read(text).pins.map { it.expression })
    }

    fun testANewerFileVersionIsReadButNeverRewritten() {
        Files.createDirectories(file.parent)
        val newer = """{"version": 9, "pins": {"settings.A": {"value": "/a"}}}"""
        Files.write(file, newer.toByteArray())
        service.reload()
        assertEquals(mapOf("settings.A" to "/a"), service.env.values)
        assertEquals(PinResult.Failed(PinsFileProblem.NEWER_VERSION, null), service.set(pin("settings.B", "/b"), true))
        assertEquals(newer, String(Files.readAllBytes(file), Charsets.UTF_8))
    }

    fun testAnOversizedFileIsIgnoredWithOneMessage() {
        Files.createDirectories(file.parent)
        Files.write(file, ByteArray(Limits.PINS_FILE_MAX_BYTES + 10) { ' '.code.toByte() })
        service.reload()
        assertEquals(PinsFileProblem.TOO_LARGE, service.snapshot.value.problem)
        assertTrue(service.env.values.isEmpty())
    }

    fun testSomethingThatIsNotAFileIsTreatedAsNoPins() {
        Files.createDirectories(file)
        service.reload()
        assertTrue(service.env.values.isEmpty())
        assertNull(service.snapshot.value.problem)
    }

    fun testNotesWithWindowsPathsAndQuotesSurviveTheRoundTrip() {
        val note = "default in C:\\work\\app\\config.py \"line 12\""
        service.set(pin("settings.A", "/a", note), true)
        service.reload()
        assertEquals(note, service.snapshot.value.pins.single().note)
    }

    fun testManyWritersAtOnceLoseNothing() {
        val writers = 16
        val pool = Executors.newFixedThreadPool(writers)
        val start = CountDownLatch(1)
        val done = CountDownLatch(writers)
        try {
            repeat(writers) { i ->
                pool.execute {
                    start.await()
                    service.set(pin("settings.P$i", "/p$i"), true)
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue("writers finished", done.await(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        val read = PinsCodec.read(String(Files.readAllBytes(file), Charsets.UTF_8))
        assertTrue(read.isClean)
        assertEquals((0 until writers).map { "settings.P$it" }.sorted(), read.pins.map { it.expression })
        assertEquals(writers, service.env.values.size)
    }

    fun testAnExternalEditIsSeenThroughTheVfsListener() {
        service.set(pin("settings.A", "/a"), true)
        val local = LocalFileSystem.getInstance()
        val vFile = local.refreshAndFindFileByNioFile(file) ?: error("file not in the VFS")
        Files.write(file, PinsCodec.write(listOf(pin("settings.A", "/changed"), pin("settings.NEW", "/new"))).toByteArray())
        VfsUtil.markDirtyAndRefresh(false, false, false, vFile)
        waitFor("external edit applied") { service.env.values["settings.A"] == "/changed" }
        assertEquals(mapOf("settings.A" to "/changed", "settings.NEW" to "/new"), service.env.values)
    }

    fun testDeletingTheFileExternallyRemovesThePins() {
        service.set(pin("settings.A", "/a"), true)
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) ?: error("file not in the VFS")
        Files.delete(file)
        VfsUtil.markDirtyAndRefresh(false, false, false, vFile)
        waitFor("deleted file applied") { service.env.values.isEmpty() }
    }

    fun testNotNowIsRememberedPerExpressionAndBounded() {
        service.dismiss("settings.A")
        service.dismiss("settings.B")
        assertEquals(setOf("settings.A", "settings.B"), service.dismissed.value)
        service.undismiss("settings.A")
        assertEquals(setOf("settings.B"), service.dismissed.value)
        repeat(Limits.PINS_MAX_DISMISSED + 25) { service.dismiss("settings.X$it") }
        assertEquals(Limits.PINS_MAX_DISMISSED, service.dismissed.value.size)
        assertTrue("the oldest are dropped first", "settings.B" !in service.dismissed.value)
        assertFalse("nothing about it reaches the shared file", Files.exists(file))
    }
}
