package com.routegenie

import java.util.Locale
import java.util.ResourceBundle

/**
 * The product name used in every UI string. The values live in gradle.properties and reach this bundle through
 * processResources, so renaming the product never means touching Kotlin sources.
 */
object Branding {
    private val bundle: ResourceBundle =
        ResourceBundle.getBundle("messages.Branding", Locale.ROOT, Branding::class.java.classLoader)

    /** Full product name, as shown in the settings page and notifications. */
    val NAME: String = bundle.getString("product.name")

    /** Short name for menus, the tool window stripe and undo entries. */
    val SHORT: String = bundle.getString("product.short")

    /** Name of the per-project folder for files meant to be shared with the team. */
    val DIR: String = bundle.getString("product.dir")
}
