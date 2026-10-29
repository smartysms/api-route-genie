# Conventions

These rules apply to every change. Most of them are enforced by tests, so a violation fails the build instead of waiting
for a review.

## 1. One place per fact (centralized coding)

| Fact | The one place | Referenced as |
| --- | --- | --- |
| Product name, short name, per-project folder, repo | `gradle.properties` | `Branding.NAME`, `Branding.SHORT`, `Branding.DIR`; `messages/Branding.properties` for `plugin.xml` |
| Tool window, action, notification group and extension point ids | `Ids.kt` | `Ids.TOOL_WINDOW` and so on; `plugin.xml` repeats them and `IdsConsistencyTest` compares both |
| Every user-visible string except product names | `messages/RouteGenieBundle.properties` | `RouteGenieBundle.message("key")`; product names are passed as `{0}` arguments |
| Every number that bounds behavior or speed (timeouts, caps, budgets, debounce) | `Limits.kt` | `Limits.NAME` |
| A framework's display name | `Framework` enum | `framework.displayName` |
| The text for a "could not determine" situation | `DiagnosticCode` enum plus one bundle key each | `code.bundleKey` |
| Path notation for unknown parts (`{?}`) | `PathSyntax` | `PathSyntax.UNKNOWN_SEGMENT` |

Rules:

- Never type an id, a product name or a UI string twice. Never write a literal such as `5000` or `16` where a limit
  belongs; add a named constant to `Limits` with a comment saying what it bounds.
- Renaming the product means editing `gradle.properties` and running `./gradlew syncDocs`. Nothing else.
- Adding a framework: one new `Framework` entry, one provider registered through the `routeProvider` extension point, the
  decorator and function names of that framework kept in one catalog object inside its package.

Enforced by: `BrandingTest`, `IdsConsistencyTest`, `CentralizationTest`.

## 1a. Correct or absent

An endpoint is listed only when its HTTP method and complete path are exactly determined by the code. There is no fallback
and no guessing: a route with a computed prefix, an unresolved include, a dynamic path or a regex that does not convert
exactly is omitted, and the omission is recorded as a `Diagnostic` (never as an `Endpoint`) so coverage can be measured.
The same applies to request schemas: an unknown schema means nothing is validated, never an invented one.

Route recognition is data, not code: the primitives a framework registers routes through live in one table per framework and
are consumed by generic extraction code; do not add per-decorator branches.

## 2. Layering

```
model, client, openapi    pure Kotlin: no com.intellij, com.jetbrains, java.awt or javax.swing imports
discovery, frameworks.*   PSI code; behind RouteProvider; produce immutable, PSI-free facts and snapshots
schema                    extractors, JSON Schema model, validator
ui, settings              Swing, actions, tool window
```

Snapshots and facts never hold a PSI element, a VirtualFile or a Project. Navigation resolves a file path and offset on
demand. Enforced by `LayeringTest` for the pure layers.

## 3. Memory: no leaks, checked at every stage

- Every `Disposable` is registered with a parent (`toolWindow.disposable`, the project service, or an explicit parent).
  Nothing is registered against the application unless it must live that long.
- No listener, `Alarm`, `Timer` or coroutine outlives its owner. Scopes are cancelled by `Disposable`.
- Caches are bounded (`Limits.MAX_*`, least recently used eviction) and keyed by value, not by PSI.
- Each phase adds automated tests: weak-reference collectability for pure objects (`LeakProbe.assertCollected`), heap
  growth over repeated runs (`LeakProbe.assertNoHeapGrowth`), and for platform objects the IDE's own `LeakHunter` and
  `Disposer` assertions after closing the tool window or the project.

## 4. Speed: measured, not guessed

Budgets live in `Limits.BUDGET_*` and are asserted by `PerfBudget.assertWithin`, which compares the median of several
runs after warm-up and writes the measured numbers to `build/reports/routegenie/perf/`. Each phase quotes the measured
value next to the budget.

| Budget | Value |
| --- | --- |
| One filter keystroke on a warm tree | 16 ms |
| Cold discovery of about 600 files (background) | 5 s |
| Re-reading one edited file and relinking | 50 ms |
| Creating the tool window content | 100 ms |

Also: no PSI or index work on the UI thread, `ActionUpdateThread.BGT` for actions that look at the project, no work while
the window is hidden and nothing changed, results published as immutable snapshots through a `StateFlow`.

## 5. Tests and building

```
JAVA_HOME=<PyCharm>/jbr  ./gradlew test --rerun        # never run two Gradle builds in the same project at once
```

Pure code gets plain JUnit tests; PSI code gets platform tests with fixtures. A test that cannot fail proves nothing:
helpers such as `PerfBudget` and `LeakProbe` have tests that make them fail on purpose (`HarnessTest`).
