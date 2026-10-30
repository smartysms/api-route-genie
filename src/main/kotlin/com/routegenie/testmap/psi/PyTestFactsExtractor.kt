package com.routegenie.testmap.psi

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.jetbrains.python.PyTokenTypes
import com.jetbrains.python.psi.PyAssertStatement
import com.jetbrains.python.psi.PyAssignmentStatement
import com.jetbrains.python.psi.PyBinaryExpression
import com.jetbrains.python.psi.PyBoolLiteralExpression
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyDecorator
import com.jetbrains.python.psi.PyDictLiteralExpression
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyForStatement
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyIfStatement
import com.jetbrains.python.psi.PyKeyValueExpression
import com.jetbrains.python.psi.PyKeywordArgument
import com.jetbrains.python.psi.PyListLiteralExpression
import com.jetbrains.python.psi.PyNoneLiteralExpression
import com.jetbrains.python.psi.PyNumericLiteralExpression
import com.jetbrains.python.psi.PyParenthesizedExpression
import com.jetbrains.python.psi.PyPrefixExpression
import com.jetbrains.python.psi.PyRecursiveElementVisitor
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.PyReturnStatement
import com.jetbrains.python.psi.PySetLiteralExpression
import com.jetbrains.python.psi.PyStarArgument
import com.jetbrains.python.psi.PyStringLiteralExpression
import com.jetbrains.python.psi.PyTargetExpression
import com.jetbrains.python.psi.PyTryExceptStatement
import com.jetbrains.python.psi.PyTupleExpression
import com.jetbrains.python.psi.PyWhileStatement
import com.jetbrains.python.psi.PyWithItem
import com.jetbrains.python.psi.PyYieldExpression
import com.routegenie.Limits
import com.routegenie.discovery.core.Expr
import com.routegenie.discovery.core.Opaque
import com.routegenie.frameworks.common.PyExprBuilder
import com.routegenie.frameworks.common.RefFact
import com.routegenie.frameworks.common.SourcePos
import com.routegenie.testmap.ArgsStyle
import com.routegenie.testmap.ClientBindingFact
import com.routegenie.testmap.ClientCallFact
import com.routegenie.testmap.ClientSinks
import com.routegenie.testmap.LitValue
import com.routegenie.testmap.LocalAssignFact
import com.routegenie.testmap.NamedLit
import com.routegenie.testmap.StatusAssertFact
import com.routegenie.testmap.TestClassFact
import com.routegenie.testmap.TestConventions
import com.routegenie.testmap.TestFileFacts
import com.routegenie.testmap.TestFunctionFact
import com.routegenie.testmap.TestMark
import com.routegenie.testmap.UrlFact
import java.util.IdentityHashMap

/**
 * One pass over a Python test file producing [TestFileFacts]. The facts are a function of the file's text: nothing is
 * resolved, library imports are never followed (a light project has no SDK), and whether a receiver is a client or a function
 * is a test is decided later by the pure linker from the import table and the class bases.
 *
 * Calls are recorded when their method name is one of the sink table's call names and the receiver is a plain name chain or a
 * client constructor call; a call without a URL argument cannot be a request and is dropped here. The outermost function owns
 * every call in its body, nested functions and lambdas included.
 */
object PyTestFactsExtractor {
    fun extract(file: PyFile): TestFileFacts? {
        val imports = PyExprBuilder.importsOf(file, recursive = true)
        val collector = Collector(file, imports.bindings)
        file.accept(collector)
        val facts = collector.build(imports, PyExprBuilder.constsOf(file))
        return facts.takeUnless { it.isEmpty }
    }

    private class FunctionState(val qualified: String, val className: String?, val isSetup: Boolean) {
        val returned = ArrayList<String>()
        val returnedCtors = ArrayList<RefFact>()
        val lastCallByName = HashMap<String, Int>()
        var branchBase = 0
    }

    private class Collector(file: PyFile, private val importBindings: List<com.routegenie.discovery.core.ImportBinding>) : PyRecursiveElementVisitor() {
        private val lineStarts = lineStartsOf(file.viewProvider.contents)
        private var visited = 0
        private val classes = ArrayList<TestClassFact>()
        private val functions = ArrayList<TestFunctionFact>()
        private val bindings = ArrayList<ClientBindingFact>()
        private val calls = ArrayList<ClientCallFact>()
        private val asserts = ArrayList<StatusAssertFact>()
        private val assigns = ArrayList<LocalAssignFact>()
        private val classStack = ArrayList<String>()
        private var function: FunctionState? = null
        private var functionDepth = 0
        private var branchDepth = 0
        private var testLike = 0
        private val moduleLastCall = HashMap<String, Int>()

