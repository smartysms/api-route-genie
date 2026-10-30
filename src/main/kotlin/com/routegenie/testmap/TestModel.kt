package com.routegenie.testmap

import com.routegenie.model.DiagnosticCode
import com.routegenie.model.Endpoint
import com.routegenie.model.HttpMethod
import com.routegenie.model.SourceRef

/** A function or method the runner would collect, found by static rules. Identity is the pytest node id. */
data class ProjectTest(
    /** `path/to/test_x.py::Class::test_name` (the shape pytest takes on its command line). */
    val id: String,
    val file: String,
    val className: String?,
    val name: String,
    val style: TestStyle,
    val mark: TestMark,
    val source: SourceRef,
) {
    /** The name with its class, for display: `TestItems.test_create`. */
    val displayName: String get() = if (className == null) name else "$className.$name"
}

/** Direct: the call is in the body of the test. Indirect: in a helper, fixture or setUp it uses (not followed yet). */
enum class CallOrigin { DIRECT, INDIRECT }

/** The body a test sends, when the code spells it out. */
sealed interface BodyLiteral {
    data class Json(val text: String) : BodyLiteral
    data class Form(val fields: List<Pair<String, String>>) : BodyLiteral
    data class Text(val text: String) : BodyLiteral
}

/**
 * What the code spells out of the request one call makes. A part that is not fully literal is null ("not determined"), a part
 * that the call does not pass is empty/absent; nothing is ever guessed.
 */
data class CallRequest(
    val query: List<Pair<String, String>>?,
    val headers: List<Pair<String, String>>?,
    val body: BodyLiteral?,
    /** True when the call passes a body that is not literal (so the empty body is not "no body"). */
    val bodyNotDetermined: Boolean,
    val contentType: String? = null,
) {
    companion object {
        val NONE = CallRequest(emptyList(), emptyList(), null, false)
    }
}

/** A call that was attributed to exactly one endpoint. */
data class TestCall(
    val test: ProjectTest,
    val verb: HttpMethod,
    /** The path as the test wrote it, evaluated; unknown segments are `{?}`. Without query string. */
    val path: String,
    /** The call itself: file, line and offset to navigate to. */
    val source: SourceRef,
    val origin: CallOrigin,
    /** The path differs from the endpoint's only by the trailing slash, so the framework redirects first. */
    val viaRedirect: Boolean,
    /** The route name when the call named its target (`reverse("shop:detail")`). */
    val routeName: String?,
    val expectedStatus: Int?,
    val request: CallRequest,
)

/** Which endpoints an unattributed call might still be about; used to tell "untested" from "no test found". */
sealed interface Reach {
    /** The call resolved completely and matches nothing (or is no client call): it casts no doubt on any endpoint. */
    data object Unrelated : Reach

    /** The URL is not known (computed prefix, unresolved constant): it could be any endpoint. */
    data object Anywhere : Reach

    /** The call matches these endpoints (keys) but could not be told apart. */
    data class Endpoints(val keys: Set<String>) : Reach
}

/** A client call that is not attributed, with the reason. Never an error of the user's code. */
data class UnattributedCall(
    val source: SourceRef,
    val testId: String?,
    val verb: HttpMethod?,
    val reason: DiagnosticCode,
    val detail: String?,
    val reach: Reach,
)

enum class IndexPhase {
    /** Nothing has run yet (the tab was never shown). */
    IDLE,

    /** Test files are being read or the route list is not ready. */
    INDEXING,
    READY,
}

/** Numbers of one link, shown in empty states and recorded by the benchmark. */
data class TestStats(
    val testFiles: Int,
    val testFunctions: Int,
    val clientCalls: Int,
    val mappedCalls: Int,
    val unattributedByReason: Map<DiagnosticCode, Int>,
    /** True when a cap of [com.routegenie.Limits] cut the lists; the numbers are then lower bounds. */
    val truncated: Boolean = false,
    /** Test files whose analysis threw; they are not retried until they change. */
    val failedFiles: Int = 0,
    /** Calls on `self.client` whose test case derives from a class that has not been read, so they are not known to be requests. */
    val unresolvedReceivers: Int = 0,
) {
    val unattributedCalls: Int get() = unattributedByReason.values.sum()

    companion object {
        val EMPTY = TestStats(0, 0, 0, 0, emptyMap())
    }
}

/**
 * The complete, immutable result of one test mapping. Deliberately not a data class (identity equality, O(1) in a StateFlow).
 * [byEndpoint] is keyed by [Endpoint.key] of the route snapshot it was linked against.
 */
class TestIndex(
    val generation: Long,
    val phase: IndexPhase,
    val stats: TestStats,
    byEndpoint: Map<String, List<TestCall>>,
    unattributed: List<UnattributedCall>,
) {
    val byEndpoint: Map<String, List<TestCall>> = byEndpoint
    val unattributed: List<UnattributedCall> = unattributed

    private val anywhere: Int = unattributed.count { it.reach == Reach.Anywhere }
    private val doubtByEndpoint: Map<String, Int> = HashMap<String, Int>().also { map ->
        unattributed.forEach { call -> (call.reach as? Reach.Endpoints)?.keys?.forEach { key -> map.merge(key, 1, Int::plus) } }
    }

    /** Every call attributed to [endpoint], in test order. */
    fun callsOf(endpoint: Endpoint): List<TestCall> = byEndpoint[endpoint.key].orEmpty()

    /** Distinct tests with a call to [endpoint]; unconditionally skipped or xfail tests are listed but not counted. */
    fun reachedBy(endpoint: Endpoint): Int = callsOf(endpoint).filter { !it.test.mark.isOff }.map { it.test.id }.distinct().size

    /** How many unattributed calls might be about [endpoint]: ambiguous between it and others, or with an unknown URL. */
    fun doubtFor(endpoint: Endpoint): Int = anywhere + (doubtByEndpoint[endpoint.key] ?: 0)

    /**
     * True only when the answer is certain: the index is ready, no call reaches [endpoint], and no unattributed call could be
     * about it. Otherwise the honest text is "no test found", never "untested".
     */
    fun isUntested(endpoint: Endpoint): Boolean = phase == IndexPhase.READY && callsOf(endpoint).isEmpty() && doubtFor(endpoint) == 0

    companion object {
        val EMPTY = TestIndex(0, IndexPhase.IDLE, TestStats.EMPTY, emptyMap(), emptyList())
    }
}
