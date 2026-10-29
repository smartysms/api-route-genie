package com.routegenie.client

import com.routegenie.Limits
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublisher
import java.net.http.HttpRequest.BodyPublishers
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedTrustManager
import java.net.Socket

/** Trusts everything and checks no host name. Extended on purpose: a plain X509TrustManager still enforces the host name. */
internal class TrustAllManager : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        fun context(): SSLContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(TrustAllManager()), SecureRandom()) }
    }
}

internal object Multipart {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val random = SecureRandom()

    fun boundary(): String = buildString {
        append("----RouteGenie")
        repeat(Limits.HTTP_BOUNDARY_CHARS) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    /** RFC 7578: quotes and line breaks in names are percent-encoded. */
    private fun quote(s: String) = s.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A")

    /** Throws [FileNotFoundException] when a file part cannot be read, so the caller reports it before sending. */
    fun publisher(parts: List<MultipartPart>, boundary: String): BodyPublisher {
        val pieces = ArrayList<BodyPublisher>()
        for (p in parts) {
            val head = StringBuilder("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(quote(p.name)).append('"')
            if (p.filePath != null) {
                val path = Path.of(p.filePath)
                if (!Files.isReadable(path)) throw FileNotFoundException(path.fileName.toString())
                head.append("; filename=\"").append(quote(p.fileName ?: path.fileName.toString())).append('"')
                head.append("\r\nContent-Type: ").append(p.contentType ?: "application/octet-stream").append("\r\n\r\n")
                pieces += BodyPublishers.ofString(head.toString(), Charsets.UTF_8)
                pieces += BodyPublishers.ofFile(path)
            } else {
                if (p.contentType != null) head.append("\r\nContent-Type: ").append(p.contentType)
                head.append("\r\n\r\n").append(p.value.orEmpty())
                pieces += BodyPublishers.ofString(head.toString(), Charsets.UTF_8)
            }
            pieces += BodyPublishers.ofString("\r\n")
        }
        pieces += BodyPublishers.ofString("--$boundary--\r\n")
        return BodyPublishers.concat(*pieces.toTypedArray())
    }
}

internal object Decoding {
    private const val ZLIB_CM = 0x0F
    private const val ZLIB_DEFLATE = 8
    private const val ZLIB_CHECK = 31
    private const val BYTE_MASK = 0xFF
    private const val BYTE_BITS = 8

    /**
     * Wraps [raw] for the Content-Encoding. An empty body is returned as is (a gzip reader would fail on it). The returned
     * stream's [AutoCloseable] ends the inflater too.
     */
    fun wrap(raw: InputStream, encoding: String?): InputStream {
        val enc = encoding?.trim()?.lowercase()
        if (enc == null || enc == "identity" || enc.isEmpty()) return raw
        val pb = PushbackInputStream(raw, 2)
        val first = pb.read()
        if (first < 0) return pb
        pb.unread(first)
        return when (enc) {
            "gzip", "x-gzip" -> GZIPInputStream(pb, Limits.HTTP_READ_CHUNK_BYTES)
            "deflate" -> {
                val b0 = pb.read()
                val b1 = pb.read()
                if (b1 >= 0) pb.unread(b1)
                pb.unread(b0)
                val zlib = (b0 and ZLIB_CM) == ZLIB_DEFLATE && b1 >= 0 && ((b0 shl BYTE_BITS) or (b1 and BYTE_MASK)) % ZLIB_CHECK == 0
                val inflater = Inflater(!zlib)
                object : InflaterInputStream(pb, inflater, Limits.HTTP_READ_CHUNK_BYTES) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            inflater.end()
                        }
                    }
                }
            }
            else -> pb
        }
    }
}

internal object HttpStatusText {
    private val TEXT = mapOf(
        100 to "Continue", 101 to "Switching Protocols", 200 to "OK", 201 to "Created", 202 to "Accepted",
        203 to "Non-Authoritative Information", 204 to "No Content", 205 to "Reset Content", 206 to "Partial Content",
        300 to "Multiple Choices", 301 to "Moved Permanently", 302 to "Found", 303 to "See Other", 304 to "Not Modified",
        307 to "Temporary Redirect", 308 to "Permanent Redirect", 400 to "Bad Request", 401 to "Unauthorized",
        402 to "Payment Required", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed",
        406 to "Not Acceptable", 408 to "Request Timeout", 409 to "Conflict", 410 to "Gone", 411 to "Length Required",
        412 to "Precondition Failed", 413 to "Content Too Large", 414 to "URI Too Long", 415 to "Unsupported Media Type",
        416 to "Range Not Satisfiable", 417 to "Expectation Failed", 418 to "I'm a teapot", 422 to "Unprocessable Content",
        423 to "Locked", 428 to "Precondition Required", 429 to "Too Many Requests", 431 to "Request Header Fields Too Large",
        500 to "Internal Server Error", 501 to "Not Implemented", 502 to "Bad Gateway", 503 to "Service Unavailable",
        504 to "Gateway Timeout", 505 to "HTTP Version Not Supported",
    )

    fun of(status: Int): String = TEXT[status] ?: ""
}

internal fun HttpRequest.Builder.applyBody(body: RequestBody, method: String, boundary: String, contentTypeSet: Boolean): HttpRequest.Builder {
    val (publisher, type) = when (body) {
        RequestBody.None -> BodyPublishers.noBody() to null
        is RequestBody.Text -> BodyPublishers.ofString(body.text, Charsets.UTF_8) to body.contentType
        is RequestBody.Form -> BodyPublishers.ofString(
            body.fields.joinToString("&") { (n, v) -> UrlSupport.encodeForm(n) + "=" + UrlSupport.encodeForm(v) },
        ) to "application/x-www-form-urlencoded"
        is RequestBody.Multipart -> Multipart.publisher(body.parts, boundary) to "multipart/form-data; boundary=$boundary"
    }
    if (type != null && (!contentTypeSet || body is RequestBody.Multipart)) setHeader("Content-Type", type)
    return method(method, publisher)
}
