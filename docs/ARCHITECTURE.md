# Architecture of route discovery

Short design note. The rules behind it are in `CONVENTIONS.md`; the research is in `research/06-performance-large-codebases.md`.

## Pipeline

```
 candidates        facts (context-free)          dependencies             link (pure)            publish
 ----------        --------------------          ------------             -----------            -------
 word index   ->   one read action per file  ->  ModuleWalk asks which ->  resolve constants,  ->  immutable
 decorator         on <= 2 threads, cached by    modules are missing,     imports, module         RouteSnapshot
 stub index        (provider, file id, stamp),   engine extracts them,    names, prefixes;        (StateFlow);
 + exclusions      bounded by estimated bytes    repeat until none        expand routers          skipped when the
 + text prefilter                                                                                 link input is equal
```

Packages: `discovery` (provider contract, `ProjectPaths`), `discovery.core` (engine, cache, and the pure expression and
module code), `frameworks.common` and `frameworks.<name>` (extractors on PSI; catalogs, resolver and linkers without IDE
imports). `LayeringTest` lists the pure linker files and fails when one imports `com.intellij`.

## Facts are context-free

A fact object (`PyFileFacts`, `DjangoFacts`) is a function of the text of one file. It holds names, positions, the import
table as written (relative imports keep their dot count) and unevaluated `Expr` trees (`Lit`, `Ref`, `Add`, `FStr`,
`Percent`, `Format`). It never holds a module name, a project path, another file's content, or any PSI, VirtualFile or
Project. Consequences:

- Editing constants file X changes the endpoints that use X by relinking; the files that use X are not parsed again.
- Renaming or moving a file, or changing a source root, changes only `LocatedFacts(path, source, sourceRoot)`; the cached
  `SourceFacts` stay valid. A pass that finds equal link input (same paths, same fact objects) links and publishes nothing.
- When a re-extracted file yields facts equal to the cached ones, the old object is kept, so "unchanged" is an identity check.
- Facts are immutable, compact (names are interned, strings are not copied) and compare by value, which is what a
  persistent store needs.

## The pure linker

`PyResolver` (FastAPI, Flask) and `DjangoResolver` give every file its module name from its path and source root, build a
`ModuleTable`, and evaluate expressions with `ExprEvaluator` (cycle and depth guarded, correct or absent: anything not
exactly resolved is omitted and reported as a Diagnostic). Then the existing linkers run unchanged on the resolved
facts. `ModuleWalk.missing` is the other half: it follows only the names that linking actually looks up, so an unused import
does not pull a file into the work.

## What is found, and what is not

Found: files that contain a constructor or attachment call (`APIRouter`, `include_router`, `Blueprint`,
`register_blueprint`, `add_url_rule`, `urlpatterns`, `ROOT_URLCONF`, `.register(`, DRF routers) through the word index;
files with a route decorator `<name>.<verb>` through the decorator stub index, even when they never mention the framework;
files that supply constants, re-exports, views or base classes to those, through the dependency rounds.

Not found: files in virtual environments (`site-packages`, `venv`, a directory with `pyvenv.cfg`), `migrations`, build
output, generated `*_pb2*.py`, files above `Limits.MAX_CANDIDATE_FILE_BYTES`, routers reached by `getattr` or stored in a
dict, include targets built from computed module names. Test code is read after application code. A test that uses
`@mock.patch(...)` is read (the decorator index cannot tell) and yields nothing.

Partial snapshots are not published while a scan runs: Django guesses a root when no `ROOT_URLCONF` is known yet, so a
half-loaded set could list a path that the full set would not.

## Where a persistent store plugs in (not built)

The unit of storage is `SourceFacts` per file, which is already context-free, immutable and value-comparable. Two options,
both keeping the linker unchanged:

1. `PsiFileGist<SourceFacts>` (one gist id for all providers, version bumped with the extractor): `RouteDiscoveryEngine.extractOne`
   would ask the gist first; on an unsaved or uncommitted document the platform computes in memory, otherwise it reads the
   persisted value by the platform's own staleness check. The in-memory `WeightedLruCache` stays as the first level, so the
   gist is read once per file and session.
2. `SingleEntryFileBasedIndexExtension<SourceFacts>` with the same externalizer, if benchmarks show cold start matters more
   than the indexing cost for every user. The filter cannot look at the parent directory or siblings, which context-free
   facts never needed; exclusions already run at query time in `PyCandidateRules`.

The externalizer needs one string table per file (names are repeated heavily) and a version number; `Expr` is a closed
sealed hierarchy, so it serializes with a tag byte per node. `FactsEntry.stamp` is not persisted: the platform's staleness
check replaces it.

## The request workspace

`ui/request` is wired to the stores of `clientide`:

- `RouteGenieClientState` (workspace file, bounded): environments and the last one used, the last input and method per `Endpoint.key`, confirmed hosts, history lines. It holds no secret: secret variables keep their names, sensitive header and query values are blanked by `LastInputs`.
- Secrets live in the IDE credential store (`PasswordSafeSecretStore`). `LayeredSecrets` is the in-memory layer in front: the send path (UI thread) only reads memory; switching the environment loads its secrets on a pooled thread, writes go to the store in the background.
- `HistoryLog` mirrors every line into the persisted history and keeps the JSON body of the newest `Limits.HISTORY_BODIES_KEPT` entries (at most `HISTORY_SPILLED_BODIES_KEPT` in a spill file, kept alive by a `ResponseBody.retain()` lease). Older entries say "body not kept". Bodies are never persisted.
- The response on screen is owned by `RouteGenieClientService.responses` (`ResponseSlot`). `SendController` tells the viewers to let go (`Listener.releasing`) before it replaces or drops the response, so a spilled file is never deleted under a reader.
- The JSON body goes to `JsonViewerPanel` (`ResponseJson` decides memory, spill file or cut); everything else, streams and failures keep the plain views.

## Leak tests

Weak-reference tests must not keep the object in a local variable of the test method: the interpreter keeps the locals of a running frame alive as GC roots, so the object looks leaked although nothing in the plugin holds it. Build the scenario in a separate function that returns only `WeakReference`s (see `WindowLeakTest`). The earlier finding "a disposed EndpointsPanel is not collected (about 250 KB per window)" was this effect: bisecting the panel (create, show, load, select, send, compare, environment editor) shows every part collectable, and 40 cycles of the whole window with endpoints grow the heap by less than the 3 MB limit.
