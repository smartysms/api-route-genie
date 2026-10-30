package com.routegenie.files

import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.random.Random

/**
 * One encoder per format. Each returns the smallest valid file for the pixels and, where the format has a legal place for
 * it, can add [padding] bytes of random data: a private PNG chunk, JPEG comment segments, GIF comment extensions. The padded
 * size is exactly the unpadded size plus what [padSteps] says, never more.
 */
internal object ImageEncoders {
    fun bmpSize(width: Int, height: Int): Long = BMP_HEADER + bmpRow(width).toLong() * height

    private fun bmpRow(width: Int): Int = (width * 3 + 3) and 3.inv()

    // ---- BMP: 24 bit, bottom-up, no compression. No padding is legal, so none is offered. --------------------------

    fun bmp(pixels: IntArray, width: Int, height: Int): ByteArray {
        val row = bmpRow(width)
        val out = ByteArray(bmpSize(width, height).toInt())
        out[0] = 'B'.code.toByte()
        out[1] = 'M'.code.toByte()
        putLe(out, 2, out.size)
        putLe(out, 10, BMP_HEADER.toInt())
        putLe(out, 14, BMP_INFO_HEADER)
        putLe(out, 18, width)
        putLe(out, 22, height)
        out[26] = 1
        out[28] = BMP_BITS.toByte()
        putLe(out, 34, row * height)
        putLe(out, 38, BMP_PIXELS_PER_METER)
        putLe(out, 42, BMP_PIXELS_PER_METER)
        for (y in 0 until height) {
            var at = BMP_HEADER.toInt() + (height - 1 - y) * row
            val base = y * width
            for (x in 0 until width) {
                val p = pixels[base + x]
                out[at++] = p.toByte()
                out[at++] = (p shr 8).toByte()
                out[at++] = (p shr 16).toByte()
            }
        }
        return out
    }

    // ---- PNG: RGB 8 bit, Sub filter, zlib level 1. Padding: one private ancillary chunk before IDAT. ----------------

