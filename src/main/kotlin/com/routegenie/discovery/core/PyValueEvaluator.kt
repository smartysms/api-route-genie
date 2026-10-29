package com.routegenie.discovery.core

import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.jetbrains.python.psi.PyAssignmentStatement
import com.jetbrains.python.psi.PyAugAssignmentStatement
import com.jetbrains.python.psi.PyBinaryExpression
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFormattedStringElement
import com.jetbrains.python.psi.PyKeywordArgument
import com.jetbrains.python.psi.PyNumericLiteralExpression
import com.jetbrains.python.psi.PyParenthesizedExpression
import com.jetbrains.python.psi.PyPlainStringElement
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.PyStarArgument
import com.jetbrains.python.psi.PyStringLiteralExpression
import com.jetbrains.python.psi.PyTargetExpression
import com.jetbrains.python.psi.PyTupleExpression
import com.routegenie.Limits
import com.routegenie.discovery.ValueEvaluator
import com.routegenie.model.DiagnosticCode
import com.routegenie.model.PathSyntax
import com.routegenie.model.StrValue

/**
 * Static string evaluation over PSI without an SDK: it reads the file text/AST only and never calls `resolve()` on a
 * reference, because library imports do not resolve in an SDK-less project and name lookups must behave the same
 * everywhere. Anything it cannot prove becomes [StrValue.Unknown] or [StrValue.Partial]; it never guesses.
 *
 * Supported: literals (including implicit concatenation), `+`, f-strings, `%` with `%s`/`%d`, `str.format` with `{}`,
 * `{0}` and `{name}`, module-level constants of the same file, names imported from project files (`from x import N`,
 * star imports, `import x` / `x.N`, `from pkg import module` / `module.N`, relative imports).
 */
object PyValueEvaluator : ValueEvaluator {
    override fun evaluate(expression: PyExpression?): StrValue {
        if (expression == null) return unknown()
        return Run().eval(expression, text = false)
    }

    private fun unknown(): StrValue = StrValue.Unknown(DiagnosticCode.DYNAMIC_VALUE)

    /** State of one top-level evaluation: the chain of names being resolved, to stop cycles and runaway depth. */
    private class Run {
        private val visiting = HashSet<String>()

        /** [text] widens the result to numbers, which only matters where Python converts with `str()` itself. */
        fun eval(e: PyExpression, text: Boolean): StrValue = when (e) {
            is PyParenthesizedExpression -> e.containedExpression?.let { eval(it, text) } ?: unknown()
            is PyStringLiteralExpression -> evalString(e)
            is PyNumericLiteralExpression -> if (text && e.isIntegerLiteral) StrValue.Known(e.bigIntegerValue.toString()) else unknown()
            is PyBinaryExpression -> evalBinary(e)
            is PyCallExpression -> evalCall(e)
            is PyReferenceExpression -> evalReference(e, text)
            else -> unknown()
        }

        private fun evalString(e: PyStringLiteralExpression): StrValue {
            val elements = e.stringElements
            if (elements.isEmpty()) return unknown()
            if (elements.none { it is PyFormattedStringElement }) {
                // stringValue joins implicit concatenation and decodes escapes; bytes literals are not strings.
                return if (elements.all { it is PyPlainStringElement && !it.prefix.contains('b', ignoreCase = true) }) {
                    StrValue.Known(e.stringValue)
                } else {
                    unknown()
                }
            }
            var acc: StrValue? = null
            for (element in elements) {
                val part = when (element) {
                    is PyFormattedStringElement -> evalFormatted(element)
                    is PyPlainStringElement -> plainPart(element)
                    else -> unknown()
                }
                acc = acc?.plus(part) ?: part
            }
            return acc ?: unknown()
        }

