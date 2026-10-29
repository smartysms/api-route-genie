package com.routegenie.ui.design

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Every icon of the plugin. The files live in `/icons/design/<name>.svg` with a `<name>_dark.svg` next to each; the platform
 * picks the variant for the current theme and renders the SVG at the screen's scale. Light and dark variants of one icon have
 * identical geometry and differ only in color. Icons are loaded lazily and shared.
 */
object RouteGenieIcons {
    private const val DIR = "/icons/design/"

    private fun load(path: String): Icon = IconLoader.getIcon(path, RouteGenieIcons::class.java)
    private fun design(name: String): Icon = load("$DIR$name.svg")

    /** The plugin mark (tool window stripe, actions); the existing 16 px icon, with a dark variant beside it. */
    @JvmField val PLUGIN: Icon = load("/icons/routeGenie.svg")

    /** The 12 px marker next to route declarations in the editor gutter. */
    @JvmField val GUTTER: Icon = load("/icons/routeGenieGutter.svg")

    @JvmField val SEND: Icon = design("send")
    @JvmField val STOP: Icon = design("stop")
    @JvmField val COPY: Icon = design("copy")
    @JvmField val CURL: Icon = design("curl")
    @JvmField val HISTORY: Icon = design("history")
    @JvmField val STREAM: Icon = design("stream")
    @JvmField val CLEAR: Icon = design("clear")
    @JvmField val ENVIRONMENT: Icon = design("environment")
    @JvmField val AUTH: Icon = design("auth")
    @JvmField val PANEL_HIDE: Icon = design("panelHide")
    @JvmField val SPLIT_ORIENTATION: Icon = design("splitOrientation")
    @JvmField val PRETTY_PRINT: Icon = design("prettyPrint")
    @JvmField val WRAP: Icon = design("wrap")
    @JvmField val SEARCH: Icon = design("search")

    /** 32 px illustrations for empty states. */
    @JvmField val EMPTY_ROUTES: Icon = design("emptyRoutes")
    @JvmField val EMPTY_SEARCH: Icon = design("emptySearch")
    @JvmField val EMPTY_PROJECT: Icon = design("emptyProject")

    /** File names (without extension) of the icons in [DIR]; the icon test checks each has a dark variant. */
    internal val DESIGN_FILE_NAMES: List<String> = listOf(
        "send", "stop", "copy", "curl", "history", "stream", "clear", "environment", "auth", "panelHide", "splitOrientation",
        "prettyPrint", "wrap", "search", "emptyRoutes", "emptySearch", "emptyProject",
    )
    internal const val DESIGN_DIR = DIR
}
