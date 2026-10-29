package com.routegenie.json

import com.routegenie.Limits
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * A document to show or compare: bytes in memory, or a file (a response body that was spilled to disk). The viewer never
 * deletes a file it was given.
 *
 * [fullSizeBytes] is the size of the complete body when this content is only its beginning (a cut response), -1 when complete.
 */
sealed class JsonContent {
    abstract val charset: Charset
    abstract val sizeBytes: Long
    abstract val fullSizeBytes: Long

    val isCut: Boolean get() = fullSizeBytes > sizeBytes

    class Bytes(
        val bytes: ByteArray,
        override val charset: Charset = Charsets.UTF_8,
        override val fullSizeBytes: Long = -1L,
    ) : JsonContent() {
        override val sizeBytes: Long get() = bytes.size.toLong()
    }

    class File(
        val path: Path,
        override val charset: Charset = Charsets.UTF_8,
        override val fullSizeBytes: Long = -1L,
    ) : JsonContent() {
        override val sizeBytes: Long = try {
            Files.size(path)
        } catch (e: IOException) {
            0L
        }
    }

    /** Opens the bytes for scanning and lazy reads. The caller (normally the index) closes the source. */
    fun open(): JsonSource = JsonSources.open(this)

    companion object {
        fun text(text: String): Bytes = Bytes(text.toByteArray(Charsets.UTF_8))
    }
}

/**
 * Random access to the bytes of one document. Offsets are logical: 0 is the first byte after a byte order mark, and the
 * bytes are ASCII-compatible (UTF-8 or a single-byte charset), so structure can be found by looking at single bytes.
 */
interface JsonSource : Closeable {
    val length: Long
    val charset: Charset

    /** The whole content when it lives in memory (the scanner and the printers read it without copying), otherwise null. */
    val array: ByteArray?

    /** Cached random access; returns the number of bytes copied, 0 at or after the end. */
    fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int

    /** Sequential bulk access that does not disturb the cache of [read]. */
    fun readBulk(pos: Long, dst: ByteArray, off: Int, len: Int): Int
}

internal class ByteArraySource(private val bytes: ByteArray, override val charset: Charset) : JsonSource {
    override val length: Long get() = bytes.size.toLong()
    override val array: ByteArray get() = bytes

    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (pos >= bytes.size || len <= 0) return 0
        val n = minOf(len.toLong(), bytes.size - pos).toInt()
        System.arraycopy(bytes, pos.toInt(), dst, off, n)
        return n
    }

    override fun readBulk(pos: Long, dst: ByteArray, off: Int, len: Int): Int = read(pos, dst, off, len)
    override fun close() = Unit
}

/** A file read through a small least-recently-used block cache, so the tree and search read neighbours cheaply. */
internal class FileSource(
    private val channel: FileChannel,
    private val base: Long,
    override val length: Long,
    override val charset: Charset,
) : JsonSource {
    override val array: ByteArray? get() = null

    private val blockBytes = Limits.JSON_FILE_BLOCK_BYTES
    private val cache = object : LinkedHashMap<Long, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > Limits.JSON_FILE_CACHE_BLOCKS
    }

    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (pos >= length || len <= 0) return 0
        val total = minOf(len.toLong(), length - pos).toInt()
        var done = 0
        synchronized(cache) {
            while (done < total) {
                val at = pos + done
                val blockNo = at / blockBytes
                val block = cache.getOrPut(blockNo) { loadBlock(blockNo) }
                val inBlock = (at - blockNo * blockBytes).toInt()
                if (inBlock >= block.size) break
                val n = minOf(total - done, block.size - inBlock)
                System.arraycopy(block, inBlock, dst, off + done, n)
                done += n
            }
        }
        return done
    }

    private fun loadBlock(blockNo: Long): ByteArray {
        val start = blockNo * blockBytes
        val size = minOf(blockBytes.toLong(), length - start).toInt().coerceAtLeast(0)
        val block = ByteArray(size)
        readFully(start, block, 0, size)
        return block
    }

    override fun readBulk(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (pos >= length || len <= 0) return 0
        val want = minOf(len.toLong(), length - pos).toInt()
        return readFully(pos, dst, off, want)
    }

    private fun readFully(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        val buffer = ByteBuffer.wrap(dst, off, len)
        var done = 0
        while (done < len) {
            val n = channel.read(buffer, base + pos + done)
            if (n <= 0) break
            done += n
        }
        return done
    }

    override fun close() {
        synchronized(cache) { cache.clear() }
        channel.close()
    }
}

internal object JsonSources {
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun open(content: JsonContent): JsonSource = when (content) {
        is JsonContent.Bytes -> openBytes(content.bytes, content.charset)
        is JsonContent.File -> openFile(content.path, content.charset)
    }

    private fun isWide(charset: Charset): Boolean {
        val name = charset.name().uppercase()
        return name.startsWith("UTF-16") || name.startsWith("UTF-32") || name == "UTF16" || name == "UTF32"
    }

    private fun bomCharset(head: ByteArray, n: Int): Pair<Charset, Int>? {
        fun at(i: Int) = if (i < n) head[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> Charsets.UTF_8 to 3
            at(0) == 0xFF && at(1) == 0xFE && at(2) == 0 && at(3) == 0 -> Charset.forName("UTF-32LE") to 4
            at(0) == 0 && at(1) == 0 && at(2) == 0xFE && at(3) == 0xFF -> Charset.forName("UTF-32BE") to 4
            at(0) == 0xFF && at(1) == 0xFE -> Charsets.UTF_16LE to 2
            at(0) == 0xFE && at(1) == 0xFF -> Charsets.UTF_16BE to 2
            else -> null
        }
    }

    private fun openBytes(bytes: ByteArray, charset: Charset): JsonSource {
        val bom = bomCharset(bytes, minOf(4, bytes.size))
        if (bom != null) {
            val (bomSet, skip) = bom
            if (bomSet == Charsets.UTF_8) return ByteArraySource(bytes.copyOfRange(skip, bytes.size), charset)
            return ByteArraySource(String(bytes, skip, bytes.size - skip, bomSet).toByteArray(Charsets.UTF_8), Charsets.UTF_8)
        }
        if (isWide(charset)) return ByteArraySource(String(bytes, charset).toByteArray(Charsets.UTF_8), Charsets.UTF_8)
        return ByteArraySource(bytes, charset)
    }

    private fun openFile(path: Path, charset: Charset): JsonSource {
        val channel = FileChannel.open(path, StandardOpenOption.READ)
        try {
            val size = channel.size()
            val head = ByteArray(4)
            val headLen = channel.read(ByteBuffer.wrap(head), 0).coerceAtLeast(0)
            val bom = bomCharset(head, headLen)
            val wide = bom != null && bom.first != Charsets.UTF_8 || bom == null && isWide(charset)
            if (wide) {
                if (size > Limits.JSON_TRANSCODE_MAX_BYTES) throw IOException("A UTF-16 or UTF-32 document of $size bytes is too large to convert")
                val all = ByteArray(size.toInt())
                ByteBuffer.wrap(all).let { buf -> var at = 0L; while (buf.hasRemaining()) { val n = channel.read(buf, at); if (n <= 0) break; at += n } }
                channel.close()
                return openBytes(all, charset)
            }
            val skip = bom?.second ?: 0
            return FileSource(channel, skip.toLong(), size - skip, charset)
        } catch (e: Throwable) {
            runCatching { channel.close() }
            throw e
        }
    }
}