        /** A plain element next to an f-string element: only escape-free text is taken literally. */
        private fun plainPart(element: PyPlainStringElement): StrValue =
            if (!element.isRaw && element.content.contains('\\')) {
                StrValue.Partial(PathSyntax.UNKNOWN_SEGMENT, DiagnosticCode.DYNAMIC_VALUE)
            } else {
                StrValue.Known(element.content)
            }

        private fun evalFormatted(element: PyFormattedStringElement): StrValue {
            val content = element.contentRange
            val raw = element.text
            var pos = content.startOffset
            var acc: StrValue? = null

            fun add(part: StrValue) {
                acc = acc?.plus(part) ?: part
            }

            fun literal(from: Int, to: Int) {
                if (to <= from) return
                val chunk = raw.substring(from, to)
                if (!element.isRaw && chunk.contains('\\')) {
                    add(StrValue.Partial(PathSyntax.UNKNOWN_SEGMENT, DiagnosticCode.DYNAMIC_VALUE))
                } else {
                    // Doubled braces are the f-string escape for one literal brace.
                    add(StrValue.Known(chunk.replace("{{", "{").replace("}}", "}")))
                }
            }

            for (fragment in element.fragments.sortedBy { it.startOffsetInParent }) {
                val start = fragment.startOffsetInParent
                literal(pos, start)
                pos = start + fragment.textLength
                add(evalFragment(fragment))
            }
            literal(pos, content.endOffset)
            return acc ?: StrValue.Known("")
        }

        private fun evalFragment(fragment: com.jetbrains.python.psi.PyFStringFragment): StrValue {
            val expression = fragment.expression ?: return unknown()
            // {x!r}, {x:>5} and {x=} change the text Python produces, so only the bare form is evaluated.
            val inner = fragment.text.removePrefix("{").removeSuffix("}").trim()
            if (inner != expression.text.trim()) return unknown()
            return eval(expression, text = true)
        }

        private fun evalBinary(e: PyBinaryExpression): StrValue {
            val left = e.leftExpression
            val right = e.rightExpression ?: return unknown()
            return when {
                e.isOperator("+") -> {
                    // Numbers are not coerced by `+`, so str + int stays unknown (it would raise at runtime).
                    val l = eval(left, text = false)
                    val r = eval(right, text = false)
                    l + r
                }
                e.isOperator("%") -> evalPercent(left, right)
                else -> unknown()
            }
        }

        private fun evalPercent(formatExpr: PyExpression, args: PyExpression): StrValue {
            val format = eval(formatExpr, text = false)
            if (format !is StrValue.Known) return unknown()
            val unwrapped = (args as? PyParenthesizedExpression)?.containedExpression ?: args
            val values = (unwrapped as? PyTupleExpression)?.elements?.toList() ?: listOf(unwrapped)
            val out = StringBuilder()
            val template = format.text
            var next = 0
            var i = 0
            var exact = true
            var reason: DiagnosticCode? = null
            while (i < template.length) {
                val c = template[i]
                if (c != '%') {
                    out.append(c)
                    i++
                    continue
                }
                val spec = template.getOrNull(i + 1) ?: return unknown()
                i += 2
                when (spec) {
                    '%' -> out.append('%')
                    's', 'd' -> {
                        val arg = values.getOrNull(next++) ?: return unknown()
                        val v = eval(arg, text = true)
                        out.append(v.text)
                        if (!v.isExact) {
                            exact = false
                            reason = reason ?: v.reason
                        }
                    }
                    else -> return unknown()
                }
            }
            if (next != values.size) return unknown()
            return build(out.toString(), exact, reason)
        }

        private fun evalCall(call: PyCallExpression): StrValue {
            val callee = call.callee as? PyReferenceExpression ?: return unknown()
            if (callee.referencedName != FORMAT_METHOD) return unknown()
            val qualifier = callee.qualifier ?: return unknown()
            val template = eval(qualifier, text = false)
            if (template !is StrValue.Known) return unknown()

            val positional = ArrayList<PyExpression>()
            val named = HashMap<String, PyExpression>()
            for (argument in call.arguments) {
                when (argument) {
                    is PyStarArgument -> return unknown()
                    is PyKeywordArgument -> {
                        val name = argument.keyword ?: return unknown()
                        named[name] = argument.valueExpression ?: return unknown()
                    }
                    else -> positional += argument
                }
            }
            return formatTemplate(template.text, positional, named)
        }

