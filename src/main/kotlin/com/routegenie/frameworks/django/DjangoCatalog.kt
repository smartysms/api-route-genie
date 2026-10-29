package com.routegenie.frameworks.django

import com.routegenie.model.HttpMethod

/**
 * Every fact about Django and Django REST Framework that the extractor and the linker rely on: import roots, function,
 * decorator and class names, and which HTTP methods a library base class contributes. Nothing outside this object types
 * one of these names, so supporting another alias or base class is a one-line change here.
 */
internal object DjangoCatalog {
    /** Identifies this provider in logs and facts; the DRF rows are produced by the same provider. */
    const val PROVIDER_ID = "django"

    const val DJANGO_ROOT = "django"
    const val DRF_ROOT = "rest_framework"

    /**
     * Words whose presence in a file makes it a candidate; looked up in the word index, so no file is parsed for this.
     * `register` finds files that only add viewsets to a router imported from elsewhere. The framework's own name is not a
     * word (every models and admin file mentions it): view modules are reached from the url modules instead.
     */
    val CANDIDATE_WORDS = listOf("ROOT_URLCONF", "urlpatterns", "register", "DefaultRouter", "SimpleRouter")

    /** What a candidate's text must contain before it is parsed; `register` is a common word, a call of it is not. */
    val TEXT_MARKERS = CANDIDATE_WORDS.map { if (it == "register") ".register(" else it }

    const val SETTING_ROOT_URLCONF = "ROOT_URLCONF"
    const val URLPATTERNS = "urlpatterns"
    const val APP_NAME = "app_name"

    const val ROUTER_URLS = "urls"

    /** `admin.site.urls` is opaque: it is listed as one mount point, never expanded. */
    const val ADMIN_SITE_URLS_SUFFIX = "site.urls"


    // --- Registration primitives: what each recognized call or decorator means, as data -----------------------------------

    /** Where an argument sits: by position and/or by keyword (the first one present wins). */
    data class Arg(val position: Int?, val keywords: List<String> = emptyList())

    enum class Kind {
        /** `path(route, view, kwargs, name)`: a route in a pattern list, route text in converter syntax. */
        ROUTE_PATH,

        /** `re_path(regex, view, ...)`: the same with a regular expression. */
        ROUTE_REGEX,

        /** `include(x)`: delegates to another pattern list, module or router. */
        INCLUDE,

        /** Wraps a pattern list and is transparent for the route table. */
        UNWRAP,

        /** Helpers that add things that are not endpoints. */
        IGNORED,

        /** A view decorator restricting the HTTP methods: fixed ones, or the ones listed in [Primitive.methods]. */
        METHODS_DECORATOR,

        /** `@action` on a viewset method. */
        ACTION_DECORATOR,

        /** `Router(...)` constructors of DRF; the kwargs below are its options. */
        ROUTER_CLASS,

        /** `router.register(prefix, viewset, basename)`. */
        REGISTER_METHOD,

        /** `urlpatterns.append(x)` and `.extend(x)`: mutate a pattern list. */
        LIST_MUTATOR,

        /** `View.as_view(...)`: class based view adapter; carries an optional action map. */
        AS_VIEW,
    }

    class Primitive(
        val name: String,
        /** Import root the name must come from; null for methods called on an object (register, append, as_view). */
        val root: String?,
        val kind: Kind,
        val route: Arg? = null,
        val view: Arg? = null,
        val routeName: Arg? = null,
        val namespace: Arg? = null,
        val prefix: Arg? = null,
        val basename: Arg? = null,
        val methods: Arg? = null,
        val fixedMethods: List<HttpMethod>? = null,
        /** Methods that apply when the decorator names none; null when no method restriction is implied. */
        val defaultMethods: List<HttpMethod>? = null,
        /** The decorator turns the function into a DRF API view. */
        val apiView: Boolean = false,
        /** Option keywords of a router constructor or action decorator, by role. */
        val options: Map<String, String> = emptyMap(),
    )

    const val OPT_TRAILING_SLASH = "trailing_slash"
    const val OPT_ROOT_VIEW = "include_root_view"
    const val OPT_FORMAT_SUFFIXES = "include_format_suffixes"
    const val OPT_DETAIL = "detail"
    const val OPT_URL_PATH = "url_path"
    const val OPT_URL_NAME = "url_name"
    const val OPT_ACTIONS = "actions"

    private val pathArgs = Triple(Arg(0, listOf("route", "regex")), Arg(1, listOf("view")), Arg(3, listOf("name")))