    /** [padding] is the number of bytes to add (0 or at least [PNG_CHUNK_OVERHEAD]); a smaller remainder is not used. */
    fun png(pixels: IntArray, width: Int, height: Int, padding: Int, random: Random): ByteArray {
        val rowBytes = width * 3
        val raw = ByteArray((rowBytes + 1) * height)
        var at = 0
        for (y in 0 until height) {
            raw[at++] = 1 // filter type Sub: each byte minus the byte one pixel to the left
            val base = y * width
            var pr = 0
            var pg = 0
            var pb = 0
            for (x in 0 until width) {
                val p = pixels[base + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                raw[at++] = (r - pr).toByte()
                raw[at++] = (g - pg).toByte()
                raw[at++] = (b - pb).toByte()
                pr = r
                pg = g
                pb = b
            }
        }
        val idat = deflate(raw)
        val padData = if (padding >= PNG_CHUNK_OVERHEAD) padding - PNG_CHUNK_OVERHEAD else 0
        val out = ByteArray(PNG_SIGNATURE.size + PNG_IHDR_TOTAL + (if (padding >= PNG_CHUNK_OVERHEAD) PNG_CHUNK_OVERHEAD + padData else 0) +
            PNG_CHUNK_OVERHEAD + idat.size + PNG_CHUNK_OVERHEAD)
        var o = 0
        System.arraycopy(PNG_SIGNATURE, 0, out, o, PNG_SIGNATURE.size)
        o += PNG_SIGNATURE.size
        val ihdr = ByteArray(PNG_IHDR_DATA)
        putBe(ihdr, 0, width)
        putBe(ihdr, 4, height)
        ihdr[8] = 8 // bit depth
        ihdr[9] = 2 // colour type: RGB
        o = chunk(out, o, "IHDR", ihdr, ihdr.size)
        if (padding >= PNG_CHUNK_OVERHEAD) {
            val data = ByteArray(padData)
            random.nextBytes(data)
            o = chunk(out, o, PNG_PAD_CHUNK, data, data.size)
        }
        o = chunk(out, o, "IDAT", idat, idat.size)
        chunk(out, o, "IEND", ByteArray(0), 0)
        return out
    }

    private fun chunk(out: ByteArray, offset: Int, type: String, data: ByteArray, length: Int): Int {
        putBe(out, offset, length)
        for (i in 0 until 4) out[offset + 4 + i] = type[i].code.toByte()
        System.arraycopy(data, 0, out, offset + 8, length)
        val crc = CRC32()
        crc.update(out, offset + 4, 4 + length)
        putBe(out, offset + 8 + length, crc.value.toInt())
        return offset + PNG_CHUNK_OVERHEAD + length
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(PNG_DEFLATE_LEVEL)
        try {
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream(raw.size / DEFLATE_GUESS_RATIO + DEFLATE_GUESS_MIN)
            val buffer = ByteArray(DEFLATE_BUFFER)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    // ---- JPEG: the JDK writer, then COM segments (FF FE) after the JFIF header for padding. -----------------------

    fun jpeg(pixels: IntArray, width: Int, height: Int, quality: Float): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        System.arraycopy(pixels, 0, (image.raster.dataBuffer as DataBufferInt).data, 0, pixels.size)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val bytes = ByteArrayOutputStream(width * height / DEFLATE_GUESS_RATIO + DEFLATE_GUESS_MIN)
        try {
            val param = writer.defaultWriteParam
            param.compressionMode = ImageWriteParam.MODE_EXPLICIT
            param.compressionQuality = quality
            // Memory cache on purpose: ImageIO.createImageOutputStream follows a global "use cache" flag that can mean a temp file.
            MemoryCacheImageOutputStream(bytes).use { stream ->
                writer.output = stream
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return bytes.toByteArray()
    }

    /** Inserts COM segments totalling exactly [padding] bytes (each carries 4 bytes of framing); returns [jpeg] when it is below 4. */
    fun padJpeg(jpeg: ByteArray, padding: Int, random: Random): ByteArray {
        val sizes = jpegSegments(padding)
        if (sizes.isEmpty()) return jpeg
        var at = JPEG_SOI
        if (jpeg.size > JPEG_SOI + 4 && jpeg[2] == 0xFF.toByte() && jpeg[3] == 0xE0.toByte()) {
            at = JPEG_SOI + 2 + (((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF)) // keep JFIF APP0 first
        }
        val out = ByteArray(jpeg.size + sizes.sum())
        System.arraycopy(jpeg, 0, out, 0, at)
        var o = at
        for (data in sizes.map { it - JPEG_SEGMENT_OVERHEAD }) {
            out[o++] = 0xFF.toByte()
            out[o++] = 0xFE.toByte()
            val length = data + 2
            out[o++] = (length shr 8).toByte()
            out[o++] = length.toByte()
            val block = ByteArray(data)
            random.nextBytes(block)
            System.arraycopy(block, 0, out, o, data)
            o += data
        }
        System.arraycopy(jpeg, at, out, o, jpeg.size - at)
        return out
    }

    /** Total sizes (framing included) of the comment segments that add up to as much of [padding] as the framing allows. */
    fun jpegSegments(padding: Int): List<Int> {
        val sizes = ArrayList<Int>()
        var left = padding
        while (left >= JPEG_SEGMENT_OVERHEAD) {
            var take = minOf(left, JPEG_SEGMENT_MAX_TOTAL)
            val rest = left - take
            if (rest in 1 until JPEG_SEGMENT_OVERHEAD) take -= JPEG_SEGMENT_OVERHEAD - rest // leave a remainder that is a legal segment
            sizes.add(take)
            left -= take
        }
        return sizes
    }

    // ---- GIF: 216-colour cube with ordered dither, hand-written LZW. Padding: one comment extension. -----------------

    /** [padding] is the number of bytes to add; 0 or at least [GIF_COMMENT_MIN]; other values are rounded down to what fits. */
    fun gif(pixels: IntArray, width: Int, height: Int, padding: Int, random: Random): ByteArray {
        val indices = ByteArray(width * height)
        var at = 0
        for (y in 0 until height) {
            val bayer = y and 3
            for (x in 0 until width) {
                val p = pixels[at]
                val d = BAYER[(bayer shl 2) or (x and 3)]
                indices[at++] = (CUBE_LEVEL[clamp((p shr 16 and 0xFF) + d)] * 36 + CUBE_LEVEL[clamp((p shr 8 and 0xFF) + d)] * 6 +
                    CUBE_LEVEL[clamp((p and 0xFF) + d)]).toByte()
            }
        }
        val out = ByteArrayOutputStream(width * height / DEFLATE_GUESS_RATIO + GIF_PALETTE_BYTES + DEFLATE_GUESS_MIN)
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeLe16(out, width)
        writeLe16(out, height)
        out.write(GIF_GLOBAL_TABLE_FLAGS)
        out.write(0) // background colour index
        out.write(0) // pixel aspect ratio
        for (i in 0 until GIF_PALETTE_COLORS) {
            val cube = minOf(i, GIF_CUBE_COLORS - 1)
            out.write((cube / 36) * CUBE_STEP)
            out.write((cube / 6 % 6) * CUBE_STEP)
            out.write((cube % 6) * CUBE_STEP)
        }
        if (padding >= GIF_COMMENT_MIN) writeGifComment(out, gifCommentData(padding), random)
        out.write(0x2C)
        writeLe16(out, 0)
        writeLe16(out, 0)
        writeLe16(out, width)
        writeLe16(out, height)
        out.write(0) // no local table, not interlaced
        out.write(GIF_MIN_CODE_SIZE)
        lzw(indices, out)
        out.write(0x3B)
        return out.toByteArray()
    }

    /** Bytes the comment extension adds for [data] bytes of comment text: introducer, label, sub-block lengths, terminator. */
    fun gifCommentTotal(data: Int): Int = if (data == 0) GIF_COMMENT_MIN else data + (data + 254) / 255 + GIF_COMMENT_MIN

    /** The comment length whose total is the largest not above [padding]. */
    fun gifCommentData(padding: Int): Int {
        var data = maxOf(0, padding - GIF_COMMENT_MIN)
        while (data > 0 && gifCommentTotal(data) > padding) data--
        return data
    }

    private fun writeGifComment(out: ByteArrayOutputStream, data: Int, random: Random) {
        out.write(0x21)
        out.write(0xFE)
        var left = data
        while (left > 0) {
            val n = minOf(left, 255)
            out.write(n)
            val block = ByteArray(n)
            random.nextBytes(block)
            out.write(block, 0, n)
            left -= n
        }
        out.write(0)
    }

    private fun lzw(indices: ByteArray, out: ByteArrayOutputStream) {
        val sub = SubBlocks(out)
        val keys = IntArray(HASH_SIZE) { -1 }
        val codes = IntArray(HASH_SIZE)
        var bits = GIF_MIN_CODE_SIZE + 1
        var free = LZW_FIRST_FREE
        var acc = 0
        var accBits = 0

        fun emit(code: Int) {
            acc = acc or (code shl accBits)
            accBits += bits
            while (accBits >= 8) {
                sub.put(acc and 0xFF)
                acc = acc ushr 8
                accBits -= 8
            }
            // The decoder's table is one entry behind the encoder's, so the width grows when free passes the current maximum.
            if (free > (1 shl bits) - 1 && bits < LZW_MAX_BITS) bits++
        }

        emit(LZW_CLEAR)
        if (indices.isNotEmpty()) {
            var prefix = indices[0].toInt() and 0xFF
            for (i in 1 until indices.size) {
                val c = indices[i].toInt() and 0xFF
                val key = (prefix shl 8) or c
                var slot = (key * HASH_MULT ushr HASH_SHIFT) and (HASH_SIZE - 1)
                var found = -1
                while (keys[slot] != -1) {
                    if (keys[slot] == key) {
                        found = codes[slot]
                        break
                    }
                    slot = (slot + 1) and (HASH_SIZE - 1)
                }
                if (found >= 0) {
                    prefix = found
                    continue
                }
                emit(prefix)
                if (free < LZW_TABLE_LIMIT) {
                    keys[slot] = key
                    codes[slot] = free++
                } else {
                    emit(LZW_CLEAR)
                    java.util.Arrays.fill(keys, -1)
                    free = LZW_FIRST_FREE
                    bits = GIF_MIN_CODE_SIZE + 1
                }
                prefix = c
            }
            emit(prefix)
        }
        emit(LZW_END)
        if (accBits > 0) sub.put(acc and 0xFF)
        sub.finish()
    }

    /** Collects bytes into GIF data sub-blocks of at most 255 bytes, then the zero-length terminator. */
    private class SubBlocks(private val out: ByteArrayOutputStream) {
        private val block = ByteArray(255)
        private var n = 0
        fun put(b: Int) {
            block[n++] = b.toByte()
            if (n == block.size) flush()
        }
        private fun flush() {
            if (n == 0) return
            out.write(n)
            out.write(block, 0, n)
            n = 0
        }
        fun finish() {
            flush()
            out.write(0)
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private fun clamp(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    private fun putLe(b: ByteArray, at: Int, v: Int) {
        b[at] = v.toByte()
        b[at + 1] = (v shr 8).toByte()
        b[at + 2] = (v shr 16).toByte()
        b[at + 3] = (v shr 24).toByte()
    }

    private fun putBe(b: ByteArray, at: Int, v: Int) {
        b[at] = (v shr 24).toByte()
        b[at + 1] = (v shr 16).toByte()
        b[at + 2] = (v shr 8).toByte()
        b[at + 3] = v.toByte()
    }

    private fun writeLe16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    // ---- format constants (fixed by the specifications, not tunable) -----------------------------------------------

    /** Bytes a private PNG chunk costs without its data: length, type, CRC. */
    const val PNG_CHUNK_OVERHEAD = 12
    const val JPEG_SEGMENT_OVERHEAD = 4
    const val GIF_COMMENT_MIN = 3

    private const val PNG_PAD_CHUNK = "raNd" // ancillary, private, reserved bit valid, safe to copy
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val PNG_IHDR_DATA = 13
    private const val PNG_IHDR_TOTAL = PNG_IHDR_DATA + PNG_CHUNK_OVERHEAD
    private const val JPEG_SOI = 2
    private const val JPEG_SEGMENT_MAX_TOTAL = 65_535 + 2
    private const val BMP_HEADER = 54L
    private const val BMP_INFO_HEADER = 40
    private const val BMP_BITS = 24
    private const val BMP_PIXELS_PER_METER = 2835
    private const val GIF_PALETTE_COLORS = 256
    private const val GIF_PALETTE_BYTES = GIF_PALETTE_COLORS * 3
    private const val GIF_CUBE_COLORS = 216
    private const val GIF_GLOBAL_TABLE_FLAGS = 0xF7 // global colour table, 8 bit colour resolution, 256 entries
    private const val GIF_MIN_CODE_SIZE = 8
    private const val CUBE_STEP = 51
    private const val LZW_CLEAR = 256
    private const val LZW_END = 257
    private const val LZW_FIRST_FREE = 258
    private const val LZW_TABLE_LIMIT = 4096
    private const val LZW_MAX_BITS = 12
    private const val HASH_SIZE = 8192
    private const val HASH_MULT = 0x9E3779B1.toInt()
    private const val HASH_SHIFT = 19
    private const val DEFLATE_GUESS_RATIO = 8
    private const val DEFLATE_GUESS_MIN = 1024
    private const val PNG_DEFLATE_LEVEL = 6
    private const val DEFLATE_BUFFER = 16 * 1024

    /** 0..255 to the nearest of the six cube levels 0, 51, ..., 255. */
    private val CUBE_LEVEL = IntArray(256) { (it + CUBE_STEP / 2) / CUBE_STEP }

    /** 4x4 ordered dither offsets, about one third of a cube step either side. */
    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5).map { it * 2 - 15 }.toIntArray()
}
