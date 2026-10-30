# Changelog

The newest section is what the IDE shows as the plugin's change notes and what the GitHub release says. Write it for the
person who uses the plugin, newest section first, one `## ` heading per section.

## Latest

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
