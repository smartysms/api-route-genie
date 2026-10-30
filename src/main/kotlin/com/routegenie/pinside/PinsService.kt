package com.routegenie.pinside

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.routegenie.Branding
import com.routegenie.Limits
import com.routegenie.discovery.core.PinEnv
import com.routegenie.discovery.core.RouteDiscoveryService
import com.routegenie.pins.Pin
import com.routegenie.pins.PinProblem
import com.routegenie.pins.PinRules
import com.routegenie.pins.PinsCodec
import com.routegenie.pins.PinsFileProblem
import com.routegenie.pins.PinsRead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate

/** What the pins file says right now, as the UI and the engine see it. */
data class PinsState(
    val pins: List<Pin> = emptyList(),
    val problem: PinsFileProblem? = null,
    val where: String? = null,
    val skippedCount: Int = 0,
    val canWrite: Boolean = true,
    val eol: String = "\n",
) {
    val byExpression: Map<String, Pin> get() = pins.associateBy { it.expression }

    companion object {
        val EMPTY = PinsState()

        fun of(read: PinsRead) = PinsState(read.pins, read.problem, read.where, read.skipped.size, read.canWrite, read.eol)
    }
}

/** The result of changing a pin. */
sealed interface PinResult {
    data object Saved : PinResult

    /** The value or expression breaks a rule; nothing was written. */
    data class Refused(val problem: PinProblem) : PinResult

    /** The file cannot be rewritten without losing data (newer format) or the disk refused. */
    data class Failed(val file: PinsFileProblem?, val message: String?) : PinResult
}

/**
 * The pins of a project: user-confirmed values for URL prefixes that the code takes from a setting. They live in
 * `<project>/<Branding.DIR>/pins.json`, a small file meant to be committed. This service keeps the parsed copy, applies
 * external edits (a `git pull` changes the file; the VFS listener reloads it), and writes changes atomically, merging into
 * what is on disk at that moment so a concurrent edit by hand or by a teammate's checkout is not lost.
 *
 * Nothing here ever throws on a missing, empty, hand-edited or broken file: the state then holds no pins (or the readable
 * ones) and one problem for the UI to show.
 */
