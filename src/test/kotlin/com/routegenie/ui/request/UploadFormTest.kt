package com.routegenie.ui.request

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.routegenie.client.BodyTemplateSource
import com.routegenie.client.RequestBody
import com.routegenie.files.GeneratedFileStore
import com.routegenie.files.ImageFormat
import com.routegenie.model.Endpoint
import com.routegenie.model.HttpMethod
import com.routegenie.schema.model.FileSpec
import com.routegenie.schema.model.MultipartTemplate
import com.routegenie.schema.model.TemplatePart
import com.routegenie.settings.BodyFillMode
import com.routegenie.settings.RouteGenieSettings
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Upload bodies in the request form: random files made from what the code allows, the options bar, replacing, and nothing sent. */
class UploadFormTest : BasePlatformTestCase() {
    private val engine = FakeEngine()
    private val ui = ManualUi()
    private val state = com.routegenie.clientide.RouteGenieClientState()
    private val store = MapSecretStore()
    private lateinit var root: Path
    private lateinit var files: GeneratedFileStore
    private var template: MultipartTemplate? = null

    private val source = object : BodyTemplateSource {
        override suspend fun templateFor(endpoint: Endpoint) = null
        override suspend fun multipartTemplateFor(endpoint: Endpoint, random: Boolean) = template
    }

    override fun setUp() {
        super.setUp()
        root = Files.createTempDirectory("rg-upload-test")
        files = GeneratedFileStore(root)
        setOptions(null, "", "", "")
    }

