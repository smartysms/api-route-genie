package com.routegenie.client.curl

import com.routegenie.client.Header
import com.routegenie.client.MultipartPart
import com.routegenie.client.RequestBody
import com.routegenie.client.RequestSpec
import com.routegenie.model.HttpMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parse cases P1..P31 of research 03 (5.5); P32 is CurlRoundTripTest. */
class CurlParserTest {
    private fun ok(text: String, shell: CurlShell? = null, files: Map<String, String> = emptyMap(), ps7: Boolean = false): ParsedCurl =
        (CurlParser.parse(text, shell, files, ps7) as? CurlParseResult.Parsed
            ?: throw AssertionError("not parsed: $text")).request

    private fun json(body: String) = RequestBody.Text("application/json", body)
    private val H = "Content-Type: application/json"

    @Test fun `P1 simple GET`() {
        val p = ok("curl 'http://localhost:8000/items?limit=10'")
        assertEquals(RequestSpec(HttpMethod.GET, "http://localhost:8000/items?limit=10"), p.spec)
    }

    @Test fun `P2 POST with header and body`() {
        val p = ok("""curl -X POST http://h/a -H '$H' --data-raw '{"a":1}'""")
        assertEquals(RequestSpec(HttpMethod.POST, "http://h/a", body = json("""{"a":1}""")), p.spec)
    }

    @Test fun `P3 embedded single quote`() {
        assertEquals(json("""{"name":"O'Brien"}"""), ok("""curl -H '$H' --data-raw '{"name":"O'\''Brien"}' http://h/""").spec.body)
    }

    @Test fun `P4 continuation and options after the URL`() {
        val a = ok("curl -X PUT 'http://h/x' -H 'A: 1'").spec
        val b = ok("curl \\\n  'http://h/x' \\\n  -X PUT \\\n  -H 'A: 1'").spec
        assertEquals(a, b)
    }

    @Test fun `P5 ANSI-C string`() {
        val p = ok("""curl -H '$H' --data-raw ${'$'}'{"a":"it\'s\nx"}' http://h/""")
        assertEquals(json("{\"a\":\"it's\nx\"}"), p.spec.body)
    }

    @Test fun `P6 escaped quote in double quotes`() {
        assertEquals(listOf(Header("X-Test", "a\"b")), ok("""curl -H "X-Test: a\"b" http://h/""").spec.headers)
    }

    @Test fun `P7 variable becomes a placeholder`() {
        val p = ok("""curl -H "Authorization: Bearer ${'$'}TOKEN" http://h/""")
        assertEquals("Bearer {{TOKEN}}", p.spec.headers.single().value)
        assertTrue(p.notes.contains(ParseNote(NoteKind.UNRESOLVED_VARIABLE, "TOKEN")))
    }

    @Test fun `P8 several -d join as a form`() {
        val p = ok("curl -d a=1 -d b=2 http://h/")
        assertEquals(HttpMethod.POST, p.spec.method)
        assertEquals(RequestBody.Form(listOf("a" to "1", "b" to "2")), p.spec.body)
    }

    @Test fun `P9 file reference flagged and --data-raw keeps the at sign`() {
        val a = ok("curl -d @body.json http://h/")
        assertEquals(RequestBody.None, a.spec.body)
        assertTrue(a.ignored.any { it.reason == IgnoredReason.FILE_NOT_READ })
        assertEquals(RequestBody.Text("application/x-www-form-urlencoded", "@x"), ok("curl --data-raw '@x' http://h/").spec.body)
        assertEquals(RequestBody.Form(listOf("a" to "1")), ok("curl -d @body.txt http://h/", files = mapOf("body.txt" to "a=1\r\n")).spec.body)
    }

    @Test fun `P10 -G turns data into the query`() {
        assertEquals("http://h/?q=1", ok("curl -G -d q=1 http://h/").spec.url)
        val p = ok("curl -G --data-urlencode 'q=a b' http://h/")
        assertEquals("http://h/?q=a+b", p.spec.url)
        assertEquals(HttpMethod.GET, p.spec.method)
    }

    @Test fun `P11 multipart parts`() {
        val p = ok("curl -F 'a=b' -F 'f=@/tmp/x.txt;type=text/plain;filename=y.txt' -F 'j=<in.json' http://h/")
        val parts = (p.spec.body as RequestBody.Multipart).parts
        assertEquals(MultipartPart("a", value = "b"), parts[0])
        assertEquals(MultipartPart("f", filePath = "/tmp/x.txt", fileName = "y.txt", contentType = "text/plain"), parts[1])
        assertEquals("j", parts[2].name)
        assertTrue(p.notes.any { it.kind == NoteKind.FILE_AS_PART_TEXT })
    }

