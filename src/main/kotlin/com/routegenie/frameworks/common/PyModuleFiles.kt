package com.routegenie.frameworks.common

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.routegenie.Limits
import java.util.TreeMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dotted module name to project file, without scanning: one pass over the project's Python files fills a map keyed by the
 * last name segment of every module (the file name, or the directory name for a package), and a lookup only compares the
 * path suffix of the few files that share that segment. Before this, every lookup asked the platform for all `__init__.py`
 * files and built the full path of each, which cost 4.6 s for 1,808 lookups on a project of 8,400 files and again after
 * every file that was added, deleted or renamed.
 *
 * Layout events (create, delete, move, rename, copy) are queued by the listener without any work and applied by the next
 * lookup, which runs off the UI thread; a change of the project roots or a burst larger than
 * [Limits.MAX_PENDING_LAYOUT_EVENTS] makes the next lookup rebuild the map instead. A project above
 * [Limits.MAX_MODULE_INDEX_FILES] files keeps no map and answers by the platform's file name index, as before.
 *
 * Matching is by path suffix on purpose: the facts know a module relative to its source root, an import may be written
 * relative to another one (`app.routers.items` against `src/app/routers/items.py`).
 */
@Service(Service.Level.PROJECT)
class PyModuleFiles(private val project: Project) : Disposable {
    private class Entry(val path: String, val file: VirtualFile, val isPackage: Boolean, val key: String)

    private sealed interface Change {
        class Remove(val path: String) : Change
        class Add(val file: VirtualFile) : Change
    }

    private val log = Logger.getInstance(PyModuleFiles::class.java)
    private val lock = Any()
    private val pending = ConcurrentLinkedQueue<Change>()
    private val pendingCount = AtomicInteger()

    @Volatile
    private var rebuild = true

    /** Guarded by [lock]. [byPath] answers "everything below this directory" for deletes and moves. */
    private val byKey = HashMap<String, ArrayList<Entry>>()
    private val byPath = TreeMap<String, Entry>()
    private var overflow = false

