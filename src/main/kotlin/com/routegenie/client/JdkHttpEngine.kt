package com.routegenie.client

import com.routegenie.Limits
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Authenticator
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException

/**
 * The HTTP engine on java.net.http. Differences from the JDK defaults that matter for API testing (research 03): HTTP/1.1 on
 * http://, redirects followed by our own loop (credentials removed across origins), a total deadline, streamed bodies with a
 * display cap and a spill file, gzip/deflate decoding, our own multipart body, URLs checked up front.
 *
 * Threads: at most [Limits.HTTP_MAX_CONCURRENT_SENDS] sends run at once on daemon workers that exit when idle; one timer thread
 * serves deadlines. [close] cancels everything, ends all threads and deletes the spill files this engine created.
 * IDE trust, proxy login and the spill folder are injected so this class stays free of the IDE.
 */
class JdkHttpEngine(
    private val sslContextFor: (TlsMode) -> SSLContext? = { null },
    private val authenticator: Authenticator? = null,
    private val spill: SpillDirectory = SpillDirectory.inSystemTemp(),
    private val userAgent: String? = null,
    private val spillMaxBytes: Long = Limits.HTTP_SPILL_MAX_BYTES,
) : HttpEngine {
    private val workers = ThreadPoolExecutor(
        Limits.HTTP_MAX_CONCURRENT_SENDS, Limits.HTTP_MAX_CONCURRENT_SENDS, Limits.HTTP_WORKER_IDLE_SECONDS, TimeUnit.SECONDS,
        LinkedBlockingQueue(), daemonFactory("RouteGenie-http-worker"),
    ).apply { allowCoreThreadTimeOut(true) }
    private val timer: ScheduledExecutorService = ScheduledThreadPoolExecutor(1, daemonFactory("RouteGenie-http-timer")).apply {
        removeOnCancelPolicy = true
        setKeepAliveTime(Limits.HTTP_WORKER_IDLE_SECONDS, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }
    private val clients = object : LinkedHashMap<Pair<TlsMode, HttpClient.Version>, HttpClient>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<TlsMode, HttpClient.Version>, HttpClient>): Boolean {
            if (size <= Limits.HTTP_MAX_CLIENTS) return false
            // shutdown (not close): sends already running on the evicted client may finish.
            eldest.value.shutdown()
            return true
        }
    }
    private val calls = ConcurrentHashMap.newKeySet<CallState>()

    @Volatile
    private var closed = false

    // --- public API ------------------------------------------------------------------------------------------------

    override fun execute(spec: RequestSpec, options: SendOptions, progress: ProgressListener?): HttpCall {
        val state = CallState()
        val result = CompletableFuture<ResponseData>()
        val call = object : HttpCall {
            override val result: CompletableFuture<ResponseData> = result
            override fun cancel() = state.abort(ErrorKind.CANCELLED)
        }
        if (!submit(state) {
                val data = try {
                    runBuffered(spec, options, progress, state)
                } catch (t: Throwable) {
                    ResponseData.failed(mapError(t), spec.url, 0)
                }
                // A response nobody receives must not leave its spill file behind.
                if (!result.complete(data)) data.release()
            }
        ) result.complete(ResponseData.failed(EngineError(ErrorKind.CANCELLED), spec.url, 0))
        // A cancel before the task starts must not wait for a free worker.
        state.onAbortBeforeStart = { result.complete(ResponseData.failed(EngineError(ErrorKind.CANCELLED), spec.url, 0)) }
        return call
    }

    override fun stream(spec: RequestSpec, options: SendOptions, stream: StreamOptions, listener: StreamListener): StreamCall {
        val state = CallState()
        val finished = CompletableFuture<StreamEvent>()
        val batcher = Batcher(listener, timer)
        val call = object : StreamCall {
            override val finished: CompletableFuture<StreamEvent> = finished
            override fun cancel() = state.abort(ErrorKind.CANCELLED)
        }
        val accepted = submit(state) {
            val terminal = try {
                runStream(spec, options, stream, batcher, state)
            } catch (t: Throwable) {
                StreamEvent.Failed(mapError(t))
            }
            batcher.terminal(terminal)
            finished.complete(terminal)
        }
        if (!accepted) {
            val terminal = StreamEvent.Failed(EngineError(ErrorKind.CANCELLED))
            batcher.terminal(terminal)
            finished.complete(terminal)
        }
        return call
    }

    override fun close() {
        closed = true
        calls.forEach { it.abort(ErrorKind.CANCELLED) }
        workers.shutdownNow()
        timer.shutdownNow()
        synchronized(clients) {
            clients.values.forEach { it.shutdownNow() }
            clients.clear()
        }
        workers.awaitTermination(Limits.HTTP_CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)
        spill.deleteAll()
    }

    // --- call bookkeeping --------------------------------------------------------------------------------------------

    private class CallState {
        private val lock = Any()

        @Volatile
        var abortKind: ErrorKind? = null
            private set

        @Volatile
        var future: CompletableFuture<*>? = null

        @Volatile
        var body: InputStream? = null

        @Volatile
        var onAbortBeforeStart: (() -> Unit)? = null
        private var worker: Thread? = null
        private var started = false
        private var finished = false

        fun begin() = synchronized(lock) {
            started = true
            worker = Thread.currentThread()
        }

        /** After this no abort can interrupt the thread any more, and a pending interrupt is cleared before the thread is reused. */
        fun end() {
            synchronized(lock) { finished = true }
            Thread.interrupted()
        }

        fun abort(kind: ErrorKind) {
            val callback: (() -> Unit)?
            synchronized(lock) {
                if (abortKind != null) return
                abortKind = kind
                future?.cancel(true)
                runCatching { body?.close() }
                if (started && !finished) worker?.interrupt()
                callback = if (!started) onAbortBeforeStart else null
            }
            callback?.invoke()
        }
    }

    private fun submit(state: CallState, task: () -> Unit): Boolean {
        if (closed) return false
        calls.add(state)
        return try {
            workers.execute {
                state.begin()
                try {
                    task()
                } finally {
                    state.end()
                    calls.remove(state)
                }
            }
            true
        } catch (e: RejectedExecutionException) {
            calls.remove(state)
            false
        }
    }

    // --- the shared first half: URL, headers, redirects, until final headers ---------------------------------------

    private class Open(
        val response: HttpResponse<InputStream>,
        val url: ParsedUrl,
        val method: String,
        val hops: List<RedirectHop>,
        val headersMs: Long,
        val insecure: Boolean,
        val start: Long,
        val blocked: EngineError?,
    ) {
        val headers: List<Pair<String, String>>
            get() = response.headers().map().flatMap { (k, vs) -> vs.map { k.lowercase() to it } }.filter { !it.first.startsWith(":") }
    }

    private sealed interface Step {
        class Ok(val open: Open) : Step
        class Fail(val data: ResponseData) : Step
    }

    private fun elapsedMs(start: Long) = (System.nanoTime() - start) / NANOS_PER_MS

    private fun open(spec: RequestSpec, options: SendOptions, st: CallState, start: Long): Step {
        fun fail(error: EngineError, url: String, hops: List<RedirectHop> = emptyList()) =
            Step.Fail(ResponseData.failed(error, url, elapsedMs(start), hops))

        var url = when (val r = UrlSupport.parse(spec.url)) {
            is UrlResult.Bad -> return fail(r.error, spec.url)
            is UrlResult.Ok -> r.url
        }
        var headers = ArrayList<Pair<String, String>>()
        for (h in spec.headers) {
            if (!h.enabled) continue
            val problem = headerProblem(h)
            if (problem != null) return fail(EngineError(ErrorKind.INVALID_REQUEST, problem), url.text)
            headers += h.name.trim() to h.value
        }
        if (userAgent != null && headers.none { it.first.equals("User-Agent", true) }) headers += "User-Agent" to userAgent
        if (options.compressed && headers.none { it.first.equals("Accept-Encoding", true) }) headers += "Accept-Encoding" to ACCEPT_ENCODING
        var method = spec.method.name
        var body = spec.body
        val boundary = Multipart.boundary()
        val hops = ArrayList<RedirectHop>()
        val strip = SensitiveNames.HEADERS + options.sensitiveHeaders.map { it.lowercase() }

        while (true) {
            st.abortKind?.let { return fail(EngineError(it), url.text, hops) }
            val insecure = options.tls == TlsMode.INSECURE && url.scheme == "https"
            if (insecure && !HostClasses.isLoopback(url.host)) return fail(EngineError(ErrorKind.INSECURE_NOT_ALLOWED, url.host), url.text, hops)
            val hopStart = System.nanoTime()
            val request = try {
                val b = HttpRequest.newBuilder(url.uri)
                if (options.headersTimeoutMs > 0) b.timeout(Duration.ofMillis(options.headersTimeoutMs))
                headers.forEach { (n, v) -> b.header(n, v) }
                b.applyBody(body, method, boundary, headers.any { it.first.equals("Content-Type", true) })
                b.build()
            } catch (e: FileNotFoundException) {
                return fail(EngineError(ErrorKind.INVALID_REQUEST, "file not readable: ${e.message}"), url.text, hops)
            } catch (e: IllegalArgumentException) {
                return fail(EngineError(ErrorKind.INVALID_REQUEST, e.javaClass.simpleName), url.text, hops)
            }
            val response = try {
                val future = clientFor(options, url).sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                st.future = future
                if (st.abortKind != null) future.cancel(true)
                future.get()
            } catch (t: Throwable) {
                st.abortKind?.let { return fail(EngineError(it), url.text, hops) }
                return fail(mapError(t), url.text, hops)
            }
            st.body = response.body()
            if (st.abortKind != null) {
                runCatching { response.body().close() }
                return fail(EngineError(st.abortKind!!), url.text, hops)
            }
            val status = response.statusCode()
            val location = response.headers().firstValue("location").orElse(null)
            val open = Open(response, url, method, hops, elapsedMs(start), insecure, start, null)
            if (!options.followRedirects || status !in REDIRECTS || location == null) return Step.Ok(open)

            val next = when (val r = UrlSupport.resolve(url, location)) {
                is UrlResult.Bad -> return Step.Ok(open.blockedBy(EngineError(ErrorKind.REDIRECT_BLOCKED, "bad location")))
                is UrlResult.Ok -> r.url
            }
            val crossOrigin = next.origin != url.origin
            val blocked = when {
                url.scheme == "https" && next.scheme == "http" -> "https to http"
                crossOrigin && !options.redirectHostAllowed(next.host) -> "host not allowed: ${next.host}"
                crossOrigin && options.crossOrigin == CrossOriginRedirect.STOP -> return Step.Ok(open)
                else -> null
            }
            if (blocked != null) return Step.Ok(open.blockedBy(EngineError(ErrorKind.REDIRECT_BLOCKED, blocked)))
            if (hops.size >= options.maxRedirects) {
                runCatching { response.body().close() }
                return fail(EngineError(ErrorKind.TOO_MANY_REDIRECTS, options.maxRedirects.toString()), url.text, hops)
            }
            runCatching { response.body().close() }
            hops += RedirectHop(status, url.text, next.text, (System.nanoTime() - hopStart) / NANOS_PER_MS)
            val dropBody = (status == SEE_OTHER && method != "HEAD") || ((status == MOVED || status == FOUND) && method == "POST")
            if (dropBody) {
                method = if (method == "HEAD") method else "GET"
                body = RequestBody.None
                headers = ArrayList(headers.filter { !it.first.equals("Content-Type", true) })
            }
            if (crossOrigin) headers = ArrayList(headers.filter { it.first.lowercase() !in strip })
            url = next
        }
    }

    private fun Open.blockedBy(error: EngineError) = Open(response, url, method, hops, headersMs, insecure, start, error)

    private fun headerProblem(h: Header): String? {
        val name = h.name.trim()
        if (name.isEmpty() || !name.all { it in TOKEN_CHARS || it.isLetterOrDigit() && it.code < ASCII_LIMIT }) return "invalid header name"
        if (name.lowercase() in RESTRICTED_HEADERS) return "header $name is set by the HTTP engine"
        if (h.value.any { it == '\r' || it == '\n' }) return "header $name contains a line break"
        return null
    }

    private fun clientFor(options: SendOptions, url: ParsedUrl): HttpClient {
        val version = when (options.httpVersion) {
            HttpVersionPolicy.HTTP_1_1 -> HttpClient.Version.HTTP_1_1
            HttpVersionPolicy.HTTP_2 -> HttpClient.Version.HTTP_2
            HttpVersionPolicy.AUTO -> if (url.scheme == "http") HttpClient.Version.HTTP_1_1 else HttpClient.Version.HTTP_2
        }
        val tls = if (url.scheme == "https") options.tls else TlsMode.IDE_TRUST
        synchronized(clients) {
            if (closed) throw CancellationException("engine closed")
            return clients.getOrPut(tls to version) {
                val b = HttpClient.newBuilder().version(version).followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofMillis(Limits.HTTP_CONNECT_TIMEOUT_MS))
                val ssl = if (tls == TlsMode.INSECURE) TrustAllManager.context() else sslContextFor(tls)
                if (ssl != null) b.sslContext(ssl)
                if (authenticator != null) b.authenticator(authenticator)
                b.build()
            }
        }
    }

    // --- buffered send -------------------------------------------------------------------------------------------------

    private fun runBuffered(spec: RequestSpec, options: SendOptions, progress: ProgressListener?, st: CallState): ResponseData {
        val start = System.nanoTime()
        val deadline = scheduleAbort(st, options.totalTimeoutMs, ErrorKind.TIMEOUT_TOTAL)
        try {
            val open = when (val step = open(spec, options, st, start)) {
                is Step.Fail -> return step.data
                is Step.Ok -> step.open
            }
            return readResponse(open, options, progress, st)
        } finally {
            deadline?.cancel(false)
            runCatching { st.body?.close() }
        }
    }

    private fun readResponse(open: Open, options: SendOptions, progress: ProgressListener?, st: CallState): ResponseData {
        val status = open.response.statusCode()
        val headers = open.headers
        fun data(body: ResponseBody, error: EngineError? = open.blocked) = ResponseData(
            status, HttpStatusText.of(status), headers, body, elapsedMs(open.start), open.headersMs, open.url.text, open.hops,
            open.response.version().toString().replace("HTTP_1_1", "HTTP/1.1").replace("HTTP_2", "HTTP/2"), error, open.insecure,
        )
        if (open.method == "HEAD" || status == NO_CONTENT || status == NOT_MODIFIED || status < OK) return data(ResponseBody.EMPTY)
        val encoding = headers.firstOrNull { it.first == "content-encoding" }?.second
        val declared = headers.firstOrNull { it.first == "content-length" }?.second?.toLongOrNull()
        if (encoding == null && declared != null && declared > spillMaxBytes) {
            return data(ResponseBody.EMPTY, EngineError(ErrorKind.TOO_LARGE, declared.toString()))
        }
        return try {
            val input = Decoding.wrap(open.response.body(), encoding)
            try {
                data(readBody(input, options.displayCapBytes, progress))
            } finally {
                runCatching { input.close() }
            }
        } catch (t: Throwable) {
            val kind = st.abortKind
            ResponseData.failed(if (kind != null) EngineError(kind) else mapError(t), open.url.text, elapsedMs(open.start), open.hops)
        }
    }

    /**
     * Keeps the first [cap] bytes in memory; when the body is bigger, the complete body goes to a spill file as it arrives.
     * Whatever fails, a partial file is deleted before the exception leaves.
     */
    private fun readBody(input: InputStream, cap: Int, progress: ProgressListener?): ResponseBody {
        val chunk = ByteArray(Limits.HTTP_READ_CHUNK_BYTES)
        val head = ByteArrayOutputStream(minOf(cap, Limits.HTTP_READ_CHUNK_BYTES))
        var out: OutputStream? = null
        var path: Path? = null
        var total = 0L
        var atLimit = false
        var lastProgress = System.nanoTime()
        try {
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                if (out == null) {
                    if (total + n <= cap) {
                        head.write(chunk, 0, n)
                        total += n
                    } else {
                        val keep = (cap - total).toInt()
                        head.write(chunk, 0, keep)
                        path = spill.create()
                        out = BufferedOutputStream(Files.newOutputStream(path), Limits.HTTP_READ_CHUNK_BYTES)
                        head.writeTo(out)
                        out.write(chunk, keep, n - keep)
                        total += n
                    }
                } else {
                    val room = spillMaxBytes - total
                    if (n > room) {
                        out.write(chunk, 0, room.toInt())
                        total += room
                        atLimit = true
                        break
                    }
                    out.write(chunk, 0, n)
                    total += n
                }
                if (progress != null && System.nanoTime() - lastProgress >= PROGRESS_INTERVAL_NANOS) {
                    lastProgress = System.nanoTime()
                    progress.onBytes(total)
                }
            }
            out?.close()
            progress?.onBytes(total)
            return ResponseBody(head.toByteArray(), path, total, atLimit, spill)
        } catch (t: Throwable) {
            runCatching { out?.close() }
            path?.let { spill.delete(it) }
            throw t
        }
    }

    // --- streaming send ------------------------------------------------------------------------------------------------

    private fun runStream(spec: RequestSpec, options: SendOptions, so: StreamOptions, batcher: Batcher, st: CallState): StreamEvent {
        val start = System.nanoTime()
        val total = scheduleAbort(st, options.totalTimeoutMs, ErrorKind.TIMEOUT_TOTAL)
        var idle: ScheduledFuture<*>? = null
        var maxDuration: ScheduledFuture<*>? = null
        try {
            val open = when (val step = open(spec, options, st, start)) {
                is Step.Fail -> return StreamEvent.Failed(step.data.error!!)
                is Step.Ok -> step.open
            }
            val headers = open.headers
            val mode = ResponseModes.decide(headers, so.mode)
            val status = open.response.statusCode()
            val version = open.response.version().toString().replace("HTTP_1_1", "HTTP/1.1").replace("HTTP_2", "HTTP/2")
            batcher.add(StreamEvent.HeadersReceived(status, HttpStatusText.of(status), headers, version, mode, open.url.text), 0)
            batcher.flushNow()
            if (mode == ResponseMode.BUFFERED) {
                val data = readResponse(open, options, null, st)
                batcher.add(StreamEvent.BufferedResponse(data), 0)
                return data.error?.let { StreamEvent.Failed(it) }
                    ?: StreamEvent.Completed(StreamSummary(data.bodySize, 1, elapsedMs(start)))
            }
            // A stream may run longer than the total deadline: from here only idleness and the optional maximum count.
            total?.cancel(false)
            val last = java.util.concurrent.atomic.AtomicLong(System.nanoTime())
            if (so.idleTimeoutMs > 0) {
                val period = maxOf(so.idleTimeoutMs / IDLE_CHECKS, MIN_IDLE_PERIOD_MS)
                idle = timer.scheduleWithFixedDelay({
                    if (System.nanoTime() - last.get() > so.idleTimeoutMs * NANOS_PER_MS) st.abort(ErrorKind.TIMEOUT_IDLE)
                }, period, period, TimeUnit.MILLISECONDS)
            }
            if (so.maxDurationMs > 0) maxDuration = scheduleAbort(st, so.maxDurationMs, ErrorKind.TIMEOUT_TOTAL)
            var bytes = 0L
            var events = 0L
            val sse = SseParser { events++; batcher.add(it, it.data.length) }
            val lines = LineSplitter {
                if (it.isNotEmpty()) {
                    events++
                    batcher.add(StreamEvent.Line(it), it.length)
                }
            }
            val encoding = headers.firstOrNull { it.first == "content-encoding" }?.second
            val input = Decoding.wrap(open.response.body(), encoding)
            try {
                val buf = ByteArray(Limits.HTTP_READ_CHUNK_BYTES)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    last.set(System.nanoTime())
                    bytes += n
                    when (mode) {
                        ResponseMode.SSE -> sse.feed(buf, 0, n)
                        ResponseMode.NDJSON -> lines.feed(buf, 0, n)
                        else -> {
                            events++
                            batcher.add(StreamEvent.Chunk(buf.copyOf(n)), n)
                        }
                    }
                }
                if (mode == ResponseMode.NDJSON) lines.finish()?.takeIf { it.isNotEmpty() }?.let {
                    events++
                    batcher.add(StreamEvent.Line(it), it.length)
                }
            } finally {
                runCatching { input.close() }
            }
            return StreamEvent.Completed(StreamSummary(bytes, events, elapsedMs(start)))
        } catch (t: Throwable) {
            val kind = st.abortKind
            return StreamEvent.Failed(if (kind != null) EngineError(kind) else mapError(t))
        } finally {
            total?.cancel(false)
            idle?.cancel(false)
            maxDuration?.cancel(false)
            runCatching { st.body?.close() }
        }
    }

    private fun scheduleAbort(st: CallState, ms: Long, kind: ErrorKind): ScheduledFuture<*>? {
        if (ms <= 0) return null
        return try {
            timer.schedule({ st.abort(kind) }, ms, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            null
        }
    }

    // --- error mapping -----------------------------------------------------------------------------------------------------

    private fun mapError(t: Throwable): EngineError {
        var cur: Throwable? = t
        var depth = 0
        // The JDK wraps the real cause several levels deep; the kind is decided by the most specific one found.
        val chain = ArrayList<Throwable>()
        while (cur != null && depth++ < MAX_CAUSE_DEPTH) {
            chain += cur
            cur = if (cur is ExecutionException || cur.cause !== cur) cur.cause else null
        }
        val detail = chain.last().javaClass.simpleName
        return when {
            chain.any { it is CancellationException } -> EngineError(ErrorKind.CANCELLED)
            chain.any { it is HttpConnectTimeoutException } -> EngineError(ErrorKind.TIMEOUT_CONNECT)
            chain.any { it is HttpTimeoutException } -> EngineError(ErrorKind.TIMEOUT_HEADERS)
            chain.any { it is UnknownHostException || it is UnresolvedAddressException } -> EngineError(ErrorKind.DNS)
            chain.any { it is ConnectException } -> EngineError(ErrorKind.CONNECTION_REFUSED)
            chain.any { it is SSLException } -> EngineError(ErrorKind.TLS, detail)
            chain.any { it is IllegalArgumentException } -> EngineError(ErrorKind.INVALID_REQUEST, detail)
            chain.any { it is InterruptedException } -> EngineError(ErrorKind.CANCELLED)
            chain.any { it is IOException } -> EngineError(ErrorKind.UNKNOWN, detail)
            else -> EngineError(ErrorKind.UNKNOWN, detail)
        }
    }

    // --- coalescing ----------------------------------------------------------------------------------------------------------

    /** Collects events and hands them to the listener in batches: every window, or earlier when a batch is big. */
    private class Batcher(private val listener: StreamListener, private val timer: ScheduledExecutorService) {
        private val lock = Any()
        private var pending = ArrayList<StreamEvent>()
        private var bytes = 0
        private var scheduled = false

        fun add(event: StreamEvent, size: Int) = synchronized(lock) {
            pending += event
            bytes += size
            if (pending.size >= Limits.STREAM_BATCH_MAX_EVENTS || bytes >= Limits.STREAM_BATCH_MAX_BYTES) {
                flushLocked()
            } else if (!scheduled) {
                scheduled = true
                try {
                    timer.schedule({ synchronized(lock) { scheduled = false; flushLocked() } }, Limits.STREAM_BATCH_WINDOW_MS, TimeUnit.MILLISECONDS)
                } catch (e: RejectedExecutionException) {
                    scheduled = false
                    flushLocked()
                }
            }
        }

        fun flushNow() = synchronized(lock) { flushLocked() }

        fun terminal(event: StreamEvent) = synchronized(lock) {
            pending += event
            flushLocked()
        }

        private fun flushLocked() {
            if (pending.isEmpty()) return
            val batch = pending
            pending = ArrayList()
            bytes = 0
            try {
                listener.onEvents(batch)
            } catch (t: Throwable) {
                // A failing consumer must not stop the stream; only the class name is logged (never event content).
                System.getLogger(LOGGER).log(System.Logger.Level.WARNING, "stream listener failed: " + t.javaClass.name)
            }
        }
    }

    private companion object {
        const val LOGGER = "RouteGenie.client"
        const val ACCEPT_ENCODING = "gzip, deflate"
        const val NANOS_PER_MS = 1_000_000L
        const val PROGRESS_INTERVAL_NANOS = 100L * NANOS_PER_MS
        const val ASCII_LIMIT = 128
        const val MAX_CAUSE_DEPTH = 12
        const val IDLE_CHECKS = 4L
        const val MIN_IDLE_PERIOD_MS = 10L
        const val OK = 200
        const val NO_CONTENT = 204
        const val NOT_MODIFIED = 304
        const val MOVED = 301
        const val FOUND = 302
        const val SEE_OTHER = 303
        val REDIRECTS = setOf(MOVED, FOUND, SEE_OTHER, 307, 308)
        const val TOKEN_CHARS = "!#$%&'*+-.^_`|~"
        val RESTRICTED_HEADERS = setOf("host", "connection", "content-length", "expect", "upgrade")

        fun daemonFactory(prefix: String): java.util.concurrent.ThreadFactory {
            val n = java.util.concurrent.atomic.AtomicInteger()
            return java.util.concurrent.ThreadFactory { r -> Thread(r, "$prefix-${n.incrementAndGet()}").apply { isDaemon = true } }
        }
    }
}
