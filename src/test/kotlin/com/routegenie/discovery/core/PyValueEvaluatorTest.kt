package com.routegenie.discovery.core

import com.routegenie.Limits
import com.routegenie.frameworks.common.PyFileFacts
import com.routegenie.frameworks.fastapi.FastApiRouteProvider
import com.routegenie.model.DiagnosticCode
import com.routegenie.model.PathSyntax
import com.routegenie.model.StrValue
import com.routegenie.testing.PythonPlatformTestCase

/**
 * Evaluator cases. Each test builds a small project in the light fixture, extracts the context-free facts of every file
 * (expression trees and import tables, nothing evaluated) and evaluates the right-hand side of `result` in main.py with the
 * pure [ExprEvaluator]. No Python SDK exists in the fixture, so every cross-file case proves that following names works
 * without one.
 */
class PyValueEvaluatorTest : PythonPlatformTestCase() {
    private val hole = PathSyntax.UNKNOWN_SEGMENT
    private val provider = FastApiRouteProvider()

    private fun entries(): List<ModuleEntry> = allFiles.mapNotNull { file ->
        val facts = provider.extractDependency(file) as? PyFileFacts ?: return@mapNotNull null
        val path = file.virtualFile.path.substringAfter("/src/")
        ModuleEntry(path, ModuleNames.moduleOf(path, ""), ModuleNames.isPackage(path), facts)
    }

    private val allFiles = ArrayList<com.intellij.psi.PsiFile>()

    private fun eval(main: String, vararg others: Pair<String, String>): StrValue {
        others.forEach { (path, text) -> allFiles += pythonFile(path, text) }
        allFiles += pythonFile("main.py", main)
        val entries = entries()
        val entry = entries.single { it.path == "main.py" }
        val value = entry.constsNamed("result").singleOrNull()?.value ?: error("main.py needs one `result = ...`")
        return ExprEvaluator(ModuleTable(entries)).evaluate(entry, value)
    }

    private fun assertKnown(expected: String, actual: StrValue) = assertEquals(StrValue.Known(expected), actual)

    private fun assertPartial(expected: String, actual: StrValue) {
        assertTrue("expected Partial but was $actual", actual is StrValue.Partial)
        assertEquals(expected, actual.text)
        assertEquals(DiagnosticCode.DYNAMIC_VALUE, actual.reason)
    }

    private fun assertUnknown(actual: StrValue) {
        assertTrue("expected Unknown but was $actual", actual is StrValue.Unknown)
        assertEquals(DiagnosticCode.DYNAMIC_VALUE, actual.reason)
    }

    fun testNullExpressionIsUnknown() {
        allFiles += pythonFile("main.py", "x = 1\n")
        val entry = entries().single()
        assertUnknown(ExprEvaluator(ModuleTable(listOf(entry))).evaluate(entry, null))
    }

    fun testPlainLiteral() = assertKnown("/items", eval("result = '/items'\n"))

    fun testRawLiteralKeepsBackslashes() = assertKnown("^a\\d/$", eval("result = r'^a\\d/$'\n"))

    fun testEscapesAreDecoded() = assertKnown("a\tb", eval("result = 'a\\tb'\n"))

    // Resolves research 02 "implicit concatenation UNVERIFIED".
    fun testImplicitConcatenation() = assertKnown("/api/items", eval("result = ('/api' '/items')\n"))

    fun testPlus() = assertKnown("/api/items", eval("result = '/api' + '/items'\n"))

    fun testBytesLiteralIsUnknown() = assertUnknown(eval("result = b'/x'\n"))

    fun testNumberAloneIsUnknown() = assertUnknown(eval("result = 5\n"))

    fun testSameFileConstant() = assertKnown("/v1/items", eval("API = '/v1'\nresult = API + '/items'\n"))

    fun testConstantChain() = assertKnown("/a", eval("A = '/a'\nB = A\nC = B\nresult = C\n"))

    fun testAnnotatedConstant() = assertKnown("/a", eval("A: str = '/a'\nresult = A\n"))

    fun testCycleIsUnknownNotAnEndlessLoop() = assertUnknown(eval("A = B\nB = A\nresult = A\n"))