        /** `r = client.get(...)`: the call expression (by identity) and the variable its result goes to. */
        private val pendingAssign = IdentityHashMap<PsiElement, String>()

        fun build(imports: PyExprBuilder.Imports, consts: List<com.routegenie.discovery.core.ConstFact>) = TestFileFacts(
            imports.bindings, imports.stars, consts, classes, functions, bindings, calls, asserts, assigns, testLike,
        )

        override fun visitElement(element: PsiElement) {
            if (++visited % Limits.CANCEL_CHECK_EVERY_NODES == 0) ProgressManager.checkCanceled()
            super.visitElement(element)
        }

        // ---- structure ---------------------------------------------------------------------------------------------

        override fun visitPyClass(node: PyClass) {
            if (functionDepth > 0) {
                super.visitPyClass(node)
                return
            }
            val name = node.name.orEmpty()
            val qualified = (classStack + name).joinToString(".")
            val bases = node.superClassExpressions.mapNotNull { refOf(unwrap(it)) }
            val hasInit = node.findMethodByName(INIT, false, null) != null
            classes += TestClassFact(name, qualified, bases, hasInit, markOf(node.decoratorList?.decorators), posOf(node))
            classStack += name
            super.visitPyClass(node)
            classStack.removeAt(classStack.lastIndex)
        }

        override fun visitPyFunction(node: PyFunction) {
            if (functionDepth > 0) {
                functionDepth++
                super.visitPyFunction(node)
                functionDepth--
                return
            }
            val name = node.name.orEmpty()
            val className = classStack.joinToString(".").ifEmpty { null }
            val qualified = (classStack + name).joinToString(".")
            val decorators = node.decoratorList?.decorators
            val isFixture = decorators.orEmpty().any { refOf(it.callee)?.let(TestConventions::isFixtureDecorator) == true }
            val state = FunctionState(qualified, className, name in TestConventions.setupMethods)
            state.branchBase = branchDepth
            val callsBefore = calls.size
            val bindingsBefore = bindings.size
            val assignsBefore = assigns.size
            function = state
            functionDepth = 1
            super.visitPyFunction(node)
            functionDepth = 0
            function = null
            if (TestConventions.isTestFunctionName(name)) testLike++
            val keep = isFixture || calls.size > callsBefore || bindings.size > bindingsBefore || assigns.size > assignsBefore ||
                state.returned.isNotEmpty() || state.returnedCtors.isNotEmpty()
            if (keep) {
                val params = node.parameterList.parameters.mapNotNull { it.name }
                functions += TestFunctionFact(
                    name, className, qualified, params, node.isAsync, isFixture, markOf(decorators), state.returned, state.returnedCtors, posOf(node),
                )
            }
        }

        /** Decorator arguments hold no client calls, and route decorators in test files are not requests. */
        override fun visitPyDecorator(node: PyDecorator) = Unit

        override fun visitPyIfStatement(node: PyIfStatement) = branch { super.visitPyIfStatement(node) }
        override fun visitPyForStatement(node: PyForStatement) = branch { super.visitPyForStatement(node) }
        override fun visitPyWhileStatement(node: PyWhileStatement) = branch { super.visitPyWhileStatement(node) }
        override fun visitPyTryExceptStatement(node: PyTryExceptStatement) = branch { super.visitPyTryExceptStatement(node) }

        private inline fun branch(block: () -> Unit) {
            branchDepth++
            try {
                block()
            } finally {
                branchDepth--
            }
        }

        private fun markOf(decorators: Array<PyDecorator>?): TestMark {
            var mark = TestMark.NONE
            for (d in decorators.orEmpty()) refOf(d.callee)?.let { mark = mark.max(TestConventions.markOf(it)) }
            return mark
        }

        // ---- bindings and assignments ------------------------------------------------------------------------------

