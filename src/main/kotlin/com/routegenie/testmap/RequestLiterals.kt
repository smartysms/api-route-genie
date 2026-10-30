package com.routegenie.testmap

import com.routegenie.Limits
import com.routegenie.discovery.core.ExprEvaluator
import com.routegenie.discovery.core.ModuleEntry
import com.routegenie.model.HttpMethod
import com.routegenie.model.PathSyntax
import com.routegenie.model.StrValue

/**
 * Reads the request a test call makes from its literal arguments: the query (`params=`), headers (`headers=`, Django
 * `HTTP_*=`) and body (`json=`, `data=`, `content=`, Django's second positional). Only fully literal values qualify
 * (dict, list, str, int, float, bool, None, and f-strings that evaluate); a part with anything else in it is "not
 * determined" (null) and left empty, never guessed. The values are shown to the user and sent only when they press Run.
 */
object RequestLiterals {
    fun evaluate(evaluator: ExprEvaluator, owner: ModuleEntry, call: ClientCallFact, verb: HttpMethod, sink: ClientSink?, urlQuery: String? = null): CallRequest {
        val reader = Reader(evaluator, owner)
        val named = call.parts.filter { it.name.isNotEmpty() }.associate { it.name to it.value }
        val positional = call.parts.firstOrNull { it.name.isEmpty() }?.value
        val secondIsQuery = sink?.secondPositional == SecondPositional.DJANGO_DATA && (verb == HttpMethod.GET || verb == HttpMethod.HEAD)
        val secondIsBody = sink?.secondPositional == SecondPositional.DJANGO_DATA && !secondIsQuery

        // Query: params= / query_string= (Flask also takes a string), or Django's second positional of a GET.
        val queryValue = named["params"] ?: named["query_string"] ?: if (secondIsQuery) positional else null
        val argumentQuery = if (queryValue == null) emptyList() else reader.queryOf(queryValue)
        // A query written in the URL itself, when it has no computed part.
        val urlPairs = when {
            urlQuery.isNullOrEmpty() -> emptyList()
            urlQuery.contains(PathSyntax.UNKNOWN_SEGMENT) -> null
            else -> reader.queryOf(urlQuery)
        }
        val query = if (argumentQuery == null || urlPairs == null) null else urlPairs + argumentQuery

        // Headers: headers={...} and Django HTTP_NAME=value keywords.
        var headers: List<Pair<String, String>>? = emptyList()
        named["headers"]?.let { headers = reader.pairsOf(it) }
        for ((key, value) in named) {
            if (!key.startsWith(ClientSinks.HEADER_KEYWORD_PREFIX)) continue
            val text = reader.text(value)
            headers = if (text == null || headers == null) null else headers!! + (headerName(key) to text)
        }
        val contentType = named["content_type"]?.let { reader.text(it) }

        // Body: json= wins; data= is a form for a dict and text for a string; content= is text; DRF format="json" makes data JSON.
        val dataValue = named["data"] ?: if (secondIsBody) positional else null
        val format = named["format"]?.let { reader.text(it) }
        val bodies = listOfNotNull(named["json"]?.let { "json" to it }, dataValue?.let { "data" to it }, named["content"]?.let { "content" to it })
        var body: BodyLiteral? = null
        var notDetermined = false
        if (bodies.size > 1) {
            notDetermined = true
        } else if (bodies.size == 1) {
            val (kind, value) = bodies[0]
            body = when {
                kind == "json" || (kind == "data" && format == JSON_FORMAT) -> reader.jsonOf(value)?.let { BodyLiteral.Json(it) }
                kind == "content" -> reader.text(value)?.let { BodyLiteral.Text(it) }
                value is LitValue.Dict -> reader.pairsOf(value)?.let { BodyLiteral.Form(it) }
                else -> reader.text(value)?.let { BodyLiteral.Text(it) }
            }
            if (body == null) notDetermined = true
        }
        return CallRequest(query, headers, body, notDetermined, contentType)
    }