    fun testSelfReferenceStaysPartialNotAnEndlessLoop() = assertPartial("$hole/x", eval("A = A + '/x'\nresult = A\n"))

    fun testReassignedConstantIsUnknown() = assertUnknown(eval("A = '/a'\nA = '/b'\nresult = A\n"))

    fun testAugmentedAssignmentMakesConstantUnknown() = assertUnknown(eval("A = '/a'\nA += '/b'\nresult = A\n"))

    fun testUndefinedNameIsUnknown() = assertUnknown(eval("result = missing_name\n"))

    fun testFunctionCallIsUnknown() = assertUnknown(eval("def p():\n    return '/x'\nresult = p()\n"))

    fun testUnknownPlusKnownIsPartial() = assertPartial("$hole/x", eval("result = compute() + '/x'\n"))

    fun testKnownPlusUnknownIsPartial() = assertPartial("/x$hole", eval("result = '/x' + compute()\n"))

    fun testFStringKnownParts() = assertKnown("/v1/items", eval("V = 'v1'\nresult = f'/{V}/items'\n"))

    // Resolves research 02: PyEvaluator returned the raw placeholder text "/v{PREFIX}" for f-strings.
    fun testFStringUnknownHoleBecomesMarker() =
        assertPartial("/users/$hole/posts", eval("result = f'/users/{uid}/posts'\n"))

    fun testFStringWithOnlyUnknownIsUnknown() = assertUnknown(eval("result = f'{base}'\n"))

    fun testFStringEscapedBraces() = assertKnown("/{x}", eval("result = f'/{{x}}'\n"))

    fun testFStringFormatSpecIsHole() = assertPartial("/$hole", eval("N = 5\nresult = f'/{N:>5}'\n"))

    fun testFStringConversionIsHole() = assertPartial("/$hole", eval("N = 'a'\nresult = f'/{N!r}'\n"))

    fun testFStringIntegerConstant() = assertKnown("/v2", eval("N = 2\nresult = f'/v{N}'\n"))

    fun testFStringBackslashInLiteralIsNotTrusted() = assertPartial("$hole$hole", eval("result = f'a\\n{x}'\n"))

    fun testFStringAdjacentToPlain() = assertKnown("/a/b", eval("R = 'a'\nresult = f'/{R}' '/b'\n"))

    fun testPercentFormat() = assertKnown("/users/7", eval("result = '/users/%d' % 7\n"))

    fun testPercentFormatTuple() = assertKnown("/a/b", eval("result = '/%s/%s' % ('a', 'b')\n"))

    fun testPercentFormatUnknownArgument() = assertPartial("/a/$hole", eval("result = '/%s/%s' % ('a', x)\n"))

    fun testPercentFormatWrongArgumentCountIsUnknown() = assertUnknown(eval("result = '/%s/%s' % ('a',)\n"))

    fun testPercentDictFormatIsUnknown() = assertUnknown(eval("result = '/%(a)s' % {'a': 'b'}\n"))

    fun testFormatPositional() = assertKnown("/a/b", eval("result = '/{}/{}'.format('a', 'b')\n"))

    fun testFormatIndexed() = assertKnown("/b/a", eval("result = '/{1}/{0}'.format('a', 'b')\n"))

    fun testFormatNamed() = assertKnown("/api/x", eval("result = '/{p}/x'.format(p='api')\n"))

    fun testFormatMissingArgumentIsUnknown() = assertUnknown(eval("result = '/{}/{}'.format('a')\n"))

    fun testFormatWithSpecIsUnknown() = assertUnknown(eval("result = '/{:>3}'.format('a')\n"))

    fun testFormatUnknownArgumentIsPartial() = assertPartial("/$hole", eval("result = '/{}'.format(y)\n"))

    fun testOtherMethodCallIsUnknown() = assertUnknown(eval("result = '/a'.upper()\n"))

    fun testImportedNameFromProjectFile() =
        assertKnown("/v1", eval("from consts import API\nresult = API\n", "consts.py" to "API = '/v1'\n"))

