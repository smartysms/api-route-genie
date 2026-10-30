package com.routegenie.ui.tests

import com.routegenie.client.UrlSupport
import com.routegenie.model.Endpoint
import com.routegenie.model.PathSyntax
import com.routegenie.testmap.BodyLiteral
import com.routegenie.testmap.TestCall
import com.routegenie.ui.request.BodyType
import com.routegenie.ui.request.FormRow
import com.routegenie.ui.request.RequestForm

/** The parts of a test's request that the code does not spell out, so they are left empty in the editor. */
enum class NotDetermined(val bundleKey: String) {
    PATH("tests.exec.part.path"),
    QUERY("tests.exec.part.query"),
    HEADERS("tests.exec.part.headers"),
    BODY("tests.exec.part.body"),
}

/**
 * Turns what a test call says into the form of the Request page, as far as the code determines it: the verb, the path
 * parameters from the literal path, the query from `params=`, the headers from `headers=`, the body from `json=`, `data=` or
 * `content=`. Anything not fully literal stays empty and is reported in [Built.notDetermined]; nothing is guessed.
 */
object QuickTestForm {
    class Built(val form: RequestForm, val notDetermined: List<NotDetermined>)

    fun build(endpoint: Endpoint, call: TestCall): Built {
        val form = RequestForm(endpoint)
        val missing = ArrayList<NotDetermined>()
        if (form.methodIsChoosable && call.verb.isConcrete) form.method = call.verb

        val values = pathValues(endpoint, call.path)
        for (name in form.pathValues.keys) form.pathValues[name] = values[name].orEmpty()
        if (form.pathValues.keys.any { form.pathValues[it].isNullOrEmpty() }) missing += NotDetermined.PATH

        val request = call.request
        if (request.query == null) missing += NotDetermined.QUERY else form.query = request.query.map { FormRow(it.first, it.second) }
        if (request.headers == null) missing += NotDetermined.HEADERS else form.headers = request.headers.map { FormRow(it.first, it.second) }
        when (val body = request.body) {
            is BodyLiteral.Json -> { form.bodyKind = BodyType.JSON; form.bodyText = body.text }
            is BodyLiteral.Form -> { form.bodyKind = BodyType.FORM; form.formFields = body.fields.map { FormRow(it.first, it.second) } }
            is BodyLiteral.Text -> { form.bodyKind = BodyType.TEXT; form.bodyText = body.text }
            null -> if (request.bodyNotDetermined) missing += NotDetermined.BODY
        }
        return Built(form, missing)
    }

    /** Values of the endpoint's `{name}` parameters read from the literal [calledPath]; a computed or unknown segment gives no value. */
    internal fun pathValues(endpoint: Endpoint, calledPath: String): Map<String, String> {
        if (endpoint.pathParams.isEmpty()) return emptyMap()
        val names = ArrayList<String>()
        val regex = StringBuilder()
        var last = 0
        for (m in PLACEHOLDER.findAll(endpoint.path)) {
            regex.append(Regex.escape(endpoint.path.substring(last, m.range.first)))
            val name = m.groupValues[1]
            names += name
            regex.append(if (endpoint.pathParams.firstOrNull { it.name == name }?.type == REST_TYPE) "(.+)" else "([^/]+)")
            last = m.range.last + 1
        }
        regex.append(Regex.escape(endpoint.path.substring(last)))
        val trimmed = if (calledPath.length > 1 && calledPath.endsWith("/") != endpoint.path.endsWith("/")) calledPath.trimEnd('/') + if (endpoint.path.endsWith("/")) "/" else "" else calledPath
        val match = Regex(regex.toString()).matchEntire(trimmed) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        names.forEachIndexed { i, name ->
            val raw = match.groupValues[i + 1]
            // A value that is itself a placeholder (route-name calls) or contains a computed part is not determined.
            if (!raw.contains('{') && !raw.contains(PathSyntax.UNKNOWN_SEGMENT)) out[name] = UrlSupport.decode(raw)
        }
        return out
    }

    private val PLACEHOLDER = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)}""")
    private const val REST_TYPE = "path"
}