@Service(Service.Level.PROJECT)
class PinsService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val log = Logger.getInstance(PinsService::class.java)
    private val state = MutableStateFlow(PinsState.EMPTY)
    private val dismissedNow = MutableStateFlow<Set<String>>(emptySet())
    private val reloads = Channel<Unit>(Channel.CONFLATED)
    private val writeLock = Any()

    @Volatile
    private var loaded = false

    @Volatile
    private var environment: PinEnv = PinEnv.EMPTY

    /** Tests point this at a temporary directory; null means the project's base path. */
    @Volatile
    var baseDirOverride: Path? = null

    val snapshot: StateFlow<PinsState> get() = state

    /** Settings the user answered "Not now" to in this IDE session; never written to the shared file. */
    val dismissed: StateFlow<Set<String>> get() = dismissedNow

    /** The pins as plain data for the linkers; reads the file once if nobody has yet. */
    val env: PinEnv
        get() {
            ensureLoaded()
            return environment
        }

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val target = filePath()?.toString()?.let(::normalized) ?: return
                if (events.any { touches(it.path, target) }) reloads.trySend(Unit)
            }
        })
        scope.launch(Dispatchers.IO) {
            ensureLoaded()
            for (signal in reloads) reload()
        }
    }

    /** The pins file, whether or not it exists; null when the project has no directory. */
    fun filePath(): Path? {
        val base = baseDirOverride ?: project.basePath?.let { Path.of(it) } ?: return null
        return base.resolve(Branding.DIR).resolve(FILE_NAME)
    }

    /** True for a change to the file itself or to a folder above it (deleted, moved or renamed as a whole). */
    private fun touches(eventPath: String, target: String): Boolean {
        val path = normalized(eventPath)
        return path.equals(target, ignoreCase = true) || target.startsWith("$path/", ignoreCase = true)
    }

    private fun normalized(path: String): String = path.replace('\\', '/').trimEnd('/')

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        reload()
    }

    /** Reads the file again and publishes the new state when it differs. Blocking disk read: call from a background thread. */
    fun reload() {
        loaded = true
        val read = readFile()
        apply(read)
    }

    private fun readFile(): PinsRead {
        val file = filePath() ?: return PinsRead.EMPTY
        return try {
            if (!Files.isRegularFile(file)) return PinsRead.EMPTY
            if (Files.size(file) > Limits.PINS_FILE_MAX_BYTES) return PinsRead(emptyList(), PinsFileProblem.TOO_LARGE, canWrite = false)
            PinsCodec.read(String(Files.readAllBytes(file), Charsets.UTF_8))
        } catch (e: IOException) {
            // An unreadable file (locked, permission) is "no pins" plus one message; the next change event tries again.
            log.info("Cannot read $file: ${e.javaClass.simpleName}")
            PinsRead(emptyList(), PinsFileProblem.BROKEN, where = null, canWrite = false)
        } catch (e: RuntimeException) {
            log.warn("Pins file could not be interpreted", e)
            PinsRead(emptyList(), PinsFileProblem.BROKEN, canWrite = false)
        }
    }

    private fun apply(read: PinsRead) {
        val next = PinsState.of(read)
        if (next == state.value) return
        state.value = next
        val values = next.pins.associate { it.expression to it.value }
        val changedValues = environment.values != values
        environment = PinEnv(values)
        // The service of discovery may not exist yet (window never opened); its first pass reads [env] anyway.
        if (changedValues) project.getServiceIfCreated(RouteDiscoveryService::class.java)?.requestRefresh()
    }

    // --- changes ---------------------------------------------------------------------------------------------------

    /** Stores [pin] ([requireLeadingSlash] as the rule of the framework that uses it). Blocking: call from a background thread. */
    fun set(pin: Pin, requireLeadingSlash: Boolean): PinResult {
        PinRules.check(pin, requireLeadingSlash)?.let { return PinResult.Refused(it) }
        val stamped = pin.copy(at = pin.at.ifEmpty { LocalDate.now().toString() }, note = PinRules.cutNote(pin.note))
        return change { current -> current.filter { it.expression != stamped.expression } + stamped }
    }

    /** Removes the pin of [expression]; removing one that is not there is a success. */
    fun remove(expression: String): PinResult = change { current -> current.filter { it.expression != expression } }

    private fun change(edit: (List<Pin>) -> List<Pin>): PinResult = synchronized(writeLock) {
        val file = filePath() ?: return PinResult.Failed(null, null)
        // Merge into what is on disk now, not into the copy in memory: a hand edit or a checkout since the last read survives.
        val disk = readFile()
        if (!disk.canWrite) return PinResult.Failed(disk.problem, null)
        val pins = edit(disk.pins).take(Limits.MAX_PINS)
        val text = PinsCodec.write(pins, disk.eol)
        try {
            writeAtomically(file, text)
        } catch (e: IOException) {
            log.warn("Could not write $file", e)
            return PinResult.Failed(null, e.message)
        }
        apply(PinsCodec.read(text))
        refreshVfs(file)
        PinResult.Saved
    }

    private fun writeAtomically(file: Path, text: String) {
        val dir = file.parent
        Files.createDirectories(dir)
        val temp = Files.createTempFile(dir, FILE_NAME, TEMP_SUFFIX)
        try {
            Files.write(temp, text.toByteArray(Charsets.UTF_8))
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun refreshVfs(file: Path) {
        // Asynchronous: the editor and the project view learn about the new file without blocking this thread.
        runCatching { VfsUtil.markDirtyAndRefresh(true, false, true, file.parent.toFile()) }
    }

    // --- "Not now" ---------------------------------------------------------------------------------------------------

    fun dismiss(expression: String) {
        val next = LinkedHashSet(dismissedNow.value)
        next.remove(expression)
        next.add(expression)
        while (next.size > Limits.PINS_MAX_DISMISSED) next.remove(next.first())
        dismissedNow.value = next
    }

    fun undismiss(expression: String) {
        if (expression in dismissedNow.value) dismissedNow.value = dismissedNow.value - expression
    }

    override fun dispose() {
        reloads.close()
    }

    companion object {
        const val FILE_NAME = "pins.json"
        private const val TEMP_SUFFIX = ".tmp"

        fun getInstance(project: Project): PinsService = project.service()
    }
}
