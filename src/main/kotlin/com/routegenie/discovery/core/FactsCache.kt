package com.routegenie.discovery.core

import com.routegenie.Limits
import com.routegenie.discovery.SourceFacts
import com.routegenie.model.Diagnostic

/**
 * What one extraction of one file produced, valid for exactly one [stamp] of that file. [facts] is null when the file
 * holds no routes; [failure] is set when the provider threw, so a broken file is not retried on every refresh until it
 * changes. Holds no PSI, VirtualFile or Project.
 */
class FactsEntry(val stamp: Long, val facts: SourceFacts?, val failure: Diagnostic?) {
    /** Estimated bytes: the fixed cost of an entry plus what its facts keep alive. */
    val weight: Int = Limits.FACT_ENTRY_BYTES + (facts?.weight ?: 0) + (if (failure != null) Limits.FACT_ENTRY_BYTES else 0)
}

/**
 * Least-recently-used map bounded by the summed weight of its values (estimated bytes), not by their number: a repository
 * with a hundred thousand files and many tiny route files must not evict what the next pass needs. Safe for use from
 * several threads. The newest entry always stays, even when it alone is heavier than the bound.
 */
class WeightedLruCache<K : Any, V : Any>(private val maxWeight: Long, private val weigh: (V) -> Int) {
    init {
        require(maxWeight > 0) { "maxWeight must be positive: $maxWeight" }
    }

    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    private var total = 0L

    @Synchronized
    operator fun get(key: K): V? = map[key]

    @Synchronized
    operator fun set(key: K, value: V) {
        map.put(key, value)?.let { total -= weigh(it) }
        total += weigh(value)
        val iterator = map.entries.iterator()
        while (total > maxWeight && map.size > 1 && iterator.hasNext()) {
            val eldest = iterator.next()
            total -= weigh(eldest.value)
            iterator.remove()
        }
    }

    @Synchronized
    fun remove(key: K) {
        map.remove(key)?.let { total -= weigh(it) }
    }

    @Synchronized
    fun clear() {
        map.clear()
        total = 0
    }

    val size: Int @Synchronized get() = map.size

    /** Summed weight of the values currently held. */
    val weight: Long @Synchronized get() = total

    @Synchronized
    fun keys(): List<K> = map.keys.toList()
}
