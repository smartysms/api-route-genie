package com.routegenie.files

import com.routegenie.Limits
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * Valid minimal files for the non-image types a form field may accept. Correct or absent: a type that cannot be made as a
 * real, parseable file gets null, never a pretend file. A size is honored exactly (text, csv, json, pdf, zip, octet) by
 * padding in a place the format allows; a size too small for a valid file of that type gives null.
 *
 * Supported: text/plain, text/csv, application/json, application/pdf, application/zip, application/octet-stream (also by
 * extension: txt, csv, json, pdf, zip, bin). Image types are handed to [ImageSynth] with default dimensions.
 */
object FileSynth {
    private enum class Kind(val mime: String, val extension: String) {
        TEXT("text/plain", "txt"),
        CSV("text/csv", "csv"),
        JSON("application/json", "json"),
        PDF("application/pdf", "pdf"),
        ZIP("application/zip", "zip"),
        OCTET("application/octet-stream", "bin"),
    }

    private val kindByType: Map<String, Kind> = buildMap {
        for (k in Kind.entries) {
            put(k.mime, k)
            put(k.extension, k)
        }
        put("text", Kind.TEXT)
        put("application/x-zip-compressed", Kind.ZIP)
        put("application/x-pdf", Kind.PDF)
        put("text/json", Kind.JSON)
        put("text/x-csv", Kind.CSV)
    }

    private fun kindOf(typeOrExtension: String): Kind? =
        kindByType[typeOrExtension.substringBefore(';').trim().removePrefix(".").lowercase(Locale.ROOT)]

    /** True when [generate] can make a file of this MIME type or extension (images included). */
    fun supports(typeOrExtension: String): Boolean =
        kindOf(typeOrExtension) != null || ImageFormat.fromMime(typeOrExtension) != null || ImageFormat.fromExtension(typeOrExtension) != null

    /**
     * Makes a file of the given MIME type or extension (".pdf", "pdf", "application/pdf"), exactly [sizeBytes] long when given.
     * Returns null for an unsupported type (including wildcards) or a size too small for a valid file of that type.
     * Throws [FileLimitExceeded] above [Limits.FILE_MAX_BYTES].
     */
    fun generate(typeOrExtension: String, sizeBytes: Long?, random: Random): GeneratedFile? {
        require(sizeBytes == null || sizeBytes >= 0) { "sizeBytes must not be negative" }
        if (sizeBytes != null && sizeBytes > Limits.FILE_MAX_BYTES) throw FileLimitExceeded(FileLimit.BYTES, sizeBytes, Limits.FILE_MAX_BYTES)
        val kind = kindOf(typeOrExtension)
        if (kind == null) {
            val image = ImageFormat.fromMime(typeOrExtension) ?: ImageFormat.fromExtension(typeOrExtension) ?: return null
            return try {
                ImageSynth.generate(ImageSpec(image, targetBytes = sizeBytes), random)
            } catch (e: ImageSizeUnreachable) {
                null
            }
        }
        val size = sizeBytes?.toInt()
        val bytes = when (kind) {
            Kind.TEXT -> text(size, random)
            Kind.CSV -> csv(size, random)
            Kind.JSON -> json(size, random)
            Kind.PDF -> pdf(size, random)
            Kind.ZIP -> zip(size, random)
            Kind.OCTET -> ByteArray(size ?: Limits.FILE_DEFAULT_OCTET_BYTES.toInt()).also { random.nextBytes(it) }
        } ?: return null
        check(size == null || bytes.size == size) { "${kind.mime}: made ${bytes.size} bytes for $size" }
        return GeneratedFile(bytes, kind.mime, kind.extension, null, null)
    }

    // ---- text ------------------------------------------------------------------------------------------------------

