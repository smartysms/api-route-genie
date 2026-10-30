package com.routegenie.files

import com.routegenie.Limits
import kotlin.random.Random

/**
 * Makes real, valid, random images: a random-colour gradient with a few translucent shapes, so every seed looks different
 * and a human can see it is a picture. The same seed gives identical bytes. Pure JDK, stateless, safe to call from any
 * number of threads (each call has its own random source and buffers).
 *
 * Size control. With [ImageSpec.targetBytes] the file is padded with random data in a place the format provides for it, to
 * within a few bytes of the target and never above it:
 * - PNG: a private ancillary chunk before IDAT. Reaches target - 11 .. target (a chunk costs 12 bytes).
 * - JPEG: comment (COM) segments after the JFIF header. Reaches target - 3 .. target. If the plain picture is already too
 *   big, lower JPEG qualities are tried, so JPEG can also go smaller than the default.
 * - GIF: one comment extension. Reaches target - 2 .. target (an extension costs 3 bytes at least).
 * - BMP: no legal padding; the size is fixed by width and height, so the target must equal it.
 * A target below the smallest file or above the largest the format can pad to throws [ImageSizeUnreachable] (never a file of
 * the wrong size). Pixel dimensions, pixel count and file size beyond [Limits] throw [FileLimitExceeded].
 */
object ImageSynth {
    fun generate(spec: ImageSpec, random: Random): GeneratedFile {
        checkLimits(spec)
        val width = spec.width
        val height = spec.height
        val target = spec.targetBytes
        val pixels = ImageCanvas.render(width, height, random)
        val bytes = when (spec.format) {
            ImageFormat.BMP -> {
                val file = ImageEncoders.bmp(pixels, width, height)
                if (target != null && target != file.size.toLong()) throw ImageSizeUnreachable(file.size.toLong(), file.size.toLong(), target)
                file
            }
            ImageFormat.PNG -> {
                val plain = ImageEncoders.png(pixels, width, height, 0, random)
                if (target == null) plain else {
                    requireReachable(plain.size, target)
                    val padding = (target - plain.size).toInt()
                    if (padding < ImageEncoders.PNG_CHUNK_OVERHEAD) plain else ImageEncoders.png(pixels, width, height, padding, random)
                }
            }
            ImageFormat.JPEG -> jpeg(pixels, width, height, target, random)
            ImageFormat.GIF -> {
                val plain = ImageEncoders.gif(pixels, width, height, 0, random)
                if (target == null) plain else {
                    requireReachable(plain.size, target)
                    val padding = (target - plain.size).toInt()
                    if (padding < ImageEncoders.GIF_COMMENT_MIN) plain else ImageEncoders.gif(pixels, width, height, padding, random)
                }
            }
        }
        check(target == null || bytes.size <= target) { "generated ${bytes.size} bytes for a target of $target" }
        return GeneratedFile(bytes, spec.format.mime, spec.format.extension, width, height)
    }

    private fun jpeg(pixels: IntArray, width: Int, height: Int, target: Long?, random: Random): ByteArray {
        var plain = ImageEncoders.jpeg(pixels, width, height, Limits.IMAGE_JPEG_QUALITIES.first())
        if (target == null) return plain
        for (quality in Limits.IMAGE_JPEG_QUALITIES.drop(1)) {
            if (plain.size <= target) break
            plain = ImageEncoders.jpeg(pixels, width, height, quality)
        }
        requireReachable(plain.size, target)
        return ImageEncoders.padJpeg(plain, (target - plain.size).toInt(), random)
    }

    private fun requireReachable(plainSize: Int, target: Long) {
        if (target < plainSize || target > Limits.FILE_MAX_BYTES) throw ImageSizeUnreachable(plainSize.toLong(), Limits.FILE_MAX_BYTES, target)
    }

    /** The pixel size used when the caller gives nothing for a format. */
    fun defaultSpec(format: ImageFormat): ImageSpec = ImageSpec(format)

    /**
     * About how many bytes the image will have without padding, for "about N KB" labels, without making it. Exact for BMP;
     * for the other formats a calibrated typical value (the picture is smooth, so real files are usually smaller).
     */
    fun estimateBytes(spec: ImageSpec): Long {
        val pixels = spec.width.toLong() * spec.height
        return when (spec.format) {
            ImageFormat.BMP -> ImageEncoders.bmpSize(spec.width, spec.height)
            ImageFormat.PNG -> modelled(PNG_FIXED_BYTES, pixels, Limits.IMAGE_PNG_MILLI_BYTES_PER_PIXEL, Limits.IMAGE_PNG_BYTES_PER_ROOT_PIXEL)
            ImageFormat.JPEG -> modelled(JPEG_FIXED_BYTES, pixels, Limits.IMAGE_JPEG_MILLI_BYTES_PER_PIXEL, Limits.IMAGE_JPEG_BYTES_PER_ROOT_PIXEL)
            ImageFormat.GIF -> modelled(GIF_FIXED_BYTES, pixels, Limits.IMAGE_GIF_MILLI_BYTES_PER_PIXEL, Limits.IMAGE_GIF_BYTES_PER_ROOT_PIXEL)
        }
    }

