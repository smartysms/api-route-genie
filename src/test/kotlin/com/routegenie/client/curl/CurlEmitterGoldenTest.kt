package com.routegenie.client.curl

import com.routegenie.Limits
import com.routegenie.client.Header
import com.routegenie.client.MultipartPart
import com.routegenie.client.RequestBody
import com.routegenie.client.RequestSpec
import com.routegenie.model.HttpMethod
import com.routegenie.testing.PerfBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The 12 generation cases of research 03 (5.5) as exact strings, plus the safety refusals. */
class CurlEmitterGoldenTest {
    private val url = "http://127.0.0.1:18765/g/1"
    private val json = """{"name":"O'Brien \"Bob\"","n":1}"""

    private fun post(body: String, type: String = "application/json", url: String = this.url, headers: List<Header> = emptyList()) =
        RequestSpec(HttpMethod.POST, url, headers, RequestBody.Text(type, body))

    private fun emit(spec: RequestSpec, shell: CurlShell, multiline: Boolean = false, ps7: Boolean = false, masked: Boolean = false) =
        CurlEmitter.emit(spec, CurlOptions(shell, multiline = multiline, powerShell7 = ps7, maskSecrets = masked))

    @Test
    fun `G1 bash exact`() {
        assertEquals(
            """curl -X POST 'http://127.0.0.1:18765/g/1' -H 'Content-Type: application/json' --data-raw '{"name":"O'\''Brien \"Bob\"","n":1}'""",
            emit(post(json), CurlShell.BASH).text,
        )
    }

