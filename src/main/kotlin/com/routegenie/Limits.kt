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

    /** Re-extracting one real-size route file from its PSI (no link, no read action overhead). */
    const val BUDGET_REEXTRACT_ONE_FILE_MS = 50L

    /** A refresh in which nothing changed over 5,000 cached files: stamp comparison and location only. */
    const val BUDGET_NOOP_REFRESH_5000_FILES_MS = 500L

    /** Re-reading one edited file and relinking. */
    const val BUDGET_REFRESH_ONE_FILE_MS = 50L

    /** Creating the tool window content. */
    const val BUDGET_WINDOW_OPEN_MS = 100L

    // --- Memory and size caps ---------------------------------------------------------------------------------------

    /**
     * Upper bound of the estimated bytes of per-file facts kept in memory; least recently used entries are dropped first.
     * A weight bound (not an entry count) so that a 100k-file repository with many small route files does not thrash.
     */
    const val MAX_FACTS_CACHE_BYTES = 64L * 1024 * 1024

    /** Estimated fixed cost of one cache entry (stamp, key, wrapper objects), whatever the facts hold. */
    const val FACT_ENTRY_BYTES = 160

    /** Estimated cost of one fact node (an import, a constant, a call, an expression node). */
    const val FACT_NODE_BYTES = 56

    /** Estimated cost of one character of text held by a fact. */
    const val FACT_CHAR_BYTES = 2

    /** Deepest chain of constants, imports and star imports the value evaluator follows; also guards against cycles. */
    const val MAX_VALUE_RESOLVE_DEPTH = 16

    /** Cached stamps compared inside one read action; the check is a few microseconds per file, extraction never happens in it. */
    const val STAMP_CHECK_BATCH_FILES = 500

    /**
     * Most threads that extract files at the same time. Low on purpose: extraction runs next to the user's work, and the
     * research (06) measured 2 threads at about 2.4x the single-thread speed.
     */
    const val EXTRACT_PARALLELISM_CAP = 2

    /** Largest Python file that is read for routes (bytes); bigger files are generated or data and are skipped. */
    const val MAX_CANDIDATE_FILE_BYTES = 1_000_000L

    /** Rounds of "extract the files that facts refer to" per discovery pass; chains of re-exports are a few levels deep. */
    const val MAX_DEPENDENCY_ROUNDS = 8

    /** The extractor's tree walk checks for cancellation once per this many nodes, so a huge file stays interruptible. */
    const val CANCEL_CHECK_EVERY_NODES = 256

    /** Dotted module names whose project file lookup is remembered between passes. */
    const val MAX_MODULE_LOOKUPS = 20_000

    /** How many nested include() levels (and list splices) the Django linker follows before it reports a cycle. */
    const val MAX_URLCONF_DEPTH = 32

    /** Linking about 5000 Django endpoints from already extracted facts (pure code, no PSI). */
    const val BUDGET_LINK_5000_ENDPOINTS_MS = 100L

    /** Characters of an unrecognized source expression quoted in a diagnostic; enough to recognize it, short enough for a row. */
    const val MAX_QUOTED_SOURCE_CHARS = 60

    /** Threads for extraction on a machine with [cores] logical processors: half of them, between one and the cap. */
    fun extractParallelism(cores: Int): Int = (cores / 2).coerceIn(1, EXTRACT_PARALLELISM_CAP)

    // --- Timing ---------------------------------------------------------------------------------------------------

    /** Quiet period after the last edit before discovery runs again; further edits inside it restart the wait. */
    const val REFRESH_DEBOUNCE_MS = 300L
}
