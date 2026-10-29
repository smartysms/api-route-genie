package com.routegenie.ui.gutter

import com.intellij.openapi.util.text.StringUtil
import com.routegenie.frameworks.django.DjangoCatalog
import com.routegenie.frameworks.fastapi.FastApiCatalog
import com.routegenie.frameworks.flask.FlaskCatalog

/**
 * Cheap text test (no parsing) that decides whether opening a file is worth starting discovery for. The engine's own
 * markers alone are too loose for this (`.get(` is in nearly every Python file), so a framework's import root must be
 * present as well. A decorator-only file that never names its framework is found by the scan once it runs, not here.
 */
object RouteFileText {
    private class Gate(val roots: Collection<String>, val markers: List<Set<String>>)

    private val gates = listOf(
        Gate(FastApiCatalog.table.importRoots, FastApiCatalog.table.textMarkers),
        Gate(FlaskCatalog.table.importRoots, FlaskCatalog.table.textMarkers),
        Gate(listOf(DjangoCatalog.DJANGO_ROOT, DjangoCatalog.DRF_ROOT), DjangoCatalog.TEXT_MARKERS.map { setOf(it) }),
    )

    fun looksLikeRouteFile(text: CharSequence): Boolean = gates.any { gate ->
        gate.roots.any { StringUtil.contains(text, it) } && gate.markers.any { group -> group.all { StringUtil.contains(text, it) } }
    }
}
