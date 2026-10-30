package com.routegenie.frameworks.common

import com.intellij.psi.PsiElement
import com.jetbrains.python.psi.PyAssignmentStatement
import com.jetbrains.python.psi.PyBoolLiteralExpression
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyExpressionStatement
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyKeywordArgument
import com.jetbrains.python.psi.PyListLiteralExpression
import com.jetbrains.python.psi.PyParenthesizedExpression
import com.jetbrains.python.psi.PyRecursiveElementVisitor
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.PySetLiteralExpression
import com.jetbrains.python.psi.PyStarArgument
import com.jetbrains.python.psi.PyReturnStatement
import com.jetbrains.python.psi.PyTargetExpression
import com.jetbrains.python.psi.PyTypeDeclarationStatement
import com.jetbrains.python.psi.PyTupleExpression
import com.intellij.openapi.progress.ProgressManager
import com.routegenie.Limits
import com.routegenie.discovery.core.ConstFact
import com.routegenie.discovery.core.AttrFact
import com.routegenie.discovery.core.AttrKind
import com.routegenie.discovery.core.Expr
import com.routegenie.discovery.core.SettingsCatalog
import com.routegenie.discovery.core.SettingsClassFact
import com.routegenie.discovery.core.SettingsInstanceFact

/**
 * One pass over a Python file producing [PyFileFacts]. Frameworks are recognized later from import origins, never by
 * resolving, because the project may not have the library installed. The facts depend on the text of the file only:
 * values stay unevaluated [Expr] trees and imports stay as written, so another file changing never invalidates them.
 */
object PyFactsExtractor {
    /**
     * Null when the file holds nothing this provider could use. With [asDependency] a file that declares no routes still
     * returns its imports and constants, because other files' routes may be built from them.
     */
    fun extract(file: PyFile, spec: PyExtractionSpec, asDependency: Boolean = false): PyFileFacts? {
        val imports = PyExprBuilder.importsOf(file, recursive = true)
        val collector = Collector(file, spec, imports)
        file.accept(collector)
        val facts = collector.build(PyExprBuilder.constsOf(file))
        if (!facts.isEmpty) return facts
        val supports = facts.imports.isNotEmpty() || facts.stars.isNotEmpty() || facts.consts.isNotEmpty() || facts.definesSettings
        return if (asDependency && supports) facts else null
    }

