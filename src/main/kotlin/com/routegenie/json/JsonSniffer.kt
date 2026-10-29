package com.routegenie.json

/** Decides from the Content-Type and the first bytes whether a response body should go to the JSON viewer. */
object JsonSniffer {
    private val JSON_TYPES = Regex("""^(application|text)/(([a-z0-9.\-]+\+)?json|x-ndjson|ndjson|jsonl|x-json-stream|x-jsonlines)\s*($|;)""", RegexOption.IGNORE_CASE)
    private val GENERIC_TYPES = Regex("""^(text/plain|application/octet-stream|binary/octet-stream)\s*($|;)""", RegexOption.IGNORE_CASE)

    fun isJsonContentType(contentType: String?): Boolean = contentType != null && JSON_TYPES.containsMatchIn(contentType.trim())

    /** True when the first significant byte opens an object or an array (after a byte order mark and white space). */
    fun looksLikeJson(head: ByteArray, length: Int = head.size): Boolean {
        var i = 0
        val n = minOf(length, head.size)
        if (n >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) i = 3
        while (i < n) {
            val b = head[i].toInt()
            if (b == ' '.code || b == '\t'.code || b == '\n'.code || b == '\r'.code) {
                i++
                continue
            }
            return b == '{'.code || b == '['.code
        }
        return false
    }

    /** A JSON content type, or a missing or generic one with a body that starts like JSON. */
    fun isJson(contentType: String?, head: ByteArray, length: Int = head.size): Boolean {
        if (isJsonContentType(contentType)) return true
        val generic = contentType == null || contentType.isBlank() || GENERIC_TYPES.containsMatchIn(contentType.trim())
        return generic && looksLikeJson(head, length)
    }
}
