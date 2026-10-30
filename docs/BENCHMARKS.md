# Benchmarks: the real providers on real repositories

Measured on 2026-10-30 with `src/test/kotlin/com/routegenie/bench/CorpusBenchmarkTest.kt` (opt-in, see Method) against commit
`4344224`. Everything below was produced by the shipped providers and the shipped `RouteDiscoveryEngine`; the only additions
are timers in test code (`TimedProvider`). The repositories were read, never executed.

## Method

- Corpora: shallow clones of full-stack-fastapi-template, fastapi, flask, superset, django, netbox, django-rest-framework,
  sentry. Each is added as a content root of the light platform test project (nothing is copied). Web front end folders
  (any sub-folder with a `package.json`) are excluded, as a Python IDE user would have them. `src`, `backend` and `netbox`
  are marked as source roots for flask, sentry, full-stack-fastapi-template and netbox (their Python packages live there).
- Enable with `ROUTEGENIE_CORPUS=<dir with the clones>`. Run from the Gradle lock script with a larger test heap
  (`maxHeapSize = 4g` through an init script; the default 512 MB is too small for Sentry). Results: `build/reports/routegenie/bench/`
  (`<repo>.txt`, `<repo>-endpoints.tsv`, `<repo>-diagnostics.tsv`, `<repo>-candidates.tsv`).
- Per repository, in one JVM, sequentially: cold discovery (`engine.refreshNow()` with an empty cache, the same call the
  tool window triggers; the test thread keeps pumping the UI event queue and records the longest gap), 5 warm runs (median),
  10 edit cycles on the non-test file with the most endpoints (append a probe route, refresh, revert, refresh), 10 end to end
  cycles through the engine's own listeners and debounce (an active engine, a real document edit, wait for the snapshot),
  the same with a 50 ms debounce, 50 edit cycles for heap growth, 10 add and 10 delete cycles of a `.py` file in a second
  content root.
- Heap numbers are used heap after three `System.gc()` calls. They include the platform test fixture (about 115 to 130 MB
  before anything is loaded), so the retained cost of discovery is the difference to `heap_baseline_mb`. Peak heap is sampled
  every 20 ms and is a lower bound.
- Timers (per provider, summed over threads, so they can exceed wall time): candidate lookup, `PsiFile.node` (syntax tree
  built on demand by the timer; the product only builds it when it needs it), extraction, `missingModules`,
  `findModuleFile`, `extractDependency`, link. Publish is not timed separately: it compares and stores a list.

## Machine and conditions

AMD Ryzen 7 1700 (8 cores, 16 threads), 15.9 GB RAM, SSD, Windows 10 Pro 19045, JBR OpenJDK 25.0.4, test JVM `-Xmx4g`,
PyCharm 2023.2.6 libraries (platform 262.x APIs). Other agents were building on the same machine during the first runs.
The numbers in the table come from a run started after three consecutive 6 second windows in which no other `java.exe`
used more than 1 CPU second (only an idle Gradle daemon and an idle IDE were alive; CPU load 29 percent from other
programs). Three complete runs exist; cold times of the small repos differ by up to 2x between runs (django-rest-framework
cold: 0.95, 1.88, 1.69, 1.54 s over four runs; fastapi 1.75, 2.52, 3.26, 2.43 s), so treat cold times under 5 s as
plus or minus 50 percent. Sentry cold was 17.0, 17.7, 18.0, 17.8 s over the four runs.

## Results

Cold = first published snapshot. Files per second = all `.py` files of the repository (not only candidates) divided by cold.
Refresh = `refreshNow()` after one edit of one real route file (reparse and relink, no debounce). E2E = real edit to updated
snapshot with the shipped 300 ms debounce, and with a 50 ms debounce.

| repo | py files | cold ms | files/s | warm ms | endpoints | diagnostics | refresh ms | e2e 300 / 50 ms debounce | add file e2e ms | heap after cold MB (baseline) | peak MB |
|---|---|---|---|---|---|---|---|---|---|---|---|
| full-stack-fastapi-template | 43 | 172 | 250 | 9 | 0 | 23 | not measured (no endpoint) | not measured | 618 | 125 (115) | 135 |
| fastapi | 1,219 | 2,427 | 502 | 74 | 1,471 | 47 | 118 | 717 / 205 | 722 | 125 (131) | 383 |
| flask | 83 | 221 | 376 | 9 | 115 | 222 | 23 | 625 / 124 | 624 | 122 | 172 |
| superset | 3,069 | 4,008 | 766 | 30 | 11 | 113 | 55 | 677 / 169 | 742 | 127 | 394 |
| django | 2,937 | 2,183 | 1,345 | 50 | 740 | 359 | 53 | 655 / 152 | 1,041 | 131 | 407 |
| netbox | 1,293 | 739 | 1,749 | 16 | 0 | 1 | not measured (no endpoint) | not measured | 682 | 129 | 315 |
| django-rest-framework | 155 | 1,535 | 101 | 60 | 139 | 38 | 81 | 669 / 170 | 634 | 116 | 223 |
| sentry | 8,422 | 17,829 | 472 | 225 | 0 | 0 | not measured (no endpoint) | not measured | 5,945 | 146 (131) | 598 |

