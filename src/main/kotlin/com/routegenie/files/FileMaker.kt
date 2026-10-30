package com.routegenie.files

import com.routegenie.Limits
import com.routegenie.schema.model.FileSpec
import java.util.Locale
import kotlin.random.Random

/**
 * What the user asked for in the advanced options; every member is optional and null means "decide for me".
 * [maxKb] is an upper bound on the file size in kilobytes, never a promise to reach it.
 */
data class FileChoice(val format: ImageFormat? = null, val width: Int? = null, val height: Int? = null, val maxKb: Int? = null)

sealed interface MadeFile {
    /** A file ready to be written: [name] carries the extension the declared limits allow. */
    class Ok(val file: GeneratedFile, val name: String) : MadeFile

    /** Nothing was made, and why: [bundleKey] and [args] are a message for the user. Nothing is ever made up to fill the gap. */
    class Refused(val bundleKey: String, val args: List<Any> = emptyList()) : MadeFile
}

/**
 * Makes the bytes of one uploaded file so that they satisfy what the code declares ([FileSpec]) and what the user chose
 * ([FileChoice]). Correct or absent: a field that accepts only a type this package cannot write validly (WEBP, SVG, video)
 * gets no file and a reason, and an image is never sent where the code says it wants another type.
 */
object FileMaker {
    private val EXTENSION_TYPES = mapOf(
        "pdf" to "application/pdf", "txt" to "text/plain", "text" to "text/plain", "log" to "text/plain", "md" to "text/plain",
        "csv" to "text/csv", "json" to "application/json", "zip" to "application/zip", "bin" to "application/octet-stream",
    )
    private const val VOWELS = "aeiou"
    private const val CONSONANTS = "bcdfghjklmnprstvz"

    fun make(spec: FileSpec, choice: FileChoice, random: Random): MadeFile {
        val formats = imageFormats(spec)
        val restricted = spec.accept.isNotEmpty() || spec.extensions.isNotEmpty()
        return try {
            when {
                formats.isNotEmpty() -> image(spec, choice, formats, random)
                spec.image -> MadeFile.Refused("files.refused.image_conflict", listOf(declared(spec)))
                !restricted -> image(spec, choice, ImageFormat.entries, random)
                else -> other(spec, random)
            }
        } catch (e: ImageSizeUnreachable) {
            MadeFile.Refused(e.bundleKey, e.args)
        } catch (e: FileLimitExceeded) {
            MadeFile.Refused(e.limit.bundleKey, e.args)
        }
    }

    /** The image formats the declared accept list and extensions both allow; all of them when nothing is declared. */
    internal fun imageFormats(spec: FileSpec): List<ImageFormat> {
        var set: List<ImageFormat> = ImageFormat.entries
        if (spec.accept.isNotEmpty()) set = set.filter { it in ImageFormat.parseAllowed(spec.accept) }
        if (spec.extensions.isNotEmpty()) set = set.filter { f -> spec.extensions.any { ImageFormat.fromExtension(it) == f } }
        return set
    }

    private fun image(spec: FileSpec, choice: FileChoice, formats: List<ImageFormat>, random: Random): MadeFile {
        val format = choice.format?.takeIf { it in formats } ?: formats[random.nextInt(formats.size)]
        val width = choice.width ?: Limits.IMAGE_DEFAULT_WIDTH
        val height = choice.height ?: Limits.IMAGE_DEFAULT_HEIGHT
        val bound = listOfNotNull(spec.maxBytes, choice.maxKb?.let { it * BYTES_PER_KB }).minOrNull()
        val fitted = ImageSynth.fit(
            ImageSpec(format, width, height), formats, bound,
            maxWidth = spec.maxWidth, maxHeight = spec.maxHeight, minWidth = spec.minWidth, minHeight = spec.minHeight,
        ) ?: return MadeFile.Refused("files.refused.no_fit", listOf(declared(spec)))
        val file = ImageSynth.generate(fitted, random)
        if (bound != null && file.size > bound) return MadeFile.Refused("files.refused.too_big", listOf(file.size, bound))
        return MadeFile.Ok(file, name(random, file.extension))
    }

    private fun other(spec: FileSpec, random: Random): MadeFile {
        val types = LinkedHashSet<String>()
        // The extension validators are the strict ones, so they choose first; the accept list then only adds types.
        for (extension in spec.extensions) EXTENSION_TYPES[extension.trim().removePrefix(".").lowercase(Locale.ROOT)]?.let { types += it }
        if (spec.extensions.isEmpty()) types += spec.accept.map { it.substringBefore(';').trim().lowercase(Locale.ROOT) }
        if (spec.accept.any { it.trim() == "*/*" || it.trim() == "*" }) types += "application/octet-stream"
        val type = types.firstOrNull { FileSynth.supports(it) }
            ?: return MadeFile.Refused("files.refused.no_generator", listOf(declared(spec)))
        val file = FileSynth.generate(type, null, random) ?: return MadeFile.Refused("files.refused.no_generator", listOf(declared(spec)))
        val extension = spec.extensions.map { it.trim().removePrefix(".").lowercase(Locale.ROOT) }.firstOrNull { EXTENSION_TYPES[it] == type } ?: file.extension
        if (spec.maxBytes != null && file.size > spec.maxBytes) return MadeFile.Refused("files.refused.too_big", listOf(file.size, spec.maxBytes))
        return MadeFile.Ok(file, name(random, extension))
    }

    private fun declared(spec: FileSpec): String = (spec.accept + spec.extensions).joinToString(", ").ifEmpty { "-" }

    /** A pronounceable lower-case name such as `kebuza.png`. */
    private fun name(random: Random, extension: String): String {
        val length = random.nextInt(Limits.FILE_NAME_MIN_CHARS, Limits.FILE_NAME_MAX_CHARS + 1)
        val base = StringBuilder(length)
        var vowel = random.nextBoolean()
        repeat(length) {
            val alphabet = if (vowel) VOWELS else CONSONANTS
            base.append(alphabet[random.nextInt(alphabet.length)])
            vowel = !vowel
        }
        return "$base.$extension"
    }

    private const val BYTES_PER_KB = 1024L
}
