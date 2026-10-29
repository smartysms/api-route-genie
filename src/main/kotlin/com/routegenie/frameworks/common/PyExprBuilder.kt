package com.routegenie.frameworks.common

import com.intellij.openapi.progress.ProgressManager
import com.jetbrains.python.psi.PyAssignmentStatement
import com.jetbrains.python.psi.PyAugAssignmentStatement
import com.jetbrains.python.psi.PyBinaryExpression
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFormattedStringElement
import com.jetbrains.python.psi.PyFromImportStatement
import com.jetbrains.python.psi.PyImportStatement
import com.jetbrains.python.psi.PyKeywordArgument
import com.jetbrains.python.psi.PyNumericLiteralExpression
import com.jetbrains.python.psi.PyParenthesizedExpression
import com.jetbrains.python.psi.PyPlainStringElement
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.PyStarArgument
import com.jetbrains.python.psi.PyStringLiteralExpression
import com.jetbrains.python.psi.PyTargetExpression
import com.jetbrains.python.psi.PyTupleExpression
import com.routegenie.discovery.core.Add
import com.routegenie.discovery.core.ConstFact
import com.routegenie.discovery.core.Expr
import com.routegenie.discovery.core.FStr
import com.routegenie.discovery.core.Format
import com.routegenie.discovery.core.Hole
import com.routegenie.discovery.core.ImportBinding
import com.routegenie.discovery.core.IntLit
import com.routegenie.discovery.core.Interp
import com.routegenie.discovery.core.Lit
import com.routegenie.discovery.core.Opaque
import com.routegenie.discovery.core.Percent
import com.routegenie.discovery.core.Ref
import com.routegenie.discovery.core.StarBinding

/**
 * Reads what one Python file says about string values and imports, without resolving or evaluating anything: the output
 * is the same for the same text, whatever other files exist. Library imports do not resolve in an SDK-less project, so
 * nothing here calls `resolve()`; evaluation happens later, in the pure [com.routegenie.discovery.core.ExprEvaluator].
 */
object PyExprBuilder {
    private const val FORMAT_METHOD = "format"
    private const val PLUS = "+"
    private const val PERCENT = "%"

    fun build(e: PyExpression?): Expr {
        if (e == null) return Opaque
        return when (e) {
            is PyParenthesizedExpression -> e.containedExpression?.let { build(it) } ?: Opaque
            is PyStringLiteralExpression -> string(e)
            is PyNumericLiteralExpression -> if (e.isIntegerLiteral) IntLit(e.bigIntegerValue.toString()) else Opaque
            is PyBinaryExpression -> binary(e)
            is PyCallExpression -> format(e)
            is PyReferenceExpression -> reference(e)
            else -> Opaque
        }
    }

    // --- strings ---------------------------------------------------------------------------------------------------

    private fun string(e: PyStringLiteralExpression): Expr {
        val elements = e.stringElements
        if (elements.isEmpty()) return Opaque
        if (elements.none { it is PyFormattedStringElement }) {
            // stringValue joins implicit concatenation and decodes escapes; bytes literals are not strings.
            return if (elements.all { it is PyPlainStringElement && !it.prefix.contains('b', ignoreCase = true) }) Lit(e.stringValue) else Opaque
        }
        val parts = elements.map { element ->
            when (element) {
                is PyFormattedStringElement -> formatted(element)
                is PyPlainStringElement -> plainPart(element)
                else -> Opaque
            }
        }
        return parts.singleOrNull() ?: FStr(parts)
    }

    /** A plain element next to an f-string element: only escape-free text is taken literally. */
    private fun plainPart(element: PyPlainStringElement): Expr =
        if (!element.isRaw && element.content.contains('\\')) Hole else Lit(element.content)

    private fun formatted(element: PyFormattedStringElement): Expr {
        val content = element.contentRange
        val raw = element.text
        var pos = content.startOffset
        val parts = ArrayList<Expr>()

        fun literal(from: Int, to: Int) {
            if (to <= from) return
            val chunk = raw.substring(from, to)
            // Doubled braces are the f-string escape for one literal brace.
            parts += if (!element.isRaw && chunk.contains('\\')) Hole else Lit(chunk.replace("{{", "{").replace("}}", "}"))
        }

        for (fragment in element.fragments.sortedBy { it.startOffsetInParent }) {
            val start = fragment.startOffsetInParent
            literal(pos, start)
            pos = start + fragment.textLength
            val expression = fragment.expression
            // {x!r}, {x:>5} and {x=} change the text Python produces, so only the bare form is evaluated.
            val inner = fragment.text.removePrefix("{").removeSuffix("}").trim()
            parts += if (expression == null || inner != expression.text.trim()) Opaque else Interp(build(expression))
        }
        literal(pos, content.endOffset)
        return FStr(parts)
    }