Budgets from CONVENTIONS section 4: cold discovery of about 600 files in 5 s is a rate of 120 files/s. Every repo is above
that rate (101 files/s only for django-rest-framework, whose cold time is dominated by first-use class loading; Sentry runs
at 472 files/s, so 8,422 files take 17.8 s). Re-reading one edited file and relinking has a 50 ms budget: the refresh column is
23 to 118 ms, of which the edited file's own extraction is 0.5 to 2.6 ms and its link 2 to 26 ms; the rest is candidate lookup
and stamp checks over all candidates (fastapi 639 + 606 + 1, django 326 + 35 + 63), so only flask (23 ms) and django (53 ms,
borderline) are near the budget and fastapi (118 ms), superset (55 ms) and django-rest-framework (81 ms) exceed it.
Filter keystroke time and tool window creation time were not measured here.

Retention (`heap_before_50_edits` to `heap_after_50_edits`, 100 reparses and relinks of one file): growth of 0.4 to 4.5 MB
(fastapi 4.1, flask 1.7, superset 4.5, django 1.5, django-rest-framework 2.9), cache entry count unchanged
(`cache_entries_after_edits` equals `cache_entries`), endpoints after the edits restored to the original set in all repos
where an edit was run. The growth is within what a JVM leaves uncollected after three GC calls; no per-edit leak shows up.
After `Disposer.dispose` the engine was weakly collectable in all 8 runs. Engine providers ran 0 times on the UI thread.
Longest gap between UI event pumps during a cold run: 0 to 13 ms for all repos except Sentry and Superset (60 ms),
so no long UI stall; cold work is off the UI thread.

Add and delete of a file in a project: 618 to 742 ms for all repos except django (about 1.0 s) and Sentry (5.9 s add,
6.2 s delete). The pass starts about 600 ms after the file event (see bug 3) and then takes 10 to 130 ms, except django
(about 440 ms) and Sentry (about 5.3 to 6.1 s), see bug 2.

### Counts by framework and method

| repo | by framework | by method | diagnostics by code |
|---|---|---|---|
| full-stack-fastapi-template | none | none | DYNAMIC_PREFIX 23 |
| fastapi | FASTAPI 1470, FLASK 1 | GET 1030, POST 389, PUT 39, DELETE 6, PATCH 4, HEAD 1, OPTIONS 1, TRACE 1 | UNATTACHED_ROUTER 17, DYNAMIC_VALUE 12, UNRESOLVED_RECEIVER 11, UNRESOLVED_INCLUDE 6, DYNAMIC_PREFIX 1 |
| flask | FLASK 115 | GET 101, POST 14 | UNRESOLVED_RECEIVER 202, UNRESOLVED_VIEW 14, UNATTACHED_ROUTER 3, DYNAMIC_PREFIX 2, UNRESOLVED_INCLUDE 1 |
| superset | FLASK 11 | GET 10, PUT 1 | UNRESOLVED_RECEIVER 108, UNATTACHED_ROUTER 4, UNRESOLVED_INCLUDE 1 |
| django | DJANGO 740 | ANY 409, GET 159, POST 46, PUT 38, DELETE 28, HEAD 20, OPTIONS 20, PATCH 20 | GUESSED_ROOT 112, UNRESOLVED_VIEW 152, UNSUPPORTED_REGEX 64, DYNAMIC_VALUE 27, DYNAMIC_PREFIX 3, UNRESOLVED_INCLUDE 1 |
| netbox | none | none | DYNAMIC_PREFIX 1 |
| django-rest-framework | DRF 128, DJANGO 11 | GET 78, POST 25, PUT 11, ANY 11, DELETE 6, PATCH 5, OPTIONS 3 | GUESSED_ROOT 20, UNRESOLVED_VIEW 16, UNSUPPORTED_REGEX 2 |
| sentry | none | none | none (see bug 1) |

Invariants checked on every snapshot (path starts with `/`, no `{?}`, not partial): 0 violations in all repos.
No `INTERNAL_ERROR`, no timeout, no crash, no cancelled pass, no `NO_REACTION`, no added file that was not found and no
deleted file that stayed listed in any run.

## Where the time goes (Sentry, 8,422 files, cold 17.8 s)

Summed thread time per provider (Django, FastAPI, Flask providers run one after the other; extraction uses 2 threads):

| step | django provider | fastapi provider | flask provider |
|---|---|---|---|
| candidate lookup (word index, stub index) | 13 ms (493 files) | 26 ms (959) | 24 ms (920) |
| extraction incl. syntax tree (`extract`) | 691 ms (281 with facts) | 9,006 ms (345 with facts) | 7,328 ms (676 with facts) |
| of which forced syntax tree build | 694 ms | 1,278 ms | 322 ms |
| `findModuleFile` (module name to file), calls | 4,649 ms (1,808) | 195 ms (72) | 597 ms (278) |
| `extractDependency`, calls | 2,907 ms (852) | 179 ms (28) | 916 ms (191) |
| `missingModules` | 167 ms | 18 ms | 49 ms |
| link | 7 ms | 17 ms | 14 ms |

Reading: extraction by the FastAPI and Flask providers is about 16 s of thread time, about 8 s of wall time on 2 threads and
about half the cold time. Candidate lookup (66 ms) and link (38 ms) are negligible. About 5.4 s is `findModuleFile`, which runs
serially inside the provider loop; this is a defect (bug 2), not cost of the work. The FastAPI and Flask providers read nearly
the same files (959 and 920 candidates, 345 and 676 with facts, 614 and 244 rejected after the text prefilter) and each
walks them separately. Retained heap after cold is 15 MB over the baseline for 3,443 cached entries; sampled peak 598 MB.

Other repos follow the same shape with smaller numbers; the only other place where a lookup cost shows is django
(`findModuleFile` 98 ms for 95 calls, 109 ms again after a file is added).

## Endpoint counts against the ground truth (STATS.md)

