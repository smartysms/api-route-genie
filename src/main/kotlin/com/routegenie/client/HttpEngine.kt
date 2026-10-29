package com.routegenie.client

import java.util.concurrent.CompletableFuture

/** Receives the number of body bytes read so far (decompressed) from the worker thread, at most about ten times a second. */
fun interface ProgressListener {
    fun onBytes(bytesSoFar: Long)
}

/** A send in flight. [result] always completes normally (see [ResponseData]); [cancel] ends it within about 100 ms. */
interface HttpCall {
    val result: CompletableFuture<ResponseData>
    fun cancel()
}

/**
 * Sends requests. Thread-safe; many calls may run at once. Never call [HttpCall.result] `.get()` on the UI thread.
 * Closing the engine cancels the calls in flight and releases its threads and connections.
 */
interface HttpEngine : AutoCloseable {
    /** Buffered send: the body is capped for display ([SendOptions.displayCapBytes]) and the rest is only counted. */
    fun execute(spec: RequestSpec, options: SendOptions = SendOptions(), progress: ProgressListener? = null): HttpCall

    /**
     * Streaming send: [listener] gets coalesced batches of [StreamEvent]s. The mode (SSE, NDJSON, raw, buffered) is
     * decided from the headers unless [stream] overrides it. Nothing of the body is retained by the engine; a stream ends
     * on its own end, [StreamOptions.idleTimeoutMs] without bytes, [StreamOptions.maxDurationMs], an error or cancel.
     */
    fun stream(
        spec: RequestSpec,
        options: SendOptions = SendOptions(),
        stream: StreamOptions = StreamOptions(),
        listener: StreamListener,
    ): StreamCall
}