        private fun formatTemplate(
            template: String,
            positional: List<PyExpression>,
            named: Map<String, PyExpression>,
        ): StrValue {
            val out = StringBuilder()
            var exact = true
            var reason: DiagnosticCode? = null
            var auto = 0
            var i = 0
            while (i < template.length) {
                val c = template[i]
                when {
                    c == '{' && template.getOrNull(i + 1) == '{' -> { out.append('{'); i += 2 }
                    c == '}' && template.getOrNull(i + 1) == '}' -> { out.append('}'); i += 2 }
                    c == '}' -> return unknown()
                    c == '{' -> {
                        val end = template.indexOf('}', i)
                        if (end < 0) return unknown()
                        val field = template.substring(i + 1, end)
                        i = end + 1
                        if (field.any { it == ':' || it == '!' || it == '.' || it == '[' }) return unknown()
                        val arg = when {
                            field.isEmpty() -> positional.getOrNull(auto++)
                            field.all { it.isDigit() } -> positional.getOrNull(field.toInt())
                            else -> named[field]
                        } ?: return unknown()
                        val v = eval(arg, text = true)
                        out.append(v.text)
                        if (!v.isExact) {
                            exact = false
                            reason = reason ?: v.reason
                        }
                    }
                    else -> { out.append(c); i++ }
                }
            }
            return build(out.toString(), exact, reason)
        }

        private fun build(text: String, exact: Boolean, reason: DiagnosticCode?): StrValue =
            if (exact) StrValue.Known(text) else StrValue.Partial(text, reason ?: DiagnosticCode.DYNAMIC_VALUE)

        // --- names and attribute chains -------------------------------------------------------------------------

        private fun evalReference(ref: PyReferenceExpression, text: Boolean): StrValue {
            val name = ref.referencedName ?: return unknown()
            val file = ref.containingFile as? PyFile ?: return unknown()
            val qualifier = ref.qualifier
            val value = if (qualifier == null) {
                lookupName(file, name, text)
            } else {
                val parts = dottedParts(qualifier) ?: return unknown()
                moduleFile(file, parts)?.let { lookupName(it, name, text) }
            }
            return value ?: unknown()
        }

        /** `a.b.c` as [a, b, c]; null when the qualifier is anything but plain names. */
        private fun dottedParts(e: PyExpression): List<String>? {
            val ref = e as? PyReferenceExpression ?: return null
            val name = ref.referencedName ?: return null
            val qualifier = ref.qualifier ?: return listOf(name)
            return dottedParts(qualifier)?.plus(name)
        }

        /** Null means "no such name here" so callers can try star imports; [StrValue] means found. */
        private fun lookupName(file: PyFile, name: String, text: Boolean): StrValue? {
            val key = "${file.virtualFile?.path ?: file.name}#$name"
            if (key in visiting || visiting.size >= Limits.MAX_VALUE_RESOLVE_DEPTH) return unknown()
            visiting += key
            try {
                val assigned = topLevelAssignments(file, name)
                if (assigned.isNotEmpty()) {
                    // A second assignment makes the value depend on execution order, so it is not asserted.
                    val only = assigned.singleOrNull() ?: return unknown()
                    return only?.let { eval(it, text) } ?: unknown()
                }
                importedName(file, name, text)?.let { return it }
                return viaStarImports(file, name, text)
            } finally {
                visiting -= key
            }
        }