STATS.md counts route declaration lines with greps; matches inside docstrings and comments are included, and nothing about
mounts or prefixes is resolved. Line level comparison: for each grep line, was an endpoint reported at that file and line,
or was a diagnostic reported there, or is it a docstring.

| repo | ground truth lines | endpoints | recall estimate | what the rest is |
|---|---|---|---|---|
| full-stack-fastapi-template | 23 decorators (23 non-test) | 0 | 0 of 23 | all 23 omitted with DYNAMIC_PREFIX: `include_router(api_router, prefix=settings.API_V1_STR)` where `API_V1_STR` is a pydantic `BaseSettings` attribute (can be overridden by the environment, so correctly not guessed) |
| fastapi | 1,789 decorator lines (494 non-test) | 1,471 | 99 percent of real routes; 80 percent of lines | 313 lines are identical routes of different function-local apps that collapsed (bug 4), 35 are docstring examples, 11 omitted with a diagnostic (UNATTACHED_ROUTER 14 incl. callbacks, DYNAMIC_VALUE), 1 real unsupported (`APIRouter.route`) |
| flask | 312 `@x.route` lines (28 non-test) | 115 | about 61 percent of lines; 100 percent of production and example code | 84 collapsed duplicates (bug 4), 123 omitted with a diagnostic (202 UNRESOLVED_RECEIVER: the receiver is a pytest fixture parameter), 7 docstrings |
| superset | 17 `.route` + 328 `@expose` | 11 (all in unit tests) | 0 of about 332 production routes | Flask-AppBuilder `@expose` is not supported and gives no diagnostic; the 4 `health_blueprint` routes are omitted with UNATTACHED_ROUTER because the blueprint is registered through an unresolved variable |
| django | 898 `path/re_path/url` lines (42 non-test) | 740 | 62 percent of lines | 200 omitted with a diagnostic, 141 silent (admin `get_urls()` methods, urlconfs that only include each other, function-local lists) |
| django-rest-framework | 149 path lines, 25 `router.register` | 139 | 38 percent of path lines, about all registered viewsets | 12 with a diagnostic, 81 silent: test urlpatterns built inside test methods, docstrings |
| netbox | 395 `path`, 147 `router.register`, 1,205 `@register_model_view` | 0 | 0 | the root is `path(settings.BASE_PATH, include(_patterns))`, `BASE_PATH` is computed from the environment: 1 DYNAMIC_PREFIX diagnostic, everything below is correctly not listed; `register_model_view` is also unsupported |
| sentry | 1,030 path lines (998 non-test), 607 endpoint classes | 0 | 0 | bug 1: silent |

## Precision: 15 random endpoints per repo, checked against the source

Method: `shuf` with a fixed seed over the `*-endpoints.tsv`, then read the decorator or `path()` line, the router or
blueprint declaration, and each `include`/`register` that adds a prefix.

| repo | checked | wrong | notes |
|---|---|---|---|
| fastapi | 14 random + a complete audit of the 42 endpoints of `test_include_router_defaults_overrides.py` (a 400 KB file with three levels of `include_router` with and without prefix) and of the 4 `sub_applications`/`wsgi` endpoints | 1 (the `wsgi` one, bug 5) | every prefix composition of the 42 matched the include tree (`/level1/level2/level3/level4/override5` and so on) |
| flask | 13 random + all `test_blueprints` nested, renamed and double-registered blueprints (`/1/foo`, `/2/foo`, `/parent/child/grandchild/`, `/a/a/`) | 0 | `defaults=` and `url_prefix=` at registration are applied |
| superset | all 11 | 0 | `add_url_rule` with `methods=["GET"]` and `@app.route(..., methods=["PUT"])` read correctly |
| django | 13 random | 0 wrong paths; 2 debatable | `path("test_admin/admin/", site.urls)` is listed as `ANY /test_admin/admin/` with handler `site.urls` (bug 6) |
| django-rest-framework | 15 random + 12 router/viewset routes | 0 | method sets of `APIView` classes match the defined `get/post/put` methods; `path('html_new_model_viewset', include(router.urls))` yields `/html_new_model_viewset{pk}/` with no slash, which is exactly what Django builds; `@action(url_path=...)` routes correct |
| full-stack-fastapi-template, netbox, sentry | 0 endpoints | none | vacuous |

Sum: about 80 checked, 0 invented and 1 wrong path (bug 5), 2 debatable (bug 6). The target is zero, so this does not pass
until bug 5 is fixed; bug 6 should be decided.

## Fifteen real routes that were not reported, and why

1. full-stack-fastapi-template `backend/app/api/routes/items.py`, `@router.get("/")` under `APIRouter(prefix="/items")`
   included with `prefix=settings.API_V1_STR` in `app/main.py`: DYNAMIC_PREFIX (correct per correct-or-absent; an instance
   attribute of a `BaseSettings` object).
2. Same for `users.py` (all methods), `login.py` (5 routes), `utils.py`, `private.py`: 23 in total, one diagnostic per route
   (23 diagnostics where 1 would say it).
3. superset `superset/dashboards/api.py` `@expose("/", methods=("GET",))` (34 in this file, 328 in the repo): unsupported
   Flask-AppBuilder pattern, no diagnostic.
4. superset `superset/views/health.py:26` `@health_blueprint.route("/health")` (also `/healthcheck`, `/ping`, `/version`):
   UNATTACHED_ROUTER; the blueprint is registered with a variable in `superset/initialization/__init__.py` (correct).
5. netbox `netbox/netbox/urls.py:93` `path(settings.BASE_PATH, include(_patterns))` and with it every route in `dcim/urls.py`,
   `ipam/urls.py` and so on: DYNAMIC_PREFIX (correct).
