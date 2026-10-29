package com.routegenie.frameworks.common

import com.routegenie.model.HttpMethod

/** Where an argument sits in a call: by keyword, or by position when passed positionally. */
data class ArgSlot(val keyword: String, val index: Int) {
    fun of(call: CallFact): ArgFact? = call.arg(keyword, index)
}

enum class PrimitiveKind {
    /** `x = Callee(...)` creating a root application. */
    APP,

    /** `x = Callee(...)` creating a router or blueprint that is attached to something else. */
    CONTAINER,

    /** `@x.name(path)` declaring a route on the receiver. */
    ROUTE_DECORATOR,

    /** `x.name(path, handler)` declaring a route imperatively. */
    ROUTE_CALL,

    /** `x.name(child, prefix=...)` attaching a router or blueprint to the receiver. */
    ATTACH,

    /** `x.name(path, app)` attaching an independent sub application under a path. */
    MOUNT,
}

/** How an attachment combines its prefix with the parent and the child's own prefix. */
enum class Composition {
    /** FastAPI: parent prefix, then the include prefix, then the child's own prefix. */
    STACKED,

    /** Flask: the registration prefix replaces the child's own; nested prefixes are joined with one slash. */
    OVERRIDE_JOIN,
}

/**
 * One registration primitive of a framework, as data. A catalog is a list of these; extraction and linking read the
 * table instead of spelling decorator or class names, so a new framework (or a new primitive) is a new row.
 * Slots that do not apply stay null.
 */
data class Primitive(
    val name: String,
    val kind: PrimitiveKind,
    /** Import origins (qualified names) a constructor must come from; empty for method primitives. */
    val origins: Set<String> = emptySet(),
    /** The route path (or rule, or mount path). */
    val path: ArgSlot? = null,
    /** The prefix of a constructor or of an attachment. */
    val prefix: ArgSlot? = null,
    /** The router, blueprint or sub application an attachment or mount points at. */
    val child: ArgSlot? = null,
    /** The handler (function or view) of an imperative route. */
    val handler: ArgSlot? = null,
    /** An explicit endpoint name. */
    val endpoint: ArgSlot? = null,
    /** A display or registration name (route name, blueprint name, name override). */
    val label: ArgSlot? = null,
    val tags: ArgSlot? = null,
    val deprecated: ArgSlot? = null,
    /** The method list of a route; absent means [fixedMethod] or [defaultMethod] applies. */
    val methods: ArgSlot? = null,
    val fixedMethod: HttpMethod? = null,
    val defaultMethod: HttpMethod? = null,
    val composition: Composition? = null,
)

/** The table a framework provides, and the sets the shared extractor derives from it. */
class PrimitiveTable(
    override val providerId: String,
    val importRoots: Set<String>,
    val primitives: List<Primitive>,
    override val capturesClasses: Boolean,
) : PyExtractionSpec {
    private val byName: Map<String, List<Primitive>> = primitives.groupBy { it.name }

    fun methodPrimitive(name: String, vararg kinds: PrimitiveKind): Primitive? =
        byName[name]?.firstOrNull { it.kind in kinds }

    /** The constructor primitive whose import origin is [origin], or null. */
    fun constructorFor(origin: String): Primitive? = primitives.firstOrNull {
        (it.kind == PrimitiveKind.APP || it.kind == PrimitiveKind.CONTAINER) && origin in it.origins
    }

    fun isFrameworkImport(module: String): Boolean = importRoots.any { module == it || module.startsWith("$it.") }

    /** True when the file imports from the framework; only such files may report routes whose receiver is unknown. */
    fun importsFramework(file: PyRouteFacts): Boolean =
        file.imports.any { isFrameworkImport(it.module) } || file.starModules.any { isFrameworkImport(it) }

    private fun names(vararg kinds: PrimitiveKind) = primitives.filter { it.kind in kinds }.map { it.name }.toSet()

    override val constructorNames: Set<String> =
        primitives.filter { it.kind == PrimitiveKind.APP || it.kind == PrimitiveKind.CONTAINER }
            .flatMap { it.origins }.map { it.substringAfterLast('.') }.toSet()
    override val decoratorMethods: Set<String> = names(PrimitiveKind.ROUTE_DECORATOR)
    override val callMethods: Set<String> = names(PrimitiveKind.ROUTE_CALL, PrimitiveKind.ATTACH, PrimitiveKind.MOUNT)

    /** Every primitive whose first argument is a path, plus constructors whose first argument names the object. */
    override val pathFirstMethods: Set<String> =
        primitives.filter { it.path?.index == 0 || (it.kind == PrimitiveKind.CONTAINER && it.label?.index == 0) }
            .map { if (it.origins.isEmpty()) it.name else it.origins.first().substringAfterLast('.') }.toSet()

    override val stringKeywords: Set<String> = primitives.flatMap { listOfNotNull(it.path, it.prefix, it.label, it.endpoint) }
        .map { it.keyword }.toSet()

    /** The distinct words that identify a candidate file: import roots and the method names of attachments. */
    val candidateWords: List<String> =
        (importRoots + names(PrimitiveKind.ATTACH, PrimitiveKind.ROUTE_CALL) + (names(PrimitiveKind.ROUTE_DECORATOR) - GENERIC_VERBS)).toList()

    private companion object {
        /** Verb words are too common in code to select candidates; `route` and `api_route` are not in this set. */
        val GENERIC_VERBS = HttpMethod.entries.map { it.name.lowercase() }.toSet()
    }
}