    // Resolves research 02 "cross-file names": PyEvaluator returned null here.
    fun testImportedNameInExpression() =
        assertKnown("/v1/items", eval("from consts import API\nresult = API + '/items'\n", "consts.py" to "API = '/v1'\n"))

    fun testImportedWithAlias() =
        assertKnown("/v1", eval("from consts import API as A\nresult = A\n", "consts.py" to "API = '/v1'\n"))

    fun testModuleAttribute() =
        assertKnown("/v1", eval("import consts\nresult = consts.API\n", "consts.py" to "API = '/v1'\n"))

    fun testModuleAlias() =
        assertKnown("/v1", eval("import consts as c\nresult = c.API\n", "consts.py" to "API = '/v1'\n"))

    fun testDottedModuleImport() = assertKnown(
        "/v1",
        eval("import pkg.consts\nresult = pkg.consts.API\n", "pkg/__init__.py" to "", "pkg/consts.py" to "API = '/v1'\n"),
    )

    fun testFromPackageImportModule() = assertKnown(
        "/v1",
        eval("from pkg import consts\nresult = consts.API\n", "pkg/__init__.py" to "", "pkg/consts.py" to "API = '/v1'\n"),
    )

    fun testFromDottedImport() = assertKnown(
        "/v1",
        eval("from pkg.consts import API\nresult = API\n", "pkg/__init__.py" to "", "pkg/consts.py" to "API = '/v1'\n"),
    )

    fun testPackageReExport() = assertKnown(
        "/v1",
        eval(
            "from pkg import API\nresult = API\n",
            "pkg/__init__.py" to "from .consts import API\n",
            "pkg/consts.py" to "API = '/v1'\n",
        ),
    )

    fun testStarImport() =
        assertKnown("/v1", eval("from consts import *\nresult = API\n", "consts.py" to "API = '/v1'\n"))

    fun testChainAcrossThreeFiles() = assertKnown(
        "/a/b/c",
        eval(
            "from one import X\nresult = X\n",
            "one.py" to "from two import Y\nX = Y + '/c'\n",
            "two.py" to "from three import Z\nY = Z + '/b'\n",
            "three.py" to "Z = '/a'\n",
        ),
    )

    fun testConstantUsingFStringOfImportedName() = assertKnown(
        "/api/v2/users",
        eval(
            "from cfg import BASE\nresult = BASE\n",
            "cfg.py" to "from ver import V\nBASE = f'/api/{V}/users'\n",
            "ver.py" to "V = 'v2'\n",
        ),
    )

    fun testCrossFileCycleIsUnknown() = assertUnknown(
        eval("from a import A\nresult = A\n", "a.py" to "from b import B\nA = B\n", "b.py" to "from a import A\nB = A\n"),
    )

    fun testLibraryImportIsUnknown() = assertUnknown(eval("from settings_lib import PREFIX\nresult = PREFIX\n"))

    fun testImportedNameThatDoesNotExistIsUnknown() =
        assertUnknown(eval("from consts import NOPE\nresult = NOPE\n", "consts.py" to "API = '/v1'\n"))

    fun testImportedUnknownValueStaysPartialInExpression() = assertPartial(
        "$hole/x",
        eval("from consts import API\nresult = API + '/x'\n", "consts.py" to "import os\nAPI = os.environ['A']\n"),
    )

    fun testResolveDepthIsLimited() {
        val depth = Limits.MAX_VALUE_RESOLVE_DEPTH + 4
        val chain = (0 until depth).joinToString("") { "C$it = C${it + 1}\n" } + "C$depth = '/end'\nresult = C0\n"
        assertUnknown(eval(chain))
    }

    fun testChainWithinDepthResolves() {
        val depth = Limits.MAX_VALUE_RESOLVE_DEPTH - 4
        val chain = (0 until depth).joinToString("") { "C$it = C${it + 1}\n" } + "C$depth = '/end'\nresult = C0\n"
        assertKnown("/end", eval(chain))
    }

    fun testConstantInsideIfIsNotAsserted() = assertUnknown(eval("if flag:\n    A = '/a'\nresult = A\n"))

    fun testPartialKeepsOrderAcrossStringElements() = assertPartial("$hole-/x", eval("result = f'{a}-' '/x'\n"))
}
