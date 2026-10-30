# Manual checklist: endpoint window (Phase 2, tool window)

Things that cannot be shown by the headless tests: real painting, real focus, real tool window show/hide. Check them by eye
in the IDE the plugin is installed in. Use a project with FastAPI, Flask and Django routes if you have one.

## Opening and states
1. Press Ctrl+Alt+Shift+R. The Route Genie window opens on the right. Expected: a short "Scanning web endpoints" page, then the tree.
2. Open the window in a project without Python files: "No Python files in this project".
3. Open it in a Python project without routes: "No endpoints found yet" with the hint line.
4. Hide the window (click the stripe button) and edit a route file, then show it again: the list is up to date, and the status
   line may say "Updating..." for a moment.

## Look
5. Light theme and dark theme (Settings > Appearance): method badges are readable (GET green, POST blue, PUT orange,
   PATCH purple, DELETE red, others grey, ANY outlined in teal). Selection colors are readable on a selected row.
6. A deprecated endpoint is struck through, dimmed and marked "deprecated". Tags appear as #tag.
7. Rows have the same height everywhere; nothing flickers while typing in the filter box.
8. Resize the window narrow and wide; drag the divider between list and details and reopen the IDE: the divider position is kept.

## Search and grouping
9. Type words in the filter box: path, handler, file name. Try `method:POST`, `method:GET,POST`, `framework:fastapi`,
   `tag:users`, `path:/api`, `file:routers`, and a combination. A route with method ANY must stay visible for every `method:` filter.
10. "Group By" menu: Framework and path, File, Path prefix, Flat list. The choice is remembered after restart.
11. "Show Deprecated" toggle hides and shows deprecated endpoints.
12. Click into the tree and just start typing: speed search highlights visible rows.
13. Expand All / Collapse All. In a very large project the tree starts collapsed and Expand All stops at a few thousand rows.

## Navigation and details
14. Select an endpoint: the details panel shows method, path, handler, framework, path parameters with types, tags, route name, source.
15. Double-click, Enter and F4 open the source at the decorator or route line. Edit the file first so the line moved: still lands inside the file.
16. Right click: Navigate to Source, Copy Path. Ctrl+C copies the path. The "Copy Path" button in the details does the same.
17. Click the source link in the details panel: opens the file.
18. Delete a route file and press Navigate: a warning balloon says the file no longer exists (no exception).
19. Press Refresh in the toolbar after a change made outside the IDE.

## Selection by gutter (needs the gutter work, later)
20. Click the gutter icon on a route decorator: the window opens, expands the group, selects the endpoint and scrolls to it.
    Do it while a filter hides it: the filter is cleared and the endpoint is selected.

## Memory and background behaviour
21. With the window hidden, edit Python files: no CPU activity from the plugin (Help > Diagnostic Tools > Activity Monitor, or the
    status bar shows no background task). Show the window again: one scan.
22. Open and close the window several times, then run Memory Indicator (Settings > Appearance > Show memory indicator) and a
    forced GC: the heap goes back to the level before.

## Not testable headless (and therefore only checked here)
- Painting of badges, strike-through, colors in both themes, row height with high DPI scaling.
- The tool window show/hide events driving `setActive` (the tests call the same entry point directly).
- Real focus, F4 and Enter routing through the IDE key map; popup menu; speed search overlay.
- The divider position being stored across IDE restarts.
- Behaviour of a real project of 50,000 endpoints (the tests measure the model and filter only).

## Window and gutter together
23. With the Route Genie window hidden, open a route file: the gutter icons still appear and stay after hiding/showing the window
    (the window does not switch discovery off while the gutter uses it).

## Gutter icons and actions
24. Open a file with route decorators or `path()` calls: one small icon appears next to each route declaration, in light and
    dark theme. Hover: the tooltip reads like "GET /health" or "GET, POST /multi" (one icon per line, even for several methods).
25. Click an icon: the window opens and selects that endpoint. When one line carries several endpoints, a small chooser popup
    appears next to the icon.
26. Edit a route (change its path): the icon stays on the right line after the short refresh pause; for a moment it may sit on a
    stale line.
