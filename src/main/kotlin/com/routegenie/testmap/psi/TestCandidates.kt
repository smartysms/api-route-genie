package com.routegenie.testmap.psi

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.routegenie.discovery.ProjectPaths
import com.routegenie.frameworks.common.PyCandidateRules
import com.routegenie.frameworks.common.PyProviderSupport
import com.routegenie.testmap.ClientSinks
import com.routegenie.testmap.TestConventions

/**
 * Which files are read for tests: Python files of the project that sit on a test path (or in a test source root) and
 * contain a word of the client table, taken from the persisted word index without parsing anything. Virtual environments,
 * generated modules and oversized files are already out ([PyCandidateRules.select]).
 */
object TestCandidates {
    /** Runs inside a read action. */
    fun find(project: Project): List<VirtualFile> {
        val index = ProjectFileIndex.getInstance(project)
        return PyProviderSupport.candidatesByWords(project, ClientSinks.candidateWords).filter { isTestSource(project, it, index) }
    }

    fun isTestSource(project: Project, file: VirtualFile, index: ProjectFileIndex): Boolean {
        val path = ProjectPaths.relative(project, file)
        return index.isInTestSourceContent(file) || PyCandidateRules.isTestPath(path) || TestConventions.isTestFile(path)
    }
}
