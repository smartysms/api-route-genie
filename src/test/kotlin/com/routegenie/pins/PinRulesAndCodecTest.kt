package com.routegenie.pins

import com.routegenie.Limits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests of what a pin may be and of the text of `pins.json`: no IDE, no files. */
class PinRulesAndCodecTest {
    // --- secrets ---------------------------------------------------------------------------------------------------------

    @Test
    fun `names that look like secrets are refused`() {
        listOf(
            "settings.SECRET_KEY", "settings.DB_PASSWORD", "settings.api_token", "settings.ApiKey", "settings.accessToken",
            "settings.AWS_CREDENTIALS", "os.environ[\"SMTP_PASSWD\"]", "settings.JWT_SECRET", "config.KEYS", "settings.secretKeyBase",
        ).forEach { assertEquals("$it must be refused", PinProblem.EXPRESSION_SECRET, PinRules.checkExpression(it)) }
    }

    @Test
    fun `ordinary prefix names are accepted`() {
        listOf(
            "settings.API_V1_STR", "settings.BASE_PATH", "app.config[\"API_PREFIX\"]", "os.environ[\"URL_PREFIX\"]",
            "settings.KEYCLOAK_PREFIX", "settings.MONKEY_ROOT", "config.STATIC_URL", "settings.PASSTHROUGH_PREFIX",
        ).forEach { assertNull("$it must be accepted", PinRules.checkExpression(it)) }
    }

    @Test
    fun `an expression must be a clean bounded single line`() {
        assertEquals(PinProblem.EXPRESSION_EMPTY, PinRules.checkExpression("  "))
        assertEquals(PinProblem.EXPRESSION_TOO_LONG, PinRules.checkExpression("a".repeat(Limits.PIN_MAX_EXPRESSION_CHARS + 1)))
        assertEquals(PinProblem.EXPRESSION_CHARS, PinRules.checkExpression("settings.X\n"))
        assertEquals(PinProblem.EXPRESSION_CHARS, PinRules.checkExpression(" settings.X"))
    }

    // --- values ----------------------------------------------------------------------------------------------------------

    @Test
    fun `a prefix for FastAPI or Flask starts with a slash and does not end with one`() {
        assertNull(PinRules.checkValue("/api/v1", requireLeadingSlash = true))
        assertNull(PinRules.checkValue("/", requireLeadingSlash = true))
        assertEquals(PinProblem.VALUE_SLASH_START, PinRules.checkValue("api/v1", requireLeadingSlash = true))
        assertEquals(PinProblem.VALUE_SLASH_END, PinRules.checkValue("/api/", requireLeadingSlash = true))
    }

    @Test
    fun `an empty value is a real prefix and is always allowed`() {
        assertNull(PinRules.checkValue("", requireLeadingSlash = true))
        assertNull(PinRules.checkValue("", requireLeadingSlash = false))
    }

    @Test
    fun `Django route text may end with a slash and has no leading one`() {
        assertNull(PinRules.checkValue("netbox/", requireLeadingSlash = false))
        assertNull(PinRules.checkValue("api/v2/", requireLeadingSlash = false))
    }

    @Test
    fun `spaces dots controls and url syntax characters are refused`() {
        assertEquals(PinProblem.VALUE_BLANK_CHARS, PinRules.checkValue("/a b", requireLeadingSlash = true))
        assertEquals(PinProblem.VALUE_BLANK_CHARS, PinRules.checkValue("/a\t", requireLeadingSlash = true))
        assertEquals(PinProblem.VALUE_DOTS, PinRules.checkValue("/a/../b", requireLeadingSlash = true))
        assertEquals(PinProblem.VALUE_CHARS, PinRules.checkValue("/a?x=1", requireLeadingSlash = true))
        assertEquals(PinProblem.VALUE_CHARS, PinRules.checkValue("/a#b", requireLeadingSlash = true))
        assertEquals(PinProblem.VALUE_CHARS, PinRules.checkValue("/a\\b", requireLeadingSlash = true))
        assertEquals(PinProblem.VALUE_CHARS, PinRules.checkValue("/a\u0001", requireLeadingSlash = true))
        assertEquals(PinProblem.VALUE_TOO_LONG, PinRules.checkValue("/" + "a".repeat(Limits.PIN_MAX_VALUE_CHARS), requireLeadingSlash = true))
    }

