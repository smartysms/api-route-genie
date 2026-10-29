package com.routegenie.ui.actions

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.testFramework.PlatformTestUtil
import com.routegenie.Ids
import com.routegenie.discovery.core.RouteDiscoveryService
import com.routegenie.testing.PythonPlatformTestCase
import com.routegenie.ui.EndpointNavigator
import com.routegenie.ui.gutter.RouteGutterIndex
import java.io.File

class SelectInActionTest : PythonPlatformTestCase() {
    override fun getTestDataPath(): String = File("src/test/testData").absolutePath

    private val action = SelectInRouteGenieAction()

    override fun setUp() {
        super.setUp()
        com.routegenie.settings.RouteGenieSettings.getInstance().showGutterIcons = true
    }

    override fun tearDown() {
        try {
            com.routegenie.settings.RouteGenieSettings.getInstance().state.showGutterIcons = null
            RouteDiscoveryService.getInstance(project).setActive(false)
        } finally {
            super.tearDown()
        }
    }

    private fun openMain() {
        myFixture.copyDirectoryToProject("fastapi/basic", "")
        val provider = com.routegenie.frameworks.fastapi.FastApiRouteProvider()
        val endpoints = provider.link(com.routegenie.testing.ProviderPipeline.collect(project, provider)).endpoints
        endpointList = endpoints
        RouteGutterIndex.getInstance(project).installModelForTests(
            com.routegenie.ui.gutter.GutterModel.build(com.routegenie.model.RouteSnapshot(1, endpoints)),
        )
        myFixture.configureFromTempProjectFile("app/main.py")
    }

    private fun caretAtLine(line: Int, column: Int = 2) {
        val doc = myFixture.editor.document
        myFixture.editor.caretModel.moveToOffset(doc.getLineStartOffset(line - 1) + column)
    }

    private fun lineOf(text: String): Int = myFixture.editor.document.text.lines().indexOfFirst { it.contains(text) } + 1

    private fun enabled(): Boolean = myFixture.testActionUpdate()

    private fun CodeInsightTestFixtureAlias.testActionUpdate() = this.let {
        val presentation = it.testAction(action)
        presentation.isEnabled
    }

    fun testRegisteredAndInBothMenusWithoutAShortcut() {
        val manager = ActionManager.getInstance()
        val registered = manager.getAction(Ids.ACTION_SELECT_IN_WINDOW)
        assertTrue(registered is SelectInRouteGenieAction)
        assertEquals(ActionUpdateThread.BGT, registered.actionUpdateThread)
        assertTrue((manager.getAction("EditorPopupMenu") as ActionGroup).getChildren(null).contains(registered))
        assertTrue((manager.getAction("GoToMenu") as ActionGroup).getChildren(null).contains(registered))
        assertTrue(registered.shortcutSet.shortcuts.isEmpty())
    }

    fun testDisabledWithoutDataAndOutsideDeclarations() {
        myFixture.configureByText("plain.py", "x = 1\n")
        assertFalse(enabled())
    }

    fun testOnDecoratorEnabledAndRevealsTheEndpoint() {
        openMain()
        val decorator = lineOf("@app.get(\"/health\")")
        caretAtLine(decorator)
        assertTrue(enabled())
        val key = discovery().first { it.path == "/health" }.key
        assertEquals(key, EndpointNavigator.getInstance(project).requested.value)
    }

    fun testOnDefLineEnabledButInsideTheBodyDisabled() {
        openMain()
        val def = lineOf("async def health")
        caretAtLine(def, column = 8)
        assertTrue("def line of a decorated function", enabled())
        caretAtLine(def + 1, column = 8)
        assertFalse("function body is not a declaration", enabled())
        caretAtLine(lineOf("app = FastAPI()"))
        assertFalse(enabled())
    }

    private var endpointList: List<com.routegenie.model.Endpoint> = emptyList()

    private fun discovery() = endpointList
}

private typealias CodeInsightTestFixtureAlias = com.intellij.testFramework.fixtures.CodeInsightTestFixture
