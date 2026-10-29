package com.routegenie

/**
 * Every number that bounds the plugin's behavior or its speed. Code reads them from here; tests assert the budgets
 * (BUDGET_*) and report the measured value next to each one. Adding a literal such as 5000 or 16 to a source file instead
 * of a constant here is a review failure.
 */
object Limits {
    // --- Speed budgets, in milliseconds. Measured by tests; see docs/CONVENTIONS.md. -------------------------------

    /** One keystroke in the endpoint filter, on a warm tree of a large project. */
    const val BUDGET_FILTER_KEYSTROKE_MS = 16L

    /** Cold discovery of about 600 Python files; runs in the background and never on the UI thread. */
    const val BUDGET_COLD_DISCOVERY_MS = 5_000L

    /** Re-reading one edited file and relinking. */
    const val BUDGET_REFRESH_ONE_FILE_MS = 50L

    /** Creating the tool window content. */
    const val BUDGET_WINDOW_OPEN_MS = 100L

    // --- Memory and size caps ---------------------------------------------------------------------------------------

    /** Upper bound of per-file fact entries kept in memory; least recently used entries are dropped first. */
    const val MAX_CACHED_FILE_FACTS = 5_000

    /** Deepest chain of constants, imports and star imports the value evaluator follows; also guards against cycles. */
    const val MAX_VALUE_RESOLVE_DEPTH = 16

    /** Files read inside one read action, so typing (a write action) can interleave between batches. */
    const val DISCOVERY_BATCH_FILES = 50

    /** How many nested include() levels (and list splices) the Django linker follows before it reports a cycle. */
    const val MAX_URLCONF_DEPTH = 32

    /** Linking about 5000 Django endpoints from already extracted facts (pure code, no PSI). */
    const val BUDGET_LINK_5000_ENDPOINTS_MS = 100L

    /** Characters of an unrecognized source expression quoted in a diagnostic; enough to recognize it, short enough for a row. */
    const val MAX_QUOTED_SOURCE_CHARS = 60

    // --- Timing ---------------------------------------------------------------------------------------------------

    /** Quiet period after the last edit before discovery runs again; further edits inside it restart the wait. */
    const val REFRESH_DEBOUNCE_MS = 300L
}
