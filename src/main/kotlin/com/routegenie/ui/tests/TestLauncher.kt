package com.routegenie.ui.tests

import com.intellij.execution.PsiLocation
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFunction
import com.routegenie.testmap.ProjectTest
import com.routegenie.ui.EndpointSourceNavigation

/**
 * Starts the project's own test through the IDE's run configuration producers (the ones behind the green gutter icon), so
 * the normal Run window shows the real execution. Only an explicit click calls [run]; nothing runs on its own. When the IDE
 * has no producer for the test (no interpreter or runner configured) the answer is false and the caller offers the
 * copyable command instead.
 */
object TestLauncher {
    private val log = Logger.getInstance(TestLauncher::class.java)

    /** The configuration the IDE would create for [test], or null. Runs in a read action; does not start anything. */
    fun configurationFor(project: Project, test: ProjectTest): RunnerAndConfigurationSettings? = runCatching {
        runReadAction {
            val element = elementOf(project, test) ?: return@runReadAction null
            ConfigurationContext.createEmptyContextForLocation(PsiLocation(element)).configuration
        }
    }.onFailure { log.info("No run configuration for ${test.id}", it) }.getOrNull()

    /** Looks up the configuration on a pooled thread and starts it on the UI thread; [done] gets false when none exists. */
    fun run(project: Project, test: ProjectTest, done: (Boolean) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val settings = configurationFor(project, test)
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                if (settings == null) {
                    done(false)
                    return@invokeLater
                }
                val started = runCatching { ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance()) }
                    .onFailure { log.info("Could not start ${test.id}", it) }.isSuccess
                done(started)
            }, ModalityState.nonModal())
        }
    }

    /** The function (or, for a missing function, the class) that [test] names, found by its recorded position. */
    private fun elementOf(project: Project, test: ProjectTest): PsiElement? {
        val file = EndpointSourceNavigation.resolve(project, test.file) ?: return null
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        val at = psi.findElementAt(test.source.offset.coerceIn(0, maxOf(psi.textLength - 1, 0))) ?: return null
        return PsiTreeUtil.getParentOfType(at, PyFunction::class.java, false) ?: PsiTreeUtil.getParentOfType(at, PyClass::class.java, false)
    }
}
