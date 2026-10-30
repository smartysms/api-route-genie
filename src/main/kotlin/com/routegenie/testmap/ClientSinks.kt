package com.routegenie.testmap

import com.routegenie.model.Framework
import com.routegenie.model.HttpMethod

/**
 * Everything the test mapping knows about HTTP test clients, as data in the style of `frameworks/common/Primitives.kt`:
 * nothing else spells out a client class, a fixture name, a verb method or a name-resolving function. A new test client is a
 * new [ClientSink] row; a new way of building a URL from a route name is a new [NameResolver] row.
 */

/** Where an argument sits in a call: by keyword (any of [keywords]) or by position ([index] -1: keyword only). */
data class Slot(val keywords: Set<String>, val index: Int)

/** How a client method takes its verb and URL. A fixed verb comes from the method name (`get`), else from the [verb] slot. */
data class CallShape(val fixedVerb: HttpMethod?, val verb: Slot?, val url: Slot)

/** What a second positional argument of a verb call means for this client family. */
enum class SecondPositional {
    /** Not a request part (Starlette and Flask clients take keywords only). */
    NONE,

    /** Django style: the query of GET/HEAD, the body of POST/PUT/PATCH/DELETE. */
    DJANGO_DATA,
}

/** How several endpoints that unify with one call are narrowed. */
enum class MatchPolicy {
    /** Exactly one candidate or the call is not attributed (registration order is not known). */
    UNIQUE,

    /** Werkzeug orders rules by weight: a candidate that is more static in the first differing segment wins. */
    STATIC_BEATS_DYNAMIC,
}

/** A keyword the constructor call must pass; when [callees] is not empty its value must be a call of one of these names. */
data class KeywordRequirement(val keyword: String, val callees: Set<String> = emptySet())

/**
 * One client family.
 * - [targets]: the frameworks whose endpoints this client can reach (a Starlette client never reaches a Django view).
 * - [constructorOrigins]: import origins of the client class; a name is never recognized by the method it calls.
 * - [requiresAny]: when not empty, the constructor call must satisfy one of them (`httpx.AsyncClient` is an in-process client
 *   only with an ASGI transport; without it the client talks to a running server and its calls are not attributed).
 * - [factoryMethods]: `app.test_client()` style factories.
 * - [fixtureNames]: pytest fixture or parameter names that mean this client when no fixture definition says otherwise.
 * - [classAttributes] with [baseClassOrigins]: `self.client` of a test case that derives from one of the bases.
 * - [implicitVerbs]: extra endpoint verbs a call verb also reaches (werkzeug answers HEAD from a GET rule).
 */
data class ClientSink(
    val id: String,
    val targets: Set<Framework>,
    val constructorOrigins: Set<String>,
    val requiresAny: List<KeywordRequirement> = emptyList(),
    val factoryMethods: Set<String> = emptySet(),
    val fixtureNames: Set<String> = emptySet(),
    val classAttributes: Set<String> = emptySet(),
    val baseClassOrigins: Set<String> = emptySet(),
    val secondPositional: SecondPositional = SecondPositional.NONE,
    val matchPolicy: MatchPolicy = MatchPolicy.UNIQUE,
    val implicitVerbs: Map<HttpMethod, Set<HttpMethod>> = emptyMap(),
    /** Path prefix is read from the constructor keyword `base_url`. */
    val baseUrlKeyword: String? = "base_url",
)

/**
 * A function that turns a route name into a path: [origins] when it is a plain function (checked through the import table,
 * `from django.urls import reverse`), [asMethod] when it is a method of some application object (`app.url_path_for(...)`).
 * [argsStyle] says how arguments name the path parameters.
 */
data class NameResolver(
    val name: String,
    val origins: Set<String>,
    val asMethod: Boolean,
    val frameworks: Set<Framework>,
    val argsStyle: ArgsStyle,
)

enum class ArgsStyle {
    /** `reverse(name, args=[...], kwargs={...})` */
    DJANGO_REVERSE,

    /** `url_for(name, **values)`: keywords that start with an underscore are options (`_external`). */
    KEYWORDS,
}

object ClientSinks {
    private val django = setOf(Framework.DJANGO, Framework.DRF)

    private val starlette = ClientSink(
        id = "starlette",
        targets = setOf(Framework.FASTAPI),
        constructorOrigins = setOf("starlette.testclient.TestClient", "fastapi.testclient.TestClient"),
        fixtureNames = setOf("client", "async_client", "test_client", "ac"),
    )

    /** An httpx client is an in-process client only when it carries an ASGI transport (or the old `app=` argument). */
    private val httpxAsgi = ClientSink(
        id = "httpx-asgi",
        targets = setOf(Framework.FASTAPI),
        constructorOrigins = setOf("httpx.AsyncClient"),
        requiresAny = listOf(KeywordRequirement("transport", setOf("ASGITransport")), KeywordRequirement("app")),
    )

