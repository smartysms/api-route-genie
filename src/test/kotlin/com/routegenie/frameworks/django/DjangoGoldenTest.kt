package com.routegenie.frameworks.django

import com.routegenie.discovery.core.Lit
import com.routegenie.model.DiagnosticCode
import java.io.File

/**
 * Whole fixture projects with exact expected endpoint lists. A missing expected.txt is written to
 * build/golden-actual/ and fails the test, so a new golden is always reviewed by a person before it is committed.
 */
class DjangoGoldenTest : DjangoTestBase() {
    private fun assertGolden(fixture: String, actual: String) {
        val expectedFile = File(TEST_DATA, "$fixture/expected.txt")
        if (!expectedFile.exists()) {
            File("build/golden-actual/$fixture.txt").also { it.parentFile.mkdirs() }.writeText(actual + "\n")
            fail("No expected.txt for $fixture; review build/golden-actual/$fixture.txt and copy it")
        }
        assertEquals(expectedFile.readText().trimEnd(), actual.trimEnd())
    }

    fun testDjangoProjectWithAppsNamespacesAndNestedIncludes() {
        val result = linkFixture("django_basic")
        assertGolden("django_basic", render(result))
    }

    fun testDjangoProjectDiagnostics() {
        val result = linkFixture("django_basic")
        assertEquals(
            listOf(
                DiagnosticCode.UNRESOLVED_INCLUDE, DiagnosticCode.UNSUPPORTED_REGEX, DiagnosticCode.UNSUPPORTED_REGEX, DiagnosticCode.DYNAMIC_VALUE,
            ),
            result.diagnostics.map { it.code },
        )
    }

    fun testDrfProjectWithRoutersViewSetsActionsAndApiViews() {
        val result = linkFixture("django_drf")
        assertGolden("django_drf", render(result))
    }

    fun testGoldenEndpointsAreAllExact() {
        val endpoints = linkFixture("django_basic").endpoints + linkFixture("django_drf").endpoints
        assertTrue(endpoints.all { !it.isPartial && !it.path.contains("{?}") })
    }

    fun testDrfProjectHasNoDiagnostics() {
        assertTrue(linkFixture("django_drf").diagnostics.isEmpty())
    }

    fun testLinkingTwiceGivesTheSameResult() {
        val facts = extractAll("django_basic")
        assertEquals(provider.link(facts), provider.link(facts.reversed()))
    }

    fun testFilesWithoutRoutingFactsExtractToNull() {
        val plain = myFixture.addFileToProject("plain.py", "import os\n\nVALUE = os.getcwd()\n")
        val unrelated = myFixture.addFileToProject("models_like.py", "# mentions django only in a comment\nx = 1\n")
        assertNull(provider.extract(plain))
        assertNull(provider.extract(unrelated))
    }

    fun testASettingsFileOnlyCarriesTheRootUrlconf() {
        val facts = provider.extract(myFixture.addFileToProject("proj/settings.py", "ROOT_URLCONF = 'proj.urls'\n")) as DjangoFacts
        assertEquals(Lit("proj.urls"), facts.rootUrlconf)
        assertTrue(facts.patternLists.isEmpty())
    }

    // Candidates are the files that route or attach routes; the framework's name alone selects nothing, and view modules
    // are reached from the url modules that import them (see DjangoDependencyTest).
    fun testCandidateSearchFindsRoutingFilesOnly() {
        myFixture.addFileToProject("a/urls.py", "from django.urls import path\nurlpatterns = []\n")
        myFixture.addFileToProject("a/drf.py", "from rest_framework import viewsets\n")
        myFixture.addFileToProject("a/settings.py", "ROOT_URLCONF = 'x'\n")
        myFixture.addFileToProject("a/routers.py", "from rest_framework.routers import DefaultRouter\nr = DefaultRouter()\n")
        myFixture.addFileToProject("a/other.py", "import os\n")
        myFixture.addFileToProject("a/models.py", "from django.db import models\n")
        val names = provider.findCandidates(project).map { it.name }.toSet()
        assertEquals(setOf("urls.py", "settings.py", "routers.py"), names)
    }

    fun testLinkedEndpointsReportPathsRelativeToTheProject() {
        val result = link("pkg/sub/urls.py" to "from django.urls import path\nfrom . import views\nurlpatterns = [path('a/', views.v)]\n", "pkg/sub/views.py" to "def v(request): ...\n")
        val endpoint = result.endpoints.single()
        assertEquals("pkg/sub/urls.py", endpoint.source.path)
        assertEquals("pkg.sub.views.v", endpoint.handler.qualifiedName)
    }

    fun testSourcePointsAtTheRouteCall() {
        val text = "from django.urls import path\n\nurlpatterns = [\n    path('a/', v),\n]\n"
        val result = link("urls.py" to text)
        val source = result.endpoints.single().source
        assertEquals("urls.py", source.path)
        assertEquals(4, source.line)
        assertEquals(text.indexOf("path('a/'"), source.offset)
    }
}
