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