        override fun visitPyAssignmentStatement(node: PyAssignmentStatement) {
            val target = node.leftHandSideExpression as? PyTargetExpression
            val value = unwrapValue(node.assignedValue)
            if (target != null && node.targets.size == 1) {
                val name = targetName(target)
                if (name != null) {
                    val (scope, scopedName) = scopeOf(name)
                    val plain = !name.contains('.')
                    if (plain) lastCalls().remove(name)
                    if (value is PyCallExpression) {
                        ctorBinding(value, scopedName, scope)?.let { bindings += it }
                        if (plain && isCallCandidate(value)) pendingAssign[value] = name
                    }
                    if (value != null && isUrlish(value)) {
                        val url = urlFact(value)
                        if (!(url is UrlFact.Text && url.expr == Opaque) && assigns.size < Limits.TEST_CALLS_MAX_PER_FILE) {
                            assigns += LocalAssignFact(scopedName, scope, url, branchDepth > (function?.branchBase ?: 0), posOf(node))
                        }
                    }
                }
            }
            super.visitPyAssignmentStatement(node)
        }

        override fun visitPyWithItem(node: PyWithItem) {
            val call = unwrapValue(node.expression) as? PyCallExpression
            val target = node.target as? PyTargetExpression
            val name = target?.name
            if (call != null && name != null && target.qualifier == null) {
                ctorBinding(call, name, function?.qualified ?: MODULE_SCOPE)?.let { bindings += it }
            }
            super.visitPyWithItem(node)
        }

        private fun ctorBinding(call: PyCallExpression, name: String, scope: String): ClientBindingFact? {
            val ctor = refOf(call.callee) ?: return null
            if (!isClientConstructor(ctor)) return null
            val keywords = LinkedHashSet<String>()
            val callees = LinkedHashMap<String, RefFact>()
            var baseUrl: Expr? = null
            for (arg in call.arguments) {
                if (arg !is PyKeywordArgument) continue
                val key = arg.keyword ?: continue
                keywords += key
                (unwrapValue(arg.valueExpression) as? PyCallExpression)?.let { c -> refOf(c.callee)?.let { callees[key] = it } }
                if (key == BASE_URL) baseUrl = PyExprBuilder.build(arg.valueExpression)
            }
            return ClientBindingFact(name, scope, ctor, keywords, callees, baseUrl, posOf(call))
        }

        /** The scope and the name an assignment target is stored under: class attributes and setup-method fields belong to the class as `self.x`. */
        private fun scopeOf(name: String): Pair<String, String> {
            val fn = function
            if (fn == null) {
                val cls = classStack.joinToString(".")
                return if (cls.isEmpty()) MODULE_SCOPE to name else cls to "$SELF.$name"
            }
            if (name.startsWith("$SELF.") && fn.isSetup && fn.className != null) return fn.className to name
            return fn.qualified to name
        }

        /** `x`, or `self.x` / `cls.x` as `self.x`; null for any other attribute target. */
        private fun targetName(target: PyTargetExpression): String? {
            val qualifier = target.qualifier ?: return target.name
            val head = (qualifier as? PyReferenceExpression)?.takeIf { it.qualifier == null }?.referencedName
            return if (head == SELF || head == CLS) "$SELF.${target.name}" else null
        }

        private fun lastCalls(): MutableMap<String, Int> = function?.lastCallByName ?: moduleLastCall

        // ---- returns (fixtures) ------------------------------------------------------------------------------------

        override fun visitPyReturnStatement(node: PyReturnStatement) {
            returned(node.expression)
            super.visitPyReturnStatement(node)
        }

        override fun visitPyYieldExpression(node: PyYieldExpression) {
            returned(node.expression)
            super.visitPyYieldExpression(node)
        }

        private fun returned(expression: PyExpression?) {
            val fn = function ?: return
            if (functionDepth != 1) return
            when (val value = unwrapValue(expression)) {
                is PyReferenceExpression -> refOf(value)?.takeIf { it.parts.size == 1 }?.let { fn.returned += it.head }
                is PyCallExpression -> refOf(value.callee)?.takeIf(::isClientConstructor)?.let { fn.returnedCtors += it }
                else -> Unit
            }
        }

        // ---- client calls and status asserts -----------------------------------------------------------------------

        override fun visitPyCallExpression(node: PyCallExpression) {
            recordCall(node)
            recordEqualityAssert(node)
            super.visitPyCallExpression(node)
        }

        /** The last name is a client constructor or factory, or the name is an import alias of one (`from fastapi.testclient import TestClient as TC`). */
        private fun isClientConstructor(ref: RefFact): Boolean {
            if (ref.last in ClientSinks.constructorNames) return true
            if (ref.parts.size != 1) return false
            return importBindings.any { it.local == ref.head && it.name != null && it.name in ClientSinks.constructorNames }
        }

        /** The name a callee has in its library: `rev` imported as `from django.urls import reverse as rev` is `reverse`. */
        private fun canonicalName(ref: RefFact): String {
            if (ref.parts.size != 1) return ref.last
            return importBindings.firstOrNull { it.local == ref.head && it.name != null }?.name ?: ref.last
        }