6. netbox `@register_model_view` (1,205): unsupported dynamic registration, no diagnostic.
7. sentry `src/sentry/web/urls.py:71` `urlpatterns: list[...] = [...]` and the whole `api/urls.py` tree: bug 1 (bug).
8. flask `tests/test_appctx.py:12` `@app.route("/")`: UNRESOLVED_RECEIVER, `app` is a pytest fixture parameter (correct).
9. flask `tests/test_blueprints.py:122` `Blueprint("test", __name__, url_prefix=prefix)` with a parametrized `prefix`:
   DYNAMIC_PREFIX (correct).
10. fastapi `tests/test_include_route.py:9` `@router.route("/items/")` on an `APIRouter` (Starlette style): not supported, no
    diagnostic (limitation).
11. fastapi `docs_src/openapi_callbacks/tutorial001_py310.py:26` callback router never included: UNATTACHED_ROUTER (correct;
    callback routes are not served by the app).
12. fastapi `tests/test_path_alias_choices.py:91` `@app.get("/{" + alias + "}")` computed path: DYNAMIC_VALUE (correct).
13. fastapi `tests/.../test_nullable_and_defaults.py:60` and 46 more `@app.post("/parameter")` in other test functions:
    reported once, the others collapsed (bug 4).
14. django `django/contrib/admin/sites.py` `path("", wrap(self.index))` inside `get_urls()` (11 lines) and
    `tests/urlpatterns_reverse/namespace_urls.py` (two modules that include each other, so neither is a root): silent.
15. django-rest-framework `tests/test_urlpatterns.py` and `tests/test_versioning.py`: `urlpatterns` assigned inside test
    methods: silent (function-local lists are not followed).

## Recall of the narrowed candidate rules

For each repository: files that contain a route decorator, `add_url_rule`, `include_router`, `register_blueprint`,
`urlpatterns`, `path(`, `.register(`, `@api_view`, `@action(` or `ROOT_URLCONF` (grep, excluding migrations, venvs, build
output), minus the union of the candidates of the three providers.

| repo | such files | candidates (union of 3 providers) | such files that were not candidates |
|---|---|---|---|
| full-stack-fastapi-template | 7 | 7 | 0 |
| fastapi | 629 | 640 | 7: all docstring examples (`fastapi/security/*.py`, `background.py`, ...) |
| flask | 39 | 57 | 1: `src/flask/helpers.py` (docstring) |
| superset | 41 | 284 | 4: `@action` of Flask-AppBuilder and a `.register()` in a worker (not routes) |
| django | 307 | 370 | 8: docstrings and `admin/options.py`/`actions.py` (methods, unsupported anyway) |
| netbox | 38 | 62 | 6: `@action` methods in API view modules (they are pulled in by the dependency rounds when needed) |
| django-rest-framework | 39 | 43 | 6: docstrings, `@api_view` functions in test modules that no urlconf uses |
| sentry | 618 | 1,480 | 4: `*_urlpatterns` modules (`auth_v2`, `notifications/platform`, `preprod`, `workflow_engine`), reached as dependencies of `api/urls.py` |

No file that actually serves a route in these corpora was missed by the narrowed rules. The price of the rules is breadth
rather than misses: on Sentry 1,480 candidates (18 percent of the files) are read, of which the FastAPI provider extracts facts
from 345 of 959 and the Flask provider from 676 of 920.

## After the fixes (second run, same harness)

Measured on 2026-10-30 after the changes listed below, same machine and harness (all 8 repositories, 4 GB test heap,
`EXTRACT_PARALLELISM_CAP` 4). No other build of this project ran during the measurement; the machine was not otherwise idle
(an IDE and a Gradle daemon were alive); treat small numbers as plus or minus 50 percent as before. The BEFORE column is
the table above (commit `4344224`); the AFTER column is `build/reports/routegenie/bench/<repo>.txt` of this run.

| repo | cold ms before / after | endpoints before / after | diagnostics before / after | refresh ms before / after | e2e 300 ms debounce before / after | e2e 50 ms debounce before / after | add file e2e ms before / after | delete file e2e ms after |
|---|---|---|---|---|---|---|---|---|
| fastapi | 2,427 / 1,823 | 1,471 / 1,779 | 47 / 50 | 118 / 127 | 717 / 411 | 205 / 151 | 722 / 405 | 394 |
| flask | 221 / 293 | 115 / 152 | 222 / 222 | 23 / 21 | 625 / 325 | 124 / 74 | 624 / 325 | 316 |
| superset | 4,008 / 812 | 11 / 11 | 113 / 8 | 55 / 41 | 677 / 350 | 169 / 101 | 742 / 325 | 319 |
| django | 2,183 / 638 | 740 / 740 | 359 / 374 | 53 / 39 | 655 / 340 | 152 / 91 | 1,041 / 346 | 338 |
| netbox | 739 / 335 | 0 / 0 | 1 / 1 | n/a | n/a | n/a | 682 / 331 | 327 |
| sentry | 17,829 / 1,675 | 0 / 1,222 | 0 / 60 | n/a / 213 | n/a / 492 | n/a / 248 | 5,945 / 431 | 424 |

