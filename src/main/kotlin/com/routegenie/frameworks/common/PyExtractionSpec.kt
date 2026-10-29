package com.routegenie.frameworks.common

/**
 * What the shared extractor needs to know about one framework. Implemented by the framework's catalog object, so the
 * names are written down once per framework.
 */
interface PyExtractionSpec {
    val providerId: String

    /** Last name segments of constructors worth recording, e.g. `FastAPI`, `APIRouter`. */
    val constructorNames: Set<String>

    /** Decorator method names that may declare a route, e.g. `get`, `route`. */
    val decoratorMethods: Set<String>

    /** Statement-call method names that attach or declare routes, e.g. `include_router`, `add_url_rule`. */
    val callMethods: Set<String>

    /** Methods whose first positional argument is a path; it is evaluated as a string even when it is a name. */
    val pathFirstMethods: Set<String>

    /** Keyword arguments evaluated as strings even when they are names (prefix, name, ...). */
    val stringKeywords: Set<String>

    /** Whether classes with base classes are recorded (class based views). */
    val capturesClasses: Boolean
}