        /**
         * Right-hand sides of top-level assignments to [name]. A null entry marks a binding whose value cannot be read
         * (augmented assignment), which makes the name ambiguous.
         */
        private fun topLevelAssignments(file: PyFile, name: String): List<PyExpression?> {
            val found = ArrayList<PyExpression?>()
            for (statement in file.statements) {
                when (statement) {
                    is PyAssignmentStatement ->
                        if (statement.targets.any { it is PyTargetExpression && it.name == name }) found += statement.assignedValue
                    is PyAugAssignmentStatement ->
                        if ((statement.target as? PyReferenceExpression)?.referencedName == name) found += null
                }
            }
            return found
        }

        private fun importedName(file: PyFile, name: String, text: Boolean): StrValue? {
            for (statement in file.fromImports) {
                if (statement.isStarImport) continue
                val element = statement.importElements.firstOrNull { it.visibleName == name } ?: continue
                val original = element.importedQName?.lastComponent ?: return unknown()
                val source = sourceFile(file, statement) ?: return unknown()
                return lookupName(source, original, text) ?: unknown()
            }
            return null
        }

        private fun sourceFile(file: PyFile, statement: com.jetbrains.python.psi.PyFromImportStatement): PyFile? =
            findModule(file, statement.relativeLevel, statement.importSourceQName?.components.orEmpty())

        private fun viaStarImports(file: PyFile, name: String, text: Boolean): StrValue? {
            for (statement in file.fromImports) {
                if (!statement.isStarImport) continue
                val source = sourceFile(file, statement) ?: continue
                lookupName(source, name, text)?.let { return it }
            }
            return null
        }

        /** The module a dotted qualifier refers to, via `import a.b [as c]` or `from p import module`. */
        private fun moduleFile(file: PyFile, parts: List<String>): PyFile? {
            val head = parts.first()
            for (statement in file.fromImports) {
                if (statement.isStarImport || parts.size != 1) continue
                val element = statement.importElements.firstOrNull { it.visibleName == head } ?: continue
                val name = element.importedQName?.components ?: return null
                val source = statement.importSourceQName?.components.orEmpty()
                return findModule(file, statement.relativeLevel, source + name)
            }
            for (element in file.importTargets) {
                val qName = element.importedQName ?: continue
                val alias = element.asName
                if (alias != null) {
                    if (alias == head && parts.size == 1) return findModule(file, 0, qName.components)
                } else if (qName.components == parts) {
                    return findModule(file, 0, parts)
                }
            }
            return null
        }

        /**
         * Finds the project file of a module by its dotted name, without the resolve machinery (which needs an SDK and
         * internal APIs): relative imports walk the directories, absolute ones match the path suffix in the project.
         */
        private fun findModule(from: PyFile, level: Int, components: List<String>): PyFile? {
            if (components.isEmpty()) return null
            if (level > 0) {
                var dir = from.containingDirectory
                repeat(level - 1) { dir = dir?.parentDirectory }
                for (part in components.dropLast(1)) dir = dir?.findSubdirectory(part)
                val last = components.last()
                return (dir?.findFile(last + PY_SUFFIX) ?: dir?.findSubdirectory(last)?.findFile(INIT_FILE)) as? PyFile
            }
            val project = from.project
            val scope = GlobalSearchScope.projectScope(project)
            val relative = components.joinToString("/")
            val manager = PsiManager.getInstance(project)
            val module = FilenameIndex.getVirtualFilesByName(components.last() + PY_SUFFIX, scope)
                .filter { it.path.endsWith("/$relative$PY_SUFFIX") }
            val package_ = FilenameIndex.getVirtualFilesByName(INIT_FILE, scope)
                .filter { it.path.endsWith("/$relative/$INIT_FILE") }
            val found = (module + package_).minByOrNull { it.path } ?: return null
            return manager.findFile(found) as? PyFile
        }
    }

    private const val FORMAT_METHOD = "format"
    private const val INIT_FILE = "__init__.py"
    private const val PY_SUFFIX = ".py"
}
