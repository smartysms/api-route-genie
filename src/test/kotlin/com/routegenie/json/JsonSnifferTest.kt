package com.routegenie.json

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonSnifferTest {
    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    @Test
    fun `json content types are recognized`() {
        for (type in listOf("application/json", "application/json; charset=utf-8", "APPLICATION/JSON", "application/problem+json", "application/vnd.api+json", "application/x-ndjson", "application/jsonl", "text/json")) {
            assertTrue(type, JsonSniffer.isJsonContentType(type))
        }
        for (type in listOf("text/html", "application/xml", "text/plain", "application/jsonp", "image/png", "")) {
            assertFalse(type, JsonSniffer.isJsonContentType(type))
        }
        assertFalse(JsonSniffer.isJsonContentType(null))
    }

    @Test
    fun `a body that starts like json is sniffed after white space and a byte order mark`() {
        assertTrue(JsonSniffer.looksLikeJson(bytes("  \n{\"a\":1}")))
        assertTrue(JsonSniffer.looksLikeJson(bytes("[1,2]")))
        assertTrue(JsonSniffer.looksLikeJson(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes("{}")))
        assertFalse(JsonSniffer.looksLikeJson(bytes("<html>")))
        assertFalse(JsonSniffer.looksLikeJson(bytes("hello")))
        assertFalse(JsonSniffer.looksLikeJson(bytes("   ")))
        assertFalse(JsonSniffer.looksLikeJson(ByteArray(0)))
    }

    @Test
    fun `sniffing applies only to a missing or generic content type`() {
        assertTrue(JsonSniffer.isJson(null, bytes("{}")))
        assertTrue(JsonSniffer.isJson("text/plain", bytes("[]")))
        assertTrue(JsonSniffer.isJson("application/octet-stream", bytes("{}")))
        assertFalse(JsonSniffer.isJson("text/html", bytes("{}")))
        assertFalse(JsonSniffer.isJson("image/png", bytes("[1]")))
        assertTrue(JsonSniffer.isJson("application/json", bytes("not json at all")))
    }
}
