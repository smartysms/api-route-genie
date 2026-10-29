package com.routegenie.testing

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.python.psi.PyFile

/**
 * Base for tests that need Python PSI. The test fixtures have no Python SDK, so library imports do not resolve: tests
 * must recognize frameworks by import text, never by resolve(). Methods must be named testXxx (JUnit 3 style).
 */
abstract class PythonPlatformTestCase : BasePlatformTestCase() {
    /** Creates [path] in the light project and returns its Python PSI (ASCII text only, see the contract). */
    protected fun pythonFile(path: String, text: String): PyFile {
        val file: PsiFile = myFixture.addFileToProject(path, text)
        return file as? PyFile ?: error("$path was not parsed as a Python file")
    }

    /** Asserts that the scenario released its editors and left no disposed project or editor reachable. */
    protected fun assertLeakFree(label: String, scenario: () -> Unit) = PlatformLeaks.assertScenarioClean(label, scenario)
}