Notes on the table: cold times of small repositories vary between runs by more than the differences shown (flask 221 vs
293 ms is noise, see Machine); the large changes are Sentry (10.6x), superset and django. Endpoint increases are the
function-scope collapse fix (fastapi +308, flask +37, all in test files) and, for Sentry, the re-export fix.
Superset diagnostics fell from 113 to 8 (UNRESOLVED_RECEIVER 108 to 3), most likely because test files that only use
`@mock.patch(...)` are no longer candidates (not verified file by file); its 11 endpoints are unchanged. Django diagnostics
rose by 15: 7 are the new INFO UNSUPPORTED_PATTERN counters, 8 more UNRESOLVED_VIEW (152 to 160) were not investigated.

Sentry detail (cold, thread time summed over threads): `findModuleFile` 5,441 ms (1,808 + 72 + 278 calls) before, 68 ms
(1,809 calls) after; candidates for the FastAPI provider 959 before, 89 after; for the Flask provider 920 before, 51 after
(decorator stub keys without a receiver, and `mock.patch`, no longer select files; 931 of 959 candidates were selected only by
`@patch(...)`/`@mock.patch(...)` decorators). Extraction thread time: FastAPI 9,006 ms before, 628 ms after; Flask 7,328 ms
before, 142 ms after. Warm run 225 ms before, 102 ms after. Edit to snapshot with the shipped 300 ms debounce: 492 ms (a
refresh of 213 ms plus the debounce; the earlier 2x debounce pattern is gone: fastapi 717 to 411 ms, django 655 to 340 ms).
Longest UI event-pump gap during cold: 53 ms. Heap after cold 167 MB (baseline about 131), 50 edits grew the heap by 4.7 MB with the cache
size unchanged.

Sentry endpoint spot check (15 random lines of `sentry-endpoints.tsv`, fixed seed, read against `src/sentry/api/urls.py`,
`web/urls.py` and the included modules): prefixes composed correctly in all 15 (`api/0/` + `organizations/` + route,
`api/0/` + `projects/` + route, `api/0/` + `users/` + route, `account/` + `settings/emails/`, `api/hooks/mailgun/inbound/`);
0 wrong. Two cosmetic points seen, not fixed: some handler names carry the source root (`src.sentry...`) because Django module
names come from the project-relative path, and a few views whose base class is outside the project are labelled DJANGO although
they are DRF endpoints.

### What changed, by bug

1. Re-exported `urlpatterns` (`from app.urls import urlpatterns`, `from .x import *`, `urlpatterns = other.urlpatterns`,
   and `urlpatterns += [...]` after an imported list) is followed by the Django linker; a root that offers no list at all
   gives UNRESOLVED_INCLUDE. Sentry: 0 to 1,222 endpoints.
2. `PyModuleFiles` (project service): module name to file map built in one pass, layout events queued without work and applied
   by the next lookup, rebuilt on a root change or a burst above `MAX_PENDING_LAYOUT_EVENTS`, bounded by
   `MAX_MODULE_INDEX_FILES` (falls back to the platform name index above it). Sentry lookups 5.4 s to 68 ms total, add/delete 5.9/6.2 s to 0.43/0.42 s.
3. Candidate selection: decorator keys need a receiver and may not come from `mock`; this took Sentry's FastAPI/Flask candidates from
   959/920 to 89/51. `EXTRACT_PARALLELISM_CAP` 2 to 4. A shared FastAPI/Flask extraction pass was NOT built: after the narrowing
   the two providers share few files on Sentry (and the target was reached without it); on the fastapi corpus they still both read about
   600 files, so that case would still gain.
4. Debounce waits one delay after the last request (`lastTriggerNanos`), not one delay per request.
5. `Endpoint.ordinal`/`EndpointKeys.disambiguate` give identical declarations unique stable keys at publish time; the first keeps
   the old key, so stored per-endpoint UI state is unaffected.
6. A Flask or FastAPI app handed to `mount(...)` (also wrapped, e.g. `WSGIMiddleware(flask_app)`) is no longer listed as a root app;
   MOUNTED_APP is reported at the mount. The prefix is not applied (the wrapper may change it).
7. DYNAMIC_PREFIX once per include (fastapi: 23 per-route diagnostics for one include become 1); UNSUPPORTED_PATTERN (INFO) counts
   `APIRouter.route`, `urlpatterns` assigned in a function, and urlconfs that only include each other (guessed-root mode).
   Not done: `@expose`, `register_model_view`, admin `get_urls()`, and WSGI wrapping other than `mount(...)`
   (`DispatcherMiddleware`, `app.wsgi_app = ...`), which still list the wrapped Flask app under its own path.

## Bugs and defects, by priority

No crash, no hang, no timeout, no engine exception in any run (all 8 repos completed; the slowest pass was 18 s).

1. **High, silent total miss: a `ROOT_URLCONF` module that only re-exports `urlpatterns` produces nothing and no
   diagnostic.** Sentry (`sentry.conf.urls`: `from sentry.web.urls import handler500, urlpatterns`) lists 0 of about 1,000
   routes. In `DjangoLinker.execute()` a root without its own `urlpatterns` list is skipped. Repro
   (`BenchReproTest.testRootUrlconfThatReExportsUrlpatterns`): `proj/settings.py` `ROOT_URLCONF = 'proj.urls'`,
   `proj/urls.py` `from app.urls import urlpatterns`, `app/urls.py` `urlpatterns = [path('a/', views.a)]` gives
   `endpoints=[] diagnostics=[]`. Expected `ANY /a/`, or at least an UNRESOLVED_INCLUDE diagnostic. (The annotated form
   `urlpatterns: list = [...]` followed by `urlpatterns += [...]` works: `testAnnotatedUrlpatternsWithAugmentedAssignment`.)
