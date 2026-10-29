package com.routegenie.ui.design

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import com.routegenie.model.HttpMethod
import com.routegenie.ui.design.color.PaletteSpec
import javax.swing.UIManager
import javax.swing.plaf.ColorUIResource

/** The binding of the palette numbers to JBColors, in both modes, and the theme awareness of the inherited colors. */
class PaletteTest : BasePlatformTestCase() {
    private val originalDark = !JBColor.isBright()

    override fun tearDown() {
        try {
            JBColor.setDark(originalDark)
        } finally {
            super.tearDown()
        }
    }

    private fun rgb(color: java.awt.Color) = color.rgb and 0xFFFFFF

    fun testEverySpecSwatchIsBoundAndNothingElseIs() {
        assertEquals(PaletteSpec.ALL_SWATCHES.map { it.name }.sorted(), Palette.names().sorted())
    }

    fun testOwnTonesResolveToTheirLightAndDarkValues() {
        for (dark in listOf(false, true)) {
            JBColor.setDark(dark)
            for (tone in PaletteSpec.TONES) {
                assertEquals("${tone.name}.fill dark=$dark", tone.fill.of(dark), rgb(Palette.colorOf(tone.fill.name)!!))
                assertEquals("${tone.name}.text dark=$dark", tone.text.of(dark), rgb(Palette.colorOf(tone.text.name)!!))
            }
        }
    }

    fun testThemeCanOverrideATone() {
        val key = Palette.THEME_KEY_PREFIX + PaletteSpec.GREEN.fill.name
        val sentinel = 0x123456
        UIManager.put(key, ColorUIResource(sentinel))
        try {
            assertEquals(sentinel, rgb(Palette.method(HttpMethod.GET).fill))
        } finally {
            UIManager.put(key, null)
        }
        assertEquals(PaletteSpec.GREEN.fill.of(!JBColor.isBright()), rgb(Palette.method(HttpMethod.GET).fill))
    }

    fun testInheritedColorsFollowTheThemeAtOnce() {
        val sentinel = 0x0A0B0C
        for ((key, color) in listOf("Label.foreground" to Palette.textPrimary, "Tree.background" to Palette.surface)) {
            UIManager.put(key, ColorUIResource(sentinel))
            try {
                assertEquals("$key must reach the palette without a restart", sentinel, rgb(color))
            } finally {
                UIManager.put(key, null)
            }
        }
    }

    fun testEveryMethodAndStatusClassHasATone() {
        HttpMethod.entries.forEach { assertNotNull(it.name, Palette.method(it)) }
        StatusClass.entries.forEach { assertNotNull(it.name, Palette.status(it)) }
        val mainVerbs = listOf(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)
        assertEquals("the five main verbs must be told apart by hue", 5, mainVerbs.map { Palette.method(it).name }.toSet().size)
        val statuses = listOf(StatusClass.SUCCESS, StatusClass.REDIRECT, StatusClass.CLIENT_ERROR, StatusClass.SERVER_ERROR)
        assertEquals("2xx, 3xx, 4xx and 5xx must be told apart by hue", 4, statuses.map { Palette.status(it).name }.toSet().size)
    }

    fun testStatusClassBoundaries() {
        assertEquals(StatusClass.UNKNOWN, StatusClass.of(99))
        assertEquals(StatusClass.INFORMATIONAL, StatusClass.of(100))
        assertEquals(StatusClass.INFORMATIONAL, StatusClass.of(199))
        assertEquals(StatusClass.SUCCESS, StatusClass.of(200))
        assertEquals(StatusClass.SUCCESS, StatusClass.of(204))
        assertEquals(StatusClass.REDIRECT, StatusClass.of(301))
        assertEquals(StatusClass.CLIENT_ERROR, StatusClass.of(404))
        assertEquals(StatusClass.SERVER_ERROR, StatusClass.of(599))
        assertEquals(StatusClass.UNKNOWN, StatusClass.of(600))
        assertEquals(StatusClass.UNKNOWN, StatusClass.of(0))
        assertSame(Palette.status(StatusClass.SUCCESS), Palette.status(200))
    }

    fun testTheDarkSwitchOfTheTestHarnessReallyFlipsTheOwnColors() {
        JBColor.setDark(false)
        val light = rgb(Palette.method(HttpMethod.POST).text)
        JBColor.setDark(true)
        val dark = rgb(Palette.method(HttpMethod.POST).text)
        assertTrue("the switch must change the color or the gallery proves nothing", light != dark)
    }
}
