# Changelog

The newest section is what the IDE shows as the plugin's change notes and what the GitHub release says. Write it for the
person who uses the plugin, newest section first, one `## ` heading per section.

## Latest

- Tests tab (after History in the response area): select an endpoint and see which of your project's own tests call it, found by reading the test files (nothing is run or sent while reading). Each row shows the test, the verb, the file and line, whether the call is direct, skipped or expected-to-fail tests, and the status the test asserts; double-click opens the call. The tab title shows how many tests reach the endpoint, and an endpoint that no test calls says "Untested" only when that is certain. FastAPI and Flask: pytest and unittest tests (`TestClient`, `httpx.AsyncClient` with an ASGI transport, `app.test_client()`, fixtures, `unittest.TestCase`, `IsolatedAsyncioTestCase`, async tests); Django and DRF: also `TestCase` and `APITestCase` (`self.client`, `reverse(...)`). Paths from literals, f-strings and constants of the same file, `reverse(...)`, `url_for(...)` and `app.url_path_for(...)`. A call is attributed only when its verb and path match exactly one endpoint; the others are listed under "Not Attributed..." with the reason.
- Quick test: select a test to see the request it makes (path, query, headers and body, as far as the test code spells them out), change anything, and press Run Quick Test to send it with your environment and compare the answer with the status the test asserts. Your edits stay in memory and never touch the test file. Open in Request Tab moves the request to the Request page; Run Test in the IDE starts the real test through the IDE's run configuration, and Copy Command gives the pytest command.
- Pins: when a URL prefix comes from a setting the code cannot reveal (the official FastAPI template's `prefix=settings.API_V1_STR`, a Flask `app.config["API_PREFIX"]`, a Django `path(settings.BASE_PATH, ...)`), the window now asks once, in a slim strip, to confirm the value instead of silently hiding the routes. One click uses the default the code writes (a class default or `os.environ.get(..., default)`), or you enter another value; "Not now" keeps the count of hidden routes in a collapsed line. Confirmed values are saved in `.route-genie/pins.json` (readable, mergeable, shareable with the team, never holding secrets), picked up when the file changes, shown in the endpoint details, and editable in Tools | Edit Route Genie Pins.
- Random body: a button next to Generate body fills the body with random values that fit the types and limits the code declares (nested models, lists, enums, patterns, formats, dates, decimals), and gives another set every time you press it. Settings > Tools lets you choose how a body is filled when you first open an endpoint: sample values, random values, or not at all.
- Uploads: endpoints that take files (FastAPI UploadFile and File, DRF and Django FileField and ImageField, Flask request.files) now get a multipart body. Random body makes real PNG, JPEG, GIF or BMP images, or valid pdf, csv, json, zip and text files, as the code allows. A bar above the parts lets you pick the format, the size in pixels and a maximum size in KB, and Regenerate makes new files.
- Call endpoints from the window: pick the method, fill in path parameters, query, headers and body, press Send (or Ctrl+Enter) and read the answer in the same window.
- Streaming answers (server-sent events, NDJSON, chunked) appear live; large answers are kept on disk and shown in pieces.
- JSON viewer with Tree, Pretty, Raw and Table views, search, and a structural diff between two responses.
- The body of a POST, PUT or PATCH is filled in automatically from the Pydantic model, serializer or form the code declares, the first time you open the endpoint. Your own text is never overwritten, and the Generate body button next to Body type still works at any time.
- Copy as cURL for bash, Windows cmd and PowerShell; import a cURL command into the form.
- Focus on response hides the request form and the endpoint list so the answer fills the window, and Open in separate window moves the whole window out of the IDE so you can resize or maximize it.
- Errors of the plugin now have a Report button in the IDE's error dialog that opens a pre-filled GitHub issue (nothing is sent before you press Submit there).
- Much faster scanning of large projects, and endpoints behind re-exported URL modules (Sentry-style) are found.

## Earlier

- Project skeleton: tool window and the `Ctrl+Alt+Shift+R` action, centralized branding, ids, strings and limits.
- Shared model: `Endpoint`, `RouteSnapshot`, `StrValue`, `Diagnostic`, and the `RouteProvider` extension point.
- Quality gates: tests that enforce one-place-per-fact, layering, speed budgets and leak checks.
