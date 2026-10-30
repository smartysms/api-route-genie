package com.routegenie.files

import com.routegenie.Limits
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Paints the random picture into an int-per-pixel buffer (0xRRGGBB, row by row): a three-stop gradient at a random angle
 * and a few translucent circles, rectangles and rings. Everything is plain integer arithmetic on an array: no Graphics2D,
 * no fonts, no toolkit, so it behaves the same headless on a CI runner as in the IDE, and it is fast.
 */
internal object ImageCanvas {
    private const val PALETTE_SIZE = 256
    private const val FIXED_SHIFT = 16
    private const val RB_MASK = 0xFF00FF
    private const val G_MASK = 0x00FF00
    private const val ALPHA_ONE = 256

    fun render(width: Int, height: Int, random: Random): IntArray {
        val pixels = IntArray(width * height)
        paintGradient(pixels, width, height, random)
        val shapes = random.nextInt(Limits.IMAGE_SHAPES_MIN, Limits.IMAGE_SHAPES_MAX + 1)
        val scale = max(MIN_SCALE, sqrt(width.toDouble() * height).toInt())
        repeat(shapes) { paintShape(pixels, width, height, scale, random) }
        return pixels
    }

    private fun paintGradient(pixels: IntArray, width: Int, height: Int, random: Random) {
        val palette = IntArray(PALETTE_SIZE)
        val hue = random.nextFloat()
        val c0 = hsv(hue, random.nextFloat() * 0.4f + 0.5f, random.nextFloat() * 0.4f + 0.6f)
        val c1 = hsv((hue + random.nextFloat() * 0.5f + 0.15f) % 1f, random.nextFloat() * 0.4f + 0.5f, random.nextFloat() * 0.4f + 0.6f)
        val c2 = hsv((hue + random.nextFloat() * 0.5f + 0.5f) % 1f, random.nextFloat() * 0.4f + 0.5f, random.nextFloat() * 0.5f + 0.5f)
        val half = PALETTE_SIZE / 2
        for (i in 0 until PALETTE_SIZE) {
            palette[i] = if (i < half) mix(c0, c1, i * ALPHA_ONE / half) else mix(c1, c2, (i - half) * ALPHA_ONE / half)
        }
        val random360 = random.nextDouble() * Math.PI * 2
        // A one-pixel strip only varies along its long side, so the gradient must run that way or it would be flat.
        val angle = if (height == 1) 0.0 else if (width == 1) Math.PI / 2 else random360
        val dx = cos(angle)
        val dy = sin(angle)
        // Projection of the four corners gives the span, so the palette is used completely at any angle and aspect ratio.
        val corners = doubleArrayOf(0.0, (width - 1) * dx, (height - 1) * dy, (width - 1) * dx + (height - 1) * dy)
        val low = corners.min()
        val span = max(1.0, corners.max() - low)
        val stepX = (dx * (PALETTE_SIZE - 1) / span * (1 shl FIXED_SHIFT)).toInt()
        val stepY = (dy * (PALETTE_SIZE - 1) / span * (1 shl FIXED_SHIFT)).toInt()
        val origin = (-low * (PALETTE_SIZE - 1) / span * (1 shl FIXED_SHIFT)).toInt()
        val top = PALETTE_SIZE - 1
        var rowBase = origin
        var at = 0
        for (y in 0 until height) {
            var t = rowBase
            for (x in 0 until width) {
                val i = t shr FIXED_SHIFT
                pixels[at++] = palette[if (i < 0) 0 else if (i > top) top else i]
                t += stepX
            }
            rowBase += stepY
        }
    }

    private fun paintShape(pixels: IntArray, width: Int, height: Int, scale: Int, random: Random) {
        val color = hsv(random.nextFloat(), random.nextFloat() * 0.5f + 0.4f, random.nextFloat() * 0.5f + 0.5f)
        val alpha = random.nextInt(ALPHA_MIN, ALPHA_MAX)
        val cx = random.nextInt(width)
        val cy = random.nextInt(height)
        val radius = max(1, (scale * (random.nextFloat() * 0.25f + 0.07f)).toInt())
        when (random.nextInt(SHAPE_KINDS)) {
            0 -> disc(pixels, width, height, cx, cy, radius, 0, color, alpha)
            1 -> ring(pixels, width, height, cx, cy, radius, color, alpha)
            else -> {
                val halfW = max(1, radius + random.nextInt(radius + 1) / 2)
                val halfH = max(1, radius + random.nextInt(radius + 1) / 2)
                for (y in max(0, cy - halfH)..min(height - 1, cy + halfH)) {
                    span(pixels, y * width, max(0, cx - halfW), min(width - 1, cx + halfW), color, alpha)
                }
            }
        }
    }

    /** Filled circle, or with [inner] > 0 a ring of that inner radius. */
    private fun disc(pixels: IntArray, width: Int, height: Int, cx: Int, cy: Int, radius: Int, inner: Int, color: Int, alpha: Int) {
        val r2 = radius.toLong() * radius
        val i2 = inner.toLong() * inner
        for (y in max(0, cy - radius)..min(height - 1, cy + radius)) {
            val dy = (y - cy).toLong()
            val outer = sqrt((r2 - dy * dy).toDouble()).toInt()
            val base = y * width
            if (inner > 0 && dy * dy < i2) {
                val hole = sqrt((i2 - dy * dy).toDouble()).toInt()
                span(pixels, base, max(0, cx - outer), min(width - 1, cx - hole - 1), color, alpha)
                span(pixels, base, max(0, cx + hole + 1), min(width - 1, cx + outer), color, alpha)
            } else {
                span(pixels, base, max(0, cx - outer), min(width - 1, cx + outer), color, alpha)
            }
        }
    }

    private fun ring(pixels: IntArray, width: Int, height: Int, cx: Int, cy: Int, radius: Int, color: Int, alpha: Int) =
        disc(pixels, width, height, cx, cy, radius, radius * 2 / 3, color, alpha)

    private fun span(pixels: IntArray, base: Int, from: Int, to: Int, color: Int, alpha: Int) {
        val inverse = ALPHA_ONE - alpha
        val srcRb = (color and RB_MASK) * alpha
        val srcG = (color and G_MASK) * alpha
        for (x in from..to) {
            val d = pixels[base + x]
            val rb = ((srcRb + (d and RB_MASK) * inverse) shr 8) and RB_MASK
            val g = ((srcG + (d and G_MASK) * inverse) shr 8) and G_MASK
            pixels[base + x] = rb or g
        }
    }

    private fun mix(a: Int, b: Int, alpha: Int): Int {
        val inverse = ALPHA_ONE - alpha
        val rb = (((a and RB_MASK) * inverse + (b and RB_MASK) * alpha) shr 8) and RB_MASK
        val g = (((a and G_MASK) * inverse + (b and G_MASK) * alpha) shr 8) and G_MASK
        return rb or g
    }

    private fun hsv(h: Float, s: Float, v: Float): Int {
        val sector = (h * 6f).toInt()
        val f = h * 6f - sector
        val p = v * (1f - s)
        val q = v * (1f - f * s)
        val t = v * (1f - (1f - f) * s)
        val (r, g, b) = when (sector % 6) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    private fun channel(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    private const val MIN_SCALE = 2
    private const val ALPHA_MIN = 90
    private const val ALPHA_MAX = 230
    private const val SHAPE_KINDS = 3
}