27. Put the caret on a route decorator or call (or on the `def` line of a decorated function) and open the editor menu or
    Navigate: "Select in Route Genie" is present and reveals the endpoint. In the function body the entry is hidden.
28. Open a plain Python file that uses no web framework: no icons, and no background scan starts.
29. Settings | Tools | API Route Genie: the "show gutter icons" option is automatic by default (off when the paid Python plugin is
    installed, on otherwise); an explicit choice always wins; Apply adds or removes the icons immediately without a restart.
30. In an IDE that has the paid Python plugin enabled, the default is off and the settings page says so.

# Calling endpoints (request panel, response viewer, cURL)

Things the headless tests cannot show: real painting, focus, the confirmation dialog, a real server. Start a local FastAPI or
Flask app (for example `uvicorn app:app`) and select one of its endpoints. Everything below happens inside the Route Genie
window; no editor tab, pop-up or separate window opens (the only dialog is the one-time confirmation for a non-local host).

## Layout
31. Select an endpoint: the tabs below the list show "Overview" and "Request". The Request page has the form on top and the
    response below. Widen the window past about 900 px: the response moves to the right of the form; narrow it: stacked again.
    Layout menu in the page toolbar: Automatic / Request above response / Request beside response; the choice is kept after restart.
32. "Hide endpoint list" in the page toolbar hides the filter bar and the list, so the Request page fills the window. Press it
    again to bring the list back. Hiding must not lose the selected endpoint or the typed values.
33. Light and dark theme: status pill colors (2xx green, 3xx cyan, 4xx orange, 5xx red), table and editor colors are readable.

## Building and sending
34. An endpoint whose method is ANY shows a method dropdown (default GET); a normal endpoint shows its method as fixed text.
    Pick POST for an ANY endpoint, select another endpoint and come back: POST is still chosen (also after an IDE restart).
35. Path parameters appear as fields with their type as hint. Type a wrong value for an int parameter (letters) and press Send:
    a red message appears next to the field and nothing is sent (the server log stays quiet).
36. Base URL field: change it to your server address. Query and Headers tables: add a row with the plus button, untick a row
    to disable it. Auth: Bearer with a token; the secret is not saved anywhere (restart the IDE: it is gone).
37. Body: choose JSON, type a body, press Ctrl+Enter inside the editor: the request is sent (and only then).
38. Press Send for a normal endpoint: the pill shows the status, the line shows time, size and protocol, the Body tab shows the
    pretty-printed JSON with highlighting. Headers tab lists the response headers. "Request sent" shows the exact request
    (credentials as <redacted>).
39. Point the base URL at a public host (for example an example domain) and press Send: ONE confirmation dialog names the host.
    Cancel: nothing is sent. Confirm: it is sent and the host is not asked again in this session.
40. Stop the server and press Send: a friendly message ("Connection refused ...") appears, the Send button comes back.
41. Press Send on a slow endpoint and press Cancel/Stop: the request ends as "stopped" within a moment; no error balloon.
42. "Generate body": for an endpoint with no declared body shows "The code does not declare a request body for this endpoint"
    and changes nothing. When a body can be derived (later phase), typing something first makes a Replace/Cancel bar appear.
    The button is beside "Body type" and is visible while the type is None, JSON or Text (a new POST/PUT/PATCH endpoint starts at
    None): on an endpoint with a Pydantic model parameter, pressing it at type None switches the type to JSON and fills the sample.

## Streaming
43. Call an SSE endpoint (text/event-stream): the Body tab fills live, an Events tab appears with rows (time, event, id,
    data); selecting a row shows its JSON data pretty-printed. Counters (bytes, events, elapsed) keep running; Stop ends it.
44. Scroll the live body up: auto-scroll turns off by itself; scroll to the end: it follows again. The toolbar toggle does the same.
45. A very fast stream stays responsive (typing in the form, switching tabs); when it outruns the viewer a notice says how
    much older output was dropped. Clear empties the view. Mode menu: "Always stream" on a normal JSON endpoint and
    "Always wait for the whole response" on a stream behave as named.
