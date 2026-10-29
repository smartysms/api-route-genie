package com.routegenie.clientide

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.routegenie.client.HttpEngine
import com.routegenie.client.PreparedRequest
import com.routegenie.client.ProgressListener
import com.routegenie.client.ResponseData
import com.routegenie.client.ResponseSlot
import com.routegenie.client.SendOptions
import com.routegenie.client.SpillDirectory
import com.routegenie.client.StreamCall
import com.routegenie.client.StreamListener
import com.routegenie.client.StreamOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch

/**
 * The project's HTTP client: one engine, the response currently shown, and the glue to the IDE (trust store, proxy, history).
 * Disposed with the project: calls in flight are cancelled, threads end and spill files are deleted. The injected [scope]
 * is cancelled by the platform at the same time.
 */
@Service(Service.Level.PROJECT)
class RouteGenieClientService(private val project: Project, scope: CoroutineScope) : Disposable {
    private val spill: SpillDirectory = IdeEngineFactory.spillDirectory()
    private val lazyEngine = lazy { IdeEngineFactory.create(spill) }

    val engine: HttpEngine get() = lazyEngine.value

    /** The response being shown; replacing it deletes the previous one's spill file. */
    val responses = ResponseSlot()

    init {
        // Leftovers of an IDE that was killed: not on the UI thread, and bounded by the age rule.
        scope.launch(Dispatchers.IO) { spill.sweepStale() }
    }

    /** Sends and records the call in the history (redacted). Cancelling the coroutine cancels the HTTP call. */
    suspend fun send(prepared: PreparedRequest, environmentName: String, base: SendOptions = SendOptions(), progress: ProgressListener? = null): ResponseData {
        val call = engine.execute(prepared.spec, prepared.options(base), progress)
        try {
            val response = call.result.await()
            RouteGenieClientState.getInstance(project).history.record(prepared, response, environmentName)
            return response
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        }
    }

    fun stream(prepared: PreparedRequest, listener: StreamListener, base: SendOptions = SendOptions(), stream: StreamOptions = StreamOptions()): StreamCall =
        engine.stream(prepared.spec, prepared.options(base), stream, listener)

    override fun dispose() {
        responses.close()
        if (lazyEngine.isInitialized()) lazyEngine.value.close() else spill.deleteAll()
    }
}
