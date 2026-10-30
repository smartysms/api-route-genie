package com.routegenie.ui.model

import com.routegenie.Limits
import com.routegenie.model.Endpoint
import com.routegenie.testmap.IndexPhase
import com.routegenie.testmap.TestCall
import com.routegenie.testmap.TestIndex

/** Which page the Tests tab shows. */
enum class TestsCard {
    /** Nothing, or a group, is selected in the endpoint list. */
    SELECT_ENDPOINT,

    /** The first read of the test files is running (or the route list is not ready). */
    INDEXING,

    /** The project has no test file that uses a test client. */
    NO_TEST_FILES,

    /** Test files exist, none call this endpoint. */
    NONE_FOUND,
    TESTS,
}

/**
 * What the tab shows for one endpoint, computed from the immutable [TestIndex]. Pure (no Swing), so the states are tested
 * without a window. [untested] is true only when the answer is certain; [doubt] is how many calls might still be about this
 * endpoint, and [unattributed] how many test calls in the whole project could not be attributed.
 */
class TestsView(
    val card: TestsCard,
    val calls: List<TestCall>,
    val reached: Int,
    val direct: Int,
    val indirect: Int,
    val off: Int,
    val untested: Boolean,
    val doubt: Int,
    val unattributed: Int,
    /** Rows that exist beyond [Limits.TESTS_SHOWN_PER_ENDPOINT_MAX]. */
    val hidden: Int,
) {
    /** The number on the tab title; zero shows no number. */
    val badge: Int get() = reached

    companion object {
        val NONE = TestsView(TestsCard.SELECT_ENDPOINT, emptyList(), 0, 0, 0, 0, false, 0, 0, 0)
    }
}

/** How an answer compares with what the test asserts. */
enum class Verdict { MATCHES, DIFFERS, UNASSERTED, NO_ANSWER }

object TestVerdict {
    /** [expected] is the status the test asserts (null when it asserts none that the code spells out), [actual] the status received (null: no answer). */
    fun of(expected: Int?, actual: Int?): Verdict = when {
        actual == null || actual <= 0 -> Verdict.NO_ANSWER
        expected == null -> Verdict.UNASSERTED
        expected == actual -> Verdict.MATCHES
        else -> Verdict.DIFFERS
    }
}

object TestsViewModel {
    fun build(index: TestIndex, endpoint: Endpoint?): TestsView {
        if (endpoint == null) return TestsView.NONE
        if (index.phase != IndexPhase.READY) return TestsView(TestsCard.INDEXING, emptyList(), 0, 0, 0, 0, false, 0, 0, 0)
        val unattributed = index.stats.unattributedCalls
        if (index.stats.testFiles == 0 && index.stats.clientCalls == 0) {
            return TestsView(TestsCard.NO_TEST_FILES, emptyList(), 0, 0, 0, 0, false, 0, unattributed, 0)
        }
        val all = index.callsOf(endpoint)
        val doubt = index.doubtFor(endpoint)
        if (all.isEmpty()) {
            return TestsView(TestsCard.NONE_FOUND, emptyList(), 0, 0, 0, 0, index.isUntested(endpoint), doubt, unattributed, 0)
        }
        val counted = all.filter { !it.test.mark.isOff }
        val shown = all.take(Limits.TESTS_SHOWN_PER_ENDPOINT_MAX)
        return TestsView(
            TestsCard.TESTS, shown, index.reachedBy(endpoint),
            direct = counted.filter { it.origin == com.routegenie.testmap.CallOrigin.DIRECT }.map { it.test.id }.distinct().size,
            indirect = counted.filter { it.origin == com.routegenie.testmap.CallOrigin.INDIRECT }.map { it.test.id }.distinct().size,
            off = all.filter { it.test.mark.isOff }.map { it.test.id }.distinct().size,
            untested = false, doubt = doubt, unattributed = unattributed, hidden = all.size - shown.size,
        )
    }
}
