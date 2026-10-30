package com.routegenie.ui.tests

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.routegenie.model.SourceRef
import com.routegenie.testmap.ProjectTest
import com.routegenie.testmap.TestMark
import com.routegenie.testmap.TestStyle

/**
 * Spike SP2, as far as a headless platform test can go: asking the IDE's own run configuration producers for a test must
 * neither throw nor start anything. Whether a configuration comes back depends on the interpreter and runner of the project
 * (none in a light fixture), so both answers are accepted; the real Run window is in the manual checklist (T13).
 */
class TestLauncherTest : BasePlatformTestCase() {
    fun testAskingForAConfigurationDoesNotThrowAndDoesNotRunAnything() {
        val text = "import pytest\n\ndef test_it():\n    assert True\n"
        myFixture.addFileToProject("tests/test_launch.py", text)
        val test = ProjectTest(
            "tests/test_launch.py::test_it", "tests/test_launch.py", null, "test_it", TestStyle.PYTEST, TestMark.NONE,
            SourceRef("tests/test_launch.py", text.indexOf("def test_it"), 3),
        )
        val settings = TestLauncher.configurationFor(project, test)
        println("TESTS ide_run_configuration_in_light_fixture=${settings?.name ?: "none"}")
        assertTrue(settings == null || settings.name.isNotEmpty())
    }

    fun testAMissingFileYieldsNoConfiguration() {
        val test = ProjectTest("tests/none.py::test_a", "tests/none.py", null, "test_a", TestStyle.PYTEST, TestMark.NONE, SourceRef("tests/none.py", 0, 1))
        assertNull(TestLauncher.configurationFor(project, test))
    }
}