    // --- the file --------------------------------------------------------------------------------------------------------

    private fun pin(expression: String, value: String, note: String = "") = Pin(expression, value, note)

    @Test
    fun `missing empty and blank text mean no pins and no problem`() {
        listOf("", "   ", "\n\n", "\uFEFF").forEach {
            val read = PinsCodec.read(it)
            assertTrue(read.pins.isEmpty())
            assertNull(read.problem)
            assertTrue(read.isClean)
        }
    }

    @Test
    fun `a written file reads back the same pins sorted by expression`() {
        val pins = listOf(pin("settings.Z", "/z"), pin("settings.A", "/a", "default in app/config.py"), pin("os.environ[\"M\"]", ""))
        val text = PinsCodec.write(pins)
        val read = PinsCodec.read(text)
        assertTrue(read.isClean)
        assertEquals(listOf("os.environ[\"M\"]", "settings.A", "settings.Z"), read.pins.map { it.expression })
        assertEquals(pins.sortedBy { it.expression }, read.pins)
    }

    @Test
    fun `the file has one pin per line, sorted keys, a version and a final newline`() {
        val text = PinsCodec.write(listOf(pin("settings.B", "/b"), pin("settings.A", "/a", "n")))
        assertEquals(
            "{\n  \"version\": 1,\n  \"pins\": {\n" +
                "    \"settings.A\": {\"value\": \"/a\", \"note\": \"n\"},\n" +
                "    \"settings.B\": {\"value\": \"/b\"}\n" +
                "  }\n}\n",
            text,
        )
    }

    @Test
    fun `writing the same pins twice gives identical text so a commit shows only real changes`() {
        val pins = listOf(pin("settings.B", "/b"), pin("settings.A", "/a"))
        assertEquals(PinsCodec.write(pins), PinsCodec.write(pins.reversed()))
        assertEquals(PinsCodec.write(pins), PinsCodec.write(PinsCodec.read(PinsCodec.write(pins)).pins))
    }

    @Test
    fun `an empty file is written as an object with no pins`() {
        assertEquals("{\n  \"version\": 1,\n  \"pins\": {}\n}\n", PinsCodec.write(emptyList()))
    }

    @Test
    fun `CRLF files are read and the line ending is kept when writing`() {
        val crlf = PinsCodec.write(listOf(pin("settings.A", "/a")), eol = "\r\n")
        assertTrue(crlf.contains("\r\n"))
        assertFalse(crlf.replace("\r\n", "").contains('\n'))
        val read = PinsCodec.read(crlf)
        assertEquals("\r\n", read.eol)
        assertEquals(listOf("settings.A"), read.pins.map { it.expression })
        assertEquals(crlf, PinsCodec.write(read.pins, read.eol))
    }

    @Test
    fun `notes with quotes backslashes and Windows paths survive`() {
        val note = "default in C:\\work\\app\\config.py: \"x\"\tdone"
        val read = PinsCodec.read(PinsCodec.write(listOf(pin("settings.A", "/a", note))))
        assertEquals(note, read.pins.single().note)
    }

    @Test
    fun `a broken file is no pins plus one problem with the position, never an exception`() {
        listOf("{", "{\"version\": 1,", "not json", "{\"pins\": {\"a\": }}", "{\"pins\": [1,}", "{'a': 1}", "{\"a\": \"\\q\"}").forEach {
            val read = PinsCodec.read(it)
            assertTrue("$it", read.pins.isEmpty())
            assertEquals("$it", PinsFileProblem.BROKEN, read.problem)
            assertTrue("$it", read.where != null && read.where!!.matches(Regex("""\d+:\d+""")))
        }
    }

