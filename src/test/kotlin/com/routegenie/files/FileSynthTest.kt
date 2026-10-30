package com.routegenie.files

import com.routegenie.json.JsonContent
import com.routegenie.json.JsonScanner
import com.routegenie.json.ScanStatus
import com.routegenie.testing.LeakProbe
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.random.Random

class FileSynthTest {
    private val types = listOf("text/plain", "text/csv", "application/json", "application/pdf", "application/zip", "application/octet-stream")

    private fun make(type: String, size: Long?, seed: Int = 1) = FileSynth.generate(type, size, Random(seed))

    // ---- validators ------------------------------------------------------------------------------------------------

    private fun assertValidJson(bytes: ByteArray) {
        JsonScanner.scan(JsonContent.Bytes(bytes)).use { index ->
            assertEquals(String(bytes, Charsets.US_ASCII), ScanStatus.COMPLETE, index.status)
            assertNull(index.error?.toString(), index.error)
        }
    }

    private fun assertValidZip(bytes: ByteArray): Pair<String, ByteArray> {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val entry = zip.nextEntry
            assertNotNull("no zip entry", entry)
            val content = zip.readAllBytes() // verifies the CRC
            assertNull("more than one entry", zip.nextEntry)
            return entry.name to content
        }
        // ZipInputStream does not read the central directory; the file API does and checks the end record.
    }

    private fun assertZipCentralDirectory(bytes: ByteArray) {
        val file = File.createTempFile("routegenie-zip", ".zip")
        try {
            file.writeBytes(bytes)
            java.util.zip.ZipFile(file).use { zip ->
                assertEquals(1, zip.size())
                val entry = zip.entries().nextElement()
                zip.getInputStream(entry).use { assertEquals(entry.size, it.readAllBytes().size.toLong()) }
            }
        } finally {
            file.delete()
        }
    }

    /** Header, trailer, every xref offset pointing at its object, startxref pointing at xref, stream length correct. */
    private fun assertPdfStructure(bytes: ByteArray) {
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1."))
        assertTrue(text.endsWith("%%EOF\n"))
        val startxref = Regex("startxref\\n(\\d+)\\n%%EOF\\n$").find(text)!!.groupValues[1].toInt()
        assertTrue("startxref $startxref does not point at xref", text.startsWith("xref\n0 6\n", startxref))
        val entries = Regex("(\\d{10}) (\\d{5}) ([nf]) \\n").findAll(text.substring(startxref)).toList()
        assertEquals(6, entries.size)
        for (i in 1..5) {
            val offset = entries[i].groupValues[1].toInt()
            assertTrue("object $i offset $offset", text.startsWith("$i 0 obj\n", offset))
        }
        val length = Regex("/Length (\\d+) >>\\nstream\\n").find(text)!!
        val start = length.range.last + 1
        assertTrue(text.startsWith("\nendstream", start + length.groupValues[1].toInt()))
        assertTrue(text.contains("/Type /Catalog") && text.contains("/Count 1") && text.contains("Tj"))
    }

    private fun assertValidCsv(bytes: ByteArray) {
        val lines = String(bytes, Charsets.US_ASCII).also { assertTrue(it.endsWith("\n")) }.removeSuffix("\n").split("\n")
        assertEquals("id,name,email,score,note", lines.first())
        assertTrue(lines.size >= 2)
        for (line in lines.drop(1)) assertEquals(line, 5, line.split(",").size)
    }

    private fun assertValid(type: String, bytes: ByteArray) {
        when (type) {
            "text/plain" -> assertTrue(bytes.all { it in 9..126 })
            "text/csv" -> assertValidCsv(bytes)
            "application/json" -> assertValidJson(bytes)
            "application/pdf" -> assertPdfStructure(bytes)
            "application/zip" -> {
                assertValidZip(bytes)
                assertZipCentralDirectory(bytes)
            }
        }
    }

    // ---- tests -----------------------------------------------------------------------------------------------------

    @Test
    fun `every type without a size is a valid file`() {
        for (type in types) {
            val file = make(type, null)!!
            assertEquals(type, file.mime)
            assertNull(file.width)
            assertNull(file.height)
            assertTrue(type, file.bytes.isNotEmpty())
            assertValid(type, file.bytes)
        }
    }

    @Test
    fun `every type hits an exact size or says it cannot`() {
        val failures = ArrayList<String>()
        for (type in types) for (size in listOf(0L, 1L, 2L, 3L, 7L, 10L, 25L, 33L, 64L, 100L, 250L, 700L, 1_000L, 4_321L, 65_537L, 1_000_000L)) {
            val file = make(type, size, seed = size.toInt())
            if (file == null) {
                // null is only acceptable for a size below the smallest valid file of the type
                val minimum = when (type) {
                    "application/octet-stream" -> -1L
                    "text/plain" -> 0L
                    "application/json" -> 1L
                    "text/csv" -> 46L
                    "application/pdf" -> 600L
                    else -> 100L
                }
                if (size > minimum) failures.add("$type refused $size although it is above $minimum")
                continue
            }
            assertEquals("$type $size", size.toInt(), file.bytes.size)
            if (size > 0 || type != "application/octet-stream") assertValid(type, file.bytes)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `smallest sizes per type`() {
        val minimum = StringBuilder()
        for (type in types) {
            val smallest = (0L..2_000L).first { make(type, it) != null }
            minimum.append("$type=$smallest ")
            if (smallest > 0) assertNull(type, make(type, smallest - 1))
        }
        println("smallest exact sizes: $minimum")
    }

    @Test
    fun `lookup by extension and by mime gives the same kind`() {
        for ((ext, mime) in listOf("txt" to "text/plain", ".CSV" to "text/csv", "json" to "application/json", ".pdf" to "application/pdf",
            "ZIP" to "application/zip", "bin" to "application/octet-stream", "APPLICATION/PDF" to "application/pdf")) {
            assertEquals(ext, mime, make(ext, 2_000)!!.mime)
        }
        assertEquals("pdf", make("application/pdf; charset=binary", null)!!.extension)
    }

    @Test
    fun `types it cannot make validly give null, never a pretend file`() {
        for (type in listOf("image/svg+xml", "image/webp", "image/*", "*/*", "video/mp4", "application/x-unknown", "application/msword", "xyz", "", ".exe", "text/html")) {
            assertNull(type, make(type, null))
            assertNull(type, make(type, 1_000))
            assertFalse(FileSynth.supports(type))
        }
    }

    private fun assertFalse(value: Boolean) = assertTrue(!value)

    @Test
    fun `image types are delegated to the image maker`() {
        val png = make("image/png", 200_000)!!
        assertEquals("image/png", png.mime)
        assertTrue(png.bytes.size <= 200_000 && 200_000 - png.bytes.size <= 11)
        assertNotNull(javax.imageio.ImageIO.read(ByteArrayInputStream(png.bytes)))
        assertNull(make("image/png", 10))
        assertTrue(FileSynth.supports("image/png"))
        assertTrue(FileSynth.supports(".jpg"))
        assertTrue(FileSynth.supports("application/pdf"))
    }

    @Test
    fun `sizes above the cap are a limit error`() {
        val e = runCatching { make("text/plain", com.routegenie.Limits.FILE_MAX_BYTES + 1) }.exceptionOrNull()
        assertTrue("$e", e is FileLimitExceeded)
        assertTrue(runCatching { make("text/plain", -1) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(com.routegenie.Limits.FILE_MAX_BYTES.toInt(), make("application/octet-stream", com.routegenie.Limits.FILE_MAX_BYTES)!!.bytes.size)
    }

    @Test
    fun `a seed gives the same bytes and another seed differs`() {
        for (type in types) {
            assertArrayEquals(type, make(type, null, 5)!!.bytes, make(type, null, 5)!!.bytes)
            assertArrayEquals(type, make(type, 3_000, 5)!!.bytes, make(type, 3_000, 5)!!.bytes)
            val others = (6..10).map { make(type, 3_000, it)!!.bytes.toList() }.toSet()
            assertEquals("$type differs by seed", 5, others.size)
        }
    }

    @Test
    fun `zip holds one readable entry of the asked size`() {
        val file = make("application/zip", 5_000)!!
        val (name, content) = assertValidZip(file.bytes)
        assertTrue(name.endsWith(".txt"))
        assertTrue(content.isNotEmpty())
        val plain = make("application/zip", null)!!
        assertEquals(1, ZipInputStream(ByteArrayInputStream(plain.bytes)).use { z -> generateSequence { z.nextEntry }.count() })
    }

    @Test
    fun `pdf padding keeps the structure valid for tiny and large gaps`() {
        val base = (0L..3_000L).first { make("application/pdf", it) != null }
        for (extra in listOf(0L, 1L, 2L, 3L, 100L, 100_000L)) {
            val file = make("application/pdf", base + extra)!!
            assertEquals(base + extra, file.bytes.size.toLong())
            assertPdfStructure(file.bytes)
        }
    }

    @Test
    fun `json is parsed by the project reader and a tiny size is whitespace padding`() {
        for (size in listOf(2L, 3L, 5L, 50L, 99L, 100L, 101L, 500L)) {
            val file = make("application/json", size, seed = 3)!!
            assertEquals(size.toInt(), file.bytes.size)
            assertValidJson(file.bytes)
        }
        assertNull(make("application/json", 1))
    }

    @Test
    fun `pdf is accepted by pypdf when python with pypdf is available`() {
        val python = listOf("python", "python3").firstOrNull { exe ->
            runCatching {
                val p = ProcessBuilder(exe, "-I", "-c", "import pypdf").redirectErrorStream(true).start()
                p.inputStream.readAllBytes()
                p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
            }.getOrDefault(false)
        }
        assumeTrue("no python with pypdf; structure was checked by hand-written parsing", python != null)
        val dir = java.nio.file.Files.createTempDirectory("routegenie-pdf").toFile()
        try {
            val base = (0L..3_000L).first { make("application/pdf", it, 2) != null }
            val files = listOf(null, base, base + 1, 5_000L, 123_457L).map { size ->
                File(dir, "f${size ?: "plain"}.pdf").also { it.writeBytes(make("application/pdf", size, 2)!!.bytes) }
            }
            for (pdf in files) {
                val script = "import sys, pypdf\nr = pypdf.PdfReader(sys.argv[1], strict=True)\n" +
                    "assert len(r.pages) == 1\nt = r.pages[0].extract_text()\nassert len(t) > 0\nprint(len(t))"
                val p = ProcessBuilder(python, "-I", "-c", script, pdf.absolutePath).redirectErrorStream(true).start()
                val out = String(p.inputStream.readAllBytes())
                assertTrue("pypdf rejected ${pdf.name}: $out", p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `nothing is retained between calls`() {
        var seed = 0
        for (type in types) {
            LeakProbe.assertNoHeapGrowth("file $type", maxGrowthBytes = 2L * 1024 * 1024, warmups = 20, iterations = 200) {
                FileSynth.generate(type, 20_000, Random(seed++))
            }
        }
        assertNotEquals(0, seed)
    }
}
