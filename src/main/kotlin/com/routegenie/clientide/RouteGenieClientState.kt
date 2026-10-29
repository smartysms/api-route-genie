package com.routegenie.clientide

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.routegenie.Limits
import com.routegenie.client.DevServerDefaults
import com.routegenie.client.Environment
import com.routegenie.client.HostGuard
import com.routegenie.client.LastInputs
import com.routegenie.client.RequestHistory
import com.routegenie.model.Framework

/**
 * What the project remembers about calling endpoints, kept in the workspace file (per user, not shared): environments, the last
 * environment used, the last method and inputs per endpoint, the confirmed hosts and a bounded redacted history.
 * Secrets are not here (see [SecretStore]). All collections are bounded by [Limits].
 */
@Service(Service.Level.PROJECT)
@State(name = "RouteGenieClient", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class RouteGenieClientState : PersistentStateComponent<ClientStateData> {
    private val lock = Any()
    private var environments = ArrayList<Environment>()
    private var lastEnvironment: String? = null

    var lastInputs = LastInputs()
        private set
    var history = RequestHistory()
        private set
    var hostGuard = HostGuard()
        private set

    fun environments(): List<Environment> = synchronized(lock) { environments.toList() }

    fun selected(): Environment? = synchronized(lock) { environments.firstOrNull { it.name == lastEnvironment } ?: environments.firstOrNull() }

    fun select(name: String) = synchronized(lock) { if (environments.any { it.name == name }) lastEnvironment = name }

    /** Adds or replaces the environment with the same name; the oldest one is dropped beyond [Limits.ENVIRONMENTS_MAX]. */
    fun save(env: Environment) = synchronized(lock) {
        val capped = env.copy(variables = env.variables.entries.take(Limits.ENVIRONMENT_VARIABLES_MAX).associate { it.key to it.value })
        val i = environments.indexOfFirst { it.name == env.name }
        if (i >= 0) environments[i] = capped else environments.add(capped)
        while (environments.size > Limits.ENVIRONMENTS_MAX) environments.removeAt(0)
    }

    fun remove(name: String) = synchronized(lock) {
        environments.removeAll { it.name == name }
        if (lastEnvironment == name) lastEnvironment = null
    }

    /** The selected environment, or a new one named [name] that points at the framework's usual dev server. */
    fun ensureDefault(framework: Framework, name: String): Environment = synchronized(lock) {
        selected() ?: Environment(name, DevServerDefaults.baseUrl(framework)).also {
            environments.add(it)
            lastEnvironment = it.name
        }
    }

    override fun getState(): ClientStateData = synchronized(lock) {
        ClientStateData().also { d ->
            d.lastEnvironment = lastEnvironment.orEmpty()
            d.environments = environments.map { ClientStateMapper.toData(it) }.toMutableList()
            // Oldest first, so that loading restores the recency order.
            d.lastInputs = lastInputs.entries().map { ClientStateMapper.toData(it.first, it.second) }.toMutableList()
            d.history = history.snapshot().map { ClientStateMapper.toData(it) }.toMutableList()
            d.confirmedHosts = hostGuard.snapshot().toMutableList()
        }
    }

    override fun loadState(state: ClientStateData) = synchronized(lock) {
        environments = ArrayList(state.environments.take(Limits.ENVIRONMENTS_MAX).map { ClientStateMapper.fromData(it) })
        lastEnvironment = state.lastEnvironment.ifEmpty { null }
        lastInputs = ClientStateMapper.lastInputsFrom(state.lastInputs)
        history = RequestHistory(initial = state.history.mapNotNull { ClientStateMapper.fromData(it) })
        hostGuard = HostGuard(state.confirmedHosts)
    }

    companion object {
        fun getInstance(project: Project): RouteGenieClientState = project.getService(RouteGenieClientState::class.java)
    }
}