        private fun isCallCandidate(call: PyCallExpression): Boolean {
            val callee = call.callee as? PyReferenceExpression ?: return false
            return callee.qualifier != null && callee.referencedName in ClientSinks.callMethodNames
        }

        private fun recordCall(node: PyCallExpression) {
            if (calls.size >= Limits.TEST_CALLS_MAX_PER_FILE) return
            val callee = node.callee as? PyReferenceExpression ?: return
            val method = callee.referencedName ?: return
            val shape = ClientSinks.callShapes[method] ?: return
            val qualifier = unwrapValue(callee.qualifier) ?: return
            var receiver: RefFact? = null
            var inline: RefFact? = null
            when (qualifier) {
                is PyReferenceExpression -> receiver = refOf(qualifier) ?: return
                is PyCallExpression -> inline = refOf(qualifier.callee)?.takeIf(::isClientConstructor) ?: return
                else -> return
            }
            val positional = ArrayList<PyExpression>()
            val keywords = LinkedHashMap<String, PyExpression?>()
            var starAt = Int.MAX_VALUE
            for (argument in node.arguments) {
                when (argument) {
                    is PyKeywordArgument -> argument.keyword?.let { keywords[it] = argument.valueExpression }
                    is PyStarArgument -> starAt = minOf(starAt, positional.size)
                    else -> positional += argument
                }
            }
            val urlArg = argOf(shape.url, positional, keywords, starAt)
            if (urlArg.absent) return
            val verbArg = shape.verb?.let { argOf(it, positional, keywords, starAt) }
            val parts = ArrayList<NamedLit>()
            for ((key, value) in keywords) {
                if (key in ClientSinks.partKeywords || key.startsWith(ClientSinks.HEADER_KEYWORD_PREFIX)) parts += NamedLit(key, litValue(value, 0))
            }
            if (shape.fixedVerb != null && positional.size > 1 && starAt > 1) parts += NamedLit("", litValue(positional[1], 0))
            val url = if (urlArg.expression == null) UrlFact.Dynamic else urlFact(urlArg.expression)
            val verb = verbArg?.expression?.let { PyExprBuilder.build(it) }
            val assignedTo = pendingAssign.remove(node)
            val pos = posOf(node)
            calls += ClientCallFact(receiver, inline, method, url, verb, function?.qualified ?: MODULE_SCOPE, assignedTo, parts, pos)
            if (assignedTo != null) lastCalls()[assignedTo] = pos.offset
        }

        private class Arg(val expression: PyExpression?, val absent: Boolean)

        /** The argument in [slot]: by keyword, else by position unless a `*args` comes first (then it is present but unknown). */
        private fun argOf(slot: com.routegenie.testmap.Slot, positional: List<PyExpression>, keywords: Map<String, PyExpression?>, starAt: Int): Arg {
            for (key in slot.keywords) if (key in keywords) return Arg(keywords[key], false)
            if (slot.index < 0) return Arg(null, true)
            if (slot.index >= starAt) return Arg(null, false)
            return positional.getOrNull(slot.index)?.let { Arg(it, false) } ?: Arg(null, true)
        }

        override fun visitPyAssertStatement(node: PyAssertStatement) {
            val condition = unwrap(node.arguments.firstOrNull())
            if (condition is PyBinaryExpression && condition.isOperator("==")) {
                statusAssert(condition.leftExpression, condition.rightExpression)
            }
            super.visitPyAssertStatement(node)
        }

        private fun recordEqualityAssert(node: PyCallExpression) {
            val callee = node.callee as? PyReferenceExpression ?: return
            if (callee.referencedName !in TestConventions.equalityAsserts) return
            val args = node.arguments
            if (args.size >= 2 && args[0] !is PyKeywordArgument && args[1] !is PyKeywordArgument) statusAssert(args[0], args[1])
        }

        /** `<response>.status_code == <int>` in either order, bound to the call that made the response. */
        private fun statusAssert(a: PyExpression?, b: PyExpression?) {
            val subject = statusSubject(a)
            val other: PyExpression?
            val offset: Int?
            if (subject != null) {
                offset = subject
                other = b
            } else {
                offset = statusSubject(b)
                other = a
            }
            if (offset == null) return
            val code = statusCode(other) ?: return
            asserts += StatusAssertFact(offset, code, function?.qualified ?: MODULE_SCOPE, posOf(other ?: return))
        }

