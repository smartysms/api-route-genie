package com.routegenie.ui.json

import com.routegenie.json.JsonContent
import com.routegenie.json.JsonIndex
import com.routegenie.json.JsonScanner
import com.routegenie.json.ScanListener
import com.routegenie.json.ScanOptions
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One document on its way into the viewer: the content, the index being built on a background thread, and the cancel flag.
 * The index belongs to the session and is closed with it (after the scan has stopped, never under it). Callbacks run on the
 * scanning thread; the viewer hops to the UI thread itself.
 */
internal class JsonSession(val content: JsonContent) {
    @Volatile
    var index: JsonIndex? = null
        private set

    @Volatile
    var readError: IOException? = null
        private set

    @Volatile
    var done: Boolean = false
        private set

    val startedNanos: Long = System.nanoTime()

    @Volatile
    var finishedNanos: Long = 0
        private set

    private val cancelled = AtomicBoolean(false)
    private val closeRequested = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    val isCancelled: Boolean get() = cancelled.get()

    interface Callbacks {
        fun onStarted(index: JsonIndex)
        fun onProgress(index: JsonIndex)
        fun onFinished(index: JsonIndex?, error: IOException?)
    }

    fun start(callbacks: Callbacks) {
        running.set(true)
        JsonUi.background {
            var created: JsonIndex? = null
            try {
                val source = content.open()
                created = JsonScanner.scanInto(
                    source, ScanOptions(),
                    object : ScanListener {
                        override fun isCancelled(): Boolean = cancelled.get()
                        override fun onStart(index: JsonIndex) {
                            this@JsonSession.index = index
                            callbacks.onStarted(index)
                        }

                        override fun onProgress(index: JsonIndex) = callbacks.onProgress(index)
                    },
                    onCreated = { this.index = it },
                )
            } catch (e: IOException) {
                readError = e
            } finally {
                finishedNanos = System.nanoTime()
                done = true
                running.set(false)
                if (closeRequested.get()) closeIndex()
            }
            if (!cancelled.get()) callbacks.onFinished(created, readError)
        }
    }

    /** Stops the scan and releases the document as soon as nothing reads it any more. */
    fun close() {
        cancelled.set(true)
        closeRequested.set(true)
        if (!running.get()) closeIndex()
    }

    private fun closeIndex() {
        if (closed.compareAndSet(false, true)) runCatching { index?.close() }
    }
}