    /** `HTTP_X_API_KEY` is the header `X-Api-Key` (Django's CGI-style name). */
    private fun headerName(keyword: String): String =
        keyword.removePrefix(ClientSinks.HEADER_KEYWORD_PREFIX).split('_').joinToString("-") { part -> part.lowercase().replaceFirstChar { it.uppercase() } }

    private class Reader(val evaluator: ExprEvaluator, val owner: ModuleEntry) {
        private var nodes = 0

        /** An exactly known string, or null. */
        fun text(value: LitValue): String? = when (value) {
            is LitValue.Str -> (evaluator.evaluate(owner, value.expr) as? StrValue.Known)?.text
            is LitValue.Num -> value.text
            is LitValue.Bool -> if (value.value) "true" else "false"
            else -> null
        }

        fun queryOf(value: LitValue): List<Pair<String, String>>? {
            if (value is LitValue.Str) return queryOf(text(value) ?: return null)
            return pairsOf(value)
        }

        fun queryOf(raw: String): List<Pair<String, String>> =
            raw.split('&').filter { it.isNotEmpty() }.map { it.substringBefore('=') to it.substringAfter('=', "") }

        /** The entries of a dict literal whose keys and values are all known strings or numbers; null when any is not. */
        fun pairsOf(value: LitValue): List<Pair<String, String>>? {
            if (value !is LitValue.Dict) return null
            val out = ArrayList<Pair<String, String>>(value.entries.size)
            for ((k, v) in value.entries) {
                val key = text(k) ?: return null
                // A list value is a repeated query parameter / form field.
                if (v is LitValue.Seq) {
                    for (item in v.items) out += key to (text(item) ?: return null)
                } else {
                    out += key to (text(v) ?: return null)
                }
            }
            return out
        }

        fun jsonOf(value: LitValue): String? {
            val sb = StringBuilder()
            return if (render(value, sb, 0)) sb.toString() else null
        }

        private fun render(value: LitValue, sb: StringBuilder, depth: Int): Boolean {
            if (++nodes > Limits.TEST_LITERAL_MAX_NODES || depth > Limits.TEST_LITERAL_MAX_DEPTH) return false
            when (value) {
                is LitValue.Str -> quote(text(value) ?: return false, sb)
                is LitValue.Num -> sb.append(value.text)
                is LitValue.Bool -> sb.append(value.value)
                LitValue.None -> sb.append("null")
                LitValue.Unknown -> return false
                is LitValue.Seq -> {
                    if (value.items.isEmpty()) return sb.append("[]").let { true }
                    sb.append("[\n")
                    value.items.forEachIndexed { i, item ->
                        indent(sb, depth + 1)
                        if (!render(item, sb, depth + 1)) return false
                        sb.append(if (i < value.items.lastIndex) ",\n" else "\n")
                    }
                    indent(sb, depth)
                    sb.append(']')
                }
                is LitValue.Dict -> {
                    if (value.entries.isEmpty()) return sb.append("{}").let { true }
                    sb.append("{\n")
                    value.entries.forEachIndexed { i, (k, v) ->
                        indent(sb, depth + 1)
                        quote(text(k) ?: return false, sb)
                        sb.append(": ")
                        if (!render(v, sb, depth + 1)) return false
                        sb.append(if (i < value.entries.lastIndex) ",\n" else "\n")
                    }
                    indent(sb, depth)
                    sb.append('}')
                }
            }
            return sb.length <= Limits.TEST_LITERAL_MAX_CHARS
        }

        private fun indent(sb: StringBuilder, depth: Int) {
            repeat(depth) { sb.append(INDENT) }
        }

        private fun quote(text: String, sb: StringBuilder) {
            sb.append('"')
            for (c in text) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
                }
            }
            sb.append('"')
        }
    }

    private const val JSON_FORMAT = "json"
    private const val INDENT = "  "
}
