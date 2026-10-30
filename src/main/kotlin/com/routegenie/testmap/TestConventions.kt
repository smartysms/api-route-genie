package com.routegenie.testmap

import com.routegenie.frameworks.common.RefFact
import com.routegenie.model.Framework

/** How a test is written; the runner that collects it follows from this. */
enum class TestStyle {
    /** A function or a `Test*` class collected by pytest (also async tests run by pytest-asyncio or anyio). */
    PYTEST,

    /** A `unittest.TestCase` or `unittest.IsolatedAsyncioTestCase` subclass. */
    UNITTEST,

    /** A Django or Django REST framework test case (`django.test.TestCase`, `rest_framework.test.APITestCase`). */
    DJANGO_TESTCASE,
}

/** Whether a test is switched off; the strongest wins when a class and a method both say something. */
enum class TestMark {
    NONE, CONDITIONAL_SKIP, XFAIL, SKIPPED;

    fun max(other: TestMark): TestMark = if (other.ordinal > ordinal) other else this

    /** Unconditionally skipped or expected to fail: listed, but not counted as "reached". */
    val isOff: Boolean get() = this == XFAIL || this == SKIPPED
}

/**
 * The rules for what is a test, as data. [stylesByFramework] is the owner's scope decision in one place: which test styles
 * are recognized for the endpoints of each framework. FastAPI is pytest and unittest only; a Django test case that happens to
 * call a FastAPI endpoint is not listed. Widening a framework later is one more entry in its set.
 */
object TestConventions {
    val stylesByFramework: Map<Framework, Set<TestStyle>> = mapOf(
        Framework.FASTAPI to setOf(TestStyle.PYTEST, TestStyle.UNITTEST),
        Framework.FLASK to setOf(TestStyle.PYTEST, TestStyle.UNITTEST),
        Framework.DJANGO to setOf(TestStyle.PYTEST, TestStyle.UNITTEST, TestStyle.DJANGO_TESTCASE),
        Framework.DRF to setOf(TestStyle.PYTEST, TestStyle.UNITTEST, TestStyle.DJANGO_TESTCASE),
    )

    fun allows(framework: Framework, style: TestStyle): Boolean = style in stylesByFramework[framework].orEmpty()

    /** Base classes that make a class a unittest test case, by import origin. */
    val unittestBaseOrigins: Set<String> = setOf(
        "unittest.TestCase", "unittest.case.TestCase", "unittest.IsolatedAsyncioTestCase", "unittest.async_case.IsolatedAsyncioTestCase",
    )

    /** `test*.py` is the Django and unittest pattern and a superset of pytest's `test_*.py`; `*_test.py` is pytest's second one. */
    fun isTestFile(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name.endsWith(PY) && name != CONFTEST && (name.startsWith(TEST_PREFIX) || name.endsWith(TEST_SUFFIX))
    }

    fun isConftest(path: String): Boolean = path.substringAfterLast('/') == CONFTEST

    fun isTestFunctionName(name: String): Boolean = name.startsWith(TEST_PREFIX)

    /** pytest collects a class named `Test*` that has no `__init__`. */
    fun isPytestClass(name: String, hasInit: Boolean): Boolean = name.startsWith(PYTEST_CLASS_PREFIX) && !hasInit

    /** Methods that run before every test of a class; a client assigned there belongs to the whole class. */
    val setupMethods: Set<String> = setOf(
        "setUp", "setUpClass", "setUpTestData", "asyncSetUp", "setup_method", "setup_class", "setup",
    )

    /** Decorator names (last segment) that switch a test off, as data. A mark counts when written bare or on `mark.`/`unittest.`. */
    private val markNames: Map<String, TestMark> = mapOf(
        "skip" to TestMark.SKIPPED,
        "skipIf" to TestMark.CONDITIONAL_SKIP,
        "skipUnless" to TestMark.CONDITIONAL_SKIP,
        "skipif" to TestMark.CONDITIONAL_SKIP,
        "xfail" to TestMark.XFAIL,
        "expectedFailure" to TestMark.XFAIL,
    )
    private val markQualifiers = setOf("mark", "unittest", "pytest")

    fun markOf(decorator: RefFact): TestMark {
        val mark = markNames[decorator.last] ?: return TestMark.NONE
        val qualifier = decorator.parts.dropLast(1)
        return if (qualifier.isEmpty() || qualifier.any { it in markQualifiers }) mark else TestMark.NONE
    }

    /** `@pytest.fixture`, `@pytest_asyncio.fixture`, `@fixture`, with or without arguments. */
    fun isFixtureDecorator(decorator: RefFact): Boolean = decorator.last == FIXTURE

    /** Status attribute of a response: the one literal asserts are read from. */
    val statusAttributes: Set<String> = setOf("status_code")

    /** `assertEqual(response.status_code, 201)` and its aliases. */
    val equalityAsserts: Set<String> = setOf("assertEqual", "assertEquals")

    /** `status.HTTP_201_CREATED` style constants carry their code in the name. */
    val statusConstant = Regex("""HTTP_(\d{3})_[A-Z_]+""")

    private const val PY = ".py"
    private const val CONFTEST = "conftest.py"
    private const val TEST_PREFIX = "test"
    private const val TEST_SUFFIX = "_test.py"
    private const val PYTEST_CLASS_PREFIX = "Test"
    private const val FIXTURE = "fixture"
}
