package com.routegenie.discovery.core

import com.routegenie.Limits
import com.routegenie.model.DiagnosticCode
import com.routegenie.model.PathSyntax
import com.routegenie.model.SettingSuggestion
import com.routegenie.model.StrValue

/**
 * Evaluates [Expr] trees to strings without running code: literals, `+`, f-strings, `%` with `%s`/`%d`, `str.format` with
 * `{}`, `{0}` and `{name}`, module-level constants of the same file, and names imported from other project files (`from x
 * import N`, star imports, `import x` / `x.N`, `from pkg import module` / `module.N`, relative imports). Pure: the files
 * come from the [ModuleTable], so the same facts give the same value in a test and in the IDE.
 *
 * Correct or absent: anything it cannot prove is [StrValue.Unknown] or [StrValue.Partial]; it never guesses.
 */
class ExprEvaluator(private val table: ModuleTable, private val context: SettingsContext = SettingsContext.NONE) {
    private val settingsIndex by lazy(LazyThreadSafetyMode.NONE) { SettingsIndex(table) }

    /**
     * [settings] false evaluates without the settings machinery: no pin is applied and no setting is noticed. Used for values
     * that are not path pieces (a `ROOT_URLCONF`, an include's module name).
     */
    fun evaluate(owner: ModuleEntry, expr: Expr?, settings: Boolean = true): StrValue {
        if (expr == null) return unknown()
        return Run(settings).eval(owner, expr, text = false)
    }

    private fun unknown(): StrValue = StrValue.Unknown(DiagnosticCode.DYNAMIC_VALUE)

    private fun partialHole(): StrValue = StrValue.Partial(PathSyntax.UNKNOWN_SEGMENT, DiagnosticCode.DYNAMIC_VALUE)

