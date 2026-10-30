package com.routegenie.settings

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.extensions.PluginId
import com.routegenie.Ids

/** The decisions that depend on the environment, as pure functions so they can be tested without an IDE. */
object SettingsPolicy {
    /** The paid Python plugin shows its own markers, so ours default to off there; an explicit user choice always wins. */
    fun showGutterIcons(stored: Boolean?, paidPythonActive: Boolean): Boolean = stored ?: !paidPythonActive
}

/**
 * Application-wide settings. [State.showGutterIcons] stays null until the user decides, so the default can follow the
 * environment (see [SettingsPolicy]) instead of being frozen into the settings file at first start.
 */
@Service(Service.Level.APP)
@State(name = "RouteGenieSettings", storages = [Storage("routegenie.xml")])
class RouteGenieSettings : PersistentStateComponent<RouteGenieSettings.State> {
    class State {
        @JvmField
        var showGutterIcons: Boolean? = null

        /** Name of a [BodyFillMode]; null until the user chooses. */
        @JvmField
        var bodyFillMode: String? = null
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var showGutterIcons: Boolean
        get() = SettingsPolicy.showGutterIcons(state.showGutterIcons, isPaidPythonActive())
        set(value) {
            state.showGutterIcons = value
        }

    /** What the request body is filled with, on its own, when an endpoint is opened for the first time. */
    var bodyFillMode: BodyFillMode
        get() = BodyFillMode.parse(state.bodyFillMode)
        set(value) {
            state.bodyFillMode = value.name
        }

    companion object {
        fun getInstance(): RouteGenieSettings = ApplicationManager.getApplication().getService(RouteGenieSettings::class.java)

        /** True when the paid Python plugin is installed and enabled (a disabled plugin is not found by findEnabledPlugin). */
        fun isPaidPythonActive(): Boolean = PluginManager.getInstance().findEnabledPlugin(PluginId.getId(Ids.PAID_PYTHON_PLUGIN)) != null
    }
}