    /** How many full builds ran; tests use it to prove that a file change is applied incrementally. */
    val builds = AtomicInteger()

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                for (event in events) record(event)
            }
        })
        connection.subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) {
                rebuild = true
            }
        })
    }

    /**
     * The project file of [module] (`a.b` is `a/b.py` or `a/b/__init__.py`); null when absent. Must run in a read action
     * with indexes available (the first call builds the map).
     */
    fun find(module: String): VirtualFile? {
        val parts = module.split('.').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val relative = parts.joinToString("/")
        val found = synchronized(lock) {
            refresh()
            if (overflow) return scan(parts, relative)
            val modulePath = "/$relative$PY_SUFFIX"
            val packagePath = "/$relative/$INIT_FILE"
            val sameName = byKey[parts.last()].orEmpty()
            sameName.filter { if (it.isPackage) it.path.endsWith(packagePath) else it.path.endsWith(modulePath) }.map { it.file }
        }
        return PyCandidateRules.select(project, found.filter { it.isValid }).firstOrNull()
    }

    private fun scan(parts: List<String>, relative: String): VirtualFile? {
        val scope = GlobalSearchScope.projectScope(project)
        val modules = FilenameIndex.getVirtualFilesByName(parts.last() + PY_SUFFIX, scope).filter { it.path.endsWith("/$relative$PY_SUFFIX") }
        val packages = FilenameIndex.getVirtualFilesByName(INIT_FILE, scope).filter { it.path.endsWith("/$relative/$INIT_FILE") }
        return PyCandidateRules.select(project, modules + packages).firstOrNull()
    }

    /** Files currently indexed, for tests. */
    val size: Int get() = synchronized(lock) { byPath.size }

    /** Applies queued layout changes, or rebuilds when that is needed. Caller holds [lock]. */
    private fun refresh() {
        if (rebuild) {
            build()
            return
        }
        while (true) {
            val change = pending.poll() ?: break
            pendingCount.decrementAndGet()
            when (change) {
                is Change.Remove -> remove(change.path)
                is Change.Add -> add(change.file)
            }
        }
    }

    private fun build() {
        // Events that arrive while the map is built are replayed on top of it; adding a file twice is harmless.
        pending.clear()
        pendingCount.set(0)
        rebuild = false
        byKey.clear()
        byPath.clear()
        overflow = false
        val scope = GlobalSearchScope.projectScope(project)
        for (file in FilenameIndex.getAllFilesByExt(project, PY_EXTENSION, scope)) {
            if (byPath.size >= Limits.MAX_MODULE_INDEX_FILES) {
                overflow = true
                byKey.clear()
                byPath.clear()
                log.warn("Module index not kept: more than ${Limits.MAX_MODULE_INDEX_FILES} Python files")
                break
            }
            put(file)
        }
        builds.incrementAndGet()
    }

    private fun put(file: VirtualFile) {
        if (!file.isValid || file.isDirectory || file.extension != PY_EXTENSION) return
        val path = file.path
        val isPackage = file.name == INIT_FILE
        val key = if (isPackage) file.parent?.name ?: return else file.nameWithoutExtension
        if (byPath.containsKey(path)) return
        val entry = Entry(path, file, isPackage, key)
        byPath[path] = entry
        byKey.getOrPut(key) { ArrayList(1) }.add(entry)
    }

    private fun add(file: VirtualFile) {
        if (overflow || !file.isValid) return
        if (!file.isDirectory) {
            if (GlobalSearchScope.projectScope(project).contains(file)) put(file)
            return
        }
        ProjectFileIndex.getInstance(project).iterateContentUnderDirectory(file) { child ->
            put(child)
            byPath.size < Limits.MAX_MODULE_INDEX_FILES
        }
    }

    /** Drops the file or directory at [path] and everything below it. */
    private fun remove(path: String) {
        if (overflow) return
        val doomed = ArrayList<Entry>()
        byPath[path]?.let { doomed += it }
        doomed += byPath.subMap("$path/", "$path/￿").values
        for (entry in doomed) {
            byPath.remove(entry.path)
            byKey[entry.key]?.let { list ->
                list.remove(entry)
                if (list.isEmpty()) byKey.remove(entry.key)
            }
        }
    }

    /** Runs on whatever thread delivers the events (the UI thread): only queues. */
    private fun record(event: VFileEvent) {
        when (event) {
            is VFileDeleteEvent -> if (relevant(event.path)) queue(Change.Remove(event.path))
            is VFileCreateEvent -> if (relevant(event.path)) event.file?.let { queue(Change.Add(it)) }
            is VFileCopyEvent -> if (relevant(event.path)) event.newParent.findChild(event.newChildName)?.let { queue(Change.Add(it)) }
            is VFileMoveEvent -> {
                if (relevant(event.oldPath)) queue(Change.Remove(event.oldPath))
                if (relevant(event.path)) queue(Change.Add(event.file))
            }
            is VFilePropertyChangeEvent -> if (event.isRename) {
                if (relevant(event.oldPath)) queue(Change.Remove(event.oldPath))
                if (relevant(event.path)) queue(Change.Add(event.file))
            }
            else -> Unit
        }
    }

    private fun queue(change: Change) {
        if (rebuild) return
        if (pendingCount.incrementAndGet() > Limits.MAX_PENDING_LAYOUT_EVENTS) {
            rebuild = true
            return
        }
        pending.add(change)
    }

    /** A Python file, or a directory (no extension) that may hold some. */
    private fun relevant(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return !name.contains('.') || name.endsWith(PY_SUFFIX)
    }

    override fun dispose() {
        synchronized(lock) {
            byKey.clear()
            byPath.clear()
        }
        pending.clear()
    }

    companion object {
        private const val PY_EXTENSION = "py"
        private const val PY_SUFFIX = ".$PY_EXTENSION"
        private const val INIT_FILE = "__init__.py"

        fun getInstance(project: Project): PyModuleFiles = project.service()
    }
}
