package com.routegenie

/**
 * Every identifier that plugin.xml registers. Kotlin code refers to these constants and never types the string
 * again; IdsConsistencyTest fails when this file and plugin.xml disagree.
 */
object Ids {
    const val TOOL_WINDOW = "RouteGenie"
    const val NOTIFICATION_GROUP = "RouteGenie.Notifications"
    const val ACTION_OPEN_WINDOW = "RouteGenie.OpenWindow"

    /** Extension point (qualified name) through which route providers are contributed. */
    const val EP_ROUTE_PROVIDER = "com.routegenie.routeProvider"

    val TOOL_WINDOWS = setOf(TOOL_WINDOW)
    val NOTIFICATION_GROUPS = setOf(NOTIFICATION_GROUP)
    val ACTIONS = setOf(ACTION_OPEN_WINDOW)
    val EXTENSION_POINTS = setOf(EP_ROUTE_PROVIDER)

    /** Everything above in one set, for the "no id is typed twice" check. */
    val ALL: Set<String> = TOOL_WINDOWS + NOTIFICATION_GROUPS + ACTIONS + EXTENSION_POINTS
}
