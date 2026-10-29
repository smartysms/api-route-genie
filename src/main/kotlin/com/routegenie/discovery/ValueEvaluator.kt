package com.routegenie.discovery

import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyFile
import com.routegenie.discovery.core.PyValueEvaluator
import com.routegenie.model.StrValue

/**
 * Computes the string value of an expression without running the code. Extractors use it for route paths, prefixes and
 * names, so how constants, concatenation, f-strings and imported names are handled is decided in exactly one place.
 */
interface ValueEvaluator {
    /**
     * May return [StrValue.Partial] or [StrValue.Unknown]; that is information for the caller, not something to show.
     * Extractors must treat anything but [StrValue.Known] as "unresolved": omit the route and report a Diagnostic. The
     * discovery service drops partial endpoints as a last gate, but relying on it is a provider bug.
     */
    fun evaluate(expression: PyExpression?): StrValue
}

object ValueEvaluators {
    /**
     * The evaluator to use for expressions of [file]. It is stateless and follows names into other project files by
     * itself, so the same instance serves every file.
     */
    @Suppress("UNUSED_PARAMETER")
    fun forFile(file: PyFile): ValueEvaluator = PyValueEvaluator
}