    private class Collector(
        file: PyFile,
        private val spec: PyExtractionSpec,
        private val importTable: PyExprBuilder.Imports,
    ) : PyRecursiveElementVisitor() {
        private val lineStarts = lineStartsOf(file.viewProvider.contents)
        private val imports = importTable.bindings
        private var visited = 0
        private val assignments = ArrayList<AssignFact>()
        private val decorators = ArrayList<DecoratorFact>()
        private val calls = ArrayList<CallStmtFact>()
        private val classes = ArrayList<ClassFact>()
        private val scopes = ArrayList<String>()
        private val classNames = ArrayList<String>()
        private val settingClasses = ArrayList<SettingsClassFact>()
        private val settingInstances = ArrayList<SettingsInstanceFact>()

        fun build(consts: List<ConstFact>) = PyFileFacts(
            providerId = spec.providerId,
            imports = imports,
            stars = importTable.stars,
            consts = consts,
            assignments = assignments,
            decorators = decorators,
            calls = calls,
            classes = classes,
            settingClasses = settingClasses,
            settingInstances = settingInstances,
        )

        private val scope: String get() = scopes.joinToString(".")

        override fun visitElement(element: PsiElement) {
            // A generated file of a megabyte must stay cancellable while it is walked.
            if (++visited % Limits.CANCEL_CHECK_EVERY_NODES == 0) ProgressManager.checkCanceled()
            super.visitElement(element)
        }

        override fun visitPyAssignmentStatement(node: PyAssignmentStatement) {
            val target = node.targets.singleOrNull() as? PyTargetExpression
            val name = target?.name
            val call = node.assignedValue as? PyCallExpression
            val callee = call?.let { refOf(it.callee) }
            if (name != null && call != null && callee != null && isConstructor(callee)) {
                assignments += AssignFact(name, scope, callFact(call, 0), posOf(node))
            }
            if (name != null && callee != null && scopes.isEmpty() && settingInstances.size < Limits.MAX_SETTINGS_INSTANCES_PER_FILE &&
                (SettingsCatalog.looksLikeSettingsFactory(name) || SettingsCatalog.looksLikeSettingsFactory(callee.last))
            ) {
                settingInstances += SettingsInstanceFact(name.intern(), callee.toString().intern(), isFunction = false)
            }
            super.visitPyAssignmentStatement(node)
        }

        /** The last name matches a constructor, or the name is an import alias of one (`from fastapi import APIRouter as AR`). */
        private fun isConstructor(callee: RefFact): Boolean {
            if (callee.last in spec.constructorNames) return true
            if (callee.parts.size != 1) return false
            return imports.any { it.local == callee.head && (it.name ?: it.module).substringAfterLast('.') in spec.constructorNames }
        }

        override fun visitPyExpressionStatement(node: PyExpressionStatement) {
            val call = node.expression as? PyCallExpression
            val callee = call?.callee as? PyReferenceExpression
            val receiver = refOf(callee?.qualifier)
            val method = callee?.referencedName
            if (call != null && receiver != null && method != null && method in spec.callMethods) {
                calls += CallStmtFact(scope, receiver, method, callFact(call, 0), posOf(node))
            }
            super.visitPyExpressionStatement(node)
        }

        override fun visitPyFunction(node: PyFunction) {
            if (scopes.isEmpty()) settingsFactoryOf(node)?.let { settingInstances += it }
            val handler = (classNames + node.name.orEmpty()).joinToString(".")
            for (decorator in node.decoratorList?.decorators.orEmpty()) {
                val callee = decorator.callee as? PyReferenceExpression ?: continue
                val method = callee.referencedName ?: continue
                val receiver = refOf(callee.qualifier) ?: continue
                if (method !in spec.decoratorMethods) continue
                decorators += DecoratorFact(scope, receiver, method, callFact(decorator, 0), handler, posOf(decorator))
            }
            scopes += node.name.orEmpty()
            super.visitPyFunction(node)
            scopes.removeAt(scopes.lastIndex)
        }

        override fun visitPyClass(node: PyClass) {
            val name = node.name.orEmpty()
            if (scopes.isEmpty() && settingClasses.size < Limits.MAX_SETTINGS_CLASSES_PER_FILE) settingsClassOf(node)?.let { settingClasses += it }
            if (spec.capturesClasses) {
                val bases = node.superClassExpressions.mapNotNull { refOf(it) }
                if (bases.isNotEmpty()) {
                    classes += ClassFact(name, scope, bases, node.methods.mapNotNull { it.name }, null, posOf(node), methodsAttribute(node))
                }
            }
            scopes += name
            classNames += name
            super.visitPyClass(node)
            classNames.removeAt(classNames.lastIndex)
            scopes.removeAt(scopes.lastIndex)
        }

        /** `def get_settings(): return Settings()` (also under `@lru_cache`): the function stands for the class it returns. */
        private fun settingsFactoryOf(node: PyFunction): SettingsInstanceFact? {
            val name = node.name ?: return null
            if (!SettingsCatalog.looksLikeSettingsFactory(name)) return null
            val returned = node.statementList.statements.filterIsInstance<PyReturnStatement>().singleOrNull()?.expression
            val callee = refOf((returned as? PyCallExpression)?.callee) ?: return null
            return SettingsInstanceFact(name.intern(), callee.toString().intern(), isFunction = true)
        }

        /** A class that looks like a settings holder, with the attributes written in its body and their defaults. */
        private fun settingsClassOf(node: PyClass): SettingsClassFact? {
            val name = node.name ?: return null
            val bases = node.superClassExpressions.mapNotNull { refOf(it)?.toString() }
            if (!SettingsCatalog.looksLikeSettingsClass(name, bases)) return null
            val attrs = ArrayList<AttrFact>()
            for (statement in node.statementList.statements) {
                if (attrs.size >= Limits.MAX_SETTINGS_ATTRS_PER_CLASS) break
                when (statement) {
                    is PyTypeDeclarationStatement -> (statement.target as? PyTargetExpression)?.name?.let {
                        attrs += AttrFact(it.intern(), AttrKind.NONE, null, statement.textRange.startOffset, lineOf(statement.textRange.startOffset))
                    }
                    is PyAssignmentStatement -> {
                        val target = statement.targets.singleOrNull() as? PyTargetExpression ?: continue
                        attrs += attrOf(target.name ?: continue, statement.assignedValue, statement)
                    }
                }
            }
            return SettingsClassFact(name.intern(), bases.map { it.intern() }, attrs)
        }

        /** `X = "/a"`, `X: str = "/a"`, `X = Field(default="/a")`, `X = Field(default_factory=f)`. */
        private fun attrOf(name: String, value: PyExpression?, at: PsiElement): AttrFact {
            val offset = at.textRange.startOffset
            val line = lineOf(offset)
            val call = unwrap(value) as? PyCallExpression
            if (call != null && refOf(call.callee)?.last == SettingsCatalog.FIELD) {
                val arguments = call.arguments
                val factory = arguments.filterIsInstance<PyKeywordArgument>().any { it.keyword == SettingsCatalog.FIELD_FACTORY }
                val default = arguments.filterIsInstance<PyKeywordArgument>().firstOrNull { it.keyword == SettingsCatalog.FIELD_DEFAULT }?.valueExpression
                    ?: arguments.firstOrNull { it !is PyKeywordArgument && it !is PyStarArgument }
                return when {
                    factory -> AttrFact(name.intern(), AttrKind.FACTORY, null, offset, line)
                    default != null -> AttrFact(name.intern(), AttrKind.VALUE, PyExprBuilder.build(default), offset, line)
                    else -> AttrFact(name.intern(), AttrKind.NONE, null, offset, line)
                }
            }
            return AttrFact(name.intern(), AttrKind.VALUE, PyExprBuilder.build(value), offset, line)
        }

        private fun methodsAttribute(node: PyClass): List<Expr>? {
            for (statement in node.statementList.statements) {
                val assignment = statement as? PyAssignmentStatement ?: continue
                if ((assignment.targets.singleOrNull() as? PyTargetExpression)?.name != METHODS_ATTRIBUTE) continue
                return elementsOf(assignment.assignedValue)?.map { PyExprBuilder.build(it) }
            }
            return null
        }

        private fun callFact(call: PyCallExpression, depth: Int): CallFact {
            val callee = refOf(call.callee)
            val methodName = callee?.last
            val args = ArrayList<ArgFact>()
            val keywords = LinkedHashMap<String, ArgFact>()
            var dynamic = false
            for (argument in call.arguments) {
                when (argument) {
                    is PyKeywordArgument -> {
                        val key = argument.keyword ?: continue
                        keywords[key] = argFact(argument.valueExpression, key in spec.stringKeywords, depth)
                    }
                    is PyStarArgument -> dynamic = true
                    else -> {
                        val first = args.isEmpty() && methodName != null && methodName in spec.pathFirstMethods
                        args += argFact(argument, first, depth)
                    }
                }
            }
            return CallFact(callee, args, keywords, dynamic)
        }

        /** [evaluateName]: also compute a string value for a plain name, which is otherwise kept as a reference only. */
        private fun argFact(expression: PyExpression?, evaluateName: Boolean, depth: Int): ArgFact {
            val e = unwrap(expression) ?: return ArgFact()
            return when {
                e is PyBoolLiteralExpression -> ArgFact(bool = e.value)
                e is PyListLiteralExpression || e is PyTupleExpression || e is PySetLiteralExpression ->
                    ArgFact(exprs = elementsOf(e)?.map { PyExprBuilder.build(it) })
                e is PyReferenceExpression ->
                    ArgFact(ref = refOf(e), expr = if (evaluateName) PyExprBuilder.build(e) else null)
                e is PyCallExpression -> ArgFact(
                    call = if (depth < MAX_CALL_DEPTH) callFact(e, depth + 1) else null,
                    expr = if (evaluateName) PyExprBuilder.build(e) else null,
                )
                else -> ArgFact(expr = PyExprBuilder.build(e))
            }
        }

        private fun elementsOf(expression: PyExpression?): Array<PyExpression>? = when (val e = unwrap(expression)) {
            is PyListLiteralExpression -> e.elements
            is PyTupleExpression -> e.elements
            is PySetLiteralExpression -> e.elements
            else -> null
        }

        private fun unwrap(expression: PyExpression?): PyExpression? {
            var current = expression
            while (current is PyParenthesizedExpression) current = current.containedExpression
            return current
        }

        private fun posOf(element: PsiElement): SourcePos {
            val offset = element.textRange.startOffset
            return SourcePos(offset, lineOf(offset))
        }

        private fun lineOf(offset: Int): Int {
            val index = lineStarts.binarySearch(offset)
            return (if (index >= 0) index else -index - 2) + 1
        }
    }

    /** `a.b.c` as parts; null when the chain is not made of plain names (a call or subscript in the middle). */
    private fun refOf(expression: PsiElement?): RefFact? {
        val parts = ArrayList<String>()
        var current: PsiElement? = expression
        while (current is PyReferenceExpression) {
            parts += (current.referencedName ?: return null).intern()
            current = current.qualifier
        }
        if (current != null || parts.isEmpty()) return null
        parts.reverse()
        return RefFact(parts)
    }

    private fun lineStartsOf(text: CharSequence): IntArray {
        var starts = IntArray(INITIAL_LINES)
        var count = 1
        for (i in text.indices) {
            if (text[i] != '\n') continue
            if (count == starts.size) starts = starts.copyOf(count * 2)
            starts[count++] = i + 1
        }
        return starts.copyOf(count)
    }

    private const val METHODS_ATTRIBUTE = "methods"
    private const val MAX_CALL_DEPTH = 1
    private const val INITIAL_LINES = 256
}
