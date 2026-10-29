package com.routegenie.schema.psi

import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyBinaryExpression
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyIfStatement
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.PyReturnStatement
import com.jetbrains.python.psi.PyStatement
import com.routegenie.Limits
import com.routegenie.model.HttpMethod

/** How a view method hands the request body to a serializer or a form. */
internal class BodyUse(
    /** The class that receives the body; null when the view's own `get_serializer()` / `get_form()` does. */
    val cls: PyClass?,
    val partial: Boolean,
)

/**
 * Static reading of view code: which serializer or form receives `request.data`, and which class a `get_serializer_class()`
 * returns for a known action or HTTP method. Anything that depends on runtime values ends as "unknown", never as a guess.
 */
internal class ViewScan(private val mapper: TypeMapper) {
    private val symbols = mapper.session.symbols

    /**
     * The uses of the request body inside [function]: a call `X(data=request.data)` (or `X(request.POST)`) of a class accepted
     * by [accepts], or `self.get_serializer(data=request.data)` / `self.get_form(...)` ([viewMethod]).
     */
    fun uses(function: PyFunction, viewMethod: String, bodyAttributes: Set<String>, accepts: (PyClass) -> Boolean): List<BodyUse> {
        val file = function.containingFile as? PyFile ?: return emptyList()
        val out = ArrayList<BodyUse>()
        for (call in PsiTreeUtil.findChildrenOfType(function, PyCallExpression::class.java)) {
            mapper.session.check()
            val callee = call.callee as? PyReferenceExpression ?: continue
            if (!receivesBody(call, bodyAttributes)) continue
            val partial = PyLiterals.bool(PyLiterals.keyword(call, DrfSchemaCatalog.KEY_PARTIAL)) == true
            if (callee.qualifier?.text == SELF && callee.referencedName == viewMethod) {
                out += BodyUse(null, partial)
                continue
            }
            val cls = (symbols.resolve(callee, file) as? Sym.Cls)?.cls ?: continue
            if (accepts(cls)) out += BodyUse(cls, partial)
        }
        return out
    }

    /** Whether any argument of [call] is `request.data`, `request.POST`, `self.request.data` and so on. */
    private fun receivesBody(call: PyCallExpression, attributes: Set<String>): Boolean = call.arguments.any { argument ->
        val value = (argument as? com.jetbrains.python.psi.PyKeywordArgument)?.valueExpression ?: argument
        val reference = PyLiterals.unparen(value) as? PyReferenceExpression
        reference != null && reference.referencedName in attributes && reference.qualifier?.text.let { it == REQUEST || it == "$SELF.$REQUEST" }
    }

    /** Collapses several uses into the one class they agree on; null (with [ambiguous] set) when they disagree. */
    class Decision(val use: BodyUse?, val ambiguous: Boolean)

    fun decide(uses: List<BodyUse>): Decision {
        if (uses.isEmpty()) return Decision(null, false)
        val distinct = uses.map { it.cls }.distinct()
        if (distinct.size > 1) return Decision(null, true)
        return Decision(BodyUse(distinct.single(), uses.any { it.partial }), false)
    }

    // --- get_serializer_class / get_form_class ------------------------------------------------------------------------------

    sealed interface Returned {
        class Class(val cls: PyClass) : Returned
        data object Super : Returned
        data object Unknown : Returned
    }

    /**
     * What [function] returns when called for [action] / [verb]: follows `if self.action == ...` chains and plain returns;
     * null when no return is reached.
     */
    fun returned(function: PyFunction, action: String?, verb: HttpMethod): Returned? {
        val file = function.containingFile as? PyFile ?: return Returned.Unknown
        return evaluate(function.statementList.statements.toList(), file, action, verb, 0)
    }

    private fun evaluate(statements: List<PyStatement>, file: PyFile, action: String?, verb: HttpMethod, depth: Int): Returned? {
        if (depth > Limits.SCHEMA_MAX_NESTING) return Returned.Unknown
        for (statement in statements) {
            when (statement) {
                is PyReturnStatement -> return returnValue(statement.expression, file)
                is PyIfStatement -> {
                    val parts = listOf(statement.ifPart) + statement.elifParts
                    var decided = false
                    for (part in parts) {
                        when (condition(part.condition, action, verb)) {
                            true -> {
                                evaluate(part.statementList.statements.toList(), file, action, verb, depth + 1)?.let { return it }
                                decided = true
                            }
                            false -> Unit
                            null -> return Returned.Unknown
                        }
                        if (decided) break
                    }
                    if (!decided) statement.elsePart?.let { part ->
                        evaluate(part.statementList.statements.toList(), file, action, verb, depth + 1)?.let { return it }
                    }
                }
                else -> Unit
            }
        }
        return null
    }

    private fun returnValue(expression: PyExpression?, file: PyFile): Returned {
        val e = PyLiterals.unparen(expression) ?: return Returned.Unknown
        if (e is PyCallExpression && e.callee?.text?.startsWith(SUPER) == true) return Returned.Super
        return (symbols.resolve(e, file) as? Sym.Cls)?.let { Returned.Class(it.cls) } ?: Returned.Unknown
    }

    /** `self.action == "create"`, `self.action in ("a", "b")`, `self.request.method == "POST"`; null when it cannot be told. */
    private fun condition(expression: PyExpression?, action: String?, verb: HttpMethod): Boolean? {
        val e = PyLiterals.unparen(expression) as? PyBinaryExpression ?: return null
        val subject = when (e.leftExpression?.text) {
            "$SELF.${DrfSchemaCatalog.SELF_ACTION}" -> action
            "$SELF.${DrfSchemaCatalog.SELF_REQUEST}.${DrfSchemaCatalog.REQUEST_METHOD}", "${DrfSchemaCatalog.SELF_REQUEST}.${DrfSchemaCatalog.REQUEST_METHOD}" -> verb.name
            else -> return null
        } ?: return null
        val right = e.rightExpression
        val literal = PyLiterals.string(right)
        val options = literal?.let { listOf(it) } ?: PyLiterals.strings(right) ?: return null
        return when {
            e.isOperator("==") -> subject == literal
            e.isOperator("!=") -> literal?.let { subject != it }
            e.isOperator("in") -> subject in options
            e.isOperator("not in") -> subject !in options
            else -> null
        }
    }

    private companion object {
        const val SELF = "self"
        const val REQUEST = "request"
        const val SUPER = "super("
    }
}
