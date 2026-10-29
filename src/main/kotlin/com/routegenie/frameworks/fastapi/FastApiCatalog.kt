package com.routegenie.frameworks.fastapi

import com.routegenie.frameworks.common.ArgSlot
import com.routegenie.frameworks.common.Composition
import com.routegenie.frameworks.common.Primitive
import com.routegenie.frameworks.common.PrimitiveKind
import com.routegenie.frameworks.common.PrimitiveTable
import com.routegenie.model.HttpMethod

/**
 * Every fact about FastAPI the provider relies on, as a table of registration primitives (see [Primitive]).
 * Nothing else in the package spells out a class, decorator or keyword name.
 */
internal object FastApiCatalog {
    const val PROVIDER_ID = "fastapi"

    /** `api_route` and `add_api_route` without `methods` register GET only (checked against fastapi 0.162). */
    private val defaultMethod = HttpMethod.GET

    private val path = ArgSlot("path", 0)
    private val prefix = ArgSlot("prefix", NOT_POSITIONAL)
    private val tags = ArgSlot("tags", NOT_POSITIONAL)
    private val deprecated = ArgSlot("deprecated", NOT_POSITIONAL)
    private val label = ArgSlot("name", NOT_POSITIONAL)
    private val methods = ArgSlot("methods", NOT_POSITIONAL)

    private val verbDecorators = HttpMethod.entries.filter { it.isConcrete }.map {
        Primitive(it.name.lowercase(), PrimitiveKind.ROUTE_DECORATOR, path = path, fixedMethod = it, label = label, tags = tags, deprecated = deprecated)
    }

    /** `websocket` is deliberately absent: it has no HTTP method to list. */
    val table = PrimitiveTable(
        providerId = PROVIDER_ID,
        importRoots = setOf("fastapi"),
        capturesClasses = false,
        primitives = listOf(
            Primitive("FastAPI", PrimitiveKind.APP, origins = setOf("fastapi.FastAPI", "fastapi.applications.FastAPI")),
            Primitive(
                "APIRouter", PrimitiveKind.CONTAINER, origins = setOf("fastapi.APIRouter", "fastapi.routing.APIRouter"),
                prefix = prefix, tags = tags, deprecated = deprecated,
            ),
            // Starlette style `@router.route(path)` is not handled; the linker counts it so the gap is visible.
            Primitive(UNSUPPORTED_ROUTE_DECORATOR, PrimitiveKind.UNSUPPORTED_DECORATOR),
            Primitive(
                "api_route", PrimitiveKind.ROUTE_DECORATOR, path = path, methods = methods, defaultMethod = defaultMethod,
                label = label, tags = tags, deprecated = deprecated,
            ),
            Primitive(
                "add_api_route", PrimitiveKind.ROUTE_CALL, path = path, handler = ArgSlot("endpoint", 1), methods = methods,
                defaultMethod = defaultMethod, label = label, tags = tags, deprecated = deprecated,
            ),
            Primitive(
                "include_router", PrimitiveKind.ATTACH, child = ArgSlot("router", 0), prefix = prefix, tags = tags,
                deprecated = deprecated, composition = Composition.STACKED,
            ),
            Primitive("mount", PrimitiveKind.MOUNT, path = path, child = ArgSlot("app", 1), composition = Composition.STACKED),
        ) + verbDecorators,
    )

    const val UNSUPPORTED_ROUTE_DECORATOR = "route"

    /** What the diagnostic of [UNSUPPORTED_ROUTE_DECORATOR] says is unsupported. */
    const val UNSUPPORTED_ROUTE_NOTE = "APIRouter.route"

    /** Keyword-only arguments have no position. */
    private const val NOT_POSITIONAL = -1
}
