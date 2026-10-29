package com.routegenie.settings

import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.routegenie.Branding
import com.routegenie.Ids
import com.routegenie.RouteGenieBundle
import com.routegenie.ui.gutter.RouteGutterIndex

/** Settings | Tools | <product>. Applying refreshes the markers of every open project, no restart needed. */
class RouteGenieConfigurable : BoundSearchableConfigurable(Branding.NAME, "", Ids.CONFIGURABLE) {
    override fun createPanel(): DialogPanel {
        val settings = RouteGenieSettings.getInstance()
        return panel {
            row {
                checkBox(RouteGenieBundle.message("settings.gutter.show"))
                    .bindSelected({ settings.showGutterIcons }, { settings.showGutterIcons = it })
            }
            if (RouteGenieSettings.isPaidPythonActive()) {
                row { comment(RouteGenieBundle.message("settings.gutter.proNote")) }
            }
        }
    }

    override fun apply() {
        super.apply()
        ProjectManager.getInstance().openProjects.forEach { if (!it.isDisposed) RouteGutterIndex.getInstance(it).refreshMarkers() }
    }
}
