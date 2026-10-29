package com.routegenie.ui.design

import com.routegenie.ui.design.color.ColorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorMathTest {
    @Test
    fun `black on white is 21 to 1 and equal colors are 1 to 1`() {
        assertEquals(21.0, ColorMath.contrast(0x000000, 0xFFFFFF), 1e-9)
        assertEquals(1.0, ColorMath.contrast(0x336699, 0x336699), 1e-9)
    }

    @Test
    fun `contrast is symmetric`() {
        assertEquals(ColorMath.contrast(0x2A58C8, 0xFFFFFF), ColorMath.contrast(0xFFFFFF, 0x2A58C8), 1e-12)
    }

    @Test
    fun `the well known WCAG boundary greys`() {
        // #767676 is the lightest grey that passes AA on white, #777777 just misses it.
        assertTrue(ColorMath.contrast(0x767676, 0xFFFFFF) >= 4.5)
        assertTrue(ColorMath.contrast(0x777777, 0xFFFFFF) < 4.5)
    }

    @Test
    fun `luminance of the primaries`() {
        assertEquals(1.0, ColorMath.luminance(0xFFFFFF), 1e-9)
        assertEquals(0.0, ColorMath.luminance(0x000000), 1e-9)
        assertEquals(0.2126, ColorMath.luminance(0xFF0000), 1e-4)
        assertEquals(0.7152, ColorMath.luminance(0x00FF00), 1e-4)
        assertEquals(0.0722, ColorMath.luminance(0x0000FF), 1e-4)
    }

    @Test
    fun `blend follows source over compositing`() {
        assertEquals(0x2B2D30, ColorMath.blend(0xFFFFFF, 0x2B2D30, 0.0))
        assertEquals(0xFFFFFF, ColorMath.blend(0xFFFFFF, 0x2B2D30, 1.0))
        assertEquals(0x808080, ColorMath.blend(0x000000, 0xFFFFFF, 0.5))
        assertEquals(0x808080, ColorMath.blend(0xFFFFFF, 0x000000, 0.5))
        // Out of range alpha is clamped instead of producing channels outside 0..255.
        assertEquals(0x000000, ColorMath.blend(0x000000, 0xFFFFFF, 7.0))
    }

    @Test
    fun `channels round trip`() {
        val rgb = ColorMath.rgb(0x12, 0x34, 0x56)
        assertEquals(0x123456, rgb)
        assertEquals(listOf(0x12, 0x34, 0x56), listOf(ColorMath.red(rgb), ColorMath.green(rgb), ColorMath.blue(rgb)))
        assertEquals("#123456", ColorMath.toHex(rgb))
        assertEquals(0xFF00FF, ColorMath.rgb(300, -5, 255))
    }
}
