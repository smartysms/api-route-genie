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

    /**
     * The decorator names that make a file a candidate through the decorator index. Narrower than [decoratorMethods] when some
     * decorators are only read to be reported: they must not pull in every file that uses the name for something else.
     */
    val candidateDecoratorMethods: Set<String> get() = decoratorMethods

    /** Statement-call method names that attach or declare routes, e.g. `include_router`, `add_url_rule`. */
    val callMethods: Set<String>

    /** Methods whose first positional argument is a path; it is evaluated as a string even when it is a name. */
    val pathFirstMethods: Set<String>

    /** Keyword arguments evaluated as strings even when they are names (prefix, name, ...). */
    val stringKeywords: Set<String>

    /** Whether classes with base classes are recorded (class based views). */
    val capturesClasses: Boolean

    /**
     * Words that make a file a candidate through the word index: constructors and attachment calls, never the framework's
     * own name (every file that imports it would qualify). Routes declared by decorators are found through the decorator
     * index instead, see [decoratorMethods].
     */
    val candidateWords: Collection<String>

    /**
     * Text a candidate must contain before it is parsed: a file passes when every word of at least one group occurs in it.
     * A cheap `indexOf` scan, a hundred times cheaper than a parse; the groups must cover everything extraction can use.
     */
    val textMarkers: List<Set<String>>
}
