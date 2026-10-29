package com.routegenie.discovery.core

/**
 * Least-recently-used map with a hard size cap, safe for use from several threads. Values must be PSI-free; the cache
 * never keeps more than [capacity] entries, so a project with endless files cannot grow it without bound.
 */
class BoundedLruCache<K : Any, V : Any>(private val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive: $capacity" }
    }

    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > capacity
    }

    @Synchronized
    operator fun get(key: K): V? = map[key]

    @Synchronized
    operator fun set(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    fun remove(key: K) {
        map.remove(key)
    }

    @Synchronized
    fun clear() = map.clear()

    val size: Int @Synchronized get() = map.size

    @Synchronized
    fun keys(): List<K> = map.keys.toList()
}