        /** The start offset of the client call whose `.status_code` [e] reads, or null. */
        private fun statusSubject(e: PyExpression?): Int? {
            val ref = unwrap(e) as? PyReferenceExpression ?: return null
            if (ref.referencedName !in TestConventions.statusAttributes) return null
            return when (val q = unwrapValue(ref.qualifier)) {
                is PyReferenceExpression -> refOf(q)?.takeIf { it.parts.size == 1 }?.let { lastCalls()[it.head] }
                is PyCallExpression -> q.textRange.startOffset
                else -> null
            }
        }

        private fun statusCode(e: PyExpression?): Int? = when (val v = unwrap(e)) {
            is PyNumericLiteralExpression -> if (v.isIntegerLiteral) v.bigIntegerValue?.toInt() else null
            is PyReferenceExpression -> v.referencedName?.let { TestConventions.statusConstant.matchEntire(it)?.groupValues?.get(1)?.toInt() }
            else -> null
        }

        // ---- URLs and literal values -------------------------------------------------------------------------------

        private fun isUrlish(e: PyExpression): Boolean = when (e) {
            is PyStringLiteralExpression, is PyBinaryExpression -> true
            is PyCallExpression -> namedCall(e) != null || (e.callee as? PyReferenceExpression)?.referencedName == FORMAT
            else -> false
        }

        private fun urlFact(e: PyExpression?): UrlFact {
            val x = unwrapValue(e) ?: return UrlFact.Dynamic
            return when (x) {
                is PyCallExpression -> namedCall(x, null) ?: UrlFact.Text(PyExprBuilder.build(x))
                is PyReferenceExpression -> refOf(x)?.let { UrlFact.Name(it) } ?: UrlFact.Dynamic
                is PyBinaryExpression -> {
                    val left = unwrapValue(x.leftExpression) as? PyCallExpression
                    val named = if (x.isOperator("+") && left != null) namedCall(left, x.rightExpression?.let { PyExprBuilder.build(it) }) else null
                    named ?: UrlFact.Text(PyExprBuilder.build(x))
                }
                is PyStringLiteralExpression -> fstringWithCall(x) ?: UrlFact.Text(PyExprBuilder.build(x))
                is PyNumericLiteralExpression, is PyBoolLiteralExpression, is PyNoneLiteralExpression -> UrlFact.Dynamic
                else -> UrlFact.Text(PyExprBuilder.build(x))
            }
        }

        /** `f"{reverse('x')}?a=1"`: a name-resolving call first, the rest of the string as the suffix. */
        private fun fstringWithCall(x: PyStringLiteralExpression): UrlFact? {
            val element = x.stringElements.singleOrNull() as? com.jetbrains.python.psi.PyFormattedStringElement ?: return null
            val first = element.fragments.minByOrNull { it.startOffsetInParent } ?: return null
            if (first.startOffsetInParent != element.contentRange.startOffset) return null
            val call = unwrapValue(first.expression) as? PyCallExpression ?: return null
            val built = PyExprBuilder.build(x) as? com.routegenie.discovery.core.FStr ?: return null
            val rest = built.parts.drop(1)
            val suffix: Expr = if (rest.size == 1) rest[0] else com.routegenie.discovery.core.FStr(rest)
            return namedCall(call, if (rest.isEmpty()) null else suffix)
        }

        private fun namedCall(call: PyCallExpression): UrlFact.Named? = namedCall(call, null) as? UrlFact.Named

        private fun namedCall(call: PyCallExpression, suffix: Expr?): UrlFact? {
            val function = refOf(call.callee) ?: return null
            val resolver = ClientSinks.resolver(canonicalName(function)) ?: return null
            val positional = ArrayList<PyExpression>()
            val keywords = LinkedHashMap<String, PyExpression?>()
            var dynamic = false
            for (argument in call.arguments) {
                when (argument) {
                    is PyKeywordArgument -> argument.keyword?.let { keywords[it] = argument.valueExpression }
                    is PyStarArgument -> dynamic = true
                    else -> positional += argument
                }
            }
            val nameArg = positional.firstOrNull() ?: NAME_KEYWORDS.firstNotNullOfOrNull { keywords[it] } ?: return null
            val name = PyExprBuilder.build(nameArg)
            return when (resolver.argsStyle) {
                ArgsStyle.DJANGO_REVERSE -> {
                    val args = keywords["args"] ?: positional.getOrNull(2)
                    val kwargs = keywords["kwargs"] ?: positional.getOrNull(3)
                    val count = if (args == null) 0 else elementsOf(args)?.size ?: -1
                    val keys = if (kwargs == null) emptyList() else dictKeys(kwargs)
                    UrlFact.Named(function, name, count, if (dynamic) null else keys, suffix)
                }
                ArgsStyle.KEYWORDS -> {
                    val keys = if (dynamic) null else keywords.keys.filter { !it.startsWith("_") }
                    UrlFact.Named(function, name, positional.size - 1, keys, suffix)
                }
            }
        }

