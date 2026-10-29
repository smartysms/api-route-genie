package com.routegenie.json

import java.io.BufferedOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Shared helpers of the JSON engine tests. */
internal object T {
    /** A backslash, for building JSON escapes inside raw strings without the compiler or an editor turning them into characters. */
    const val BS = "\\"

    fun scan(text: String, options: ScanOptions = ScanOptions()): JsonIndex = JsonScanner.scan(JsonContent.text(text), options)

    fun scanBytes(bytes: ByteArray, options: ScanOptions = ScanOptions()): JsonIndex = JsonScanner.scan(JsonContent.Bytes(bytes), options)

    fun tempFile(text: String): Path {
        val f = Files.createTempFile("rg-json-", ".json")
        Files.write(f, text.toByteArray(Charsets.UTF_8))
        f.toFile().deleteOnExit()
        return f
    }

    /** Scans through a file with a tiny read window, so refills happen inside every kind of token. */
    fun scanFile(text: String, window: Int): JsonIndex = JsonScanner.scan(JsonContent.File(tempFile(text)), ScanOptions(windowBytes = window))

    fun dump(index: JsonIndex): String = (0 until index.nodeCount).joinToString("|") {
        "${index.kind(it)}@${index.start(it)}-${index.end(it)}^${index.parent(it)}#${index.childCount(it)}s${index.subtreeSize(it)}d${index.depth(it)}k${index.hasKey(it)}"
    }

    fun pretty(text: String, options: PrintOptions = PrintOptions()): String {
        val index = scan(text)
        require(index.error == null) { "test input is invalid: ${index.error}" }
        return JsonPrinter.toString(index, index.root, options)
    }

    fun diff(left: String, right: String, options: DiffOptions = DiffOptions()): DiffResult {
        val a = scan(left)
        val b = scan(right)
        require(a.error == null && b.error == null) { "test input is invalid: ${a.error} / ${b.error}" }
        return JsonDiff.compare(a, b, options)!!
    }

    fun summary(result: DiffResult): List<String> = result.entries.map { "${tag(it.kind)} ${it.path}" }

    private fun tag(kind: DiffKind): String = when (kind) {
        DiffKind.ADDED -> "+"
        DiffKind.REMOVED -> "-"
        DiffKind.CHANGED -> "~"
        DiffKind.TYPE_CHANGED -> "T"
        DiffKind.ORDER_CHANGED -> "O"
    }

    /** Writes a synthetic API response of about [targetBytes]: an array of user-like records, to a file, without holding it in memory. */
    fun writeUsers(file: File, targetBytes: Long, seed: Int = 1) {
        var written = 0L
        BufferedOutputStream(file.outputStream(), 1 shl 16).use { out ->
            fun w(s: String) {
                val b = s.toByteArray(Charsets.UTF_8)
                out.write(b)
                written += b.size
            }
            w("[")
            var i = 0
            while (written < targetBytes) {
                if (i > 0) w(",")
                w(user(i + seed))
                i++
            }
            w("]")
        }
    }

    fun users(targetBytes: Int, seed: Int = 1): ByteArray {
        val sb = StringBuilder(targetBytes + 512)
        sb.append('[')
        var i = 0
        while (sb.length < targetBytes) {
            if (i > 0) sb.append(',')
            sb.append(user(i + seed))
            i++
        }
        sb.append(']')
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun user(i: Int): String =
        "{\"id\":$i,\"name\":\"User number $i\",\"email\":\"user$i@example.com\",\"active\":${i % 3 != 0},\"score\":${i % 100}.${i % 10}5," +
            "\"tags\":[\"alpha\",\"beta\",\"t$i\"],\"address\":{\"city\":\"City $i\",\"zip\":\"${10000 + i % 90000}\"},\"note\":null}"
}
