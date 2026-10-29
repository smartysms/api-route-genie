package com.routegenie.client

import com.routegenie.Limits
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap

/**
 * Where complete large bodies are written while they download. Files are named with [PREFIX], created readable by the owner
 * only where the file system knows permissions (elsewhere the user's temp directory is already private), tracked while alive,
 * and deleted when released, when the owner calls [deleteAll], and at JVM exit. [sweepStale] removes what a killed IDE left.
 */
class SpillDirectory(val dir: Path) {
    private val live = ConcurrentHashMap.newKeySet<Path>()

    init {
        ShutdownCleanup.register(live)
    }

    val liveCount: Int get() = live.size

    /** Creates an empty spill file; the caller writes to it and later releases it through [ResponseBody]. */
    fun create(): Path {
        Files.createDirectories(dir)
        val path = try {
            Files.createTempFile(dir, PREFIX, SUFFIX, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY)))
        } catch (e: UnsupportedOperationException) {
            Files.createTempFile(dir, PREFIX, SUFFIX)
        }
        live.add(path)
        return path
    }

    fun delete(path: Path) {
        live.remove(path)
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            // Still open by a reader on Windows: the shutdown hook and the next sweep retry.
            live.add(path)
        }
    }

    fun deleteAll() = live.toList().forEach { delete(it) }

    /** Deletes spill files of other (dead) sessions older than [olderThanMs]; returns how many were removed. */
    fun sweepStale(olderThanMs: Long = Limits.SPILL_STALE_AGE_MS, now: Long = System.currentTimeMillis()): Int {
        if (!Files.isDirectory(dir)) return 0
        var removed = 0
        Files.newDirectoryStream(dir, "$PREFIX*$SUFFIX").use { files ->
            for (f in files) {
                if (f in live) continue
                val age = now - runCatching { Files.getLastModifiedTime(f).toMillis() }.getOrDefault(now)
                if (age >= olderThanMs && runCatching { Files.deleteIfExists(f) }.getOrDefault(false)) removed++
            }
        }
        return removed
    }

    companion object {
        const val PREFIX = "routegenie-resp-"
        const val SUFFIX = ".bin"
        private const val OWNER_ONLY = "rw-------"

        fun inSystemTemp(): SpillDirectory = SpillDirectory(Path.of(System.getProperty("java.io.tmpdir"), "routegenie"))
    }
}

/** One JVM shutdown hook for all spill directories; it holds only sets of paths, so it keeps nothing else alive. */
private object ShutdownCleanup {
    private val sets = ConcurrentHashMap.newKeySet<Set<Path>>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread({ sets.forEach { s -> s.toList().forEach { runCatching { Files.deleteIfExists(it) } } } }, "RouteGenie-spill-cleanup"))
    }

    fun register(live: Set<Path>) {
        sets.add(live)
    }
}

/**
 * The body of a response: the first bytes in memory ([head], for instant display) and, when the body was bigger than
 * the display cap, the whole body in a spill [file]. [size] is the exact number of (decompressed) bytes unless
 * [truncatedAtLimit]: then the download stopped at the spill limit and [size] is what was written.
 */
class ResponseBody internal constructor(
    val head: ByteArray,
    val file: Path?,
    val size: Long,
    val truncatedAtLimit: Boolean,
    private val owner: SpillDirectory?,
) {
    /** True when [head] is not the whole body. */
    val isCut: Boolean get() = size > head.size

    /** Reads the complete body (from the file when there is one). The caller closes the stream. */
    fun openInputStream(): InputStream =
        if (file != null) Files.newInputStream(file, StandardOpenOption.READ) else head.inputStream()

    private val leases = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var released = false

    /**
     * Deletes the spill file once the owner released it and every [retain]ed lease was closed; the head stays readable.
     * Safe to call more than once.
     */
    fun release() {
        released = true
        deleteIfUnused()
    }

    /**
     * Keeps the spill file alive after [release] until the returned lease is closed (the history keeps a few bodies this
     * way). Closing a lease twice is harmless.
     */
    fun retain(): AutoCloseable {
        leases.incrementAndGet()
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                leases.decrementAndGet()
                deleteIfUnused()
            }
        }
    }

    private fun deleteIfUnused() {
        if (file != null && released && leases.get() == 0) owner?.delete(file)
    }

    companion object {
        val EMPTY = ResponseBody(ByteArray(0), null, 0, false, null)

        fun inMemory(bytes: ByteArray) = ResponseBody(bytes, null, bytes.size.toLong(), false, null)
    }
}

/** Holds the response currently shown; setting a new one releases the previous one's spill file. */
class ResponseSlot : AutoCloseable {
    private var current: ResponseData? = null

    @Synchronized
    fun set(response: ResponseData?) {
        current?.release()
        current = response
    }

    @Synchronized
    fun get(): ResponseData? = current

    override fun close() = set(null)
}
