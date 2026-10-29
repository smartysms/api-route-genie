package com.routegenie.frameworks.common

import com.routegenie.model.Diagnostic
import com.routegenie.model.DiagnosticCode
import com.routegenie.model.HttpMethod
import com.routegenie.model.PathSyntax
import com.routegenie.model.Severity
import com.routegenie.model.SourceRef
import com.routegenie.model.StrValue

/** Small helpers the two linkers share; all pure. */
object LinkSupport {
    fun sourceOf(file: PyRouteFacts, pos: SourcePos) = SourceRef(file.path, pos.offset, pos.line)

    /** A warning for every value that is not exact; [code] overrides the reason the value carries when not null. */
    fun problemsOf(value: StrValue?, code: DiagnosticCode?, source: SourceRef): List<Diagnostic> {
        if (value == null || value.isExact) return emptyList()
        return listOf(Diagnostic(Severity.WARNING, code ?: value.reason ?: DiagnosticCode.DYNAMIC_VALUE, source = source))
    }

    fun diagnostic(code: DiagnosticCode, source: SourceRef, severity: Severity = Severity.WARNING, vararg args: String) =
        Diagnostic(severity, code, args.toList(), source)

    /** The exact tag texts of a `tags=[...]` argument; computed tags are left out because they do not affect the path. */
    fun tagsOf(arg: ArgFact?): List<String> = arg?.list.orEmpty().filter { it.isExact }.map { it.text }

    /** The concrete verbs of a `methods=[...]` argument and whether every element could be read. */
    fun methodsOf(arg: ArgFact): Pair<List<HttpMethod>, Boolean> {
        val list = arg.list ?: return emptyList<HttpMethod>() to false
        val methods = ArrayList<HttpMethod>()
        var complete = true
        for (element in list) {
            val method = if (element.isExact) HttpMethod.parse(element.text) else null
            if (method == null) complete = false else methods += method
        }
        return methods.distinct() to complete
    }

    /** The methods a route primitive registers; null when a `methods` argument cannot be read exactly. */
    fun methodsFor(primitive: Primitive, call: CallFact): List<HttpMethod>? {
        primitive.fixedMethod?.let { return listOf(it) }
        val arg = primitive.methods?.of(call) ?: return primitive.defaultMethod?.let { listOf(it) }
        val (read, complete) = methodsOf(arg)
        return if (complete && read.isNotEmpty()) read else null
    }

    /** Joins like [prefix] + [rest] but treats a missing prefix as empty. */
    fun concat(prefix: StrValue?, rest: StrValue): StrValue = if (prefix == null) rest else prefix + rest

    /** The displayed path: never empty, unknown parts as the shared marker. */
    fun displayPath(value: StrValue): String = value.text.ifEmpty { ROOT }

    private const val ROOT = "/"

    val EMPTY: StrValue = StrValue.Known("")
    const val UNKNOWN = PathSyntax.UNKNOWN_SEGMENT
}
