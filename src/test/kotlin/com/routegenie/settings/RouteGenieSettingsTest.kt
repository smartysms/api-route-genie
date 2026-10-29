package com.routegenie.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RouteGenieSettingsTest : BasePlatformTestCase() {
    private val settings get() = RouteGenieSettings.getInstance()

    override fun tearDown() {
        try {
            settings.state.showGutterIcons = null
        } finally {
            super.tearDown()
        }
    }

    fun testDefaultFollowsThePaidPlugin() {
        settings.state.showGutterIcons = null
        assertEquals(!RouteGenieSettings.isPaidPythonActive(), settings.showGutterIcons)
    }

    fun testExplicitChoiceSticksAndRoundTripsThroughState() {
        settings.showGutterIcons = false
        val saved = RouteGenieSettings.State().also { it.showGutterIcons = settings.state.showGutterIcons }
        settings.loadState(saved)
        assertFalse(settings.showGutterIcons)
    }

    fun testSettingsPageBuildsAndAppliesWithoutChanges() {
        val page = RouteGenieConfigurable()
        try {
            assertNotNull(page.createComponent())
            assertFalse(page.isModified)
            page.apply()
        } finally {
            page.disposeUIResources()
        }
    }
}