    /** State of one top-level evaluation: the chain of names being resolved, to stop cycles and runaway depth. */
    private inner class Run(private val settings: Boolean) {
        private val visiting = HashSet<String>()

        /** [text] widens the result to numbers, which only matters where Python converts with `str()` itself. */
        fun eval(owner: ModuleEntry, e: Expr, text: Boolean): StrValue = when (e) {
            is Lit -> StrValue.Known(e.text)
            is IntLit -> if (text) StrValue.Known(e.digits) else unknown()
            is Ref -> evalReference(owner, e.parts, text)
            is Add -> eval(owner, e.left, text = false) + eval(owner, e.right, text = false)
            is FStr -> evalFString(owner, e)
            is Interp -> eval(owner, e.value, text = true)
            is Percent -> evalPercent(owner, e)
            is Format -> evalFormat(owner, e)
            is SettingRead -> evalSetting(e)
            Hole -> partialHole()
            Opaque -> unknown()
        }

        // --- settings -------------------------------------------------------------------------------------------------

        /** `os.environ.get("X", "/d")`: the pin when there is one, else unknown, with the written literal default noted. */
        private fun evalSetting(e: SettingRead): StrValue {
            if (!settings) return unknown()
            pinned(e.canonical)?.let { return it }
            val default = (e.default as? Lit)?.let { SettingSuggestion(it.text, SettingsCatalog.defaultKind(e.canonical), null) }
            return unresolvedSetting(e.canonical, default)
        }

        private fun pinned(expression: String): StrValue? {
            val value = context.pins[expression] ?: return null
            return StrValue.Known(value, listOf(expression))
        }

        private fun unresolvedSetting(expression: String, suggestion: SettingSuggestion?): StrValue {
            if (context.assumeEmpty) return StrValue.Known("")
            context.collector?.note(expression, suggestion)
            return StrValue.Unknown(DiagnosticCode.DYNAMIC_VALUE, listOf(expression))
        }

        /** A name or attribute chain that nothing in the project defines: a pin, or a setting the user may be asked about. */
        private fun settingOrUnknown(owner: ModuleEntry, parts: List<String>): StrValue {
            if (!settings) return unknown()
            val expression = parts.joinToString(".")
            pinned(expression)?.let { return it }
            if (parts.size < 2) return unknown()
            val found = settingsIndex.find(owner, parts.dropLast(1), parts.last())
            if (found == null && parts[parts.size - 2] !in SettingsCatalog.OBJECT_NAMES) return unknown()
            return unresolvedSetting(expression, found?.let { settingsIndex.suggestionOf(it) })
        }

        private fun evalFString(owner: ModuleEntry, e: FStr): StrValue {
            var acc: StrValue? = null
            for (part in e.parts) {
                val value = eval(owner, part, text = false)
                acc = acc?.plus(value) ?: value
            }
            return acc ?: StrValue.Known("")
        }

        private fun evalPercent(owner: ModuleEntry, e: Percent): StrValue {
            val format = eval(owner, e.format, text = false)
            if (format !is StrValue.Known) return unknown()
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
                        val arg = e.args.getOrNull(next++) ?: return unknown()
                        val v = eval(owner, arg, text = true)
                        out.append(v.text)
                        if (!v.isExact) {
                            exact = false
                            reason = reason ?: v.reason
                        }
                    }
                    else -> return unknown()
                }
            }
            if (next != e.args.size) return unknown()
            return build(out.toString(), exact, reason)
        }

        private fun evalFormat(owner: ModuleEntry, e: Format): StrValue {
            val template = eval(owner, e.template, text = false)
            if (template !is StrValue.Known) return unknown()
            val out = StringBuilder()
            var exact = true
            var reason: DiagnosticCode? = null
            var auto = 0
            var i = 0
            val text = template.text
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '{' && text.getOrNull(i + 1) == '{' -> { out.append('{'); i += 2 }
                    c == '}' && text.getOrNull(i + 1) == '}' -> { out.append('}'); i += 2 }
                    c == '}' -> return unknown()
                    c == '{' -> {
                        val end = text.indexOf('}', i)
                        if (end < 0) return unknown()
                        val field = text.substring(i + 1, end)
                        i = end + 1
                        if (field.any { it == ':' || it == '!' || it == '.' || it == '[' }) return unknown()
                        val arg = when {
                            field.isEmpty() -> e.positional.getOrNull(auto++)
                            field.all { it.isDigit() } -> e.positional.getOrNull(field.toInt())
                            else -> e.named[field]
                        } ?: return unknown()
                        val v = eval(owner, arg, text = true)
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

        private fun evalReference(owner: ModuleEntry, parts: List<String>, text: Boolean): StrValue {
            val name = parts.last()
            val value = if (parts.size == 1) {
                lookupName(owner, name, text)
            } else {
                moduleOf(owner, parts.dropLast(1))?.let { lookupName(it, name, text) }
            }
            // Only a name that nothing defines (or whose value is unknown) can be a setting: a known value always wins.
            if (value == null || value is StrValue.Unknown && value.unresolved.isEmpty()) {
                val setting = settingOrUnknown(owner, parts)
                if (setting !is StrValue.Unknown || setting.unresolved.isNotEmpty()) return setting
            }
            return value ?: unknown()
        }

        /** Null means "no such name here" so callers can try star imports; [StrValue] means found. */
        private fun lookupName(file: ModuleEntry, name: String, text: Boolean): StrValue? {
            val key = "${file.path}#$name"
            if (key in visiting || visiting.size >= Limits.MAX_VALUE_RESOLVE_DEPTH) return unknown()
            visiting += key
            try {
                val assigned = file.constsNamed(name)
                if (assigned.isNotEmpty()) {
                    // A second assignment makes the value depend on execution order, so it is not asserted.
                    val only = assigned.singleOrNull() ?: return unknown()
                    return only.value?.let { eval(file, it, text) } ?: unknown()
                }
                importedName(file, name, text)?.let { return it }
                return viaStarImports(file, name, text)
            } finally {
                visiting -= key
            }
        }

        private fun importedName(file: ModuleEntry, name: String, text: Boolean): StrValue? {
            val binding = file.fromImportOf(name) ?: return null
            val original = binding.name ?: return unknown()
            val source = table.resolve(file, binding.level, binding.module) ?: return unknown()
            return lookupName(source, original, text) ?: unknown()
        }

        private fun viaStarImports(file: ModuleEntry, name: String, text: Boolean): StrValue? {
            for (star in file.source.stars) {
                val source = table.resolve(file, star.level, star.module) ?: continue
                lookupName(source, name, text)?.let { return it }
            }
            return null
        }

        /** The module a dotted qualifier refers to, via `import a.b [as c]` or `from p import module`. */
        private fun moduleOf(file: ModuleEntry, parts: List<String>): ModuleEntry? {
            val head = parts.first()
            if (parts.size == 1) {
                val binding = file.fromImportOf(head)
                if (binding != null) return table.resolve(file, binding.level, binding.module, binding.name)
            }
            for (binding in file.source.imports) {
                val dotted = binding.dotted ?: continue
                if (binding.aliased) {
                    if (binding.local == head && parts.size == 1) return table.find(dotted)
                } else if (dotted == parts.joinToString(".")) {
                    return table.find(dotted)
                }
            }
            return null
        }
    }
}
