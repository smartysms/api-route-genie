package com.routegenie.testmap

/**
 * The command line that runs one test in a shell, for "Copy command". Pure: the shape follows from the test's style
 * (pytest takes the node id as it is; the Django runner takes a dotted label), nothing is run here.
 */
object TestCommands {
    fun of(test: ProjectTest): String = when (test.style) {
        TestStyle.DJANGO_TESTCASE -> "python manage.py test ${djangoLabel(test)}"
        TestStyle.PYTEST, TestStyle.UNITTEST -> "pytest ${quoteIfNeeded(test.id)}"
    }

    /** `shop/tests.py` + `ShopTests` + `test_index` is `shop.tests.ShopTests.test_index`. */
    fun djangoLabel(test: ProjectTest): String =
        (test.file.removeSuffix(".py").split('/').filter { it.isNotEmpty() } + listOfNotNull(test.className).flatMap { it.split('.') } + test.name)
            .joinToString(".")

    private fun quoteIfNeeded(text: String): String = if (text.any { it.isWhitespace() || it in SHELL_SPECIAL }) "\"$text\"" else text

    private const val SHELL_SPECIAL = "&|;<>()$`\\\"'*?[]#~=%!{}"
}