    // --- operators and calls -----------------------------------------------------------------------------------------

    private fun binary(e: PyBinaryExpression): Expr {
        val right = e.rightExpression ?: return Opaque
        return when {
            e.isOperator(PLUS) -> Add(build(e.leftExpression), build(right))
            e.isOperator(PERCENT) -> {
                val unwrapped = (right as? PyParenthesizedExpression)?.containedExpression ?: right
                val values = (unwrapped as? PyTupleExpression)?.elements?.toList() ?: listOf(unwrapped)
                Percent(build(e.leftExpression), values.map { build(it) })
            }
            else -> Opaque
        }
    }

    private fun format(call: PyCallExpression): Expr {
        val callee = call.callee as? PyReferenceExpression ?: return Opaque
        if (callee.referencedName != FORMAT_METHOD) return Opaque
        val qualifier = callee.qualifier ?: return Opaque
        val positional = ArrayList<Expr>()
        val named = LinkedHashMap<String, Expr>()
        for (argument in call.arguments) {
            when (argument) {
                is PyStarArgument -> return Opaque
                is PyKeywordArgument -> {
                    val name = argument.keyword ?: return Opaque
                    named[name] = build(argument.valueExpression ?: return Opaque)
                }
                else -> positional += build(argument)
            }
        }
        return Format(build(qualifier), positional, named)
    }

    private fun reference(ref: PyReferenceExpression): Expr {
        val parts = dottedParts(ref) ?: return Opaque
        return Ref(parts)
    }

    /** `a.b.c` as [a, b, c]; null when the chain is anything but plain names. */
    fun dottedParts(e: PyExpression): List<String>? {
        val parts = ArrayList<String>()
        var current: PyExpression? = e
        while (current is PyReferenceExpression) {
            parts += (current.referencedName ?: return null).intern()
            current = current.qualifier
        }
        if (current != null || parts.isEmpty()) return null
        parts.reverse()
        return parts
    }

    // --- file level: imports and constants -----------------------------------------------------------------------------

    /** The import table of [file] as written: relative imports keep their dot count. */
    class Imports(val bindings: List<ImportBinding>, val stars: List<StarBinding>)

    /** [recursive]: also imports inside functions, classes and conditions; otherwise only statements directly in the file. */
    fun importsOf(file: PyFile, recursive: Boolean): Imports {
        val bindings = ArrayList<ImportBinding>()
        val stars = ArrayList<StarBinding>()

        fun plain(node: PyImportStatement) {
            for (element in node.importElements) {
                val qualified = element.importedQName ?: continue
                val alias = element.asName
                val dotted = qualified.toString().intern()
                if (alias != null) {
                    bindings += ImportBinding(alias.intern(), dotted, null, 0, dotted, aliased = true)
                } else {
                    val first = (qualified.firstComponent ?: continue).intern()
                    bindings += ImportBinding(first, first, null, 0, dotted, aliased = false)
                }
            }
        }

        fun from(node: PyFromImportStatement) {
            val module = (node.importSourceQName?.toString() ?: "").intern()
            if (node.isStarImport) {
                stars += StarBinding(module, node.relativeLevel)
                return
            }
            for (element in node.importElements) {
                val name = element.importedQName?.toString()?.intern() ?: continue
                bindings += ImportBinding((element.visibleName ?: name).intern(), module, name, node.relativeLevel)
            }
        }

        if (recursive) {
            file.accept(object : com.jetbrains.python.psi.PyRecursiveElementVisitor() {
                override fun visitPyImportStatement(node: PyImportStatement) = plain(node)
                override fun visitPyFromImportStatement(node: PyFromImportStatement) = from(node)
            })
        } else {
            for (statement in file.statements) {
                when (statement) {
                    is PyImportStatement -> plain(statement)
                    is PyFromImportStatement -> from(statement)
                }
            }
        }
        return Imports(bindings, stars)
    }

    /** Top-level `NAME = value` (every statement directly in the file; nothing inside `if`, `def` or `class`). */
    fun constsOf(file: PyFile): List<ConstFact> {
        val found = ArrayList<ConstFact>()
        for (statement in file.statements) {
            ProgressManager.checkCanceled()
            when (statement) {
                is PyAssignmentStatement -> {
                    val names = statement.targets.filterIsInstance<PyTargetExpression>().mapNotNull { it.name }
                    if (names.isNotEmpty()) {
                        val value = statement.assignedValue?.let { build(it) }
                        names.forEach { found += ConstFact(it.intern(), value) }
                    }
                }
                is PyAugAssignmentStatement -> {
                    val name = (statement.target as? PyReferenceExpression)?.referencedName
                    if (name != null) found += ConstFact(name.intern(), null)
                }
            }
        }
        return found
    }
}
