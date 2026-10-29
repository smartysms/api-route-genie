package com.routegenie.client

import com.routegenie.Limits

/**
 * What a stream consumer keeps: the newest items within a byte and an item cap. When a cap is exceeded the oldest items are
 * dropped and counted, so the UI can say "N older events dropped". Not thread-safe; use it from one thread.
 */
class BoundedLog<T>(
    private val maxBytes: Long = Limits.STREAM_RING_MAX_BYTES,
    private val maxItems: Int = Limits.STREAM_RING_MAX_ITEMS,
    private val sizeOf: (T) -> Int,
) {
    private val items = ArrayDeque<T>()
    private var bytes = 0L

    var droppedItems = 0L
        private set
    var droppedBytes = 0L
        private set

    val size: Int get() = items.size
    val retainedBytes: Long get() = bytes

    fun add(item: T) {
        items.addLast(item)
        bytes += sizeOf(item)
        // The newest item always stays, even when it alone is above the byte cap.
        while (items.size > 1 && (items.size > maxItems || bytes > maxBytes)) {
            val old = items.removeFirst()
            val os = sizeOf(old)
            bytes -= os
            droppedItems++
            droppedBytes += os
        }
    }

    fun snapshot(): List<T> = items.toList()

    fun clear() {
        items.clear()
        bytes = 0
        droppedItems = 0
        droppedBytes = 0
    }
}
