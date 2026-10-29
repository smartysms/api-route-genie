package com.routegenie.discovery

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile

/**
 * Where a file is, as plain strings: the project-relative path a SourceRef uses and the source root that contains it.
 * Computed on every discovery pass from the live project layout and never stored in the cached facts, so moving a file
 * or changing the source roots needs a relink and no re-parse.
 */
object ProjectPaths {
    /** Project-relative path with '/'; falls back to the content root, then to the full path. */
    fun relative(project: Project, file: VirtualFile): String {
        val path = file.path
        val base = project.guessProjectDir()?.path
        if (base != null && path.startsWith("$base/")) return path.removePrefix("$base/")
        val root = ProjectFileIndex.getInstance(project).getContentRootForFile(file)
        if (root != null && path.startsWith("${root.path}/")) return path.removePrefix("${root.path}/")
        return path.trimStart('/')
    }

    /**
     * The project-relative path of the source root that contains [file] ("" when it has none, or when the root is the
     * project directory itself, so that the relative path already starts at the root). [relativePath] is [relative] of [file].
     */
    fun sourceRoot(project: Project, file: VirtualFile, relativePath: String): String {
        val root = ProjectFileIndex.getInstance(project).getSourceRootForFile(file) ?: return ""
        val rootPath = relative(project, root)
        return if (rootPath.isNotEmpty() && relativePath.startsWith("$rootPath/")) rootPath else ""
    }
}