    @Test fun `P12 basic auth splits at the first colon`() {
        val h = ok("curl -u 'user:p@ss:word' http://h/").spec.headers.single()
        assertEquals("Basic dXNlcjpwQHNzOndvcmQ=", h.value)
    }

    @Test fun `P13 --json`() {
        val p = ok("""curl --json '{"a":1}' http://h/""")
        assertEquals(HttpMethod.POST, p.spec.method)
        assertEquals(json("""{"a":1}"""), p.spec.body)
        assertEquals(listOf(Header("Accept", "application/json")), p.spec.headers)
    }

    @Test fun `P14 header removal and empty header`() {
        val p = ok("curl -H 'Content-Type:' -H 'X-A;' -H 'X-B: 2' -d a=1 http://h/")
        assertEquals(listOf(Header("X-A", ""), Header("X-B", "2")), p.spec.headers)
    }

    @Test fun `P15 glued and clustered short options`() {
        val p = ok("curl -XPOST -H'X-T: 1' -sSLk http://h/")
        assertEquals(HttpMethod.POST, p.spec.method)
        assertEquals(listOf(Header("X-T", "1")), p.spec.headers)
        assertEquals(CurlFlags(compressed = false, followRedirects = true, insecure = true), p.flags)
    }

    @Test fun `P16 --request=POST is accepted with a warning`() {
        val p = ok("curl --request=POST http://h/")
        assertEquals(HttpMethod.POST, p.spec.method)
        assertTrue(p.notes.any { it.kind == NoteKind.INVALID_LONG_OPTION_SYNTAX })
    }

    @Test fun `P17 scheme is assumed`() {
        val p = ok("curl localhost:8000/x")
        assertEquals("http://localhost:8000/x", p.spec.url)
        assertTrue(p.notes.any { it.kind == NoteKind.SCHEME_ASSUMED })
    }

    @Test fun `P18 HEAD and GET with a body`() {
        assertEquals(HttpMethod.HEAD, ok("curl -I http://h/").spec.method)
        val p = ok("curl -X GET -d a=1 http://h/").spec
        assertEquals(HttpMethod.GET, p.method)
        assertEquals(RequestBody.Form(listOf("a" to "1")), p.body)
    }

    @Test fun `P19 pipe tail is ignored with a note`() {
        val p = ok("curl http://h/ | jq .")
        assertEquals("http://h/", p.spec.url)
        assertTrue(p.notes.any { it.kind == NoteKind.TAIL_IGNORED })
    }

    @Test fun `P20 variables in URL, several URLs, globoff`() {
        val p = ok("""curl "${'$'}BASE/items" -g 'http://h/a[0-1]'""")
        assertEquals("{{BASE}}/items", p.spec.url)
        assertEquals(listOf(IgnoredFlag("http://h/a[0-1]", IgnoredReason.UNSUPPORTED)), p.ignored)
        assertTrue(ok("curl 'http://h/a[0-1]'").notes.any { it.kind == NoteKind.GLOB_URL })
        assertTrue(ok("curl -g 'http://h/a[0-1]'").notes.none { it.kind == NoteKind.GLOB_URL })
    }

