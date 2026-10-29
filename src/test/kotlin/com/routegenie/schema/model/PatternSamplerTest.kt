package com.routegenie.schema.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternSamplerTest {
    private fun s(p: String) = PatternSampler.sample(p)

    @Test
    fun `literals and anchors`() = assertEquals("abc", s("^abc$"))

    @Test
    fun `digit and word shorthands`() {
        assertEquals("0", s("\\d"))
        assertEquals("a", s("\\w"))
        assertEquals("000", s("^\\d{3}$"))
    }

    @Test
    fun `character classes use the first member`() {
        assertEquals("A", s("^[A-Z]$"))
        assertEquals("a", s("^[a-z0-9_]+$"))
    }

    @Test
    fun `negated classes pick a character outside`() {
        assertEquals("a", s("^[^0-9]$"))
        assertEquals("A", s("^[^a]$"))
    }

    @Test
    fun `alternation takes the first branch`() = assertEquals("cat", s("^(cat|dog)$"))

    @Test
    fun `optional parts are dropped and plus keeps one`() {
        assertEquals("a", s("^ab?c*$"))
        assertEquals("ab", s("^ab+$"))
    }

    @Test
    fun `groups repeat as a whole`() = assertEquals("ababab", s("^(?:ab){3}$"))

    @Test
    fun `named groups of the Python dialect work`() = assertEquals("12", s("^(?P<n>12)$"))

    @Test
    fun `an email like pattern`() {
        val text = s("^\\S+@\\S+\\.\\S+$")
        assertTrue(text, text != null && Regex("^\\S+@\\S+\\.\\S+$").matches(text))
    }

    @Test
    fun `look around is not supported`() {
        assertNull(s("^(?=.*\\d).+$"))
        assertNull(s("(?<!a)b"))
    }

    @Test
    fun `back references and flags are not supported`() {
        assertNull(s("(a)\\1"))
        assertNull(s("(?i)abc"))
    }

    @Test
    fun `a broken pattern is not supported`() {
        assertNull(s("[abc"))
        assertNull(s("(abc"))
        assertNull(s("a{2"))
    }

    @Test
    fun `a pattern that is too long is not read`() = assertNull(s("a".repeat(500)))

    @Test
    fun `open repeats grow to reach the minimum length`() {
        assertEquals("AA", PatternSampler.sample("^[A-Z]+$", 2, 5))
        assertEquals("abab", PatternSampler.sample("^(ab)+$", 4, null))
        assertNull(PatternSampler.sample("^ab$", 5, null))
        assertNull(PatternSampler.sample("^[a-z]{3}$", null, 2))
    }

    @Test
    fun `a huge repeat is refused`() = assertNull(s("a{5000}"))

    @Test
    fun `the result always matches the pattern`() {
        for (p in listOf("^[A-Z]{3}-\\d{4}$", "^[a-z]+(-[a-z]+)*$", "^\\+?\\d{7,15}$", "^#[0-9a-fA-F]{6}$")) {
            val text = s(p)
            assertTrue("$p -> $text", text != null && Regex(p).containsMatchIn(text))
        }
    }
}
