package com.routegenie.files

import com.routegenie.Limits
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant

/**
 * Where generated files live until they are sent: one folder per file under [root], so the file keeps a readable name and two
 * files of the same name never clash. Old folders are swept ([sweep]) and the number is capped, so nothing piles up. It never
 * touches anything outside [root].
 */
class GeneratedFileStore(private val root: Path) {
    /** Writes [made] and returns the path of the new file. */
    fun write(made: MadeFile.Ok): Path {
        Files.createDirectories(root)
        val folder = Files.createTempDirectory(root, FOLDER_PREFIX)
        val path = folder.resolve(made.name)
        Files.write(path, made.file.bytes)
        return path
    }

    /** Deletes a file this store made (and its folder); a path outside [root] is left alone. */
    fun delete(path: Path) {
        val folder = path.toAbsolutePath().normalize().parent ?: return
        if (folder.parent != root.toAbsolutePath().normalize()) return
        try {
            Files.deleteIfExists(path)
            Files.deleteIfExists(folder)
        } catch (e: IOException) {
            // A file that is still open elsewhere is swept later.
        }
    }

    /** Removes folders older than [Limits.GENERATED_FILES_KEEP_DAYS] and, beyond [Limits.GENERATED_FILES_MAX], the oldest ones. */
    fun sweep(now: Instant = Instant.now()) {
        if (!Files.isDirectory(root)) return
        val folders = try {
            Files.newDirectoryStream(root, "$FOLDER_PREFIX*").use { stream -> stream.filter { Files.isDirectory(it) }.map { it to modified(it) } }
        } catch (e: IOException) {
            return
        }
        val limit = now.minus(Duration.ofDays(Limits.GENERATED_FILES_KEEP_DAYS.toLong()))
        val byAge = folders.sortedBy { it.second }
        val surplus = (byAge.size - Limits.GENERATED_FILES_MAX).coerceAtLeast(0)
        byAge.forEachIndexed { index, (folder, time) -> if (time.isBefore(limit) || index < surplus) deleteTree(folder) }
    }

    private fun modified(path: Path): Instant = try {
        Files.getLastModifiedTime(path).toInstant()
    } catch (e: IOException) {
        Instant.EPOCH
    }

    private fun deleteTree(folder: Path) {
        try {
            Files.newDirectoryStream(folder).use { stream -> stream.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(folder)
        } catch (e: IOException) {
            // Left for the next sweep.
        }
    }

    /** For tests: sets the age of a folder. */
    internal fun touch(path: Path, time: Instant) {
        Files.setLastModifiedTime(path.toAbsolutePath().normalize().parent!!, FileTime.from(time))
    }

    private companion object {
        const val FOLDER_PREFIX = "rg-"
    }
}
