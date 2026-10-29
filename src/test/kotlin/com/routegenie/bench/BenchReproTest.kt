package com.routegenie.bench

import com.routegenie.discovery.LinkResult
import com.routegenie.discovery.RouteProvider
import com.routegenie.frameworks.django.DjangoRouteProvider
import com.routegenie.frameworks.fastapi.FastApiRouteProvider
import com.routegenie.testing.ProviderPipeline
import com.routegenie.testing.PythonPlatformTestCase

/**
 * Minimal reproductions of the gaps the corpus benchmark found. They print what the pipeline returns and assert nothing,
 * so they document the current behavior without failing the suite; a fix turns the printed line into the expected one.
 */
class BenchReproTest : PythonPlatformTestCase() {
    private fun run(provider: RouteProvider, vararg files: Pair<String, String>): LinkResult {
        files.forEach { (path, text) -> myFixture.addFileToProject(path, text) }
        return provider.link(ProviderPipeline.collect(project, provider))
    }

    private fun show(label: String, result: LinkResult) {
        val endpoints = result.endpoints.joinToString(";") { "${it.method} ${it.path}" }
        val diagnostics = result.diagnostics.joinToString(";") { it.code.name }
        println("REPRO $label endpoints=[$endpoints] diagnostics=[$diagnostics]")
    }

    /** ROOT_URLCONF names a module that only re-exports urlpatterns (Sentry: sentry.conf.urls). */
    fun testRootUrlconfThatReExportsUrlpatterns() {
        show(
            "root_reexport",
            run(
                DjangoRouteProvider(),
                "proj/settings.py" to "ROOT_URLCONF = 'proj.urls'\n",
                "proj/urls.py" to "from app.urls import urlpatterns\n",
                "app/urls.py" to "from django.urls import path\nfrom app import views\nurlpatterns = [path('a/', views.a)]\n",
            ),
        )
    }

    /** Same shape written directly in the root module, with an annotation and `+=` as Sentry's web/urls.py does. */
    fun testAnnotatedUrlpatternsWithAugmentedAssignment() {
        show(
            "annotated_urlpatterns",
            run(
                DjangoRouteProvider(),
                "proj2/settings.py" to "ROOT_URLCONF = 'proj2.urls'\n",
                "proj2/urls.py" to "from django.urls import path\nfrom proj2 import views\n" +
                    "urlpatterns: list = [path('a/', views.a)]\nurlpatterns += [path('b/', views.b)]\n",
            ),
        )
    }

    /** Two apps in two test functions with the same route: both exist, one is reported. */
    fun testFunctionLocalAppsWithTheSameRoute() {
        show(
            "local_apps_same_route",
            run(
                FastApiRouteProvider(),
                "t1/test_local.py" to "from fastapi import FastAPI\n\n\ndef test_one():\n    app = FastAPI()\n\n" +
                    "    @app.get('/x')\n    def read():\n        return 1\n\n\ndef test_two():\n    app = FastAPI()\n\n" +
                    "    @app.get('/x')\n    def read():\n        return 2\n",
            ),
        )
    }

    /** Same route, different handler names: shows the collapse needs equal handler names. */
    fun testFunctionLocalAppsWithDifferentHandlerNames() {
        show(
            "local_apps_different_handlers",
            run(
                FastApiRouteProvider(),
                "t2/test_local.py" to "from fastapi import FastAPI\n\n\ndef test_one():\n    app = FastAPI()\n\n" +
                    "    @app.get('/x')\n    def read_a():\n        return 1\n\n\ndef test_two():\n    app = FastAPI()\n\n" +
                    "    @app.get('/x')\n    def read_b():\n        return 2\n",
            ),
        )
    }

    /** An admin site mounted through an attribute of an object: its many routes are listed as one ANY route. */
    fun testAdminSiteUrlsMount() {
        show(
            "admin_site_urls",
            run(
                DjangoRouteProvider(),
                "proj3/settings.py" to "ROOT_URLCONF = 'proj3.urls'\n",
                "proj3/urls.py" to "from django.contrib import admin\nfrom django.urls import path\n" +
                    "urlpatterns = [path('admin/', admin.site.urls)]\n",
            ),
        )
    }
}