2. **High, performance: `PyProviderSupport.findModuleFile` is linear in the number of `__init__.py` files per call.** It asks
   `FilenameIndex` for every `__init__.py` (and every file with the module's name) and calls `path` on each (full path string
   per file) for every dotted name. On Sentry: 1,808 lookups, 4.6 s of the 17.8 s cold run, and the same 4.6 s again after any
   file is added, deleted or renamed because the layout version invalidates the lookup cache: add to published 5.3 to 6.7 s,
   delete 6.2 s (stack dumps during the wait show the thread inside `findModuleFile` /
   `VirtualFileSystemEntry.computePath`). It stays off the UI thread, so it is latency, not a freeze. Fix idea: resolve with
   `VfsUtil.findRelativePath` / `findFileByRelativePath` from each source root and content root (a few path steps per
   lookup), or build one map of package directories per layout version.
3. **Medium: edit to updated snapshot takes about twice the debounce.** With the 300 ms debounce the end to end time is 625 to
   717 ms for refreshes of 20 to 120 ms; with a 50 ms debounce it is 124 to 205 ms. The model "2 x debounce + refresh" fits
   all repos. `debounceLoop` waits `delay(debounceMs)` and then waits again when any trigger arrived during that delay, so two
   signals that arrive together (the PSI modification and the other listener) cost two full delays. Likely cause, not
   otherwise verified. Fix: track the time of the last signal and wait for `debounce` since then.
4. **Medium: identical routes in different scopes collapse into one endpoint, silently.** Two functions each define
   `app = FastAPI()` and `@app.get('/x') def read()`: `BenchReproTest.testFunctionLocalAppsWithTheSameRoute` returns one
   endpoint; with different handler names (`testFunctionLocalAppsWithDifferentHandlerNames`) it returns both. 313 FastAPI
   and 84 Flask route lines in the corpora disappear this way (all in test files).
5. **Medium, wrong path (violates zero invented): an app mounted into another framework is listed with its own path.**
   fastapi `docs_src/wsgi/tutorial001_py310.py`: `app.mount("/v1", WSGIMiddleware(flask_app))` with
   `@flask_app.route("/")`; discovery reports `GET /` (Flask), the served path is `/v1/`. Either apply the mount prefix or
   omit the Flask route and add a diagnostic.
6. **Low, debatable: an opaque include is listed as an endpoint.** `path("admin/", admin.site.urls)` gives
   `ANY /admin/` with handler `site.urls` (`testAdminSiteUrlsMount`); the real site serves many routes under it and
   `GET /admin/` is only one. Prefer a diagnostic over a made-up `ANY`.
7. **Low, coverage cannot be measured where nothing is reported:** Flask-AppBuilder `@expose` (328 in Superset), NetBox
   `@register_model_view`, Starlette `APIRouter.route`, function-local `urlpatterns`, mutually-including urlconfs, and
   docstrings give no diagnostic.
8. **Low, noise:** one DYNAMIC_PREFIX diagnostic per route when an include prefix is dynamic (23 for the 23 routes behind one
   `include_router`); one per include would be enough. 202 UNRESOLVED_RECEIVER warnings in Flask are pytest fixtures.
9. **Info:** the first repository of a run pays class loading and JIT (django-rest-framework: 101 files/s although it has
   tiny files). Cold results under 2 s should not be compared across repos.


## Pins (settings-driven prefixes), measured 2026-10-30

Harness: `src/test/kotlin/com/routegenie/bench/PinsCorpusTest.kt` (opt-in, `ROUTEGENIE_CORPUS=<dir>`, test heap 4 GB; results in
`build/reports/routegenie/bench/pins-<repo>.txt`). The table at the top of this file was measured on commit 4344224; commit
7f46319 (benchmark fixes) changed the counts afterwards (fastapi 1,471 to 1,779, flask 115 to 152, sentry 0 to 1,222). To prove that
the pins work changes nothing without a pin, the same engine was run on the same clones for HEAD before the work (extracted copy,
counts only) and after it.

| repo | endpoints before pins work (HEAD) | endpoints now, no pin | diagnostics before / now | unresolved settings reported |
|---|---|---|---|---|
| full-stack-fastapi-template | 0 | 0 | 1 / 1 | `settings.API_V1_STR` hides 23 routes, suggestion `/api/v1` from `backend/app/core/config.py:22` |
| fastapi | 1,779 | 1,779 | 50 / 50 | none |
| flask | 152 | 152 | 222 / 222 | none |
| superset | 11 | 11 | 8 / 8 | none |
| django | 740 | 740 | 374 / 374 | none |
| django-rest-framework | 139 | 139 | 52 / 52 | none |
| netbox | 0 | 0 | 1 / 1 | `settings.BASE_PATH` hides 68 routes, no suggestion (no literal default in the code) |
| sentry | 1,222 | 1,222 | 60 / 60 | none |

With a pin:

- full-stack-fastapi-template with the suggested pin `settings.API_V1_STR = /api/v1`: **0 to 23 endpoints** (DELETE 3, GET 6, PATCH 3,
  POST 10, PUT 1), no setting left, no diagnostic, all 23 marked as pinned.
- netbox with `settings.BASE_PATH = ""` (a root deployment, chosen by hand, never suggested): 0 to 68 endpoints, no setting left.

Cost (PerfBudget medians on the synthetic project of 300 route files and 900 routes, budget 100 ms): linking with a pin 11.5 ms,
linking with the setting unresolved 6.6 ms, linking a project with literal prefixes only 14.1 ms (so noticing settings is within
noise of the normal path). Parsing a pins file of 500 entries 7.1 ms (budget 20 ms). Updating the strips for 50 settings 3.6 ms
(budget 50 ms). The corpus cold times did not move beyond run-to-run noise (template 75 ms, fastapi 2.0 s, django 0.8 s, sentry 3.1 s
in this run, machine busy with other builds).

