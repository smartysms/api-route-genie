package com.routegenie.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageFormatTest {
    @Test
    fun `mime types are found case-insensitively with parameters and aliases`() {
        assertEquals(ImageFormat.PNG, ImageFormat.fromMime("IMAGE/PNG"))
        assertEquals(ImageFormat.PNG, ImageFormat.fromMime(" image/png ; q=0.8"))
        assertEquals(ImageFormat.JPEG, ImageFormat.fromMime("image/jpeg"))
        assertEquals(ImageFormat.JPEG, ImageFormat.fromMime("image/jpg"))
        assertEquals(ImageFormat.GIF, ImageFormat.fromMime("image/gif"))
        assertEquals(ImageFormat.BMP, ImageFormat.fromMime("image/x-ms-bmp"))
    }

    @Test
    fun `unsupported mime types give null`() {
        for (mime in listOf("image/svg+xml", "image/webp", "image/*", "application/pdf", "", "png", "image/", "image/tiff")) {
            assertNull(mime, ImageFormat.fromMime(mime))
        }
    }

    @Test
    fun `extensions work with or without the dot in any case`() {
        assertEquals(ImageFormat.JPEG, ImageFormat.fromExtension(".JPG"))
        assertEquals(ImageFormat.JPEG, ImageFormat.fromExtension("jpeg"))
        assertEquals(ImageFormat.JPEG, ImageFormat.fromExtension("JPE"))
        assertEquals(ImageFormat.PNG, ImageFormat.fromExtension(" .Png "))
        assertEquals(ImageFormat.GIF, ImageFormat.fromExtension("gif"))
        assertEquals(ImageFormat.BMP, ImageFormat.fromExtension("BMP"))
        assertNull(ImageFormat.fromExtension("svg"))
        assertNull(ImageFormat.fromExtension("webp"))
        assertNull(ImageFormat.fromExtension(""))
        assertNull(ImageFormat.fromExtension("."))
    }

    @Test
    fun `accept lists read wildcards, mime types and extensions`() {
        assertEquals(ImageFormat.entries.toList(), ImageFormat.parseAllowed("image/*"))
        assertEquals(ImageFormat.entries.toList(), ImageFormat.parseAllowed("*/*"))
        assertEquals(ImageFormat.entries.toList(), ImageFormat.parseAllowed("*"))
        assertEquals(listOf(ImageFormat.JPEG, ImageFormat.PNG), ImageFormat.parseAllowed("image/jpeg, .png, image/svg+xml"))
        assertEquals(listOf(ImageFormat.JPEG), ImageFormat.parseAllowed("jpeg"))
        assertEquals(listOf(ImageFormat.PNG, ImageFormat.GIF), ImageFormat.parseAllowed("IMAGE/PNG;q=0.8 ,\n.GIF, image/png"))
        assertEquals(listOf(ImageFormat.PNG), ImageFormat.parseAllowed(listOf("image/png", ".PNG", "")))
    }

    @Test
    fun `a list with no writable image type is empty`() {
        assertTrue(ImageFormat.parseAllowed("application/pdf").isEmpty())
        assertTrue(ImageFormat.parseAllowed("image/webp, image/svg+xml, .tiff").isEmpty())
        assertTrue(ImageFormat.parseAllowed("").isEmpty())
        assertTrue(ImageFormat.parseAllowed("  ,, ").isEmpty())
    }

    @Test
    fun `webp is not offered because the JDK cannot write it`() {
        assertTrue(ImageFormat.entries.none { it.name == "WEBP" })
        assertTrue(javax.imageio.ImageIO.getImageWritersByFormatName("webp").asSequence().none())
    }

    @Test
    fun `image io names match the format`() {
        for (f in ImageFormat.entries) {
            assertTrue(f.name, javax.imageio.ImageIO.getImageReadersByFormatName(f.imageIoName).hasNext())
        }
    }
}
