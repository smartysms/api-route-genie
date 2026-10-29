package com.routegenie.testing

import org.junit.Assert.fail
import java.util.IdentityHashMap

/**
 * Walks an object graph the way a leak report would and fails when an instance of a forbidden type is reachable.
 * Only classes of this plugin are inspected by reflection; collections, maps and arrays are walked by their public
 * API, because the JDK's internals are not open to reflection.
 */
object ObjectGraph {
    fun assertNoInstanceOf(label: String, root: Any?, vararg forbidden: Class<*>) {
        val seen = IdentityHashMap<Any, Boolean>()
        val stack = ArrayDeque<Any>()
        root?.let { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (seen.put(current, true) != null) continue
            if (forbidden.any { it.isInstance(current) }) fail("$label: reachable ${current.javaClass.name}")
            when (current) {
                is Iterable<*> -> current.forEach { it?.let(stack::addLast) }
                is Map<*, *> -> current.forEach { (k, v) -> k?.let(stack::addLast); v?.let(stack::addLast) }
                is Array<*> -> current.forEach { it?.let(stack::addLast) }
                else -> if (current.javaClass.name.startsWith(PLUGIN_PACKAGE)) {
                    generateSequence<Class<*>>(current.javaClass) { it.superclass }
                        .filter { it.name.startsWith(PLUGIN_PACKAGE) }
                        .flatMap { it.declaredFields.asSequence() }
                        .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }
                        .forEach { field ->
                            field.isAccessible = true
                            field.get(current)?.let(stack::addLast)
                        }
                }
            }
        }
    }

    private const val PLUGIN_PACKAGE = "com.routegenie."
}
