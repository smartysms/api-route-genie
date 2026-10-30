package com.routegenie.files

import java.util.Locale

/**
 * The raster formats [ImageSynth] can write. WEBP is deliberately absent: the JDK (and so the IDE's runtime) has no WEBP
 * writer, and a hand-made "pretend" WEBP would be corrupt. A field that accepts only WEBP therefore gets no image.
 */
enum class ImageFormat(val mime: String, val extension: String) {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    GIF("image/gif", "gif"),
    BMP("image/bmp", "bmp");

    /** The name javax.imageio uses for this format (what a decoder reports). */
    val imageIoName: String get() = if (this == JPEG) "jpeg" else extension

    companion object {
        private val byMime: Map<String, ImageFormat> = buildMap {
            for (f in ImageFormat.entries) put(f.mime, f)
            put("image/jpg", JPEG)
            put("image/pjpeg", JPEG)
            put("image/x-png", PNG)
            put("image/x-ms-bmp", BMP)
            put("image/x-bmp", BMP)
        }
        private val byExtension: Map<String, ImageFormat> = buildMap {
            for (f in ImageFormat.entries) put(f.extension, f)
            put("jpeg", JPEG)
            put("jpe", JPEG)
            put("jfif", JPEG)
            put("dib", BMP)
        }

        /** "IMAGE/PNG", "image/png; q=0.8" and the common aliases map to a format; anything else (svg, webp, pdf) gives null. */
        fun fromMime(mime: String): ImageFormat? = byMime[mime.substringBefore(';').trim().lowercase(Locale.ROOT)]

        /** ".JPG", "jpeg" and "png" (case-insensitive, leading dot optional) map to a format; anything else gives null. */
        fun fromExtension(extension: String): ImageFormat? = byExtension[extension.trim().removePrefix(".").lowercase(Locale.ROOT)]

        /**
         * Reads an HTML-style accept list (for example "image/jpeg,image/png,.gif", or the image wildcard, or "jpeg") into the formats this package
         * can write, in the order written and without repeats. Separators are commas, spaces and
         * line breaks. A wildcard (the image wildcard, a lone star, or star slash star) allows all formats. Types that are not images
         * we can write (application/pdf, image/svg+xml, image/webp) contribute nothing, so an accept list made only of
         * those gives an empty list and the caller must not send an image.
         */
        fun parseAllowed(accept: String): List<ImageFormat> = parseAllowed(accept.split(',', ' ', '\n', '\t', '\r'))

        fun parseAllowed(tokens: Iterable<String>): List<ImageFormat> {
            val found = LinkedHashSet<ImageFormat>()
            for (raw in tokens) {
                val token = raw.substringBefore(';').trim().lowercase(Locale.ROOT)
                if (token.isEmpty()) continue
                when {
                    token == "*" || token == "*/*" || token == "image/*" -> found.addAll(entries)
                    '/' in token -> fromMime(token)?.let { found.add(it) }
                    else -> fromExtension(token)?.let { found.add(it) }
                }
            }
            return found.toList()
        }
    }
}
