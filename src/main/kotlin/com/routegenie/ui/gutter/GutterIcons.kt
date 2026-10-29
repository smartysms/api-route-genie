package com.routegenie.ui.gutter

import com.intellij.openapi.util.IconLoader

/** The small dedicated marker; the platform picks routeGenieGutter_dark.svg in dark themes. */
object GutterIcons {
    @JvmField
    val ENDPOINT = IconLoader.getIcon("/icons/routeGenieGutter.svg", GutterIcons::class.java)
}
