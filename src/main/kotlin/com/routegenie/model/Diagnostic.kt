package com.routegenie.model

enum class Severity { INFO, WARNING, ERROR }

/**
 * Why something could not be determined or looks wrong. The text shown to the user lives in RouteGenieBundle under
 * [bundleKey]; adding a code means adding its entry there (BundleConsistencyTest checks it).
 */
enum class DiagnosticCode {
    DYNAMIC_VALUE,
    DYNAMIC_PREFIX,
    UNRESOLVED_INCLUDE,
    UNRESOLVED_VIEW,
    UNSUPPORTED_REGEX,
    GUESSED_ROOT,
    UNRESOLVED_RECEIVER,
    UNATTACHED_ROUTER,
    INCLUDE_CYCLE,
    INTERNAL_ERROR,
    SCHEMA_FIELD_SKIPPED,
    SCHEMA_NOT_DETERMINED,
    MOUNTED_APP,
    UNSUPPORTED_PATTERN,

    // Why a client call in a test is not attributed to an endpoint (see testmap.TestLinker).
    TEST_URL_DYNAMIC,
    TEST_URL_RELATIVE,
    TEST_CONSTANT_UNRESOLVED,
    TEST_NAME_UNRESOLVED,
    TEST_NO_ENDPOINT,
    TEST_AMBIGUOUS,
    TEST_EXTERNAL_URL,
    TEST_HELPER_OPAQUE,
    TEST_STYLE_UNSUPPORTED,
    TEST_FACTS_FAILED;

    val bundleKey: String get() = "diagnostic.${name.lowercase()}"
}

/** A finding about the analysis, optionally tied to a place in the source. [args] fill the placeholders of the text. */
data class Diagnostic(
    val severity: Severity,
    val code: DiagnosticCode,
    val args: List<String> = emptyList(),
    val source: SourceRef? = null,
)