46. Close the tool window or the project while a stream runs: no error, and the server sees the connection close.

## cURL and history
47. cURL tab: choose bash / cmd / PowerShell (the choice is kept). Copy the command and run it in the real shell: the server
    receives the same request. Check bodies with quotes, a trailing backslash, and non-ASCII text in each shell. In cmd a long
    or non-ASCII body produces a "save as body.json" note.
48. "Include secrets" is off by default: the Authorization value shows as <redacted>. Switch it on: the real value appears.
49. Paste a command from the browser ("Copy as cURL (bash)" or "(cmd)") into "Import a cURL command" and press Import: method,
    URL pieces, headers and body fill the form; flags that were not applied are listed.
50. History tab: sent requests appear newest first; double-click one to restore its form (credential headers come back as
    <redacted> and must be typed again).
51. Right click an endpoint in the list: "Send Request..." opens its Request page and focuses Send (it does not send);
    "Copy as cURL" puts the command on the clipboard in the shell chosen on the cURL tab.

## Not testable headless (and therefore only checked here)
- Painting of the pill, tables and editors in both themes; split orientation on a real resize; focus after "Send Request...".
- The one confirmation dialog for a non-local host; the tree context menu entries.
- Behaviour against a real SSE server and a 5 MB or larger response (the tests use a fake engine and measure formatting only).

## JSON viewer
Needs a real IDE run (`runIde`) and a FastAPI/Django/Flask app that can return big JSON. J1-J5 can be done with any response.

J1. Send a request that returns JSON: the Body shows a segmented control (Tree | Pretty | Raw | Table). The tree opens with the root expanded; strings are green, numbers blue, true/false/null purple (colors follow the editor color scheme, so they change with the theme: try Light, Darcula, Dracula and a Material theme).
J2. Click a row: the breadcrumb at the bottom shows the path (`$ > users > [1] > name`); click a segment to jump to that ancestor. Right click: Copy Path, Copy Value (objects copy as formatted JSON), Copy Key. Ctrl+C copies the value.
J3. Toolbar: Expand (levels 1-4 and "as deep as possible"), Collapse All, Copy, Save to File, Open as File. In Pretty/Raw also Soft-Wrap; in Pretty also Format (Sort Keys, Minify, Indent 2/4).
J4. Type in the search field: matches appear in the tree one by one (Enter = next, Shift+Enter = previous, Esc clears); the count reads `3 / 42`. Toggle Match Case, Regex, and Keys/Values. A bad regex shows "Invalid pattern" in red and nothing freezes. In Pretty or Raw the same field searches the text on screen.
J5. Pretty view: braces and brackets fold (the gutter arrows), line numbers are shown, colors are the editor's JSON colors. Switching modes keeps the selected row (Pretty jumps to it).
J6. Table: choose a response that is an array of objects (or an object with one such array): one column per key, click a header to sort (a second click reverses, a third clears), Ctrl+C copies the cell, right click copies the row as JSON.
J7. Invalid JSON (for example an endpoint that returns a Python repr with single quotes): a banner states line, column and the reason, the Raw view opens with the text as received, Pretty/Table are greyed out with a hint, the partial tree is still available.
J8. Large response (generate 30-60 MB of JSON, or point the app at a big file): the tree appears within a fraction of a second while the status line counts "Indexing... 43%"; the window never freezes; scrolling and expanding stay smooth; the IDE heap (Memory Indicator) returns to its old level after you send a small request or close the window. Pretty and Raw show one page at a time with Previous/Next in a thin bar ("Page 3 of 41").
J9. A body above the text limit (about 128 MB): Pretty and Raw show a notice with Save to File and Open as File; the tree and search still work.
J10. Compare (needs the request panel integration): choose the previous response and the current one. Added values are green, removed red, changed amber; unchanged parts are folded into "... N unchanged". Toolbar: Ignore Key Order, Numbers by Value, Unified View, Next/Previous Difference, Copy Diff (unified text), Copy Changed Paths. The two fields filter by key or path (`updatedAt, $.items[*].ts`) and match array items by a key (`id`).
J11. Switch the IDE theme while the viewer is open: tree, table, editor text and the diff colors follow it without reopening the window.
J12. Resize the tool window narrow (about 400 px): toolbar icons stay reachable, the diff stays readable (use Unified View), nothing overlaps.