        private fun dictKeys(e: PyExpression): List<String>? {
            val dict = unwrap(e) as? PyDictLiteralExpression ?: return null
            val keys = ArrayList<String>()
            for (entry in dict.elements) {
                val key = unwrap(entry.key) as? PyStringLiteralExpression ?: return null
                keys += key.stringValue
            }
            return keys
        }

        private fun elementsOf(e: PyExpression): Array<PyExpression>? = when (val x = unwrap(e)) {
            is PyListLiteralExpression -> x.elements
            is PyTupleExpression -> x.elements
            is PySetLiteralExpression -> x.elements
            else -> null
        }

        private var literalNodes = 0

        /** A literal argument value, bounded; anything that is not spelled out is [LitValue.Unknown]. */
        private fun litValue(e: PyExpression?, depth: Int): LitValue {
            if (depth == 0) literalNodes = 0
            if (++literalNodes > Limits.TEST_LITERAL_MAX_NODES || depth > Limits.TEST_LITERAL_MAX_DEPTH) return LitValue.Unknown
            return when (val x = unwrap(e)) {
                null -> LitValue.Unknown
                is PyNoneLiteralExpression -> LitValue.None
                is PyBoolLiteralExpression -> LitValue.Bool(x.value)
                is PyNumericLiteralExpression -> numberOf(x.text, false)
                is PyPrefixExpression -> if (x.operator == PyTokenTypes.MINUS) (unwrap(x.operand) as? PyNumericLiteralExpression)?.let { numberOf(it.text, true) } ?: LitValue.Unknown else LitValue.Unknown
                is PyDictLiteralExpression -> {
                    val entries = ArrayList<Pair<LitValue, LitValue>>()
                    for (entry: PyKeyValueExpression in x.elements) {
                        entries += litValue(entry.key, depth + 1) to litValue(entry.value, depth + 1)
                    }
                    LitValue.Dict(entries)
                }
                is PyListLiteralExpression -> LitValue.Seq(x.elements.map { litValue(it, depth + 1) })
                is PyTupleExpression -> LitValue.Seq(x.elements.map { litValue(it, depth + 1) })
                is PyStringLiteralExpression, is PyBinaryExpression, is PyReferenceExpression -> LitValue.Str(PyExprBuilder.build(x))
                is PyCallExpression -> if ((x.callee as? PyReferenceExpression)?.referencedName == FORMAT) LitValue.Str(PyExprBuilder.build(x)) else LitValue.Unknown
                else -> LitValue.Unknown
            }
        }

        private fun numberOf(text: String, negative: Boolean): LitValue =
            if (NUMBER.matches(text)) LitValue.Num(if (negative) "-$text" else text) else LitValue.Unknown

        // ---- small helpers -----------------------------------------------------------------------------------------

        private fun unwrap(e: PyExpression?): PyExpression? {
            var current = e
            while (current is PyParenthesizedExpression) current = current.containedExpression
            return current
        }

        /** Like [unwrap] and also looks through `await`. */
        private fun unwrapValue(e: PyExpression?): PyExpression? {
            var current = unwrap(e)
            while (current is PyPrefixExpression && current.operator == PyTokenTypes.AWAIT_KEYWORD) current = unwrap(current.operand)
            return current
        }

        private fun posOf(element: PsiElement): SourcePos {
            val offset = element.textRange.startOffset
            val index = lineStarts.binarySearch(offset)
            return SourcePos(offset, (if (index >= 0) index else -index - 2) + 1)
        }
    }

    /** `a.b.c` as parts; null when the chain is not made of plain names. */
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

    private const val MODULE_SCOPE = ""
    private const val SELF = "self"
    private const val CLS = "cls"
    private const val INIT = "__init__"
    private const val FORMAT = "format"
    private const val BASE_URL = "base_url"
    private const val INITIAL_LINES = 256
    private val NAME_KEYWORDS = listOf("viewname", "endpoint", "name")
    private val NUMBER = Regex("""[0-9]+(\.[0-9]+)?([eE][+-]?[0-9]+)?""")
}