    @Test
    fun `a syntax error reports its line and column`() {
        val read = PinsCodec.read("{\n  \"version\": 1,\n  \"pins\": {\n    oops\n  }\n}\n")
        assertEquals("4:5", read.where)
    }

    @Test
    fun `a file that is not an object is reported`() {
        assertEquals(PinsFileProblem.NOT_AN_OBJECT, PinsCodec.read("[1, 2]").problem)
        assertEquals(PinsFileProblem.NOT_AN_OBJECT, PinsCodec.read("\"text\"").problem)
    }

    @Test
    fun `hand edited files are read leniently`() {
        val read = PinsCodec.read(
            """
            {
              "version": 1,
              "comment": "ignored",
              "pins": {
                "settings.A": "/short-form",
                "settings.B": {"value": "/b", "extra": [1, 2, {"x": null}], "by": "me", "at": "2026-10-30"}
              },
              "other": true
            }
            """.trimIndent(),
        )
        assertTrue(read.isClean)
        assertEquals(listOf("/short-form", "/b"), read.pins.map { it.value })
        assertEquals("me", read.pins[1].by)
        assertEquals("2026-10-30", read.pins[1].at)
    }

    @Test
    fun `invalid entries are skipped and counted while valid ones are kept`() {
        val read = PinsCodec.read(
            """{"pins": {
              "settings.OK": {"value": "/ok"},
              "settings.SECRET_KEY": {"value": "/x"},
              "settings.SPACE": {"value": "/a b"},
              "settings.DOTS": {"value": "/../x"},
              "settings.NOVALUE": {"note": "n"},
              "settings.NUMBER": {"value": 5}
            }}""",
        )
        assertEquals(listOf("settings.OK"), read.pins.map { it.expression })
        assertEquals(5, read.skipped.size)
        assertEquals(PinProblem.EXPRESSION_SECRET, read.skipped.first { it.expression == "settings.SECRET_KEY" }.problem)
        assertFalse(read.isClean)
        assertTrue(read.canWrite)
    }

    @Test
    fun `a newer file version is read but never rewritten`() {
        val read = PinsCodec.read("""{"version": 2, "pins": {"settings.A": {"value": "/a"}}}""")
        assertEquals(PinsFileProblem.NEWER_VERSION, read.problem)
        assertFalse(read.canWrite)
        assertEquals(listOf("settings.A"), read.pins.map { it.expression })
    }

    @Test
    fun `a file above the size limit is not parsed`() {
        val read = PinsCodec.read(" ".repeat(Limits.PINS_FILE_MAX_BYTES + 1))
        assertEquals(PinsFileProblem.TOO_LARGE, read.problem)
        assertFalse(read.canWrite)
    }

    @Test
    fun `more pins than the limit are cut and reported`() {
        val body = (0 until Limits.MAX_PINS + 3).joinToString(",") { "\"settings.P$it\": {\"value\": \"/p$it\"}" }
        val read = PinsCodec.read("{\"pins\": {$body}}")
        assertEquals(Limits.MAX_PINS, read.pins.size)
        assertEquals(3, read.skipped.count { it.problem == PinProblem.TOO_MANY })
    }

    @Test
    fun `deeply nested junk is refused instead of overflowing the stack`() {
        val read = PinsCodec.read("{\"pins\": " + "[".repeat(5000) + "]".repeat(5000) + "}")
        assertEquals(PinsFileProblem.BROKEN, read.problem)
    }

    @Test
    fun `the last of two equal keys wins like in every JSON reader`() {
        val read = PinsCodec.read("""{"pins": {"settings.A": {"value": "/one"}, "settings.A": {"value": "/two"}}}""")
        assertEquals(listOf("/two"), read.pins.map { it.value })
    }

    @Test
    fun `unicode and escapes in values and notes are decoded`() {
        val read = PinsCodec.read("{\"pins\": {\"settings.A\": {\"value\": \"/caf\\u00e9\", \"note\": \"a\\nb\"}}}")
        assertEquals("/caf\u00e9", read.pins.single().value)
        assertEquals("a\nb", read.pins.single().note)
    }
}
