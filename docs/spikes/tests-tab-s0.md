# Tests tab, slice S0: the spikes that were needed for S1 and S2

Only what was needed to start building. Method: numbers from `TestMapLeakAndSpeedTest` (platform test, synthetic files) and
from `TestCorpusBenchmarkTest` (real repositories, see BENCHMARKS.md). Read-only on the corpora.

## 1. How to find test files and test functions fast with what exists

- Files: `PyProviderSupport.candidatesByWords` over the words of the client table (`ClientSinks.candidateWords`, 20-odd words:
  constructors, factory methods, fixture names, `self.client` attributes, base-class names), then only files on a test path
  (`PyCandidateRules.isTestPath`, `TestConventions.isTestFile` for Django's `tests.py`, or a test source root). No parsing, no text
  prefilter was needed: the word index already says the word is in the file. Selectivity on the corpora: FastAPI 500 of 1,219 `.py`
  files (3.3 MB), Flask 26 of 83, DRF 57 of 155, Django 811 of 2,937 (11.1 MB), Sentry 1,957 of 8,422 (30.1 MB).
- Functions: no type evaluation, no SDK. The extractor records a function when it holds a client call, a client binding, a fixture
  return or is a fixture, and counts the others by name (`test*`), so "tests exist" costs one integer per file. Whether a function is
  a test (and in which style) is decided by the linker from the class bases and the file name (`TestConventions`).
- Word-index facts that mattered: `reverse`/`url_for` are not useful candidate words (a request needs a client word in the file anyway);
  `TestCase` is a word of Django's base classes and so also selects plain `unittest` files, which is harmless.

## 2. Where a new tab plugs in

`ResponsePanel` (the response area of the Request page) holds a `JBTabbedPane` with Body, Events, Headers, Request sent, cURL, History.
The Tests tab is added after History through an optional factory (`testsTab`), so a `ResponsePanel` without it (the quick-test page
reuses one) has no Tests tab and cannot recurse. The panel itself (`TestsPanel`) knows nothing about its host: it takes the endpoint
through `show(endpoint)`, the "on screen" signal through `setShown`, and reports its count through a callback. Moving it to a page of
the details area next to Overview and Request (plan Q1) is `details.addSection(...)` around the same component, about half a day.

## 3. Measured throughput (SP1)

- Parsing alone (platform, one thread): 0.77 to 0.82 s per MB of test source in the test JVM. Walking a parsed 34 KB file with
  `PyTestFactsExtractor`: median 14 to 23 ms (about 0.4 to 0.7 s per MB). Together about 56 to 110 ms for a 34 KB file with the tree built.
- Whole pipeline on real repositories, cold, 4 workers (final run, BENCHMARKS.md has the table): FastAPI 3.29 MB in 1.0 s (311 ms per
  MB), Django 11.13 MB in 1.9 s (167), Sentry 30.11 MB in 4.6 s (151), Superset 9.38 MB in 1.3 s (143), Flask 0.19 MB in 86 ms (444).
  Small synthetic projects in a cold test JVM are slower (0.9 to 1.7 s per MB for 0.4 to 0.6 MB) because of a fixed cost; the budget
  constants are `BUDGET_TEST_COLD_INDEX_MS_PER_MB` (650) plus `BUDGET_TEST_COLD_INDEX_FIXED_MS` (700). The plan's estimate of 1.5 to
  2 MB/s was too pessimistic for large repositories (3.2 to 7 MB/s on 4 workers) and optimistic for tiny ones.
- Memory: the facts of 2,025 Sentry test files weigh 13.4 MB (bound 48 MB).

## 4. Running a test through the IDE (SP2), as far as it can be known without a window

`ConfigurationContext.createEmptyContextForLocation(PsiLocation(element)).configuration` and
`ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())` exist in the target build and compile
against the free edition. In a light headless test project (no interpreter) the producers do return a configuration for a pytest function
(`TestLauncherTest` printed `test_launch`, the name of the test file), so asking for one works headless. Whether the Run window shows a
real pytest or unittest run, and what happens without an interpreter, needs a real project and is item T13 of the manual checklist;
nothing was started by the tests. The launcher has the fallback "Copy Command" (`pytest path::Class::test`; for a Django test case
`python manage.py test app.tests.Class.test`).

## 5. Not done in S0 (not needed for S1 and S2)

- SP3 (`Endpoint.root` in the three linkers): only needed for app binding, which stays out. Its effect is visible in the benchmark: 2,016
  of the 2,796 FastAPI test calls are ambiguous because the repository has 150 endpoints named `/`.
- SP4 (`TestStateStorage` key format, `SMTRunnerEventsListener` from a plugin that depends only on `com.intellij.modules.python`): the last
  pass/fail icon per test is not shown. Nothing was verified about the key format.
- SP5 (where `PathTemplate` should live): not needed. The linker has its own segment matcher (`EndpointMatcher`) on top of
  `PathConverters`; `QuickTestForm` reads path values with its own small template reader. `PathTemplate` stays in `ui/request`.