    @Test
    fun `G1 cmd uses the caret form`() {
        val t = emit(post(json), CurlShell.CMD).text
        assertTrue(t, t.contains("""--data-raw ^"{\^"name\^":\^"O'Brien \\\^"Bob\\\^"\^",\^"n\^":1}^""""))
        assertTrue(t, t.contains("""-H "Content-Type: application/json""""))
    }

    @Test
    fun `G1 PowerShell writes the body through a here-string file`() {
        val t = emit(post(json), CurlShell.POWERSHELL).text
        assertEquals(
            "\$body = @'\n$json\n'@\n" +
                "\$f = Join-Path \$env:TEMP 'ee_body.json'\n" +
                "[IO.File]::WriteAllText(\$f, \$body, (New-Object Text.UTF8Encoding \$false))\n" +
                "curl.exe -X POST 'http://127.0.0.1:18765/g/1' -H 'Content-Type: application/json' --data-binary ('@' + \$f)\n" +
                "Remove-Item \$f",
            t,
        )
    }

    @Test
    fun `G2 cmd metacharacters are caret escaped`() {
        val body = """{"a":"x & y | z > w < v ^ c (p) 50% off"}"""
        val t = emit(post(body), CurlShell.CMD).text
        assertTrue(t, t.contains("""--data-raw ^"{\^"a\^":\^"x ^& y ^| z ^> w ^< v ^^ c ^(p^) 50%^ off\^"}^""""))
    }

    @Test
    fun `G3 text body in all shells`() {
        val spec = post("hello world & more 'q'", "text/plain")
        assertTrue(emit(spec, CurlShell.BASH).text.endsWith("""--data-raw 'hello world & more '\''q'\'''"""))
        assertTrue(emit(spec, CurlShell.CMD).text.endsWith("""--data-raw "hello world & more 'q'""""))
        assertTrue(emit(spec, CurlShell.POWERSHELL).text.endsWith("""--data-raw 'hello world & more ''q'''"""))
    }

    @Test
    fun `G4 multi-line body`() {
        val spec = post("{\n  \"a\": 1\n}")
        assertTrue(emit(spec, CurlShell.BASH).text.contains("--data-raw '{\n  \"a\": 1\n}'"))
        assertTrue(emit(spec, CurlShell.CMD).text.contains("^\n\n"))
        val ps = emit(spec, CurlShell.POWERSHELL).text
        assertTrue(ps, ps.startsWith("\$body = @'\n{\n  \"a\": 1\n}\n'@"))
    }

    @Test
    fun `G5 trailing backslashes`() {
        val spec = post("C:\\dir\\", "text/plain", headers = listOf(Header("X-Test", "C:\\my dir\\")))
        val bash = emit(spec, CurlShell.BASH).text
        assertTrue(bash, bash.contains("'X-Test: C:\\my dir\\'") && bash.contains("--data-raw 'C:\\dir\\'"))
        val cmd = emit(spec, CurlShell.CMD).text
        assertTrue(cmd, cmd.contains("\"X-Test: C:\\my dir\\\\\"") && cmd.contains("--data-raw \"C:\\dir\\\\\""))
        val ps = emit(spec, CurlShell.POWERSHELL).text
        assertTrue(ps, ps.contains("'X-Test: C:\\my dir\\\\'") && ps.contains("--data-raw 'C:\\dir\\'"))
        val ps7 = emit(spec, CurlShell.POWERSHELL, ps7 = true).text
        assertTrue(ps7, ps7.contains("'X-Test: C:\\my dir\\'"))
    }

    @Test
    fun `G6 GET with a percent sign uses the caret form for cmd`() {
        val spec = RequestSpec(
            HttpMethod.GET, "http://127.0.0.1:18765/g/1?limit=10&q=a%20b",
            listOf(Header("Accept", "application/json"), Header("X-Test", "v 1")),
        )
        val t = emit(spec, CurlShell.CMD).text
        assertEquals(
            """curl.exe ^"http://127.0.0.1:18765/g/1?limit=10^&q=a%^20b^" -H "Accept: application/json" -H "X-Test: v 1"""",
            t,
        )
        assertFalse("GET has no -X", t.contains("-X"))
    }

    @Test
    fun `G7 globoff for brackets and braces in every shell`() {
        for (shell in CurlShell.entries) {
            val t = emit(RequestSpec(HttpMethod.GET, "http://h/a?ids[0-1]=x&f={a,b}"), shell).text
            assertTrue("$shell: $t", t.contains(" -g "))
        }
        assertFalse(emit(RequestSpec(HttpMethod.GET, "http://h/a?x=1"), CurlShell.BASH).text.contains("-g"))
    }

    @Test
    fun `G8 basic auth stays an Authorization header`() {
        val spec = RequestSpec(HttpMethod.GET, url, listOf(Header("Authorization", "Basic dXNlcjpwQHNzOndvcmQ=")))
        assertTrue(emit(spec, CurlShell.BASH).text.contains("-H 'Authorization: Basic dXNlcjpwQHNzOndvcmQ='"))
    }

    @Test
    fun `G9 multipart parts`() {
        val spec = RequestSpec(
            HttpMethod.POST, url, body = RequestBody.Multipart(
                listOf(
                    MultipartPart("name", value = "val"),
                    MultipartPart("f", filePath = "C:\\tmp\\a.txt", fileName = "x.txt", contentType = "text/plain"),
                    MultipartPart("at", value = "@not-a-file"),
                ),
            ),
        )
        val t = emit(spec, CurlShell.BASH).text
        assertEquals(
            """curl -X POST 'http://127.0.0.1:18765/g/1' -F 'name=val' -F 'f=@C:\tmp\a.txt;type=text/plain;filename=x.txt' --form-string 'at=@not-a-file'""",
            t,
        )
    }

    @Test
    fun `G10 masking hides credentials in headers and query`() {
        val spec = RequestSpec(
            HttpMethod.GET, "http://h/a?api_key=SECRET1&x=1",
            listOf(Header("Authorization", "Bearer SECRET2"), Header("X-Other", "ok")),
        )
        for (shell in CurlShell.entries) {
            val t = emit(spec, shell, masked = true).text
            assertFalse(t, t.contains("SECRET"))
            assertTrue(t, t.contains(SecretMask.PLACEHOLDER) && t.contains("X-Other: ok"))
        }
        assertTrue(emit(spec, CurlShell.BASH).text.contains("SECRET2"))
    }

    @Test
    fun `G11 cmd body with non-ASCII goes to a side file`() {
        val c = emit(post("{\"n\":\"caf\u00e9\"}"), CurlShell.CMD)
        assertTrue(c.text, c.text.contains("--data-binary \"@body.json\""))
        assertEquals(listOf(SideFile("body.json", "{\"n\":\"caf\u00e9\"}")), c.sideFiles)
        assertTrue(CurlWarning.BODY_IN_SIDE_FILE in c.warnings)
    }

    @Test
    fun `G12 cmd long body goes to a side file`() {
        val body = "x".repeat(Limits.CURL_CMD_INLINE_BODY_MAX_CHARS + 1)
        val c = emit(post(body, "text/plain"), CurlShell.CMD)
        assertEquals(1, c.sideFiles.size)
        assertFalse(c.text.contains(body))
    }

    @Test
    fun `method rules - GET has no -X, HEAD is -I, others write -X`() {
        assertEquals("curl 'http://h/'", emit(RequestSpec(HttpMethod.GET, "http://h/"), CurlShell.BASH).text)
        assertEquals("curl -I 'http://h/'", emit(RequestSpec(HttpMethod.HEAD, "http://h/"), CurlShell.BASH).text)
        assertEquals("curl -X PATCH 'http://h/'", emit(RequestSpec(HttpMethod.PATCH, "http://h/"), CurlShell.BASH).text)
        assertEquals("curl -X POST 'http://h/'", emit(RequestSpec(HttpMethod.POST, "http://h/"), CurlShell.BASH).text)
        assertTrue(emit(RequestSpec(HttpMethod.GET, "http://h/", body = RequestBody.Text("text/plain", "x")), CurlShell.BASH).text.contains("-X GET"))
    }

    @Test
    fun `flags and empty header values`() {
        val spec = RequestSpec(HttpMethod.GET, "http://h/", listOf(Header("X-Empty", ""), Header("X-Off", "1", enabled = false)))
        val t = CurlEmitter.emit(spec, CurlOptions(flags = CurlFlags(compressed = true, followRedirects = true, insecure = true), multiline = false)).text
        assertEquals("curl 'http://h/' -H 'X-Empty;' --compressed -L -k", t)
    }

    @Test
    fun `multiline joins with the continuation of each shell`() {
        val spec = RequestSpec(HttpMethod.GET, "http://h/", listOf(Header("A", "1")))
        assertEquals("curl \\\n  'http://h/' \\\n  -H 'A: 1'", emit(spec, CurlShell.BASH, multiline = true).text)
        assertEquals("curl.exe ^\n  \"http://h/\" ^\n  -H \"A: 1\"", emit(spec, CurlShell.CMD, multiline = true).text)
        assertEquals("curl.exe `\n  'http://h/' `\n  -H 'A: 1'", emit(spec, CurlShell.POWERSHELL, multiline = true).text)
    }

    @Test
    fun `refuses what it cannot represent`() {
        fun refused(spec: RequestSpec, shell: CurlShell, problem: CurlProblem) {
            try {
                emit(spec, shell); fail("expected a refusal for $shell")
            } catch (e: CurlUnsupportedException) {
                assertEquals(problem, e.problem)
            }
        }
        refused(post("a\u0000b"), CurlShell.BASH, CurlProblem.NUL_CHARACTER)
        refused(post("a\ud800b"), CurlShell.BASH, CurlProblem.LONE_SURROGATE)
        refused(RequestSpec(HttpMethod.GET, url, listOf(Header("X", "a\nb"))), CurlShell.BASH, CurlProblem.HEADER_LINE_BREAK)
        refused(RequestSpec(HttpMethod.GET, url, listOf(Header("Bad Name", "a"))), CurlShell.BASH, CurlProblem.HEADER_NAME_INVALID)
        refused(RequestSpec(HttpMethod.GET, "http://h/\u00e9"), CurlShell.CMD, CurlProblem.ARGUMENT_NOT_REPRESENTABLE)
    }

    @Test
    fun `a body line starting with the here-string terminator falls back to base64`() {
        val t = emit(post("a\n'@\nb"), CurlShell.POWERSHELL).text
        assertTrue(t, t.contains("FromBase64String") && !t.contains("= @'"))
    }

    @Test
    fun `speed - 100 headers in every shell`() {
        val headers = (1..100).map { Header("X-Header-$it", "value number $it with \"quotes\" & 50%") }
        val spec = post("{\"a\":1}", headers = headers)
        for (shell in CurlShell.entries) {
            PerfBudget.assertWithin("curl-emit-100-headers-$shell", Limits.BUDGET_WINDOW_OPEN_MS) { emit(spec, shell, multiline = true) }
        }
    }
}