    val PRIMITIVES: List<Primitive> = listOf(
        Primitive("path", DJANGO_ROOT, Kind.ROUTE_PATH, route = pathArgs.first, view = pathArgs.second, routeName = pathArgs.third),
        Primitive("re_path", DJANGO_ROOT, Kind.ROUTE_REGEX, route = pathArgs.first, view = pathArgs.second, routeName = pathArgs.third),
        Primitive("url", DJANGO_ROOT, Kind.ROUTE_REGEX, route = pathArgs.first, view = pathArgs.second, routeName = pathArgs.third),
        Primitive("include", DJANGO_ROOT, Kind.INCLUDE, view = Arg(0), namespace = Arg(null, listOf("namespace"))),
        Primitive("format_suffix_patterns", DRF_ROOT, Kind.UNWRAP),
        Primitive("i18n_patterns", DJANGO_ROOT, Kind.UNWRAP),
        Primitive("static", DJANGO_ROOT, Kind.IGNORED),
        Primitive("staticfiles_urlpatterns", DJANGO_ROOT, Kind.IGNORED),
        Primitive("require_GET", DJANGO_ROOT, Kind.METHODS_DECORATOR, fixedMethods = listOf(HttpMethod.GET)),
        Primitive("require_POST", DJANGO_ROOT, Kind.METHODS_DECORATOR, fixedMethods = listOf(HttpMethod.POST)),
        Primitive("require_safe", DJANGO_ROOT, Kind.METHODS_DECORATOR, fixedMethods = listOf(HttpMethod.GET, HttpMethod.HEAD)),
        Primitive("require_http_methods", DJANGO_ROOT, Kind.METHODS_DECORATOR, methods = Arg(0, listOf("request_method_list"))),
        Primitive(
            "api_view", DRF_ROOT, Kind.METHODS_DECORATOR, methods = Arg(0, listOf("http_method_names")),
            defaultMethods = listOf(HttpMethod.GET), apiView = true,
        ),
        Primitive(
            "action", DRF_ROOT, Kind.ACTION_DECORATOR, methods = Arg(null, listOf("methods")), defaultMethods = listOf(HttpMethod.GET),
            options = mapOf(OPT_DETAIL to "detail", OPT_URL_PATH to "url_path", OPT_URL_NAME to "url_name"),
        ),
        Primitive(
            "DefaultRouter", DRF_ROOT, Kind.ROUTER_CLASS,
            options = mapOf(OPT_TRAILING_SLASH to "trailing_slash", OPT_ROOT_VIEW to "include_root_view", OPT_FORMAT_SUFFIXES to "include_format_suffixes"),
        ),
        Primitive("SimpleRouter", DRF_ROOT, Kind.ROUTER_CLASS, options = mapOf(OPT_TRAILING_SLASH to "trailing_slash")),
        Primitive("register", null, Kind.REGISTER_METHOD, prefix = Arg(0), view = Arg(1), basename = Arg(2, listOf("basename", "base_name"))),
        Primitive("append", null, Kind.LIST_MUTATOR, view = Arg(0)),
        Primitive("extend", null, Kind.LIST_MUTATOR, view = Arg(0)),
        Primitive("as_view", null, Kind.AS_VIEW, view = Arg(0, listOf(OPT_ACTIONS))),
    )

    private val byName: Map<String, Primitive> = PRIMITIVES.associateBy { it.name }

    /** The primitive called [name], whatever its origin; the caller checks the import origin with [Primitive.root]. */
    fun primitive(name: String): Primitive? = byName[name]

    fun primitive(name: String, kind: Kind): Primitive? = byName[name]?.takeIf { it.kind == kind }


    // --- Path converters (django.urls.converters) ---------------------------------------------------------------------
    const val DEFAULT_CONVERTER = "str"

    // --- Class based views --------------------------------------------------------------------------------------------
    const val ATTR_HTTP_METHOD_NAMES = "http_method_names"
    const val ATTR_QUERYSET = "queryset"
    const val ATTR_LOOKUP_FIELD = "lookup_field"
    const val ATTR_LOOKUP_URL_KWARG = "lookup_url_kwarg"

    /** Handler method names a View can define: the lower case HTTP verbs. */
    val HANDLER_NAMES: Set<String> = HttpMethod.entries.filter { it.isConcrete }.map { it.name.lowercase() }.toSet()

    /** Methods that generic views of django.views.generic answer; derived from their base classes (ProcessFormView defines put). */
    val DJANGO_VIEW_METHODS: Map<String, List<HttpMethod>> = mapOf(
        "View" to emptyList(),
        "TemplateView" to listOf(HttpMethod.GET),
        "ListView" to listOf(HttpMethod.GET),
        "DetailView" to listOf(HttpMethod.GET),
        "ArchiveIndexView" to listOf(HttpMethod.GET),
        "YearArchiveView" to listOf(HttpMethod.GET),
        "MonthArchiveView" to listOf(HttpMethod.GET),
        "WeekArchiveView" to listOf(HttpMethod.GET),
        "DayArchiveView" to listOf(HttpMethod.GET),
        "TodayArchiveView" to listOf(HttpMethod.GET),
        "DateDetailView" to listOf(HttpMethod.GET),
        "FormView" to listOf(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT),
        "CreateView" to listOf(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT),
        "UpdateView" to listOf(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT),
        "DeleteView" to listOf(HttpMethod.GET, HttpMethod.POST, HttpMethod.DELETE),
        "RedirectView" to listOf(
            HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST, HttpMethod.OPTIONS, HttpMethod.DELETE, HttpMethod.PUT, HttpMethod.PATCH,
        ),
    )