    @Test fun `P21 cmd with escaped quotes`() {
        val p = ok("""curl.exe -X POST "http://h/" -H "$H" --data-raw "{\"a\":1}"""", CurlShell.CMD)
        assertEquals(json("""{"a":1}"""), p.spec.body)
        assertEquals(CurlShell.CMD, CurlParser.detect("""curl.exe -X POST "http://h/" --data-raw "{\"a\":1}""""))
    }

    @Test fun `P22 cmd caret goldens give back the bodies`() {
        val a = ok("""curl.exe -X POST "http://h/" -H "$H" --data-raw ^"{\^"name\^":\^"O'Brien \\\^"Bob\\\^"\^",\^"n\^":1}^"""")
        assertEquals(json("""{"name":"O'Brien \"Bob\"","n":1}"""), a.spec.body)
        val b = ok("""curl.exe -X POST "http://h/" -H "$H" --data-raw ^"{\^"a\^":\^"x ^& y ^| z ^> w ^< v ^^ c ^(p^) 50%^ off\^"}^"""")
        assertEquals(json("""{"a":"x & y | z > w < v ^ c (p) 50% off"}"""), b.spec.body)
    }

    @Test fun `P23 cmd continuation`() {
        val p = ok("curl.exe ^\n  \"http://h/\" ^\n  -H \"X-Test: 1\"")
        assertEquals(RequestSpec(HttpMethod.GET, "http://h/", listOf(Header("X-Test", "1"))), p.spec)
    }

    @Test fun `P24 cmd unquoted ampersand ends the command`() {
        val p = ok("curl.exe http://h/?a=1&b=2", CurlShell.CMD)
        assertEquals("http://h/?a=1", p.spec.url)
        assertTrue(p.notes.any { it.kind == NoteKind.CMD_AMPERSAND_ENDED })
    }

    @Test fun `P25 cmd variable in a body`() {
        val p = ok("""curl.exe -H "$H" --data-raw "{\"u\":\"%TOKEN%\"}" http://h/""", CurlShell.CMD)
        assertEquals(json("""{"u":"{{TOKEN}}"}"""), p.spec.body)
        assertTrue(p.notes.contains(ParseNote(NoteKind.UNRESOLVED_VARIABLE, "TOKEN")))
    }

    @Test fun `P26 PowerShell 5_1 style`() {
        val p = ok("""curl.exe -H '$H' -d '{\"a\":1}' http://h/""", CurlShell.POWERSHELL)
        assertEquals(json("""{"a":1}"""), p.spec.body)
        assertEquals(CurlShell.POWERSHELL, CurlParser.detect("""curl.exe -d '{\"a\":1}' http://h/"""))
    }

    @Test fun `P27 PowerShell 7_3 style`() {
        val p = ok("""curl.exe -H '$H' -d '{"a":1}' http://h/""", CurlShell.POWERSHELL)
        assertEquals(json("""{"a":1}"""), p.spec.body)
        assertTrue(p.notes.any { it.kind == NoteKind.POWERSHELL_73_STYLE })
    }

    @Test fun `P28 here-string and temp file idiom`() {
        val spec = RequestSpec(HttpMethod.POST, "http://h/", body = json("""{"name":"O'Brien \"Bob\"","n":1}"""))
        val text = CurlEmitter.emit(spec, CurlOptions(CurlShell.POWERSHELL)).text
        assertEquals(spec, ok(text).spec)
    }

    @Test fun `P29 PowerShell backtick continuation and curl without exe`() {
        val p = ok("curl `\n  'http://h/' `\n  -H 'A: 1'")
        assertEquals(CurlShell.POWERSHELL, p.shell)
        assertEquals(RequestSpec(HttpMethod.GET, "http://h/", listOf(Header("A", "1"))), p.spec)
    }

    @Test fun `P30 Invoke-RestMethod is rejected`() {
        assertEquals(
            CurlParseResult.Rejected(CurlRejection.POWERSHELL_WEB_CMDLET),
            CurlParser.parse("Invoke-RestMethod -Uri http://h/ -Method Post"),
        )
    }

    @Test fun `P31 flags that do not change the request are listed`() {
        val p = ok("curl -x http://proxy:3128 --cacert ca.pem -o out.json -v http://h/")
        assertEquals("http://h/", p.spec.url)
        assertEquals(setOf("-x", "--cacert", "-o"), p.ignored.map { it.flag }.toSet())
    }

    @Test fun `unknown flags are listed and never guessed`() {
        val p = ok("curl --frobnicate http://h/")
        assertEquals(listOf(IgnoredFlag("--frobnicate", IgnoredReason.UNKNOWN_OPTION)), p.ignored)
        assertEquals("http://h/", p.spec.url)
    }

    @Test fun `digest auth is not turned into basic`() {
        val p = ok("curl --digest -u a:b http://h/")
        assertTrue(p.spec.headers.isEmpty())
    }

    @Test fun `rejections`() {
        assertEquals(CurlParseResult.Rejected(CurlRejection.EMPTY), CurlParser.parse("  "))
        assertEquals(CurlParseResult.Rejected(CurlRejection.NOT_CURL), CurlParser.parse("wget http://h/"))
        assertEquals(CurlParseResult.Rejected(CurlRejection.NO_URL), CurlParser.parse("curl -X POST"))
    }

    @Test fun `-X with an unknown verb is reported`() {
        val p = ok("curl -X FOO http://h/")
        assertEquals(HttpMethod.GET, p.spec.method)
        assertTrue(p.notes.any { it.kind == NoteKind.UNKNOWN_METHOD })
    }

    @Test fun `Chrome style bash with dollar-quote and a literal tab`() {
        val p = ok("curl 'http://h/' \\\n  -H 'accept: */*' \\\n  --data-raw \$'{\"a\":\"b\\tc\"}' \\\n  --compressed")
        assertTrue(p.flags.compressed)
        assertEquals(RequestBody.Text("application/x-www-form-urlencoded", "{\"a\":\"b\tc\"}"), p.spec.body)
        assertNull(p.spec.headers.firstOrNull { it.name == "Content-Type" })
    }
}
