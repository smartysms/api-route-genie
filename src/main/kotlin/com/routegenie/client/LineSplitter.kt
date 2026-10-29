package com.routegenie.client

import com.routegenie.Limits

/**
 * Splits bytes into lines at LF, CRLF or a lone CR, across any chunk boundaries, and decodes each complete line as UTF-8
 * (a line is complete, so no character is cut). A leading byte order mark is dropped. A line longer than [maxLineBytes] is
 * cut and the rest of it ignored ([cutLines] counts them), so one runaway line cannot fill the memory.
 */
class LineSplitter(private val maxLineBytes: Int = Limits.STREAM_MAX_LINE_BYTES, private val sink: (String) -> Unit) {
    private var buf = ByteArray(INITIAL)
    private var len = 0
    private var overflow = false
    private var afterCr = false
    private var started = false
    private var bomCount = 0

    var cutLines = 0L
        private set

    fun feed(data: ByteArray, off: Int = 0, count: Int = data.size - off) {
        var i = off
        val end = off + count
        while (i < end) {
            val b = data[i++]
            if (!started) {
                if (bomCount < BOM.size && b == BOM[bomCount]) {
                    bomCount++
                    if (bomCount == BOM.size) started = true
                    continue
                }
                // A partial BOM that did not match is ordinary data.
                started = true
                for (k in 0 until bomCount) append(BOM[k])
                bomCount = 0
            }
            if (afterCr) {
                afterCr = false
                if (b == LF) continue
            }
            when (b) {
                LF -> emit()
                CR -> {
                    emit()
                    afterCr = true
                }
                else -> append(b)
            }
        }
    }

    /** The unterminated last line at the end of the stream, if any. SSE ignores it by spec; line streams deliver it. */
    fun finish(): String? {
        if (len == 0) return null
        val text = String(buf, 0, len, Charsets.UTF_8)
        len = 0
        return text
    }

    private fun append(b: Byte) {
        if (len >= maxLineBytes) {
            if (!overflow) {
                overflow = true
                cutLines++
            }
            return
        }
        if (len == buf.size) buf = buf.copyOf(minOf(buf.size * 2, maxLineBytes))
        buf[len++] = b
    }

    private fun emit() {
        val text = String(buf, 0, len, Charsets.UTF_8)
        len = 0
        overflow = false
        if (buf.size > SHRINK_ABOVE) buf = ByteArray(INITIAL)
        sink(text)
    }

    private companion object {
        const val INITIAL = 256
        const val SHRINK_ABOVE = 64 * 1024
        const val LF = '\n'.code.toByte()
        const val CR = '\r'.code.toByte()
        val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
