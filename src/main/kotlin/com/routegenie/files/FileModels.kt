package com.routegenie.files

import com.routegenie.Limits

/**
 * What image to make. [targetBytes] is the wanted file size: the result is as close to it as the format allows and never
 * above it; when that cannot be honored [ImageSynth.generate] throws [ImageSizeUnreachable] instead of returning a file of
 * another size.
 */
data class ImageSpec(
    val format: ImageFormat,
    val width: Int = Limits.IMAGE_DEFAULT_WIDTH,
    val height: Int = Limits.IMAGE_DEFAULT_HEIGHT,
    val targetBytes: Long? = null,
)

/** A made file. [width] and [height] are null for files that are not images. */
class GeneratedFile(val bytes: ByteArray, val mime: String, val extension: String, val width: Int?, val height: Int?) {
    val size: Int get() = bytes.size
    override fun toString(): String = "GeneratedFile($mime, ${bytes.size} bytes${if (width != null) ", ${width}x$height" else ""})"
}

/** Which bound of [Limits] a request went beyond; each has one bundle message. */
enum class FileLimit(val bundleKey: String) {
    WIDTH("files.error.width"),
    HEIGHT("files.error.height"),
    PIXELS("files.error.pixels"),
    BYTES("files.error.bytes"),
}

/** A request beyond a hard limit (size of a side, pixel count, file size). The message arguments are requested then allowed. */
class FileLimitExceeded(val limit: FileLimit, val requested: Long, val allowed: Long) :
    IllegalArgumentException("${limit.name.lowercase()} $requested is beyond the limit $allowed") {
    val args: List<Any> get() = listOf(requested, allowed)
}

/**
 * The wanted size cannot be produced for this image: it is below the smallest file the format makes for these pixel
 * dimensions ([min]) or above the largest the format can legally pad to ([max]; for BMP min equals max, because BMP has no
 * legal padding). The caller may retry with a size in [min]..[max], or smaller dimensions.
 */
class ImageSizeUnreachable(val min: Long, val max: Long, val requested: Long) :
    IllegalArgumentException("a file of $requested bytes is not possible; allowed $min..$max") {
    val bundleKey: String get() = "files.error.size_unreachable"
    val args: List<Any> get() = listOf(requested, min, max)
}