Not testable headless: real scrolling feel with 50 MB, focus/keyboard routing (Enter in the search field, arrow keys on the segmented control), the Save dialog, Open as File in an editor tab, text selection and the context menus.

## Workspace integration (environments, persistence, JSON in the response, Compare)
Needs a real IDE run and a FastAPI/Django/Flask app. Items W1-W14. Everything stays inside the Route Genie window; the only dialog is the one-time confirmation before sending to a non-local host.

W1. Open the Request page of an endpoint. The row above the form reads "Environment" with a drop-down and "Edit environments". The first time, one environment named Default exists and points at the usual dev server of the framework of the endpoint (Django and FastAPI 127.0.0.1:8000, Flask 127.0.0.1:5000).
W2. The form is split into tabs: Query parameters, Headers, Authentication, Body. Choose Body, pick JSON: the editor appears (it must not say "This request has no body"). Type a body, switch tabs and back: the text is kept.
W3. "Edit environments" replaces the form with the environment editor (no dialog). Create "Staging": a name, a base URL, authentication Bearer with a token, a variable `tenant` and a secret variable `apiKey`. Press Save: "Saved" appears, the drop-down lists Staging, and the base URL of the form changes to the staging address. "Back to request" returns to the form.
W4. Persistence: change the base URL, the method of an ANY endpoint (pick POST), a query row and the body of an endpoint. Select another endpoint and come back: all are restored. Restart the IDE: the same environment, base URL, method, query rows and body are there.
W5. Secrets: after the restart the token typed in W3 is still used (send a request to an endpoint that echoes or checks the Authorization header). Open .idea/workspace.xml: it contains the environment name and base URL but neither the token nor the value of the secret variable (search for the text you typed). The Authentication tab shows "Saved, type to replace" in the empty secret field.
W6. Switching the environment in the drop-down changes the base URL and the secrets used for the next send; a secret stored for Staging is not sent to Default.
W7. Confirm a public host once (a base URL such as https://example.org, press Send, Confirm). Restart the IDE and send again: it is not asked again. A different public host asks again.
W8. History tab after a restart: the lines of earlier sends are there (verb, URL with credentials hidden, status, time) and read "body not kept"; double-click on a line of another endpoint selects that endpoint.
W9. A JSON response appears in the visualizer (Tree, Pretty, Raw, Table, search); a text or HTML response stays in the plain viewer; an SSE or NDJSON stream stays in the live views with Events.
W10. A response larger than 5 MB (generate 20-60 MB of JSON): the status line shows the full size, the tree opens quickly while "Indexing" counts, the editor never freezes, and the folder `<temp>/routegenie` holds `routegenie-resp-*.bin` files while it is shown. Send small requests: the file of an older big response disappears once more than two big ones are newer (the newest two stay for comparison); closing the tool window or the project removes all of them.
W11. Compare: send a JSON request twice (change something on the server in between). Response toolbar, Compare menu: "Compare with previous response" switches the body area to the diff (added green, removed red, changed amber); the "Show diff" toggle in the toolbar goes back to the body and forth. "Compare with..." lists the earlier responses that still have their body (five at most); older history lines read "body not kept". With only one response the notice says there is nothing to compare with.
W12. Compare with a big (spilled) response works for the previous one; the diff refuses a side above 64 MB with a message.
W13. A call to a stopped server shows "The request did not get an answer" with the reason in the body area and "failed" in the status line (no empty editor).
W14. Polish (look at it in Light, Darcula and a third-party theme): the filter box reads "Filter endpoints" and a line with the filter syntax appears under it while the empty box has focus; the tooltip lists the keys. In the details panel the verb badge, the path and the copy icon share the title row, "Details" is a captioned block, tag chips have a complete outline. A selected row keeps the hue of its verb badge (readable lettering in focused and unfocused selection).


## Pins (prefixes taken from settings)
Needs a FastAPI project that uses `prefix=settings.X` (the full-stack-fastapi-template works), a Flask project with `app.config["X"]`, and optionally a Django project with `path(settings.X, include(...))`. Items P1-P14. Nothing here opens a modal dialog except the pins view, which you open on purpose.

P1. Open the window on the template. The list is empty ("No endpoints found yet") and a strip under the toolbar reads "N routes are hidden because their URL prefix comes from settings.API_V1_STR." with "Use /api/v1 (the default written in ...config.py:LINE)", "Enter another value" and "Not now". Light and dark themes: the stripe, the text and the links are readable.
P2. Click "Use /api/v1". The strip disappears within about a second, the endpoints appear, and `.route-genie/pins.json` exists in the project with the pin (open it: sorted, one pin per line, a note naming the config file). Nothing was saved before the click.
P3. Select an endpoint: the details show "Pinned value: Prefix taken from your pin settings.API_V1_STR = /api/v1" and a "Change" link that opens the pins view.
P4. Pins view (Tools | Edit Route Genie Pins): the pin is listed as "Used by N routes". Double-click the value, type `/v2`, press Enter: the endpoints change to /v2/... Type `v2` (no slash), `/a b`, `/a/../b`: each is refused with a message under the table and the old value stays. Select the row and press Delete: the pin is gone and the strip returns.
P5. "Enter another value": a field appears with the default; type a bad value and press Save pin: the message shows inline; type a good one and press Enter: it is saved. Leave it empty and save: an empty prefix is accepted (routes at the root).
P6. "Not now": the strip goes away, nothing is written, and a line "N routes are hidden by unresolved URL prefixes" with Show / Pins... stays. Show lists the setting, the number of routes and where it is used; Pin... brings the strip back. Restart the IDE: the strip is back (it was a session choice).
P7. Edit pins.json by hand (change a value) and save, or run `git checkout` on it: the window updates without a restart. Break the JSON on purpose: one red message "The pins file could not be read ..." appears, the pins stop applying, the plugin does not crash, and pressing "Use" does not overwrite the file. Fix it: the pins return.
P8. Two settings in one prefix (for example `settings.ROOT + settings.VERSION`): two strips, most routes first; pinning one leaves the other. With more than three unresolved settings only three strips show; the rest are in the collapsed line.
P9. A setting named like a secret (`settings.SECRET_KEY` used in a prefix) gets no strip; the collapsed line says its name looks like a secret and it cannot be pinned. Type such an expression into pins.json by hand: it is ignored and counted in a warning.
P10. Windows: a pins.json with CRLF line endings keeps them after the plugin adds a pin. A project on another drive or with a space in its path writes the file in the right folder.
P11. Flask: `url_prefix=app.config["API_PREFIX"]` shows a strip without a "Use" link (there is no default in the code); with `app.config.get("API_PREFIX", "/api")` the default is offered.
P12. Django: the strip for `settings.BASE_PATH` offers no default (the plugin does not read settings modules); entering an empty value lists the routes at the root; entering `api/` (no leading slash, trailing slash allowed) works, while the same value is refused for a FastAPI prefix.
P13. `ROOT_URLCONF` is untouched: a project that sets it keeps working with and without pins.
P14. Open and close the window and the pins view 20 times, then watch the memory indicator: no growth. Hide the window, pull a change of pins.json, show the window: the list is current.

Not testable headless for this part: the look of the real toolbar icons and tabs (the snapshot harness draws toolbar buttons as plain squares and clips some bold text), the body editor of the form in a snapshot, PasswordSafe prompts of the OS keychain, and everything that needs focus or a real resize.

## Tests tab (the project's own tests of the selected endpoint, quick test)
Needs a real IDE run and a project with tests: FastAPI (pytest with `TestClient`), Flask (`app.test_client()`), Django (`self.client` in a `TestCase`, `reverse(...)`). Items T1-T16. Reading tests never runs or sends anything; only the buttons named in T9 to T13 do.

T1. Open the Request page of an endpoint. The tab strip of the response area is Body, Headers, Request sent, cURL, History, Tests (after History). The tab title reads "Tests" until the first read of the test files is done, then "Tests (N)" with N the number of tests that call the selected endpoint (stops at "99+").
T2. Laziness: with the Request page hidden (Overview tab, or the tool window closed) the IDE does no work for tests (watch the CPU / background tasks): no "Reading test files" progress. Show the Request page: one background task "Reading test files" starts. Select another endpoint: no new task.
T3. FastAPI project: select an endpoint that a test calls with a literal path (`client.get("/items/")`). Open the tab: one row per test call with the verb badge, `Class.test_name`, the path as called, `file:line`, "direct" and, when the test asserts a literal status, "expects 200". The summary reads "Reached by N tests (D direct, I indirect)".
T4. Double-click, Enter, F4-style "Go to Test" in the tab's toolbar: the editor opens at the call in the test file. Edit the test file so the line moved, then do it again: it still lands inside the file.
T5. Flask project (`client.get(url_for("bp.view", id=1))`) and Django project (`self.client.get(reverse("shop:detail", args=[1]))`, `self.url` set in `setUp`): the same rows appear for the endpoints those names point at.
T6. A test marked `@pytest.mark.skip` or `xfail` is listed dimmed with "skipped" / "expected to fail" and is not counted; `skipif` is listed with "skipped on a condition" and counted.
T7. Empty states: an endpoint no test calls shows "No test found for this endpoint" and an "Untested" chip when the answer is certain. In a project whose tests build URLs from a settings prefix (`f"{settings.API_V1_STR}/items/"`) there is no "Untested" chip; the line says how many test calls could not be attributed. "Not Attributed..." in the tab toolbar lists those calls with the reason (computed URL, unresolved constant, no endpoint, several endpoints, helper); choosing one opens it. A project without tests shows "No test files found".
T8. Editing a test file (add a call, change the path) updates the list within about a second without a new progress task blocking the window; closing the tool window leaves no background CPU use.
T9. Select a row: the area below the list shows "Quick test: <name>" with the request the test makes (verb, path values, query from `params=`, headers from `headers=`, body from `json=` / `data=` / `content=`), and the status the test expects. Anything the code does not spell out (a variable in the body, a computed header) is named under "Not determined by the test code and left empty" and the field is empty. Nothing is sent by selecting.
T10. Edit the body, a query row or a path value, then press "Run Quick Test" (or Ctrl+Enter in that area). The answer appears below: status line with time and size, "Test expects 201 - got 201: matches" (green) or "...got 422: differs" (red), the body in the JSON viewer with search, the headers tab, cURL. Edit the test file in the editor: it does not change. Select another test and come back: your edits are still there (until the IDE is restarted).
T11. A non-local base URL (an environment such as https://api.example.org) asks for confirmation once; a host confirmed on the Request page is not asked again here, and the other way round.
T12. "Open in Request Tab": the edited request moves into the form of the Request page (path values, query, headers, body); sending from there works as usual.
T13. "Copy Command" puts `pytest path::Class::test` (Django test cases: `python manage.py test app.tests.Class.test`) on the clipboard; pasted into a shell of the project it runs that test. "Run Test in the IDE" starts the test through the IDE's own run configuration and the normal Run window shows it; with no interpreter or runner configured it says so and points at Copy Command (the Django test runner needs the paid edition or pytest-django, as for the gutter icon).
T14. Light, dark and a third-party theme: rows, chips, verdict colors and the empty states stay readable; verbs keep their colors in selected rows.
T15. Narrow window (about 400 px) and wide window: the list and the quick-test area stay usable (scrolling, the splitter between list and area).
T16. A project with two test clients for different frameworks, or tests that call a mounted sub-application, lists nothing it is not sure about: ambiguous calls (the same path in several endpoints) appear under "Not Attributed..." with "matches N endpoints", never under an endpoint.

Not testable headless for this part: the real Run window and run configuration producers (only that a configuration can be asked for), real focus and Ctrl+Enter routing, painting in selected rows, and the look of the verdict line.