## Recommendations

1. Fix bug 1 first (Sentry-shaped projects show an empty window with no explanation), then bug 5 (zero invented).
2. Fix bug 2 (module lookup) and bug 3 (debounce): cheap, and they dominate add/delete latency and the first 5 s on a big
   project.
3. Share one syntax tree walk between the FastAPI and Flask providers (and Django where it overlaps): on Sentry they walk
   about 900 of the same files and extraction is half of cold time.
4. Count diagnostics for unsupported patterns (`@expose`, `register_model_view`, `APIRouter.route`) so coverage is
   visible, and merge per-route DYNAMIC_PREFIX into one per include.
5. If cold start must stay under the 5 s budget for 8,000 files, the persistent store in ARCHITECTURE.md is the next lever;
   the numbers say extraction (not candidates, not link) is what it would remove. After bug 2 the Sentry estimate is about
   12 s cold, still above 5 s.
6. Keep the harness: `ROUTEGENIE_CORPUS=<dir> scripts/gw.sh test --rerun --tests com.routegenie.bench.CorpusBenchmarkTest`
   (needs the test heap raised to 4 GB for Sentry) and `BenchReproTest` (prints, asserts nothing).

## Not measured or not done

- One-file edit latency for full-stack-fastapi-template, netbox and Sentry (no endpoint to pick a route file from).
- Peak heap is a 20 ms sample, not an exact maximum; retained heap is relative to a fixture baseline.
- Filter keystroke time and tool window creation time (UI), and any run with a real Python SDK (the platform test project has
  none; resolution of library imports was not exercised).
- Precision is a sample of about 80 endpoints plus two complete audits; it is not a proof of zero wrong endpoints.
  Recall against grep counts is line based and includes docstrings.
- The first runs shared the machine with other builds; only the final table comes from a quiet machine, and variation
  between runs for cold times of the small repos is large (see Machine).

## Tests tab: the test mapping on the same corpora

Measured on 2026-10-30 with `src/test/kotlin/com/routegenie/bench/TestCorpusBenchmarkTest.kt` (opt-in, same switch, heap and init
script as above). Per repository, in one JVM: the real route providers and `RouteDiscoveryEngine` find the endpoints (cold), then
the real `TestMapEngine` reads the test files and maps their calls (cold, empty cache), 5 warm passes, 10 edits of the test file with
the most mapped calls (append a probe test, refresh, revert). The repositories were read, never executed. Machine as above (16
threads; the engine uses 4 workers); other builds ran on the machine at times, so single numbers move by 20 to 30 percent between
runs (the first run of this section had django-rest-framework at 404 ms per MB, the final one at 888). Raw output:
`build/reports/routegenie/bench/<repo>-tests.txt` and `<repo>-tests-unattributed.tsv`.

"Files read" are the candidates (test paths with a client word) plus the project files that base classes of test cases are imported
from (the dependency rounds). The tests of a repository are read after its routes, in the same JVM.

| repo | endpoints | files read / .py files | MB read | cold ms | ms per MB | test functions | client calls | mapped | not attributed | endpoints reached |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| full-stack-fastapi-template | 0 | 7 / 43 | 0.03 | 22 | 685 (fixed cost) | 48 | 53 | 0 | 53 | 0 |
| fastapi | 1,779 | 502 / 1,219 | 3.29 | 1,020 | 311 | 2,611 | 2,796 | 236 (8%) | 2,560 | 120 (6.7%) |
| flask | 152 | 27 / 83 | 0.19 | 86 | 444 | 326 | 344 | 80 (23%) | 264 | 54 (35.5%) |
| django-rest-framework | 139 | 74 / 155 | 0.90 | 801 | 888 (first repo: JIT) | 1,338 | 139 | 65 (47%) | 74 | 34 (24.5%) |
| django | 740 | 906 / 2,937 | 11.13 | 1,864 | 167 | 16,740 | 2,029 | 632 (31%) | 1,397 | 186 (25.1%) |
| superset | 11 | 312 / 3,069 | 9.38 | 1,342 | 143 | 5,620 | 667 | 15 | 652 | 3 (27.3%) |
| netbox | 0 | 159 / 1,293 | 4.68 | 676 | 144 | 5,285 | 200 | 0 | 200 | 0 |
| sentry | 1,222 | 2,025 / 8,422 | 30.11 | 4,551 | 151 | 30,795 | 5,590 | 3,047 (54.5%) | 2,543 | 476 (39.0%) |

Why calls are not attributed (counts per reason; "computed URL" is a URL the code builds at runtime, for example a `parametrize` argument):

| repo | reasons |
| --- | --- |
| full-stack-fastapi-template | constant unresolved 51 (`settings.API_V1_STR`, every test call of the official template), helper 2 |
| fastapi | ambiguous 2,016, computed URL 441, constant unresolved 46, no endpoint 39, helper 11, relative URL 7 |
| flask | ambiguous 220, no endpoint 20, computed URL 15, helper 4, constant unresolved 3, external host 2 |
| django-rest-framework | ambiguous 33, no endpoint 27, helper 10, name unresolved 2, constant unresolved 2 |
| django | name unresolved 786, ambiguous 345, computed URL 102, no endpoint 89, constant unresolved 57, helper 16, relative URL 2; 9 calls on `self.client` of a base class not found |
| superset | no endpoint 420, helper 201, computed URL 18, relative URL 7, constant unresolved 6; 1,137 calls on `self.client` of a base class that is not in the client table |
| netbox | helper 126, name unresolved 47, no endpoint 18, computed URL 9; 1,095 calls on `self.client` of a base class not found (no endpoints are discovered for NetBox) |
| sentry | constant unresolved 1,273 (mostly `self.url`), computed URL 454, no endpoint 446, helper 258, name unresolved 104, ambiguous 8 |

