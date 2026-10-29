package com.routegenie.client

import com.routegenie.Limits
import java.net.IDN
import java.net.URI
import java.net.URISyntaxException

/** A URL the engine can send: [text] is fully encoded and [uri] parses it with a real host. */
class ParsedUrl(val text: String, val uri: URI) {
    val scheme: String get() = uri.scheme
    val host: String get() = uri.host
    val port: Int get() = if (uri.port >= 0) uri.port else if (scheme == "https") HTTPS_PORT else HTTP_PORT

    /** scheme, host and port: the unit within which credentials may travel (redirect rule). */
    val origin: String get() = "$scheme://${host.lowercase()}:$port"

    private companion object {
        const val HTTP_PORT = 80
        const val HTTPS_PORT = 443
    }
}

sealed interface UrlResult {
    data class Ok(val url: ParsedUrl) : UrlResult
    data class Bad(val error: EngineError) : UrlResult
}

/**
 * Pre-validates and encodes URLs because java.net.URI is strict where users are not: raw spaces and braces, non-ASCII
 * characters, internationalized host names. Hosts with an underscore (typical docker-compose service names) cannot be sent
 * by the JDK client at all and are reported as such instead of failing obscurely.
 */
object UrlSupport {
    private const val UNRESERVED = "-._~"
    private const val KEEP = "-._~:/?#[]@!$&'()*+,;=%"
    private const val HEX = "0123456789ABCDEF"

    fun parse(raw: String): UrlResult {
        val text = raw.trim()
        if (text.isEmpty() || text.length > Limits.HTTP_MAX_URL_CHARS) return bad("empty or too long")
        val schemeEnd = text.indexOf("://")
        if (schemeEnd <= 0) return bad("no scheme (start with http:// or https://)")
        val scheme = text.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return bad("scheme $scheme is not supported")
        val rest = text.substring(schemeEnd + 3)
        val authEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        var authority = rest.substring(0, authEnd)
        if ('@' in authority) return bad("credentials in the URL are not supported")
        val hostPart = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBeforeLast(':', authority)
        val portPart = authority.removePrefix(hostPart)
        if (hostPart.isEmpty()) return bad("no host")
        if ('_' in hostPart) return bad("host name with an underscore ($hostPart) is not supported by the HTTP engine")
        val asciiHost = if (hostPart.all { it.code < ASCII_LIMIT }) hostPart else try {
            IDN.toASCII(hostPart)
        } catch (e: IllegalArgumentException) {
            return bad("invalid host name")
        }
        authority = asciiHost + portPart
        val encoded = scheme + "://" + authority + encodeRest(rest.substring(authEnd))
        val uri = try {
            URI(encoded)
        } catch (e: URISyntaxException) {
            return bad("not a valid URL")
        }
        if (uri.host == null) return bad("no valid host")
        return UrlResult.Ok(ParsedUrl(encoded, uri))
    }

    /** Resolves a redirect target against the URL it came from and validates it like any other. */
    fun resolve(base: ParsedUrl, location: String): UrlResult {
        val ref = location.trim()
        val joined = if (ref.contains("://")) ref else runCatching { base.uri.resolve(encodeRest(ref)).toString() }.getOrNull() ?: return bad("bad redirect location")
        return parse(joined)
    }

    /** Encodes everything but unreserved characters (RFC 3986): the form for one name or value inside a query or path segment. */
    fun encodeComponent(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c < ASCII_LIMIT && (c.toChar().isLetterOrDigit() || c.toChar() in UNRESERVED)) sb.append(c.toChar()) else pct(sb, c)
        }
        return sb.toString()
    }

    /** One path value: every segment encoded, slashes kept (for a path converter). */
    fun encodePath(s: String): String = s.split('/').joinToString("/") { encodeComponent(it) }

    /** application/x-www-form-urlencoded: space becomes plus. */
    fun encodeForm(s: String): String = encodeComponent(s).replace("%20", "+")

    fun decode(s: String): String = runCatching { java.net.URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(s)

    private fun encodeRest(rest: String): String {
        val sb = StringBuilder(rest.length + 8)
        val bytes = rest.toByteArray(Charsets.UTF_8)
        var i = 0
        while (i < bytes.size) {
            val c = bytes[i].toInt() and 0xFF
            val ch = c.toChar()
            when {
                c >= ASCII_LIMIT -> pct(sb, c)
                ch.isLetterOrDigit() -> sb.append(ch)
                ch == '%' && i + 2 < bytes.size && isHex(bytes[i + 1]) && isHex(bytes[i + 2]) -> sb.append(ch)
                ch == '%' -> pct(sb, c)
                ch in KEEP -> sb.append(ch)
                else -> pct(sb, c)
            }
            i++
        }
        return sb.toString()
    }

    private fun isHex(b: Byte): Boolean = (b.toInt().toChar()).let { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun pct(sb: StringBuilder, c: Int) {
        sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
    }

    private fun bad(detail: String) = UrlResult.Bad(EngineError(ErrorKind.INVALID_URL, detail))

    private const val ASCII_LIMIT = 128
}
