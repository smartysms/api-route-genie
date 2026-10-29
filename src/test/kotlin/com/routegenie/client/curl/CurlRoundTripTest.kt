package com.routegenie.client.curl

import com.routegenie.client.Header
import com.routegenie.client.MultipartPart
import com.routegenie.client.RequestBody
import com.routegenie.client.RequestSpec
import com.routegenie.model.HttpMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** P32: emit then parse gives the same request, for fuzzed bodies over the metacharacter alphabet of research 03. */
class CurlRoundTripTest {
    private val meta = "\"'\\$`%^&|<>()!{}[]\n\r"
    private val letters = "abcXYZ019 _-.:/=;@,#*"
    private val nonAscii = "caf\u00e9\u00fc\u4e2d\ud83d\ude00"
    private val printable = (' '..'~').joinToString("")

    private fun text(r: Random, alphabet: String, maxLen: Int): String {
        val cps = alphabet.codePoints().toArray()
        val sb = StringBuilder()
        repeat(r.nextInt(maxLen + 1)) { sb.appendCodePoint(cps[r.nextInt(cps.size)]) }
        return sb.toString()
    }

    private fun headerValue(r: Random, alphabet: String) = text(r, alphabet, 12).replace("\n", "").replace("\r", "").trim().ifEmpty { "v" }

    private fun roundTrip(spec: RequestSpec, shell: CurlShell, files: Map<String, String> = emptyMap(), flags: CurlFlags = CurlFlags()) {
        val cmd = CurlEmitter.emit(spec, CurlOptions(shell, flags, multiline = true))
        val all = files + cmd.sideFiles.associate { it.name to it.content }
        val parsed = CurlParser.parse(cmd.text, shell, all)
        val p = (parsed as? CurlParseResult.Parsed)?.request ?: throw AssertionError("not parsed: ${cmd.text}")
        assertEquals("round trip through $shell of:\n${cmd.text}", spec, p.spec)
        assertEquals(flags, p.flags)
        assertTrue("nothing may be ignored in ${cmd.text}: ${p.ignored}", p.ignored.isEmpty())
    }

    private fun alphabetFor(shell: CurlShell) = when (shell) {
        CurlShell.BASH -> meta + letters + nonAscii
        CurlShell.CMD -> meta.replace("\r", "") + letters
        CurlShell.POWERSHELL -> meta + letters + nonAscii
    }

    @Test
    fun `1000 fuzzed text bodies per shell`() {
        for (shell in CurlShell.entries) {
            val r = Random(42L + shell.ordinal)
            repeat(1000) {
                val body = text(r, alphabetFor(shell), 40)
                val spec = RequestSpec(
                    HttpMethod.POST, "http://127.0.0.1:8000/x?a=1",
                    listOf(Header("X-Test", headerValue(r, alphabetFor(shell).replace("\r", "")))),
                    RequestBody.Text("application/json", body),
                )
                roundTrip(spec, shell)
            }
        }
    }

    @Test
    fun `cmd bodies that need a side file round trip with the file`() {
        val spec = RequestSpec(HttpMethod.PUT, "http://h/", body = RequestBody.Text("application/json", "{\"n\":\"caf\u00e9\r\n\"}"))
        roundTrip(spec, CurlShell.CMD)
    }

    @Test
    fun `fuzzed form bodies`() {
        for (shell in CurlShell.entries) {
            val r = Random(7L + shell.ordinal)
            repeat(300) {
                val fields = (1..1 + r.nextInt(3)).map { "f${r.nextInt(5)}" to text(r, alphabetFor(shell).replace("\r", ""), 15) }
                roundTrip(RequestSpec(HttpMethod.POST, "http://h/", body = RequestBody.Form(fields)), shell)
            }
        }
    }

    @Test
    fun `fuzzed multipart bodies`() {
        for (shell in CurlShell.entries) {
            val r = Random(9L + shell.ordinal)
            val alphabet = when (shell) {
                CurlShell.BASH -> alphabetFor(shell)
                CurlShell.CMD -> printable.replace("\"", "")
                CurlShell.POWERSHELL -> printable.replace(Regex("[\";<@]"), "") // quoted -F values need a quote, which 5.1 cannot pass inline
            }
            repeat(300) {
                val parts = (1..1 + r.nextInt(3)).map {
                    when (r.nextInt(3)) {
                        0 -> MultipartPart("p${r.nextInt(4)}", value = text(r, alphabet, 12))
                        1 -> MultipartPart("p${r.nextInt(4)}", value = text(r, alphabet, 12).ifEmpty { "t" }, contentType = "text/plain")
                        else -> MultipartPart(
                            "file", filePath = "/tmp/" + text(r, letters, 6).replace(Regex("[;,\"@]"), "").ifEmpty { "a" },
                            fileName = "n.txt", contentType = "text/plain",
                        )
                    }
                }
                roundTrip(RequestSpec(HttpMethod.POST, "http://h/", body = RequestBody.Multipart(parts)), shell)
            }
        }
    }

    @Test
    fun `methods, urls and flags`() {
        for (shell in CurlShell.entries) {
            for (m in HttpMethod.entries.filter { it.isConcrete }) {
                roundTrip(RequestSpec(m, "http://h:8000/a/b?x=1&y[0]=2"), shell, flags = CurlFlags(true, true, true))
            }
            roundTrip(RequestSpec(HttpMethod.GET, "http://h/", body = RequestBody.Text("text/plain", "x")), shell)
        }
    }
}
