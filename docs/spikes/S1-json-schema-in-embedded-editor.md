# S1: JSON Schema validation in an embedded request-body editor

Question: in the free PyCharm build, can an editor embedded in our tool window (not a project file) get live JSON Schema
validation, completion and error highlighting by contributing a schema through the JSON plugin's extension point?

Answer: yes at the PSI/daemon level (VERIFIED by platform tests). What no headless test can show is the on-screen
squiggle in a real tool window (UNVERIFIED, needs a manual `runIde` check, steps at the end).

Build tested: PyCharm 2026.2.3, build 262.10968.92 (`product-info.json` of the installed IDE). All statements are for
that build.

## VERIFIED

### The JSON plugin is bundled
- `plugins/json/` exists in the free build. Its `META-INF/plugin.xml` has `<id>com.intellij.modules.json</id>`, name "JSON".
  Modules: `intellij.json`, `intellij.json.backend` (holds all schema code and both extension points),
  `intellij.json.networknt.wrapper` (validator engine), others.
- The PythonCore plugin does not declare a dependency on it, so our plugin must: `<depends>com.intellij.modules.json</depends>`
  (not needed for the spike, which lives under `src/test`; the Gradle test classpath gets it through
  `bundledPlugin("com.intellij.modules.json")`, one added line in `build.gradle.kts`).
- Whether a user has disabled the bundled plugin is not checkable here; with the `<depends>` the IDE then also disables us
  (or we can use an `optional` dependency and fall back to the bundled validator).

### Extension point names and classes (reflection probe)
- EP: `JavaScript.JsonSchema.ProviderFactory`, dynamic, interface `com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory`
  with `List<JsonSchemaFileProvider> getProviders(Project)`.
  plugin.xml spelling: `<extensions defaultExtensionNs="JavaScript"><JsonSchema.ProviderFactory implementation="..."/></extensions>`.
- Provider: `com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider` (Java interface). Abstract: `getName()`,
  `isAvailable(VirtualFile)`, `getSchemaFile(): VirtualFile`, `getSchemaType()`. Defaults: `getSchemaVersion()`,
  `getPresentableName()`, `isUserVisible()`, `getRemoteSource()`.
- `com.jetbrains.jsonSchema.extension.SchemaType`: `schema | embeddedSchema | userSchema | remoteSchema`.
- `com.jetbrains.jsonSchema.impl.JsonSchemaVersion` (NOT in `extension`): `SCHEMA_4/6/7/2019_09/2020_12`.
- Service: `com.jetbrains.jsonSchema.ide.JsonSchemaService` (project service interface; `reset()`, `getSchemaObject`, ...).
  Get it with `project.getService(JsonSchemaService::class.java)`; there is no `Impl.get`.
- The squiggles come from the inspection `com.jetbrains.jsonSchema.impl.inspections.JsonSchemaComplianceInspection`
  (shortName `JsonSchemaCompliance`), enabled by default in the IDE. Test fixtures must call
  `myFixture.enableInspections(JsonSchemaComplianceInspection::class.java)`, otherwise no error appears and it looks as if
  the provider did not work (this cost one iteration of the spike).

### Pattern that works
```kotlin
class SpikeSchemaFactory(schema: VirtualFile, appliesTo: (VirtualFile) -> Boolean) : JsonSchemaProviderFactory {
    override fun getProviders(project: Project) = listOf<JsonSchemaFileProvider>(SpikeSchemaProvider(schema, appliesTo))
}
class SpikeSchemaProvider(...) : JsonSchemaFileProvider {
    override fun isAvailable(file: VirtualFile) = appliesTo(file)   // match the body editor's file
    override fun getSchemaFile() = schema                            // a LightVirtualFile with the schema text works
    override fun getSchemaType() = SchemaType.userSchema
    override fun getSchemaVersion() = JsonSchemaVersion.SCHEMA_7
}
ep.point.registerExtension(factory, parentDisposable)   // dynamic EP, unregisters on Disposer.dispose(parent)
project.getService(JsonSchemaService::class.java).reset() // make the service re-ask the providers
```
Files: `src/test/kotlin/com/routegenie/spike/schema/SpikeSchemaProvider.kt` and `JsonSchemaEmbeddedSpikeTest.kt`.

### Test results (8 tests, all pass; full suite 63 tests green)
| Case | Result |
| --- | --- |
| Real file `body.json` with provider | `Missing required property 'name'`, `Property 'extra' is not allowed` |
| `LightVirtualFile` (no disk file) opened in the fixture editor | `Incompatible types. Required: integer. Actual: string.`; basic completion offers `age`, `name` |
| Value constraint on a LightVirtualFile | `Less than the minimum 0` |
| `EditorFactory.createEditor(document, project, lvf, false)` + main passes (`CodeInsightTestFixtureImpl.instantiateAndRun`) | `Missing required property 'name'` |
| `EditorTextField(document, project, JsonFileType, ...)` after `addNotify()` + main passes | `Incompatible types...` |
| No provider registered | no problems (control) |
| Schema contributed as an in-memory `LightVirtualFile` | works, no schema file on disk needed |