    override fun tearDown() {
        try {
            root.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun section(owner: com.intellij.openapi.Disposable) = RequestSection(
        project, owner, engine = { engine }, bodyTemplates = { source }, ui = ui,
        state = state, secretStore = store, secretExecutor = directExecutor, responses = com.routegenie.client.ResponseSlot(), fileStore = files,
    )

    private fun owner() = Disposer.newDisposable("upload form test")

    private fun photo(spec: FileSpec = FileSpec(image = true, extensions = listOf(".png"))) =
        MultipartTemplate(listOf(TemplatePart("title", "A title", null), TemplatePart("photo", null, spec)))

    private fun setOptions(format: ImageFormat?, width: String, height: String, maxKb: String) {
        val props = com.intellij.ide.util.PropertiesComponent.getInstance()
        props.setValue("routegenie.files.format", format?.name)
        props.setValue("routegenie.files.width", width.ifEmpty { null })
        props.setValue("routegenie.files.height", height.ifEmpty { null })
        props.setValue("routegenie.files.maxKb", maxKb.ifEmpty { null })
    }

    private fun waitFor(condition: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching("timed out", condition, 30)

    private fun fileRow(s: RequestSection) = s.formForTest().partsForTest().first { it.file }

    private fun withMode(mode: BodyFillMode, block: () -> Unit) {
        val settings = RouteGenieSettings.getInstance()
        val before = settings.bodyFillMode
        try {
            settings.bodyFillMode = mode
            block()
        } finally {
            settings.bodyFillMode = before
        }
    }

    fun testRandomBodyMakesARealImageAndTheTextPartsAndSendsNothing() = withMode(BodyFillMode.OFF) {
        val owner = owner()
        try {
            template = photo()
            val s = section(owner)
            s.show(endpoint(HttpMethod.POST, "/upload", emptyList()))
            val form = s.formForTest()
            form.randomButtonForTest().doClick()
            waitFor { form.partsForTest().isNotEmpty() }
            assertEquals(BodyType.MULTIPART, s.currentForm()!!.bodyKind)
            val rows = form.partsForTest()
            assertEquals(listOf("title", "photo"), rows.map { it.name })
            assertEquals("A title", rows[0].value)
            val file = rows[1]
            assertTrue(file.file && file.generated)
            assertEquals("image/png", file.contentType)
            assertTrue(file.fileName!!.endsWith(".png"))
            val path = Path.of(file.value)
            assertTrue(Files.isReadable(path))
            assertNotNull("a real, decodable image", ImageIO.read(path.toFile()))
            assertTrue("the options bar is offered for uploads", form.filesBarVisible())
            assertNotNull(form.generateNoteText())
            assertEquals("nothing is sent by making files", 0, engine.streams)
            val part = (s.currentForm()!!.toInput().body as RequestBody.Multipart).parts.last()
            assertEquals(file.value, part.filePath)
            assertEquals("image/png", part.contentType)
            assertEquals(file.fileName, part.fileName)
        } finally { Disposer.dispose(owner) }
    }

    fun testPressingAgainReplacesTheFileAndCleansTheOldOne() = withMode(BodyFillMode.OFF) {
        val owner = owner()
        try {
            template = photo()
            val s = section(owner)
            s.show(endpoint(HttpMethod.POST, "/upload", emptyList()))
            val form = s.formForTest()
            form.randomButtonForTest().doClick()
            waitFor { form.partsForTest().isNotEmpty() }
            val first = fileRow(s).value
            form.randomButtonForTest().doClick()
            waitFor { form.partsForTest().any { it.file && it.value != first } }
            assertFalse("no question about files this plugin made itself", form.partsReplaceBarVisible())
            assertFalse("the old file is removed", Files.exists(Path.of(first)))
            assertTrue(Files.isReadable(Path.of(fileRow(s).value)))
        } finally { Disposer.dispose(owner) }
    }

    fun testTheUsersOwnRowsAreProtectedUntilTheyConfirm() = withMode(BodyFillMode.OFF) {
        val owner = owner()
        try {
            template = photo()
            val s = section(owner)
            s.show(endpoint(HttpMethod.POST, "/upload", emptyList()))
            val form = s.formForTest()
            s.currentForm()!!.bodyKind = BodyType.MULTIPART
            s.currentForm()!!.parts = listOf(FormRow("mine", "keep me"))
            form.refresh(null)
            form.randomButtonForTest().doClick()
            waitFor { form.partsReplaceBarVisible() }
            assertEquals(listOf("mine"), form.partsForTest().map { it.name })
            form.confirmReplacePartsForTest()
            assertEquals(listOf("title", "photo"), form.partsForTest().map { it.name })
        } finally { Disposer.dispose(owner) }
    }

    fun testTheOptionsBarChoosesFormatAndSizeAndRegenerateAppliesThem() = withMode(BodyFillMode.OFF) {
        val owner = owner()
        try {
            template = photo(FileSpec(image = true))
            val s = section(owner)
            s.show(endpoint(HttpMethod.POST, "/upload", emptyList()))
            val form = s.formForTest()
            form.setFileOptionsForTest(ImageFormat.JPEG, "120", "80", "6")
            form.randomButtonForTest().doClick()
            waitFor { form.partsForTest().isNotEmpty() }
            val first = fileRow(s)
            assertEquals("image/jpeg", first.contentType)
            val image = ImageIO.read(Path.of(first.value).toFile())
            assertEquals(120, image.width)
            assertEquals(80, image.height)
            assertTrue(Files.size(Path.of(first.value)) <= 6 * 1024)
            form.setFileOptionsForTest(ImageFormat.GIF, "64", "64", "")
            form.regenerateButtonForTest().doClick()
            waitFor { form.partsForTest().any { it.file && it.contentType == "image/gif" } }
            assertFalse(Files.exists(Path.of(first.value)))
            val second = ImageIO.read(Path.of(fileRow(s).value).toFile())
            assertEquals(64, second.width)
            assertEquals("the text parts survive a regenerate", "A title", form.partsForTest().first { !it.file }.value)
        } finally { Disposer.dispose(owner) }
    }

    fun testAFieldThatAcceptsOnlyPdfGetsAPdfAndOneWeCannotMakeGetsAnHonestNote() = withMode(BodyFillMode.OFF) {
        val owner = owner()
        try {
            template = photo(FileSpec(extensions = listOf(".pdf")))
            val s = section(owner)
            s.show(endpoint(HttpMethod.POST, "/upload", emptyList()))
            val form = s.formForTest()
            form.randomButtonForTest().doClick()
            waitFor { form.partsForTest().isNotEmpty() }
            assertEquals("application/pdf", fileRow(s).contentType)
            assertTrue(Files.readAllBytes(Path.of(fileRow(s).value)).decodeToString(0, 5).startsWith("%PDF"))

            template = photo(FileSpec(extensions = listOf(".docx")))
            s.show(endpoint(HttpMethod.POST, "/upload-docs", emptyList()))
            val docs = s.formForTest()
            docs.randomButtonForTest().doClick()
            waitFor { docs.partsForTest().isNotEmpty() }
            val row = fileRow(s)
            assertEquals("no file is invented", "", row.value)
            assertFalse(row.generated)
            assertTrue(docs.generateNoteText()!!.contains(".docx"))
        } finally { Disposer.dispose(owner) }
    }

    fun testEditingAGeneratedFilePathMakesItTheUsersOwn() = withMode(BodyFillMode.OFF) {
        val owner = owner()
        try {
            template = photo()
            val s = section(owner)
            s.show(endpoint(HttpMethod.POST, "/upload", emptyList()))
            val form = s.formForTest()
            form.randomButtonForTest().doClick()
            waitFor { form.partsForTest().isNotEmpty() }
            val own = fileRow(s).copy(value = "C:/somewhere/else.png", generated = false, contentType = null, fileName = null)
            s.currentForm()!!.parts = s.currentForm()!!.parts.map { if (it.file) own else it }
            form.refresh(null)
            form.randomButtonForTest().doClick()
            waitFor { form.partsReplaceBarVisible() }
            assertEquals("C:/somewhere/else.png", fileRow(s).value)
        } finally { Disposer.dispose(owner) }
    }

    fun testOpeningAnUploadEndpointFillsItOnItsOwnAccordingToTheMode() {
        for ((mode, filled) in listOf(BodyFillMode.SAMPLE to true, BodyFillMode.RANDOM to true, BodyFillMode.OFF to false)) {
            withMode(mode) {
                val owner = owner()
                try {
                    template = photo()
                    val s = section(owner)
                    s.show(endpoint(HttpMethod.POST, "/upload-$mode", emptyList()))
                    if (filled) {
                        waitFor { s.formForTest().partsForTest().isNotEmpty() }
                        assertTrue("$mode: a file exists", Files.isReadable(Path.of(fileRow(s).value)))
                    } else {
                        Thread.sleep(150)
                        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                        assertTrue(s.formForTest().partsForTest().isEmpty())
                    }
                    assertEquals(0, engine.streams)
                } finally { Disposer.dispose(owner) }
            }
        }
    }

    fun testTheFixedSampleMakesTheSameFileEveryTime() = withMode(BodyFillMode.SAMPLE) {
        val bytes = (0 until 2).map {
            val owner = owner()
            try {
                template = photo()
                val s = section(owner)
                s.show(endpoint(HttpMethod.POST, "/upload-same-$it", emptyList()))
                waitFor { s.formForTest().partsForTest().isNotEmpty() }
                Files.readAllBytes(Path.of(fileRow(s).value)).toList()
            } finally { Disposer.dispose(owner) }
        }
        assertEquals(bytes[0], bytes[1])
    }

    fun testAnEndpointWithoutAnUploadBodyIsLeftAlone() = withMode(BodyFillMode.RANDOM) {
        val owner = owner()
        try {
            template = null
            val s = section(owner)
            s.show(endpoint(HttpMethod.POST, "/plain", emptyList()))
            Thread.sleep(150)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertTrue(s.formForTest().partsForTest().isEmpty())
            assertFalse(s.formForTest().filesBarVisible())
        } finally { Disposer.dispose(owner) }
    }
}