    /** Methods contributed by the concrete generic API views of rest_framework.generics. */
    val DRF_VIEW_METHODS: Map<String, List<HttpMethod>> = mapOf(
        "APIView" to emptyList(),
        "GenericAPIView" to emptyList(),
        "ListAPIView" to listOf(HttpMethod.GET),
        "CreateAPIView" to listOf(HttpMethod.POST),
        "RetrieveAPIView" to listOf(HttpMethod.GET),
        "DestroyAPIView" to listOf(HttpMethod.DELETE),
        "UpdateAPIView" to listOf(HttpMethod.PUT, HttpMethod.PATCH),
        "ListCreateAPIView" to listOf(HttpMethod.GET, HttpMethod.POST),
        "RetrieveUpdateAPIView" to listOf(HttpMethod.GET, HttpMethod.PUT, HttpMethod.PATCH),
        "RetrieveDestroyAPIView" to listOf(HttpMethod.GET, HttpMethod.DELETE),
        "RetrieveUpdateDestroyAPIView" to listOf(HttpMethod.GET, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE),
    )

    // --- DRF routers and viewsets ------------------------------------------------------------------------------------
    const val DEFAULT_TRAILING_SLASH = "/"

    const val ACTION_LIST = "list"
    const val ACTION_CREATE = "create"
    const val ACTION_RETRIEVE = "retrieve"
    const val ACTION_UPDATE = "update"
    const val ACTION_PARTIAL_UPDATE = "partial_update"
    const val ACTION_DESTROY = "destroy"
    private val ALL_ACTIONS = setOf(ACTION_LIST, ACTION_CREATE, ACTION_RETRIEVE, ACTION_UPDATE, ACTION_PARTIAL_UPDATE, ACTION_DESTROY)

    /** The standard action a router row maps each HTTP verb to (SimpleRouter.routes). */
    val LIST_ROUTE_MAP = listOf(HttpMethod.GET to ACTION_LIST, HttpMethod.POST to ACTION_CREATE)
    val DETAIL_ROUTE_MAP = listOf(
        HttpMethod.GET to ACTION_RETRIEVE,
        HttpMethod.PUT to ACTION_UPDATE,
        HttpMethod.PATCH to ACTION_PARTIAL_UPDATE,
        HttpMethod.DELETE to ACTION_DESTROY,
    )

    /** Standard actions each library viewset or mixin base class provides. */
    val VIEWSET_ACTIONS: Map<String, Set<String>> = mapOf(
        "ViewSet" to emptySet(),
        "GenericViewSet" to emptySet(),
        "ViewSetMixin" to emptySet(),
        "ModelViewSet" to ALL_ACTIONS,
        "ReadOnlyModelViewSet" to setOf(ACTION_LIST, ACTION_RETRIEVE),
        "ListModelMixin" to setOf(ACTION_LIST),
        "CreateModelMixin" to setOf(ACTION_CREATE),
        "RetrieveModelMixin" to setOf(ACTION_RETRIEVE),
        "UpdateModelMixin" to setOf(ACTION_UPDATE, ACTION_PARTIAL_UPDATE),
        "DestroyModelMixin" to setOf(ACTION_DESTROY),
    )

    /** A viewset implements an action either through a library base class or by defining a method of that name. */
    val STANDARD_ACTIONS: Set<String> = ALL_ACTIONS

    const val DEFAULT_LOOKUP_FIELD = "pk"
    const val LOOKUP_VALUE_REGEX = "[^/.]+"
    const val FORMAT_SUFFIX_PARAM = "format"
    const val FORMAT_SUFFIX_PATTERN = "[a-z0-9]+"
    const val ROOT_VIEW_NAME = "api-root"
    const val ROOT_VIEW_HANDLER = "rest_framework.routers.APIRootView"
    val ROOT_VIEW_METHODS = listOf(HttpMethod.GET)
    const val QUERYSET_MANAGER = "objects"

    /** Names that identify a class as part of DRF when its import cannot be followed (star imports). */
    val DRF_CLASS_NAMES: Set<String> = DRF_VIEW_METHODS.keys + VIEWSET_ACTIONS.keys
}