Notes on behavior: the validator (networknt wrapper) does not list every violation at once; it reported 1 or 2 per document.
Do not assert exact error sets in product tests.

### Time to create an editor and attach a schema
`PerfBudget` median of 9 runs after 3 warm-ups (written to `build/reports/routegenie/perf/spike-s1-editor-create-attach-highlight.txt`):
median 49 to 50 ms, best 44 to 47 ms, for: register provider + reset service, create document and editor, run all highlighting
passes (schema parse and validation included), release editor, unregister. Budget `BUDGET_WINDOW_OPEN_MS` is 100 ms.
In the product the highlighting passes run in the background, so the perceived cost is editor creation only (a few ms).
Measured headless on a warm JVM; a cold first schema load is slower and was not measured.

### Leak test
`test editor and provider are released after disposal`: editor released with `EditorFactory.releaseEditor`, provider
unregistered by `Disposer.dispose(parent)`, `reset()` called. Then: the provider is gone from the EP list,
`LeakHunter.checkLeak` finds no `EditorImpl` for the file, and `LeakProbe.assertCollected` confirms the editor, document and
provider factory are collectable. Passed 3 of 3 runs. One earlier run failed on the plain weak-reference check before
LeakHunter was added (the editor needs a UI event flush after release); keep the LeakHunter call first in the test.

## Limits and gotchas
- The provider's `isAvailable(file)` is the only link between editor and schema, so the body editor needs a stable, distinct
  `VirtualFile` per request (a `LightVirtualFile` named e.g. by a counter or holding the request id). One shared factory can
  serve all requests if it maps file to schema (keep a bounded map and clear it on disposal; do not hold the file strongly).
- `getProviders(project)` is called by the service on `reset()`; changing a schema means mutating what `getSchemaFile()` returns
  (new LightVirtualFile content) and calling `reset()`. Calling `reset()` re-reads every provider: do it only when the schema
  changes, not per keystroke.
- The provider must be registered with a parent disposable (tool window or request session), never against the application.
- Needs the JSON language PSI: the editor must be created with `JsonFileType` (or a JSON-language `LightVirtualFile`).
- `EditorEx.virtualFile` printed `null` for the `EditorTextField` editor but highlighting still worked, because the PSI file
  is found through the document's `LightVirtualFile`. Do not rely on `editor.virtualFile`; keep our own handle.
- Schemas dialect: 2020-12 is accepted by `JsonSchemaVersion`; the spike used draft 7. A 2020-12 schema with `prefixItems`,
  `$defs` and `unevaluatedProperties` was NOT tested.

## UNVERIFIED (needs a manual `runIde` check)
1. A squiggle actually painted in an `EditorTextField` inside a real tool window. Headless tests run the highlighting passes
   explicitly; they do not prove the live daemon schedules itself for an editor that is not in a file editor tab. In test
   mode the daemon is not started for such editors.
2. Completion popup (Ctrl+Space) and quick documentation in the embedded editor. Completion was verified only in the fixture
   editor over a `LightVirtualFile`, not in an `EditorTextField`.
3. Status-bar schema widget and "Add schema mapping" notifications do not appear for the embedded editor (expected, because
   `JsonSchemaEnabler` is about project files; not checked).
4. Behavior when the user has disabled the bundled JSON plugin.
5. Cold first-use time in a real IDE.

Manual check: `runIde`, open the tool window, create the body editor as a `LightVirtualFile("body.json", JsonFileType.INSTANCE, ...)`
document in an `EditorTextField`, register the factory against the tool window's disposable, type `{"age": "x"}` and look for a
red squiggle and the hover message; press Ctrl+Space inside `{ "" }`; close the tool window and confirm nothing is left in
`Help | Diagnostic Tools | Analyze Plugin Startup Performance`/heap (or run the leak test).

## Recommendation
Use live IDE validation as the primary path for the body editor, with the bundled validator as the fallback and as the source
of "Send anyway" decisions:

1. Body editor = `EditorTextField` over a `LightVirtualFile` JSON document, schema contributed through
   `JsonSchemaProviderFactory` (code above). This gives squiggles, completion and docs for free, with no real file in the
   project. Cost: 1 `<depends>com.intellij.modules.json</depends>` (or optional dependency) and about 40 lines.
2. Keep our own validator (PLAN 3.4) as the authority for severity and the "Send anyway" gate, because IDE inspection output is
   not a stable API (the validator lists only some violations, messages are IDE text, severities are IDE settings). The IDE
   squiggles are guidance; our validator decides.
3. Real-file editor (a scratch file in `.route-genie`) is not needed. Fall back to bundled-validator-only (errors shown as a
   list under the editor) if the manual check in UNVERIFIED 1 fails or the JSON plugin is disabled.
4. Before relying on it, do the manual `runIde` check above; it is the one thing this spike could not show.
