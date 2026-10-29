package com.routegenie

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.RouteGenieBundle"

/**
 * Every user-visible string that is not a product name. Product names come from [Branding] (and the Branding bundle for
 * plugin.xml); put them in a message as an argument, never as text.
 */
object RouteGenieBundle : DynamicBundle(BUNDLE) {
    @JvmStatic
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
