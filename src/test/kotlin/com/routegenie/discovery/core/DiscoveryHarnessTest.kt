package com.routegenie.discovery.core

import com.intellij.psi.PsiElement
import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.Handler
import com.routegenie.model.HttpMethod
import com.routegenie.model.SourceRef
import com.routegenie.testing.ObjectGraph
import com.routegenie.testing.SyntheticProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Pure tests: the bounded cache, and negative controls proving the object-graph check and generator can fail. */
class DiscoveryHarnessTest {
    @Test
    fun `cache never exceeds capacity and drops the least recently used entry`() {
        val cache = WeightedLruCache<String, Int>(3) { 1 }
        cache["a"] = 1; cache["b"] = 2; cache["c"] = 3
        assertEquals(1, cache["a"]) // a is now most recently used
        cache["d"] = 4
        assertEquals(3, cache.size)
        assertNull("b was the oldest", cache["b"])
        assertNotNull(cache["a"])
        repeat(1000) { cache["k$it"] = it }
        assertEquals(3, cache.size)
    }

    @Test
    fun `object graph check finds a forbidden instance and passes a clean graph`() {
        class Holder(val inner: List<Any>)
        val clean = Holder(listOf("x", 1))
        ObjectGraph.assertNoInstanceOf("clean", clean, PsiElement::class.java)

        val dirty = Holder(listOf("x", java.io.File("a")))
        try {
            ObjectGraph.assertNoInstanceOf("dirty", dirty, java.io.File::class.java)
            fail("a File inside a list must be found")
        } catch (expected: AssertionError) {
            assertTrue(expected.message!!.contains("java.io.File"))
        }
    }

    @Test
    fun `object graph check follows plugin classes and nested collections`() {
        val endpoint = Endpoint(HttpMethod.GET, "/x", Framework.FASTAPI, Handler("h", "m.h"), SourceRef("a.py", 0, 1))
        ObjectGraph.assertNoInstanceOf("endpoint", listOf(endpoint), java.io.File::class.java)
        try {
            ObjectGraph.assertNoInstanceOf("endpoint", mapOf("k" to listOf(endpoint)), SourceRef::class.java)
            fail("SourceRef is reachable through a map, a list and an Endpoint")
        } catch (expected: AssertionError) {
            assertTrue(expected.message!!.contains("SourceRef"))
        }
    }

    @Test
    fun `synthetic project is ascii and has the requested shape`() {
        val files = SyntheticProject.files(45, routesPerFile = 4, filesPerPackage = 20)
        assertEquals(45, files.size)
        assertEquals(45, files.map { it.path }.toSet().size)
        assertEquals(3, files.map { it.path.substringBefore('/') }.toSet().size)
        for (file in files) {
            assertTrue(file.path, file.text.all { it.code < 128 })
            assertEquals(4, file.text.lines().count { it.startsWith("# route ") })
        }
        assertFalse(files.first().text.contains('\r'))
    }
}
