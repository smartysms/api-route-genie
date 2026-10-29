package com.routegenie.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.routegenie.Ids

class OpenRouteGenieActionTest : BasePlatformTestCase() {
    private val action = OpenRouteGenieAction()

    fun testUpdatesOnTheBackgroundThread() {
        assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
    }

    fun testEnabledWithAProject() {
        val event = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))
        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
    }

    fun testHiddenWithoutAProject() {
        val event = TestActionEvent.createTestEvent(action, SimpleDataContext.builder().build())
        action.update(event)
        assertFalse(event.presentation.isEnabledAndVisible)
    }

    fun testRegisteredUnderItsIdAndPerformDoesNotFailHeadless() {
        val registered = ActionManager.getInstance().getAction(Ids.ACTION_OPEN_WINDOW)
        assertTrue(registered is OpenRouteGenieAction)
        val event = TestActionEvent.createTestEvent(registered, SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build())
        // Headless has no real tool window to activate; the call must still be a safe no-op.
        registered.actionPerformed(event)
    }

    fun testPresentationFromTheActionSystem() {
        val presentation = myFixture.testAction(action)
        assertTrue(presentation.isEnabledAndVisible)
    }
}
