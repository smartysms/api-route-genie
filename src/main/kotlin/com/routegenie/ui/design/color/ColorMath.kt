package com.routegenie.ui.design.color

/**
 * Color arithmetic on plain 0xRRGGBB integers: no Swing, no IDE classes, so the contrast rules of the palette are checked
 * by tests that start in milliseconds (and the same formulas run when the window paints).
 */
object ColorMath {
    private const val MAX_CHANNEL = 255
    private const val BYTE_MASK = 0xFF
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8

    // WCAG 2.x relative luminance constants.
    private const val SRGB_LINEAR_CUTOFF = 0.03928
    private const val SRGB_LINEAR_DIVISOR = 12.92
    private const val SRGB_GAMMA_OFFSET = 0.055
    private const val SRGB_GAMMA_SCALE = 1.055
    private const val SRGB_GAMMA = 2.4
    private const val LUMA_RED = 0.2126
    private const val LUMA_GREEN = 0.7152
    private const val LUMA_BLUE = 0.0722
    private const val CONTRAST_OFFSET = 0.05

    fun red(rgb: Int): Int = (rgb shr RED_SHIFT) and BYTE_MASK
    fun green(rgb: Int): Int = (rgb shr GREEN_SHIFT) and BYTE_MASK
    fun blue(rgb: Int): Int = rgb and BYTE_MASK

    fun rgb(red: Int, green: Int, blue: Int): Int =
        (red.coerceIn(0, MAX_CHANNEL) shl RED_SHIFT) or (green.coerceIn(0, MAX_CHANNEL) shl GREEN_SHIFT) or blue.coerceIn(0, MAX_CHANNEL)

    /** Relative luminance of an sRGB color, 0.0 (black) to 1.0 (white). */
    fun luminance(rgb: Int): Double =
        LUMA_RED * linear(red(rgb)) + LUMA_GREEN * linear(green(rgb)) + LUMA_BLUE * linear(blue(rgb))

    /** WCAG contrast ratio between two opaque colors, 1.0 (identical) to 21.0 (black on white); symmetric. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + CONTRAST_OFFSET) / (darker + CONTRAST_OFFSET)
    }

    /** The color that painting [foreground] with [alpha] (0.0 to 1.0) over the opaque [background] produces. */
    fun blend(foreground: Int, background: Int, alpha: Double): Int {
        val a = alpha.coerceIn(0.0, 1.0)
        fun channel(f: Int, b: Int): Int = Math.round(f * a + b * (1.0 - a)).toInt()
        return rgb(
            channel(red(foreground), red(background)),
            channel(green(foreground), green(background)),
            channel(blue(foreground), blue(background)),
        )
    }

    fun toHex(rgb: Int): String = "#%06X".format(rgb and 0xFFFFFF)

    private fun linear(channel: Int): Double {
        val c = channel.toDouble() / MAX_CHANNEL
        return if (c <= SRGB_LINEAR_CUTOFF) c / SRGB_LINEAR_DIVISOR else Math.pow((c + SRGB_GAMMA_OFFSET) / SRGB_GAMMA_SCALE, SRGB_GAMMA)
    }
}
