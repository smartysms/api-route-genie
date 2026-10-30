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

    /**
     * One step of the request workspace on the UI thread: choosing an endpoint (form binding), showing a finished response,
     * starting a comparison. Parsing and indexing run in the background, so each of these only swaps what is on screen.
     */
    const val BUDGET_UI_STEP_MS = 50L

    /** Sorted index plus grouped tree for 50,000 endpoints, off the UI thread (research 06 Q5: sort 40-90 ms, group 40-65 ms). */
    const val BUDGET_MODEL_BUILD_50K_MS = 500L

    /** Row height of the endpoint tree in unscaled pixels; fixed so the tree never measures rows (large-model mode). */
    const val TREE_ROW_HEIGHT = 22

    /** Tag chips drawn after an endpoint row; more tags are summarized as +N so one row never grows without bound. */
    const val TREE_ROW_MAX_TAG_CHIPS = 3

    /** A tree with at most this many endpoints opens fully expanded; bigger ones start collapsed. */
    const val AUTO_EXPAND_MAX_ENDPOINTS = 500

    /** Most rows "expand all" and expansion restore will open, so a 50,000-row tree cannot be expanded by accident. */
    const val MAX_EXPANDED_ROWS = 4_000

    /** Initial share of the window height taken by the list when the user has not dragged the divider yet. */
    const val DETAILS_SPLIT_PROPORTION = 0.62f

    /** How long "updating" is shown in the status line after the window opens on a stale result. */
    const val UPDATING_HINT_MS = 3_000L

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
     * Most threads that extract files at the same time. Extraction runs off the UI thread and the research (06) measured
     * near-linear scaling up to 4 threads; a machine with fewer cores gets half of them (see [extractParallelism]).
     */
    const val EXTRACT_PARALLELISM_CAP = 4

    /** Largest Python file that is read for routes (bytes); bigger files are generated or data and are skipped. */
    const val MAX_CANDIDATE_FILE_BYTES = 1_000_000L

    /** Rounds of "extract the files that facts refer to" per discovery pass; chains of re-exports are a few levels deep. */
    const val MAX_DEPENDENCY_ROUNDS = 8

    /** The extractor's tree walk checks for cancellation once per this many nodes, so a huge file stays interruptible. */
    const val CANCEL_CHECK_EVERY_NODES = 256

    /** Dotted module names whose project file lookup is remembered between passes. */
    const val MAX_MODULE_LOOKUPS = 20_000

    /** Python files the module-name index holds; a bigger project falls back to scanning the platform's file name index. */
    const val MAX_MODULE_INDEX_FILES = 250_000

    /** File layout events kept for the module-name index between two lookups; more than this means a rebuild is cheaper. */
    const val MAX_PENDING_LAYOUT_EVENTS = 5_000

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

    // --- Gutter icons -------------------------------------------------------------------------------------------------

    /** Endpoints offered in the chooser when several are declared on one line; the rest is not listed. */
    const val GUTTER_MAX_CHOICES = 20

    // --- HTTP client (client, clientide) ---------------------------------------------------------------------------

    /** Time allowed to open the TCP (and TLS) connection; the IDE's own default for idea.connection.timeout. */
    const val HTTP_CONNECT_TIMEOUT_MS = 10_000L

    /** Time allowed until the response headers arrive (the JDK request timeout; it does not bound the body). */
    const val HTTP_HEADERS_TIMEOUT_MS = 60_000L

    /** Total deadline for one send including redirects and the body download; 0 in a request means no deadline. */
    const val HTTP_TOTAL_TIMEOUT_MS = 120_000L

    /** Redirects followed by one send; the IDE's own default for idea.redirect.limit. */
    const val HTTP_MAX_REDIRECTS = 10

    /** Response bytes kept in memory for display; the rest is counted and dropped. */
    const val HTTP_DISPLAY_CAP_BYTES = 5 * 1024 * 1024

    /** Largest body written to the spill file (decompressed bytes); beyond it the download stops and is flagged. Also the zip-bomb limit. */
    const val HTTP_SPILL_MAX_BYTES = 512L * 1024 * 1024

    /** Spill files older than this are deleted by the startup sweep (leftovers of a crashed or killed IDE). */
    const val SPILL_STALE_AGE_MS = 60L * 60 * 1000

    /** Read buffer for streamed bodies. */
    const val HTTP_READ_CHUNK_BYTES = 64 * 1024

    /** Alive JDK clients (one per TLS mode and HTTP version); each owns a selector thread and a connection pool. */
    const val HTTP_MAX_CLIENTS = 4

    /** Threads that run sends at the same time, per engine. */
    const val HTTP_MAX_CONCURRENT_SENDS = 4

    /** Longest idle time of a worker thread before it exits. */
    const val HTTP_WORKER_IDLE_SECONDS = 10L

    /** Longest wait for threads to end when an engine is closed. */
    const val HTTP_CLOSE_WAIT_MS = 2_000L

    /** Length of the random multipart boundary. */
    const val HTTP_BOUNDARY_CHARS = 24

    /** Longest accepted URL (characters), a guard against pasted garbage. */
    const val HTTP_MAX_URL_CHARS = 8_192

    // --- Streaming responses -------------------------------------------------------------------------------------

    /** A stream with no bytes for this long ends with a timeout error (streams use this instead of the total deadline). */
    const val STREAM_IDLE_TIMEOUT_MS = 60_000L

    /** Events are handed to the consumer in batches collected over this window, so a fast stream cannot flood the UI thread. */
    const val STREAM_BATCH_WINDOW_MS = 50L

    /** A batch is handed over early when it holds this many events. */
    const val STREAM_BATCH_MAX_EVENTS = 500

    /** A batch is also handed over early when its payload reaches this many bytes. */
    const val STREAM_BATCH_MAX_BYTES = 1024 * 1024

    /** Largest SSE event (data, event name and id together, in bytes) that is delivered; a bigger one is dropped and counted. */
    const val SSE_MAX_EVENT_BYTES = 1024 * 1024

    /** Longest line of an NDJSON/line stream; the rest of a longer line is cut. */
    const val STREAM_MAX_LINE_BYTES = 1024 * 1024

    /** Size cap and item cap of the helper that keeps what a stream consumer shows; the oldest items are dropped first. */
    const val STREAM_RING_MAX_BYTES = 8L * 1024 * 1024
    const val STREAM_RING_MAX_ITEMS = 10_000

    /** Requests kept in the history, oldest dropped first. */
    const val HISTORY_MAX_ENTRIES = 200

    /**
     * History entries (newest first) that still hold their JSON response body for "Compare with ...", the largest body kept in
     * memory per entry, and how many of the kept bodies may live in a spill file on disk. Older entries show "body not kept".
     */
    const val HISTORY_BODIES_KEPT = 5
    const val HISTORY_BODY_KEPT_MAX_BYTES = 2 * 1024 * 1024
    const val HISTORY_SPILLED_BODIES_KEPT = 2

    /** Endpoints whose last method and inputs are remembered, least recently used dropped first. */
    const val LAST_INPUT_MAX_ENDPOINTS = 500

    /** Most name/value inputs remembered per endpoint, and most characters of one remembered value. */
    const val LAST_INPUT_MAX_FIELDS = 40
    const val STORED_VALUE_MAX_CHARS = 2_000

    /** Hosts the user confirmed once; oldest dropped first. */
    const val CONFIRMED_HOSTS_MAX = 200

    /** Environments, and variables per environment, kept in the project. */
    const val ENVIRONMENTS_MAX = 50
    const val ENVIRONMENT_VARIABLES_MAX = 200

    /** Preparing one request (variables, path, query, auth) in pure code. */
    const val BUDGET_PREPARE_REQUEST_MS = 1L

    /** Engine cost on top of a plain JDK call for a small loopback GET (median of a warm run). */
    const val BUDGET_ENGINE_OVERHEAD_MS = 15L

    // --- cURL and the request/response panel (agent G3) --------------------------------------------------------------

    /** cmd.exe refuses command lines longer than this (Microsoft KB 830473). */
    const val CURL_CMD_MAX_LINE_CHARS = 8_191

    /** A body longer than this goes to a side file in cmd, so the command stays well below the line limit. */
    const val CURL_CMD_INLINE_BODY_MAX_CHARS = 6_000

    /** Response text up to this size is pretty-printed and highlighted; above it the viewer shows plain text. */
    const val VIEWER_PRETTY_MAX_CHARS = 1_000_000

    /** The viewer never holds more than this many characters of a body; the rest is announced in a notice. */
    const val VIEWER_DISPLAY_CAP_CHARS = 5_000_000

    /** A line longer than this makes the editor slow; such text is soft-wrapped. */
    const val VIEWER_LONG_LINE_CHARS = 100_000

    /** Live view: characters appended to the viewer in one UI update at most, and the quiet time between two updates. */
    const val LIVE_APPEND_MAX_CHARS = 64 * 1024
    const val LIVE_FLUSH_INTERVAL_MS = 100L

    /** Text waiting for the UI may reach this many append budgets before the oldest is dropped (and counted). */
    const val LIVE_PENDING_FACTOR = 8

    /** Deepest JSON nesting the plain viewer re-indents; deeper documents are shown as they are. */
    const val VIEWER_JSON_MAX_DEPTH = 512

    /** Characters of a request body shown in the Request tab. */
    const val SENT_BODY_PREVIEW_CHARS = 20_000

    /** Interval of the elapsed-time refresh while a request runs. */
    const val LIVE_TICK_MS = 500L

    /** Characters of one SSE data cell in the events table; the full text is in the detail line. */
    const val LIVE_CELL_MAX_CHARS = 200

    /** Rows kept by the events view of a stream (the ring buffer of the engine bounds the bytes). */
    const val LIVE_EVENT_ROWS_MAX = 2_000

    /** Narrowest window width (unscaled px) at which request and response sit side by side instead of stacked. */
    const val WORKSPACE_SIDE_BY_SIDE_MIN_WIDTH = 900

    /** Share of the workspace taken by the request area. */
    const val WORKSPACE_REQUEST_PROPORTION = 0.45f

    // --- JSON viewer and diff (agent J, package com.routegenie.json and ui/json) --------------------------------------

    /** Deepest nesting the scanner accepts; deeper input is an error, so the stacks cannot grow without bound. */
    const val JSON_MAX_DEPTH = 10_000

    /** Most nodes the index keeps (23 bytes each, so at most about 184 MB); a bigger document stays usable up to this point. */
    const val JSON_MAX_NODES = 8_000_000

    /** Read window of the scanner when the document comes from a file. It grows only for one huge token. */
    const val JSON_SCAN_WINDOW_BYTES = 1 shl 20

    /** Longest single token (a string) the scanner holds in memory when reading from a file. */
    const val JSON_MAX_TOKEN_BYTES = 64 shl 20

    /** Block size and block count of the read cache of a file-backed document (random access for the tree and search). */
    const val JSON_FILE_BLOCK_BYTES = 64 * 1024
    const val JSON_FILE_CACHE_BLOCKS = 16

    /** Largest UTF-16/UTF-32 document that is converted to UTF-8 in memory before scanning. */
    const val JSON_TRANSCODE_MAX_BYTES = 64 shl 20

    /** The scanner publishes its partial index after this many nodes first, then every [JSON_PROGRESS_EVERY_NODES]. */
    const val JSON_PROGRESS_FIRST_NODES = 512
    const val JSON_PROGRESS_EVERY_NODES = 16_384

    /** Largest document (bytes) the diff accepts per side; bigger ones are refused with a message, never half compared. */
    const val JSON_DIFF_MAX_BYTES = 64L shl 20

    /** Smallest chunk of the index (nodes, as a power of two) and the largest; small documents get small chunks. */
    const val JSON_INDEX_MIN_CHUNK_SHIFT = 8
    const val JSON_INDEX_MAX_CHUNK_SHIFT = 16

    /** Objects with more members than this keep their original order when "sort keys" is on. */
    const val JSON_SORT_MAX_CHILDREN = 100_000

    /** Default indent width of the pretty printer. */
    const val JSON_INDENT_DEFAULT = 2

    /** Pretty or raw text up to this many characters is shown whole in the editor; bigger text is shown a page at a time. */
    const val JSON_TEXT_FULL_MAX_CHARS = 2_000_000

    /** Size of one text page, in output units (about one character each). */
    const val JSON_PAGE_UNITS = 1_000_000L

    /** Above this many bytes the text views (pretty, raw) show a notice instead of pages; tree, table and search still work. */
    const val JSON_TEXT_HARD_CAP_BYTES = 128L shl 20

    /** Fold regions created per text page (the editor slows down with tens of thousands). */
    const val JSON_MAX_FOLD_REGIONS = 20_000

    /** Children shown directly under one tree node; more are grouped into ranges such as [0 ... 99]. */
    const val JSON_TREE_BUCKET = 100

    /** Row height (unscaled px) of the JSON tree and table; fixed so the tree never measures rows. */
    const val JSON_ROW_HEIGHT = 22

    /** Child index arrays kept for expanded tree nodes (total ints, 4 bytes each), least recently used dropped first. */
    const val JSON_TREE_CHILD_CACHE_INTS = 2_000_000

    /** Longest string preview in a tree row or table cell (characters); the full value is available through copy. */
    const val JSON_STRING_PREVIEW_CHARS = 160

    /** Levels opened by "expand to level" at most, and rows it may open (see [MAX_EXPANDED_ROWS]). */
    const val JSON_EXPAND_MAX_LEVEL = 8

    /** Search: results kept, time allowed, quiet time after typing before it starts, nodes between cancel checks. */
    const val JSON_SEARCH_MAX_RESULTS = 10_000
    const val JSON_SEARCH_BUDGET_MS = 5_000L
    const val JSON_SEARCH_DEBOUNCE_MS = 250L
    const val JSON_SEARCH_CANCEL_CHECK_NODES = 4_096

    /** Table: rows sampled to discover the columns, most columns shown, most rows that can be sorted, cell preview length. */
    const val JSON_TABLE_SAMPLE_ROWS = 2_000
    const val JSON_TABLE_MAX_COLUMNS = 100
    const val JSON_TABLE_SORT_MAX_ROWS = 200_000
    const val JSON_TABLE_CELL_PREVIEW_CHARS = 120

    /** Levels and nodes searched below the root for the array that becomes the table. */
    const val JSON_TABLE_DETECT_DEPTH = 3
    const val JSON_TABLE_DETECT_NODES = 5_000

    /** Diff: differences kept (more are counted), rows rendered, nesting compared structurally, unchanged neighbours shown. */
    const val JSON_DIFF_MAX_ENTRIES = 5_000
    const val JSON_DIFF_MAX_ROWS = 20_000
    const val JSON_DIFF_MAX_DEPTH = 200
    const val JSON_DIFF_CONTEXT_SIBLINGS = 2

    /** Lines shown for a container that exists on one side only (added or removed). */
    const val JSON_DIFF_BLOCK_LINES = 40

    /** Longest text of one diff row (characters); longer values are cut with an ellipsis. */
    const val JSON_DIFF_ROW_PREVIEW_CHARS = 200

    /** Row texts and node labels remembered by the tree renderer, least recently used dropped first. */
    const val JSON_DISPLAY_CACHE_ENTRIES = 4_000

    /** Bytes the index may use per node; asserted by the memory test (the layout is 23). */
    const val JSON_MAX_BYTES_PER_NODE = 24

    /** Speed budgets (milliseconds) of the JSON engine, measured on the machine that runs the tests; see the perf reports. */
    const val BUDGET_JSON_INDEX_10MB_MS = 250L
    const val BUDGET_JSON_SEARCH_10MB_MS = 250L
    const val BUDGET_JSON_DIFF_1MB_MS = 250L
    const val BUDGET_JSON_PRETTY_10MB_MS = 400L
    const val BUDGET_JSON_TREE_FIRST_PAINT_50MB_MS = 200L

    // --- Request schemas and body templates (schema) -------------------------------------------------------------------

    /** Times one `$ref` may be entered on a single path of a sample before the branch is cut (recursive models). */
    const val SAMPLE_MAX_REF_DEPTH = 3

    /** Deepest nesting of a generated sample, a guard for schemas that nest without `$ref`. */
    const val SAMPLE_MAX_DEPTH = 24

    /** Schema nodes visited by one sample generation; bounds a pathological schema. */
    const val SAMPLE_MAX_NODES = 50_000

    /** Longest string a `minLength` can make the generator build. */
    const val SAMPLE_MAX_STRING_CHARS = 10_000

    /** Variants tried to make the items of a `uniqueItems` array distinct before giving up. */
    const val SAMPLE_UNIQUE_ATTEMPTS = 16

    /** Longest regular expression the pattern sampler reads, and the longest string it builds from one. */
    const val SAMPLE_PATTERN_MAX_CHARS = 400
    const val SAMPLE_PATTERN_MAX_OUTPUT_CHARS = 2_000

    /** Schemas (and "no schema" answers) kept per project, least recently used dropped first. */
    const val SCHEMA_CACHE_MAX_ENTRIES = 256

    /** One extraction gives up (and the endpoint has no template) after this long; reads are cancellable meanwhile. */
    const val SCHEMA_EXTRACT_BUDGET_MS = 3_000L

    /** Classes read by one extraction, a bound for models that pull in a whole code base. */
    const val SCHEMA_MAX_CLASSES_PER_CALL = 300

    /** Schema nodes one extraction may create. */
    const val SCHEMA_MAX_NODES = 20_000

    /** Hops through imports, aliases and base classes when resolving a name; also guards against cycles. */
    const val SCHEMA_RESOLVE_DEPTH = 12

    /** Depth of nested structures one extraction reads: literals, if-chains, dependencies, primary-key chains, Meta bases. */
    const val SCHEMA_MAX_NESTING = 6

    /** Longest forward reference written as a string annotation (`"Node"`) that is parsed. */
    const val SCHEMA_MAX_FORWARD_REF_CHARS = 200

    /** Largest repeat count `{n}` the pattern sampler accepts. */
    const val SAMPLE_PATTERN_MAX_REPEAT = 1_000

    /** Atoms one pattern parse may read; nested repeats of empty groups (`((a{0}){1000}){1000}`) would otherwise run for hours. */
    const val SAMPLE_PATTERN_MAX_STEPS = 20_000

    /** Longest body template (characters) offered to the editor; a bigger sample is not generated. */
    const val BODY_TEMPLATE_MAX_CHARS = 1_000_000

    /** Cold extraction of a typical endpoint whose model nests across three files, and the same when cached. */
    const val BUDGET_SCHEMA_EXTRACT_COLD_MS = 200L
    const val BUDGET_SCHEMA_EXTRACT_WARM_MS = 5L

    /** The pure sample generator for a schema of 500 fields. */
    const val BUDGET_SAMPLE_500_FIELDS_MS = 20L

    /** Error report: longest address opened in the browser (GitHub refuses about 8000), error events included, and title length. */
    const val REPORT_MAX_URL_CHARS = 7_000
    const val REPORT_MAX_EVENTS = 3
    const val REPORT_TITLE_MAX_CHARS = 90

    /** Error report: the stack trace is cut to this many characters at most, and never below the floor when shrinking to fit. */
    const val REPORT_MAX_TRACE_CHARS = 4_000
    const val REPORT_MIN_TRACE_CHARS = 400

    /** Random body: chance that an optional property is written, and chance that a nullable value is null. */
    const val RANDOM_OPTIONAL_CHANCE = 0.8
    const val RANDOM_NULL_CHANCE = 0.1

    /** Random body: length of a free string (characters) when the schema sets no bounds. */
    const val RANDOM_STRING_MIN_CHARS = 3
    const val RANDOM_STRING_MAX_CHARS = 12

    /** Random body: a number with no upper or lower bound is drawn within this distance of zero or of the one bound it has. */
    const val RANDOM_NUMBER_SPAN = 1_000L

    /** Random body: widest range a bounded number is drawn from; a wider range is cut to this width above its lower bound. */
    const val RANDOM_NUMBER_MAX_WIDTH = 1_000_000L

    /** Random body: items beyond the minimum an array may get, and the most entries of a map with unnamed keys. */
    const val RANDOM_ARRAY_EXTRA_ITEMS = 2
    const val RANDOM_MAP_MAX_ENTRIES = 3

    /** Random body: decimals tried, in turn, for a fractional number without `multipleOf`; and tries to draw a string that fits a pattern. */
    const val RANDOM_MAX_DECIMALS = 6

    /** Random body: finest decimals tried when the bounds of a number are themselves finer than [RANDOM_MAX_DECIMALS]. */
    const val RANDOM_MAX_DECIMALS_CAP = 30

    /** Random body: tries to draw a value of a known string format that fits a length limit (a URL under 30 characters needs several). */
    const val RANDOM_FORMAT_ATTEMPTS = 32
    const val RANDOM_PATTERN_ATTEMPTS = 24

    /** Random body: draws tried to find an item that is not in a `uniqueItems` array yet (a small enum needs many). */
    const val RANDOM_UNIQUE_ATTEMPTS = 64

    /** Random body: extra repeats an open-ended pattern quantifier (`*`, `+`, `{n,}`) may get. */
    const val RANDOM_PATTERN_EXTRA_REPEAT = 6

    /** The random generator on a schema of 500 fields. */
    const val BUDGET_RANDOM_SAMPLE_500_FIELDS_MS = 40L

    // --- Generated images and files (package files) -----------------------------------------------------------------

    /** Random image of 256x256 as PNG, and one full-HD JPEG, both warm and in memory. */
    const val BUDGET_IMAGE_PNG_256_MS = 15L
    const val BUDGET_IMAGE_JPEG_FULL_HD_MS = 120L

    /** Hard caps of a generated image: longest side, total pixels (the pixel buffer is 4 bytes each) and any generated file. */
    const val IMAGE_MAX_DIMENSION = 8_192
    const val IMAGE_MAX_PIXELS = 16_777_216L
    const val FILE_MAX_BYTES = 20L * 1024 * 1024

    /** Size used when the schema gives none. */
    const val IMAGE_DEFAULT_WIDTH = 256
    const val IMAGE_DEFAULT_HEIGHT = 256

    /** JPEG qualities tried in order; the first gives the normal look, the others only make a small target size reachable. */
    val IMAGE_JPEG_QUALITIES: List<Float> = listOf(0.8f, 0.5f, 0.25f, 0.05f)

    /** Random shapes drawn on top of the gradient (inclusive range). */
    const val IMAGE_SHAPES_MIN = 3
    const val IMAGE_SHAPES_MAX = 7

    /**
     * Encoded size of the generated gradient-plus-shapes picture, as bytes = fixed + pixels * milliPerPixel / 1000 +
     * sqrt(pixels) * perRoot. Measured over 12 seeds from 64x64 to 1920x1080 and set above the largest seen. They only drive
     * the "about N KB" estimate and the shrinking in fit(); the real encoders decide the actual size.
     */
    const val IMAGE_PNG_MILLI_BYTES_PER_PIXEL = 90
    const val IMAGE_PNG_BYTES_PER_ROOT_PIXEL = 65
    const val IMAGE_JPEG_MILLI_BYTES_PER_PIXEL = 38
    const val IMAGE_JPEG_BYTES_PER_ROOT_PIXEL = 22
    const val IMAGE_GIF_MILLI_BYTES_PER_PIXEL = 140
    const val IMAGE_GIF_BYTES_PER_ROOT_PIXEL = 32

    /** Generated non-image files: default sizes when the caller gives none, in bytes (text) or items. */
    const val FILE_DEFAULT_OCTET_BYTES = 256L
    const val FILE_TEXT_WORDS_MIN = 8
    const val FILE_TEXT_WORDS_MAX = 40
    const val FILE_CSV_ROWS_MIN = 3
    const val FILE_CSV_ROWS_MAX = 12
    const val FILE_ZIP_ENTRY_WORDS = 20

    /** Fixed local modification time written into generated zip entries so the same seed gives the same bytes anywhere. */
    const val FILE_ZIP_ENTRY_DATE = "2020-01-01T00:00:00"

    /** Multipart template: most file parts one body may have (a file list with a huge `minItems` is cut, not expanded), and the extra files a random body may add beyond the minimum. */
    const val MULTIPART_MAX_PARTS = 64
    const val MULTIPART_RANDOM_EXTRA_FILES = 2

    /** Multipart template for a schema of 500 fields (fixed and random), and extraction of a multipart endpoint, cold and warm. */
    const val BUDGET_MULTIPART_RENDER_500_FIELDS_MS = 40L
    const val BUDGET_MULTIPART_EXTRACT_COLD_MS = 200L
    const val BUDGET_MULTIPART_EXTRACT_WARM_MS = 5L

    /** Generated upload files: how long a stored file is kept, how many are kept, and the length of the made-up file names. */
    const val GENERATED_FILES_KEEP_DAYS = 7
    const val GENERATED_FILES_MAX = 200
    const val FILE_NAME_MIN_CHARS = 5
    const val FILE_NAME_MAX_CHARS = 9

    // --- Pins: user-confirmed values for URL prefixes that the code takes from a setting ---------------------------------------

    /** Settings (unresolved or pinned) shown and kept per snapshot; the rest is cut, most routes first. */
    const val MAX_UNRESOLVED_SETTINGS = 50

    /** Facts about settings holders read from one file: classes, attributes per class, and `settings = Settings()` lines. */
    const val MAX_SETTINGS_CLASSES_PER_FILE = 16
    const val MAX_SETTINGS_ATTRS_PER_CLASS = 200
    const val MAX_SETTINGS_INSTANCES_PER_FILE = 16

    /** The pins file: version written today, largest accepted size, most pins kept, longest expression and value, longest note. */
    const val PINS_FILE_VERSION = 1
    const val PINS_FILE_MAX_BYTES = 256 * 1024
    const val MAX_PINS = 500
    const val PIN_MAX_EXPRESSION_CHARS = 200
    const val PIN_MAX_VALUE_CHARS = 200
    const val PIN_MAX_NOTE_CHARS = 300

    /** Strips shown at once above the endpoint list (one per unresolved setting, most routes first); the rest sit in the hidden-routes line. */
    const val PINS_MAX_STRIPS = 3

    /** Settings listed in the hidden-routes line before it says "and N more". */
    const val PINS_MAX_HIDDEN_LINES = 10

    /** Unresolved settings the IDE remembers as "not now" per project (least recently added dropped first). */
    const val PINS_MAX_DISMISSED = 200

    /** Reading and validating a pins file of 500 entries, and linking (applying a changed pin, or noticing settings) a project of 300 route files with 900 routes. */
    const val BUDGET_PINS_PARSE_MS = 20L
    const val BUDGET_PINS_APPLY_MS = 100L
}
