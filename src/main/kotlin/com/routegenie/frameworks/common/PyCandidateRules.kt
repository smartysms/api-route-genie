package com.routegenie.frameworks.common

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.routegenie.Limits
import com.routegenie.discovery.ProjectPaths

/**
 * Which Python files are never read for routes, and which are read last. Kept in one place because the lists are facts
 * about how Python projects are laid out, not tuning numbers.
 *
 * Not found on purpose: routes defined in virtual environments, build output or generated code, in migrations, and in
 * files above [Limits.MAX_CANDIDATE_FILE_BYTES]. Test code is still read, but after everything else, so the routes of the
 * application itself are available first and a partial answer is never made of test fixtures.
 */
object PyCandidateRules {
    private const val PYTHON_EXTENSION = "py"
    private const val VENV_MARKER = "pyvenv.cfg"

    /** Directory names that hold installed or generated code. */
    private val EXCLUDED_DIRECTORIES = setOf(
        "site-packages", "dist-packages", "node_modules", ".venv", "venv", ".tox", "__pypackages__", ".git", ".mypy_cache",
        ".pytest_cache", "__pycache__", "migrations", "build", "dist",
    )

    /** File name endings of generated modules (protocol buffers). */
    private val GENERATED_SUFFIXES = listOf("_pb2.py", "_pb2_grpc.py", "_pb2.pyi")

    /** Receivers of decorators that are spelled like a route (`mock.patch`) but belong to test tooling. */
    private val NON_ROUTER_RECEIVERS = setOf("mock", "unittest.mock", "mocker", "monkeypatch")

    private val TEST_DIRECTORIES = setOf("tests", "test", "testing")
    private const val TEST_PREFIX = "test_"
    private const val TEST_SUFFIX = "_test.py"
    private const val CONFTEST = "conftest.py"

    /** [files] that may hold routes, application code first, test code last; each group sorted by path. */
    fun select(project: Project, files: Collection<VirtualFile>): List<VirtualFile> {
        val index = ProjectFileIndex.getInstance(project)
        val venvDirectories = HashMap<VirtualFile, Boolean>()

        fun inVirtualEnvironment(file: VirtualFile): Boolean {
            var dir = file.parent
            while (dir != null) {
                val isVenv = venvDirectories.getOrPut(dir) { dir!!.findChild(VENV_MARKER) != null }
                if (isVenv) return true
                dir = dir.parent
            }
            return false
        }

        val kept = files.filter { file ->
            file.extension == PYTHON_EXTENSION && file.isValid && !file.isDirectory && file.length <= Limits.MAX_CANDIDATE_FILE_BYTES &&
                !isExcludedByName(ProjectPaths.relative(project, file)) && !index.isExcluded(file) && !inVirtualEnvironment(file)
        }
        val (tests, application) = kept.partition { isTest(project, it, index) }
        return application.sortedBy { it.path } + tests.sortedBy { it.path }
    }

    /**
     * Whether a decorator written as [key] (`router.get`, from the decorator stub index) can declare a route of one of
     * [methods]. A route decorator always has a receiver, so a bare `@patch(...)` (thousands of them in a large test suite,
     * which is what made hundreds of files candidates for nothing) and `@mock.patch(...)` are not.
     */
    fun mayDeclareRoute(key: String, methods: Set<String>): Boolean {
        val receiver = key.substringBeforeLast('.', "")
        return receiver.isNotEmpty() && key.substringAfterLast('.') in methods && receiver !in NON_ROUTER_RECEIVERS
    }

    /** By project-relative path alone (an absolute path would make a checkout under a folder named `build` vanish). */
    fun isExcludedByName(path: String): Boolean {
        val segments = path.split('/')
        if (segments.dropLast(1).any { it in EXCLUDED_DIRECTORIES }) return true
        val name = segments.last()
        return GENERATED_SUFFIXES.any { name.endsWith(it) }
    }

    private fun isTest(project: Project, file: VirtualFile, index: ProjectFileIndex): Boolean =
        index.isInTestSourceContent(file) || isTestPath(ProjectPaths.relative(project, file))

    fun isTestPath(path: String): Boolean {
        val segments = path.split('/')
        val name = segments.last()
        return name == CONFTEST || name.startsWith(TEST_PREFIX) || name.endsWith(TEST_SUFFIX) ||
            segments.dropLast(1).any { it in TEST_DIRECTORIES }
    }
}
