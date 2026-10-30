package com.routegenie.files

import com.routegenie.schema.model.FileSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.random.Random

class FileMakerTest {
    private fun ok(spec: FileSpec, choice: FileChoice = FileChoice(), seed: Int = 1): MadeFile.Ok {
        val made = FileMaker.make(spec, choice, Random(seed))
        assertTrue("expected a file, got ${(made as? MadeFile.Refused)?.bundleKey}", made is MadeFile.Ok)
        return made as MadeFile.Ok
    }

    private fun refused(spec: FileSpec, choice: FileChoice = FileChoice()): MadeFile.Refused {
        val made = FileMaker.make(spec, choice, Random(1))
        assertTrue("expected a refusal", made is MadeFile.Refused)
        return made as MadeFile.Refused
    }

    private fun decoded(file: MadeFile.Ok) = ImageIO.read(ByteArrayInputStream(file.file.bytes))

    @Test
    fun `a field that says nothing gets a real image`() {
        for (seed in 0 until 30) {
            val file = ok(FileSpec(), seed = seed)
            assertNotNull(decoded(file))
            assertTrue(file.name.endsWith("." + file.file.extension))
            assertTrue(file.file.size > 0)
        }
    }

    @Test
    fun `the image flag and the declared extensions decide the format`() {
        for (seed in 0 until 20) {
            val png = ok(FileSpec(image = true, extensions = listOf(".png")), seed = seed)
            assertEquals("png", png.file.extension)
            val jpeg = ok(FileSpec(extensions = listOf(".jpg", ".jpeg")), seed = seed)
            assertEquals("jpg", jpeg.file.extension)
            val accept = ok(FileSpec(accept = listOf("image/gif", "image/bmp")), seed = seed)
            assertTrue(accept.file.extension in setOf("gif", "bmp"))
        }
    }

    @Test
    fun `the user's format and size choices are honoured inside what is allowed`() {
        val file = ok(FileSpec(image = true), FileChoice(format = ImageFormat.JPEG, width = 123, height = 77))
        val image = decoded(file)
        assertEquals(123, image.width)
        assertEquals(77, image.height)
        assertEquals("jpg", file.file.extension)
        // A format the code does not allow is ignored, never forced.
        val png = ok(FileSpec(extensions = listOf(".png")), FileChoice(format = ImageFormat.JPEG))
        assertEquals("png", png.file.extension)
    }

    @Test
    fun `declared dimension limits and the byte limit shrink or clamp the image`() {
        val small = ok(FileSpec(image = true, maxWidth = 50, maxHeight = 40), FileChoice(width = 500, height = 500))
        val image = decoded(small)
        assertTrue(image.width <= 50 && image.height <= 40)
        val large = ok(FileSpec(image = true, minWidth = 300, minHeight = 300), FileChoice(width = 10, height = 10))
        assertTrue(decoded(large).width >= 300 && decoded(large).height >= 300)
        val capped = ok(FileSpec(image = true, maxBytes = 3_000), FileChoice(format = ImageFormat.PNG, width = 512, height = 512))
        assertTrue("size ${capped.file.size}", capped.file.size <= 3_000)
        val byUser = ok(FileSpec(image = true), FileChoice(format = ImageFormat.JPEG, width = 800, height = 600, maxKb = 4))
        assertTrue("size ${byUser.file.size}", byUser.file.size <= 4 * 1024)
    }

    @Test
    fun `a field that accepts only other types gets a valid file of that type or a reason`() {
        assertEquals("pdf", ok(FileSpec(extensions = listOf(".pdf"))).file.extension)
        assertEquals("application/pdf", ok(FileSpec(accept = listOf("application/pdf"))).file.mime)
        assertEquals("csv", ok(FileSpec(extensions = listOf(".csv"))).file.extension)
        assertEquals("zip", ok(FileSpec(accept = listOf("application/zip"))).file.extension)
        assertTrue(ok(FileSpec(accept = listOf("*/*"), extensions = listOf(".bin"))).file.size >= 0)
        assertEquals("files.refused.no_generator", refused(FileSpec(extensions = listOf(".docx"))).bundleKey)
        assertEquals("files.refused.no_generator", refused(FileSpec(accept = listOf("video/mp4"))).bundleKey)
        assertEquals("files.refused.no_generator", refused(FileSpec(accept = listOf("image/webp"))).bundleKey)
    }

    @Test
    fun `an image field whose allowed types contain no image gets nothing`() {
        assertEquals("files.refused.image_conflict", refused(FileSpec(image = true, extensions = listOf(".pdf"))).bundleKey)
    }

    @Test
    fun `a size that is too big for the limits is shrunk, and one that cannot fit is explained, not faked`() {
        val shrunk = ok(FileSpec(extensions = listOf(".bmp")), FileChoice(width = 400, height = 400, maxKb = 1))
        assertTrue("size ${shrunk.file.size}", shrunk.file.size <= 1024)
        val clamped = ok(FileSpec(image = true), FileChoice(width = 100_000, height = 10))
        assertTrue(decoded(clamped).width <= 8_192)
        val none = refused(FileSpec(image = true), FileChoice(maxKb = 0))
        assertTrue(none.bundleKey.startsWith("files."))
    }

    @Test
    fun `the same seed gives the same file and another seed another one`() {
        val a = ok(FileSpec(), seed = 5)
        val b = ok(FileSpec(), seed = 5)
        val c = ok(FileSpec(), seed = 6)
        assertEquals(a.name, b.name)
        assertTrue(a.file.bytes.contentEquals(b.file.bytes))
        assertFalse(a.file.bytes.contentEquals(c.file.bytes))
    }

    @Test
    fun `the store writes readable files and sweeps old ones`() {
        val root = Files.createTempDirectory("rg-store-test")
        try {
            val store = GeneratedFileStore(root)
            val made = ok(FileSpec(extensions = listOf(".png")))
            val first = store.write(made)
            val second = store.write(made)
            assertTrue(Files.isReadable(first) && first.fileName.toString() == made.name)
            assertTrue("same name, different folders", first != second)
            store.touch(first, Instant.now().minus(Duration.ofDays(30)))
            store.sweep()
            assertFalse(Files.exists(first))
            assertTrue(Files.exists(second))
            store.delete(second)
            assertFalse(Files.exists(second))
            // Outside the root nothing is ever deleted.
            val outside = Files.createTempFile("rg-keep", ".txt")
            store.delete(outside)
            assertTrue(Files.exists(outside))
            Files.deleteIfExists(outside)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
