package com.routegenie.ui.request

import com.routegenie.RouteGenieBundle
import com.routegenie.client.ModeOverride
import com.routegenie.client.ResponseMode
import com.routegenie.client.curl.CurlProblem
import com.routegenie.client.curl.CurlRejection
import com.routegenie.client.curl.CurlShell

/**
 * Text for the enums of the request panel. Each key is written out in full (not built from the enum name) so the bundle test
 * can see that it is used and a rename cannot leave a dead or missing key behind.
 */
internal object RequestMessages {
    private fun msg(key: String, vararg args: Any) = RouteGenieBundle.message(key, *args)

    fun shell(s: CurlShell): String = msg(when (s) {
        CurlShell.BASH -> "req.curl.shell.bash"
        CurlShell.CMD -> "req.curl.shell.cmd"
        CurlShell.POWERSHELL -> "req.curl.shell.powershell"
    })

    fun override(o: ModeOverride): String = msg(when (o) {
        ModeOverride.AUTO -> "req.mode.auto"
        ModeOverride.FORCE_STREAM -> "req.mode.force_stream"
        ModeOverride.FORCE_BUFFERED -> "req.mode.force_buffered"
    })

    fun modeLabel(m: ResponseMode): String = msg(when (m) {
        ResponseMode.BUFFERED -> "req.mode.buffered.label"
        ResponseMode.SSE -> "req.mode.sse.label"
        ResponseMode.NDJSON -> "req.mode.ndjson.label"
        ResponseMode.RAW_STREAM -> "req.mode.raw_stream.label"
    })

    fun auth(k: AuthKind): String = msg(when (k) {
        AuthKind.INHERIT -> "req.auth.kind.inherit"
        AuthKind.NONE -> "req.auth.kind.none"
        AuthKind.BEARER -> "req.auth.kind.bearer"
        AuthKind.BASIC -> "req.auth.kind.basic"
        AuthKind.API_KEY -> "req.auth.kind.apikey"
    })

    fun body(t: BodyType): String = msg(when (t) {
        BodyType.NONE -> "req.body.kind.none"
        BodyType.JSON -> "req.body.kind.json"
        BodyType.TEXT -> "req.body.kind.text"
        BodyType.FORM -> "req.body.kind.form"
        BodyType.MULTIPART -> "req.body.kind.multipart"
    })

    fun layout(l: WorkspaceLayout): String = msg(when (l) {
        WorkspaceLayout.AUTO -> "req.layout.auto"
        WorkspaceLayout.STACKED -> "req.layout.stacked"
        WorkspaceLayout.SIDE_BY_SIDE -> "req.layout.side_by_side"
    })

    fun problem(p: CurlProblem, shell: CurlShell): String = msg(when (p) {
        CurlProblem.NUL_CHARACTER -> "req.curl.problem.nul"
        CurlProblem.LONE_SURROGATE -> "req.curl.problem.surrogate"
        CurlProblem.HEADER_LINE_BREAK -> "req.curl.problem.header_break"
        CurlProblem.HEADER_NAME_INVALID -> "req.curl.problem.header_name"
        CurlProblem.ARGUMENT_NOT_REPRESENTABLE -> "req.curl.problem.argument"
    }, shell(shell))

    fun rejection(r: CurlRejection): String = msg(when (r) {
        CurlRejection.EMPTY -> "req.curl.rejected.empty"
        CurlRejection.NOT_CURL -> "req.curl.rejected.not_curl"
        CurlRejection.POWERSHELL_WEB_CMDLET -> "req.curl.rejected.web_cmdlet"
        CurlRejection.NO_URL -> "req.curl.rejected.no_url"
    })
}
