package com.routegenie

import com.routegenie.testing.ProjectFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.ResourceBundle

class BrandingTest {
    private val bundle = ResourceBundle.getBundle("messages.Branding", Locale.ROOT, Branding::class.java.classLoader)

    @Test
    fun `branding values come from gradle properties`() {
        val gradle = ProjectFiles.gradleProperties()
        assertEquals(gradle.getProperty("productName"), Branding.NAME)
        assertEquals(gradle.getProperty("productShortName"), Branding.SHORT)
        assertEquals(gradle.getProperty("productDir"), Branding.DIR)
    }

    @Test
    fun `no build token is left unreplaced in the bundle`() {
        for (key in bundle.keys) {
            assertFalse("$key still contains a build token", bundle.getString(key).contains("@"))
        }
    }

    @Test
    fun `every action has a text that carries the short name`() {
        for (id in Ids.ACTIONS) {
            assertTrue("missing text for $id", bundle.containsKey("action.$id.text"))
            assertTrue("text of $id should contain the short name", bundle.getString("action.$id.text").contains(Branding.SHORT))
        }
    }

    @Test
    fun `every tool window has a stripe title and every notification group a name`() {
        for (id in Ids.TOOL_WINDOWS) assertEquals(Branding.SHORT, bundle.getString("toolwindow.stripe.$id"))
        assertEquals(Branding.NAME, bundle.getString("notification.group"))
    }
}