Readings (what the owner should take from it):

- **FastAPI and Flask literal calls are found completely; what is reached is limited by ambiguity and discovery, not by parsing.**
  The Kotlin extractor sees 2,796 client calls in the FastAPI repository (the Python scanner of the plan: 2,812, -0.6%), 344 in Flask
  (346, -0.6%) and 53 in the official template (53). 2,016 of the FastAPI calls match several endpoints because every test file
  of that repository builds its own small app (`client.get("/")` matches 150 endpoints named `/` in `docs_src`); in a project with
  one application this is the Sentry shape (8 of 5,590). They are listed under "Not Attributed", never under an endpoint. An app
  binding (plan Q11, `Endpoint.root`) is what removes it; not built.
- **The official FastAPI template shows no test at all** until the prefix of `settings.API_V1_STR` can be pinned (plan Q2): 51 of its
  53 calls say so, by name, and the tab then shows no "Untested" chip for any endpoint (an unresolved URL could be anything).
- **Sentry, the plan's S2 exit case:** 476 of 1,222 endpoints (39.0%, the criterion was more than 25%) are reached by 2,558 tests. This
  needs the dependency rounds: Sentry tests derive from `sentry.testutils.cases.APITestCase`, a project class outside the test
  directories. Without reading those files only 81 client calls and 5 endpoints were found (first run of this section). The
  remaining gap is `self.url` (1,273 calls, properties and attributes defined elsewhere), URLs built by helpers and the 5,098
  `get_success_response` helper calls of the plan's scan, which are not client calls here and need the configurable helper rows of
  plan slice 9. The scanner's 6,127 direct calls compare with 5,590 here (-9%).
- **Django core:** 786 `reverse("...")` calls name routes that discovery does not list (admin model URLs, URLconfs swapped per test
  class); they are reported as "route name matches no endpoint that fits", not guessed. 632 calls map (31%), 186 endpoints (25%) are reached.
- **DRF:** the plan's scanner counted 484 calls, 331 of them on `APIRequestFactory`/`RequestFactory` receivers that build a request
  for a view and never go through URL routing; those are not client calls here by design (plan: view-direct, not in v1). The client
  based calls are 153 for the scanner and 139 here (-9%); the difference was not traced further.
- **NetBox and Superset:** discovery finds no (NetBox) or 11 (Superset) endpoints, so nothing can map. 1,095 and 1,137 calls on
  `self.client` are counted as "client attribute of a test case whose base class was not found". NetBox imports its base classes
  through a package that re-exports them (`utilities/testing/__init__.py` has `from .base import *`), which the dependency rounds do
  not follow yet. Superset's `SupersetTestCase` derives from `flask_testing.TestCase`, a library class that is not in the client table
  (a new row of `ClientSinks`).

Speed against the budgets of the plan (Appendix C):

| what | budget | measured |
| --- | --- | --- |
| cold index per MB of selected test source (background) | 650 ms | 143 to 444 ms on every repository from 0.19 MB up, except the first repository of the run (888, JIT warm-up); the 0.03 MB template is dominated by a fixed cost (22 ms in all) |
| link of 20,000 calls against 5,000 endpoints (pure) | 250 ms | median 160 ms (`TestLinkPerfTest`) |
| re-read one edited file of about 34 KB (walk over a parsed tree) | 50 ms | median 14 to 23 ms; with the platform building the tree first 53 to 110 ms (parsing alone costs 0.75 to 0.8 s per MB) |
| one edited test file, refresh (candidates, stamps, one extraction, link), 150 files | 100 ms | median 52 to 69 ms (`TestMapLeakAndSpeedTest`) |
| the same on the real repositories (file with the most calls) | 100 ms | superset 46, flask 74, django-rest-framework 110; fastapi 150, django 152, sentry 337 (a 163 KB file) over budget |
| show the tab for an endpoint, select a test (500 rows) | 50 ms | 1.4 ms and 4 to 8 ms |
| a pass with nothing changed | - | fastapi 34 ms, django 77 ms, sentry 140 ms |

The edit refresh is over the budget on the three big repositories. Part of it is the pass itself even when nothing changed (stamp
comparison of up to 2,000 files, word index lookup, the dependency lookups), the rest is the platform building the syntax tree of the
edited document (about 1.7 ms per KB, the files are 22 to 163 KB) and relinking 2,000 to 5,000 calls. An incremental link (only the
calls of the changed file and of the files that depend on it) would remove the last part; it is not built.

Memory: the facts cache holds 13.4 MB for Sentry (2,025 entries, bound 48 MB), 5.2 MB for Django, 3.0 MB for FastAPI. The heap
retained after the cold pass is 17 MB for Sentry and under 10 MB elsewhere (relative to the fixture, GC timing makes small numbers
noisy). The UI-thread gap during a pass was not measured in this benchmark; the engine does no PSI work on the UI thread by construction
(`smartReadAction` on a pool of at most 4 threads).

Not measured: 50 edit cycles for heap growth on the corpora (the leak tests in `TestMapLeakAndSpeedTest` cover cycles of the engine and
the tab with a 3 MB limit), the Sentry and Superset numbers after package re-exports are followed.