    private fun modelled(fixed: Long, pixels: Long, milli: Int, perRoot: Int): Long =
        fixed + pixels * milli / MILLI + (kotlin.math.sqrt(pixels.toDouble()) * perRoot).toLong()

    /** The smallest size a target may ask for, as far as it can be known without encoding (equals [estimateBytes]). */
    fun minimumBytes(spec: ImageSpec): Long = estimateBytes(spec)

    /** The largest size a target may ask for: [Limits.FILE_MAX_BYTES], or for BMP its one fixed size. */
    fun maximumBytes(spec: ImageSpec): Long =
        if (spec.format == ImageFormat.BMP) ImageEncoders.bmpSize(spec.width, spec.height) else Limits.FILE_MAX_BYTES

    /**
     * Clamps a request into what a form field allows, or returns null when no image may be sent (an empty [allowedFormats],
     * for example a field that accepts only application/pdf: correct or absent). The spec's own format is kept when allowed,
     * otherwise the first allowed one is used. Sides are clamped into [minWidth]..[maxWidth] and [minHeight]..[maxHeight]
     * (and the hard limits); then the picture is shrunk, in steps, until its estimated size fits [maxBytes] and the target.
     * If the bounds contradict each other, or no format can fit, the result is null.
     */
    fun fit(
        spec: ImageSpec,
        allowedFormats: Collection<ImageFormat>,
        maxBytes: Long? = null,
        maxWidth: Int? = null,
        maxHeight: Int? = null,
        minWidth: Int? = null,
        minHeight: Int? = null,
    ): ImageSpec? {
        if (allowedFormats.isEmpty()) return null
        val order = if (spec.format in allowedFormats) listOf(spec.format) + allowedFormats.filter { it != spec.format } else allowedFormats.toList()
        val lowW = maxOf(1, minWidth ?: 1)
        val lowH = maxOf(1, minHeight ?: 1)
        val highW = minOf(Limits.IMAGE_MAX_DIMENSION, maxWidth ?: Int.MAX_VALUE)
        val highH = minOf(Limits.IMAGE_MAX_DIMENSION, maxHeight ?: Int.MAX_VALUE)
        if (lowW > highW || lowH > highH) return null
        val byteCap = minOf(Limits.FILE_MAX_BYTES, maxBytes ?: Long.MAX_VALUE, spec.targetBytes ?: Long.MAX_VALUE)
        if (byteCap < 1) return null
        for (format in order) {
            var w = spec.width.coerceIn(lowW, highW)
            var h = spec.height.coerceIn(lowH, highH)
            while (true) {
                val candidate = ImageSpec(format, w, h, spec.targetBytes?.coerceAtMost(maxBytes ?: Long.MAX_VALUE))
                val fits = w.toLong() * h <= Limits.IMAGE_MAX_PIXELS && estimateBytes(candidate) <= byteCap
                if (fits) return candidate
                if (w <= lowW && h <= lowH) break
                w = shrink(w, lowW)
                h = shrink(h, lowH)
            }
        }
        return null
    }

    /** One step smaller (by a fifth, at least one pixel), never below [low]. */
    private fun shrink(value: Int, low: Int): Int = maxOf(low, minOf(value - 1, value * SHRINK_NUM / SHRINK_DEN))

    private fun checkLimits(spec: ImageSpec) {
        require(spec.width >= 1 && spec.height >= 1) { "width and height must be at least 1" }
        if (spec.width > Limits.IMAGE_MAX_DIMENSION) throw FileLimitExceeded(FileLimit.WIDTH, spec.width.toLong(), Limits.IMAGE_MAX_DIMENSION.toLong())
        if (spec.height > Limits.IMAGE_MAX_DIMENSION) throw FileLimitExceeded(FileLimit.HEIGHT, spec.height.toLong(), Limits.IMAGE_MAX_DIMENSION.toLong())
        val pixels = spec.width.toLong() * spec.height
        if (pixels > Limits.IMAGE_MAX_PIXELS) throw FileLimitExceeded(FileLimit.PIXELS, pixels, Limits.IMAGE_MAX_PIXELS)
        val target = spec.targetBytes
        if (target != null && target > Limits.FILE_MAX_BYTES) throw FileLimitExceeded(FileLimit.BYTES, target, Limits.FILE_MAX_BYTES)
        if (spec.format == ImageFormat.BMP) {
            val size = ImageEncoders.bmpSize(spec.width, spec.height)
            if (size > Limits.FILE_MAX_BYTES) throw FileLimitExceeded(FileLimit.BYTES, size, Limits.FILE_MAX_BYTES)
        }
    }

    private const val MILLI = 1000L
    private const val PNG_FIXED_BYTES = 100L
    private const val JPEG_FIXED_BYTES = 700L
    private const val GIF_FIXED_BYTES = 800L
    private const val SHRINK_NUM = 4
    private const val SHRINK_DEN = 5
}