    private val flask = ClientSink(
        id = "flask",
        targets = setOf(Framework.FLASK),
        constructorOrigins = setOf("flask.testing.FlaskClient"),
        factoryMethods = setOf("test_client"),
        fixtureNames = setOf("client", "test_client", "c"),
        matchPolicy = MatchPolicy.STATIC_BEATS_DYNAMIC,
        implicitVerbs = mapOf(HttpMethod.HEAD to setOf(HttpMethod.GET)),
    )

    private val djangoClient = ClientSink(
        id = "django",
        targets = django,
        constructorOrigins = setOf(
            "django.test.Client", "django.test.client.Client", "django.test.AsyncClient", "django.test.client.AsyncClient",
        ),
        fixtureNames = setOf("client", "admin_client", "async_client"),
        classAttributes = setOf("client", "async_client"),
        baseClassOrigins = setOf(
            "django.test.TestCase", "django.test.SimpleTestCase", "django.test.TransactionTestCase", "django.test.LiveServerTestCase",
            "django.test.testcases.TestCase", "django.test.testcases.SimpleTestCase", "django.test.testcases.TransactionTestCase",
            "django.test.testcases.LiveServerTestCase", "django.contrib.staticfiles.testing.StaticLiveServerTestCase",
        ),
        secondPositional = SecondPositional.DJANGO_DATA,
    )

    private val drf = ClientSink(
        id = "drf",
        targets = django,
        constructorOrigins = setOf("rest_framework.test.APIClient"),
        fixtureNames = setOf("api_client"),
        classAttributes = setOf("client"),
        baseClassOrigins = setOf(
            "rest_framework.test.APITestCase", "rest_framework.test.APISimpleTestCase", "rest_framework.test.APITransactionTestCase",
            "rest_framework.test.APILiveServerTestCase",
        ),
        secondPositional = SecondPositional.DJANGO_DATA,
    )

    val all: List<ClientSink> = listOf(starlette, httpxAsgi, flask, djangoClient, drf)

    /** The verb methods every client has: the method name is the HTTP verb. */
    private val verbSlot = Slot(setOf("url", "path"), 0)
    private val verbNames = listOf("get", "post", "put", "patch", "delete", "head", "options", "trace")

    /**
     * Call shapes by method name, the union over all families: `request("PUT", url)` (Starlette, httpx), `open(url, method=)`
     * (Flask), `generic("PUT", path)` (Django). Which receiver is a client is decided later; a method name alone never is.
     */
    val callShapes: Map<String, CallShape> =
        verbNames.associateWith { name -> CallShape(HttpMethod.parse(name), null, verbSlot) } + mapOf(
            "request" to CallShape(null, Slot(setOf("method"), 0), Slot(setOf("url", "path"), 1)),
            "generic" to CallShape(null, Slot(setOf("method"), 0), Slot(setOf("path", "url"), 1)),
            "open" to CallShape(null, Slot(setOf("method"), -1), Slot(setOf("path", "url"), 0)),
        )

    /** Keywords that carry a part of the request, kept as literal values for the execution view. */
    val partKeywords: Set<String> = setOf("params", "query_string", "headers", "json", "data", "content", "content_type", "format")

    /** Django passes headers as `HTTP_NAME=value` keywords. */
    const val HEADER_KEYWORD_PREFIX = "HTTP_"

    val nameResolvers: List<NameResolver> = listOf(
        NameResolver("reverse", setOf("django.urls.reverse", "rest_framework.reverse.reverse", "django.core.urlresolvers.reverse"), false, django, ArgsStyle.DJANGO_REVERSE),
        NameResolver("reverse_lazy", setOf("django.urls.reverse_lazy"), false, django, ArgsStyle.DJANGO_REVERSE),
        NameResolver("url_for", setOf("flask.url_for", "flask.helpers.url_for"), false, setOf(Framework.FLASK), ArgsStyle.KEYWORDS),
        NameResolver("url_path_for", emptySet(), true, setOf(Framework.FASTAPI), ArgsStyle.KEYWORDS),
    )

    /** Hosts a test client serves in process: a URL with one of them is routed by its path alone. */
    val localHosts: Set<String> = setOf("testserver", "test", "localhost", "127.0.0.1", "[::1]")

    /** Methods called on a receiver: one marker per name, for the quick text check and the extractor. */
    val callMethodNames: Set<String> = callShapes.keys

    /** Constructor and factory names (last name segment) that can start a binding. */
    val constructorNames: Set<String> =
        all.flatMap { sink -> sink.constructorOrigins.map { it.substringAfterLast('.') } + sink.factoryMethods }.toSet()

    /** Last names of the base classes that give a test case a client attribute. */
    val baseClassNames: Set<String> = all.flatMap { sink -> sink.baseClassOrigins.map { it.substringAfterLast('.') } }.toSet()

    /** Words that make a file a candidate through the word index: client names, fixture names, client attributes, bases. */
    val candidateWords: Set<String> =
        constructorNames + baseClassNames + all.flatMap { it.fixtureNames + it.classAttributes }.toSet()

    fun resolver(name: String): NameResolver? = nameResolvers.firstOrNull { it.name == name }
}
