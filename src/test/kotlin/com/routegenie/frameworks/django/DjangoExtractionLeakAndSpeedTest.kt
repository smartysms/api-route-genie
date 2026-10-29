package com.routegenie.frameworks.django

import com.intellij.psi.PsiManager
import com.routegenie.Limits
import com.routegenie.testing.LeakProbe
import com.routegenie.testing.PerfBudget
import com.routegenie.testing.ProviderPipeline
import java.util.IdentityHashMap

/** Facts must hold no PSI, repeated extraction must not grow the heap, and re-reading one file must be fast. */
class DjangoExtractionLeakAndSpeedTest : DjangoTestBase() {
    private fun assertHoldsNoIdeObjects(root: Any) {
        val seen = IdentityHashMap<Any, Boolean>()
        val pending = ArrayDeque<Any>().also { it.add(root) }
        while (pending.isNotEmpty()) {
            val value = pending.removeLast()
            if (seen.put(value, true) != null) continue
            val type = value.javaClass
            check(FORBIDDEN.none { type.name.startsWith(it) }) { "facts reference ${type.name}" }
            when {
                value is Map<*, *> -> value.forEach { (k, v) -> k?.let(pending::add); v?.let(pending::add) }
                value is Iterable<*> -> value.forEach { it?.let(pending::add) }
                type.isArray && !type.componentType.isPrimitive -> (value as Array<*>).forEach { it?.let(pending::add) }
                type.name.startsWith("com.routegenie") && !type.isEnum -> generateSequence<Class<*>>(type) { it.superclass }
                    .flatMap { it.declaredFields.asSequence() }
                    .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                    .forEach { field ->
                        field.isAccessible = true
                        field.get(value)?.let(pending::add)
                    }
            }
        }
    }

    fun testFactsOfEveryFixtureFileHoldNoPsiOrVirtualFiles() {
        val facts = extractAll("django_basic") + extractAll("django_drf")
        assertTrue(facts.size > 10)
        facts.forEach { assertHoldsNoIdeObjects(it) }
    }

    fun testHelperDetectsAnIdeObject() {
        val psi = myFixture.addFileToProject("x.py", "x = 1\n")
        try {
            assertHoldsNoIdeObjects(listOf("a", psi))
            fail("expected the walker to report the PSI file")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("facts reference"))
        }
    }

    fun testRepeatedExtractionAndLinkingDoesNotGrowTheHeap() {
        val facts = extractAll("django_drf")
        val urls = myFixture.findFileInTempDir("library/urls.py")
        val psi = PsiManager.getInstance(project).findFile(urls)!!
        LeakProbe.assertNoHeapGrowth("django-extract-link", maxGrowthBytes = 4L * 1024 * 1024, warmups = 30, iterations = 150) {
            val again = ProviderPipeline.locate(psi, provider.extract(psi)!!)
            provider.link(facts.filter { it.path != again.path } + again)
        }
    }

    fun testReReadingOneEditedFileAndRelinkingIsWithinTheBudget() {
        val facts = extractAll("django_basic")
        val target = myFixture.findFileInTempDir("shop/urls.py")
        val psi = PsiManager.getInstance(project).findFile(target)!!
        val result = PerfBudget.assertWithin("django-refresh-one-file", Limits.BUDGET_REFRESH_ONE_FILE_MS) {
            val fresh = ProviderPipeline.locate(psi, provider.extract(psi) as DjangoFacts)
            provider.link(facts.filter { it.path != fresh.path } + fresh)
        }
        assertTrue(result.medianMs <= Limits.BUDGET_REFRESH_ONE_FILE_MS)
    }

    private companion object {
        val FORBIDDEN = listOf("com.intellij", "com.jetbrains", "java.awt", "javax.swing")
    }
}