    private fun text(size: Int?, random: Random): ByteArray? {
        if (size == null) return wordsLine(random.nextInt(Limits.FILE_TEXT_WORDS_MIN, Limits.FILE_TEXT_WORDS_MAX + 1), random).plus("\n").toByteArray()
        if (size == 0) return null
        val sb = StringBuilder()
        while (sb.length < size) sb.append(WORDS[random.nextInt(WORDS.size)]).append(if (random.nextInt(WORDS_PER_LINE) == 0) '\n' else ' ')
        sb.setLength(size - 1)
        sb.append('\n')
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun wordsLine(count: Int, random: Random): String = List(count) { WORDS[random.nextInt(WORDS.size)] }.joinToString(" ")

    // ---- csv: header, rows; a remainder is absorbed by the free-text last column of the last row ----------------------

    private fun csv(size: Int?, random: Random): ByteArray? {
        val header = "id,name,email,score,note\n"
        val rows = ArrayList<String>()
        fun row(id: Int): String {
            val name = WORDS[random.nextInt(WORDS.size)]
            return "$id,$name,$name@example.com,${random.nextInt(SCORE_RANGE)},${wordsLine(random.nextInt(1, NOTE_WORDS_MAX + 1), random)}\n"
        }
        if (size == null) {
            for (i in 1..random.nextInt(Limits.FILE_CSV_ROWS_MIN, Limits.FILE_CSV_ROWS_MAX + 1)) rows.add(row(i))
            return (header + rows.joinToString("")).toByteArray(Charsets.US_ASCII)
        }
        val minimalRow = "1,a,a@example.com,0,a\n"
        if (size < header.length + minimalRow.length) return null
        var used = header.length
        while (true) {
            val next = row(rows.size + 1)
            if (used + next.length > size) break
            rows.add(next)
            used += next.length
        }
        if (rows.isEmpty()) {
            rows.add(minimalRow)
            used += minimalRow.length
        }
        val last = rows.removeAt(rows.size - 1).removeSuffix("\n")
        rows.add(last + "x".repeat(size - used) + "\n")
        return (header + rows.joinToString("")).toByteArray(Charsets.US_ASCII)
    }

    // ---- json: an object; a size is reached with a "note" string, or trailing whitespace after {} for tiny sizes -------

    private fun json(size: Int?, random: Random): ByteArray? {
        val tags = List(random.nextInt(1, TAGS_MAX + 1)) { "\"${WORDS[random.nextInt(WORDS.size)]}\"" }.joinToString(",")
        val fields = "{\"id\":${random.nextInt(ID_RANGE)},\"name\":\"${wordsLine(2, random)}\",\"active\":${random.nextBoolean()}," +
            "\"score\":${random.nextInt(SCORE_RANGE)}.${random.nextInt(DECIMAL_RANGE)},\"tags\":[$tags]"
        if (size == null) return "$fields}".toByteArray(Charsets.US_ASCII)
        val base = "$fields,\"note\":\"\"}".length
        if (size >= base) return "$fields,\"note\":\"${"x".repeat(size - base)}\"}".toByteArray(Charsets.US_ASCII)
        if (size < 2) return null
        return ("{}" + " ".repeat(size - 2)).toByteArray(Charsets.US_ASCII)
    }

    // ---- pdf: one page, Helvetica (a standard font, so nothing is embedded), exact xref; padding is a comment ---------

    private fun pdf(size: Int?, random: Random): ByteArray? {
        val lines = List(random.nextInt(PDF_LINES_MIN, PDF_LINES_MAX + 1)) { wordsLine(random.nextInt(PDF_WORDS_MIN, PDF_WORDS_MAX + 1), random) }
        val content = buildString {
            append("BT\n/F1 14 Tf\n72 720 Td\n18 TL\n")
            lines.forEachIndexed { i, line -> append(if (i == 0) "($line) Tj\n" else "T* ($line) Tj\n") }
            append("ET")
        }
        val base = buildPdf(content, 0).size
        if (size == null) return buildPdf(content, 0)
        if (size < base) return null
        // The startxref number gets longer as padding grows, so correct the padding by what is still missing (converges fast).
        var padding = size - base
        repeat(PDF_FIX_ROUNDS) {
            val made = buildPdf(content, padding).size
            if (made == size) return buildPdf(content, padding)
            padding -= made - size
            if (padding < 0) return null
        }
        return null
    }

    private fun buildPdf(content: String, padding: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val offsets = IntArray(PDF_OBJECTS)
        fun put(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))
        put("%PDF-1.4\n%âãÏÓ\n")
        // A comment (or a lone newline) between the header and the first object is whitespace to every reader.
        when {
            padding == 1 -> put("\n")
            padding >= 2 -> put("%" + "p".repeat(padding - 2) + "\n")
        }
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        )
        objects.forEachIndexed { i, body ->
            offsets[i + 1] = out.size()
            put("${i + 1} 0 obj\n$body\nendobj\n")
        }
        val xref = out.size()
        put("xref\n0 $PDF_OBJECTS\n0000000000 65535 f \n")
        for (i in 1 until PDF_OBJECTS) put(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[i]))
        put("trailer\n<< /Size $PDF_OBJECTS /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    // ---- zip: one stored (exact size) or deflated entry ------------------------------------------------------------

    private fun zip(size: Int?, random: Random): ByteArray? {
        val name = WORDS[random.nextInt(WORDS.size)] + ".txt"
        if (size == null) return buildZip(name, wordsLine(Limits.FILE_ZIP_ENTRY_WORDS, random).toByteArray(), false)
        var n = size - ZIP_FIXED_BYTES - 2 * name.length
        if (n < 1) return null
        var built = buildZip(name, text(n, random)!!, true)
        if (built.size != size) { // an unexpected extra field: correct once by the difference
            n -= built.size - size
            if (n < 1) return null
            built = buildZip(name, text(n, random)!!, true)
        }
        return built.takeIf { it.size == size }
    }

    private fun buildZip(name: String, data: ByteArray, stored: Boolean): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            val entry = ZipEntry(name)
            entry.setTimeLocal(LocalDateTime.parse(Limits.FILE_ZIP_ENTRY_DATE))
            if (stored) {
                val crc = CRC32()
                crc.update(data)
                entry.method = ZipEntry.STORED
                entry.size = data.size.toLong()
                entry.compressedSize = data.size.toLong()
                entry.crc = crc.value
            }
            zip.putNextEntry(entry)
            zip.write(data)
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }

    private val WORDS = listOf(
        "amber", "river", "stone", "maple", "quiet", "bright", "cloud", "ember", "forest", "garden", "harbor", "island",
        "jungle", "kernel", "lantern", "meadow", "nectar", "orchid", "pebble", "quartz", "ripple", "silver", "thunder",
        "umber", "velvet", "willow", "yonder", "zephyr", "anchor", "breeze", "candle", "dusk", "echo", "falcon", "glimmer",
        "hollow", "ivory", "juniper", "kettle", "lagoon", "marble", "north", "ocean", "prairie", "quill", "raven", "summit",
        "tulip", "valley", "winter",
    )

    private const val WORDS_PER_LINE = 8
    private const val NOTE_WORDS_MAX = 3
    private const val TAGS_MAX = 4
    private const val SCORE_RANGE = 100
    private const val DECIMAL_RANGE = 10
    private const val ID_RANGE = 100_000
    private const val PDF_OBJECTS = 6
    private const val PDF_FIX_ROUNDS = 4
    private const val PDF_LINES_MIN = 2
    private const val PDF_LINES_MAX = 5
    private const val PDF_WORDS_MIN = 3
    private const val PDF_WORDS_MAX = 8

    /** Zip bytes that do not depend on the entry: local header 30, central directory entry 46, end record 22. */
    private const val ZIP_FIXED_BYTES = 98
}
