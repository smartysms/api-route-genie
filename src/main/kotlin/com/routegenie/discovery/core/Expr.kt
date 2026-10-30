package com.routegenie.discovery.core

/**
 * A string-valued Python expression exactly as the file writes it, with nothing evaluated. Extraction stores these in the
 * per-file facts; the pure linker evaluates them with [ExprEvaluator] once it knows the other files. Because a tree
 * depends on the text of its own file only, equal text always gives equal facts, whatever else is in the project.
 */
sealed interface Expr

/** A string literal (implicit concatenation joined, escapes decoded). */
data class Lit(val text: String) : Expr

/** An integer literal; it only becomes text where Python converts it itself (f-string fields, `%s`, `.format`). */
data class IntLit(val digits: String) : Expr

/** A name or attribute chain: `API`, `settings.PREFIX`. */
data class Ref(val parts: List<String>) : Expr

/** `left + right`. */
data class Add(val left: Expr, val right: Expr) : Expr

/** An f-string (or several adjacent string literals of which some are f-strings); parts are joined in order. */
data class FStr(val parts: List<Expr>) : Expr

/** A `{field}` of an f-string: converted with `str()`, so integers count as text here. */
data class Interp(val value: Expr) : Expr

/** `format % args`; [args] holds the elements of a tuple, or the one argument. */
data class Percent(val format: Expr, val args: List<Expr>) : Expr

/** `template.format(*positional, **named)`. */
data class Format(val template: Expr, val positional: List<Expr>, val named: Map<String, Expr>) : Expr

/**
 * A read of a setting that only the running process knows: `os.environ.get("X", "/d")`, `os.getenv("X")`, `os.environ["X"]`,
 * `app.config["X"]`. [canonical] is the one text used to pin it (`os.environ["X"]`); [default] is the written default.
 * Without a pin it evaluates to unknown, like any dynamic value.
 */
data class SettingRead(val canonical: String, val default: Expr?) : Expr

/** Anything this evaluator never reads (calls, subscripts, bytes, floats, ...): the value is unknown. */
data object Opaque : Expr

/** A piece of an f-string that is only partly trustworthy (a literal with escapes): known to exist, not to be exact. */
data object Hole : Expr

/** Number of nodes in the tree; used to estimate the memory of cached facts. */
fun Expr.nodeCount(): Int = when (this) {
    is Lit, is IntLit, Opaque, Hole -> 1
    is SettingRead -> 1 + (default?.nodeCount() ?: 0)
    is Ref -> 1 + parts.size
    is Add -> 1 + left.nodeCount() + right.nodeCount()
    is FStr -> 1 + parts.sumOf { it.nodeCount() }
    is Interp -> 1 + value.nodeCount()
    is Percent -> 1 + format.nodeCount() + args.sumOf { it.nodeCount() }
    is Format -> 1 + template.nodeCount() + positional.sumOf { it.nodeCount() } + named.values.sumOf { it.nodeCount() }
}

/** Every dotted name the tree mentions, so that the files those names come from can be found. */
fun Expr.collectRefs(out: MutableCollection<List<String>>) {
    when (this) {
        is Ref -> out += parts
        is Add -> { left.collectRefs(out); right.collectRefs(out) }
        is FStr -> parts.forEach { it.collectRefs(out) }
        is Interp -> value.collectRefs(out)
        is Percent -> { format.collectRefs(out); args.forEach { it.collectRefs(out) } }
        is Format -> { template.collectRefs(out); positional.forEach { it.collectRefs(out) }; named.values.forEach { it.collectRefs(out) } }
        is SettingRead -> default?.collectRefs(out)
        is Lit, is IntLit, Opaque, Hole -> Unit
    }
}
