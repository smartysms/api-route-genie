package com.routegenie.testmap

import kotlinx.coroutines.flow.StateFlow

/**
 * What the Tests tab needs from the test mapping, without the IDE: the latest immutable [TestIndex], and the two switches
 * that keep the work lazy. The project service implements it; UI tests use a fake.
 */
interface TestIndexSource {
    val index: StateFlow<TestIndex>

    /** The tab is on screen (true) or not (false). Indexing runs only while somebody looks. */
    fun setActive(active: Boolean)

    /** Re-reads what changed and relinks; the user asked for it. */
    fun requestRefresh()
}
