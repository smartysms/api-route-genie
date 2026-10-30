package com.routegenie.frameworks.flask

import com.routegenie.frameworks.common.RouteProviderTestCase
import com.routegenie.model.DiagnosticCode
import com.routegenie.model.Framework
import com.routegenie.model.PathParam

/**
 * Flask semantics, each observed against flask 3.1.3 / werkzeug (see the notes per test). Test names state the
 * observed truth. Methods are named testXxx because the platform base class is JUnit 3 style.
 */
class FlaskRoutesTest : RouteProviderTestCase(FlaskRouteProvider()) {
    /** Same indentation as the raw strings of the tests, so the shared trimIndent leaves the combined text valid Python. */
    private val appFile = "from flask import Flask, Blueprint\napp = Flask(__name__)".prependIndent(" ".repeat(12))

    /** Correct or absent: no endpoint, and exactly one diagnostic with [code] that says why. */
    private fun assertOmitted(result: com.routegenie.discovery.LinkResult, code: DiagnosticCode) {
        assertTrue("no endpoint expected: ${routes(result)}", result.endpoints.isEmpty())
        assertEquals(listOf(code), result.diagnostics.map { it.code })
    }

    fun testRoutesDeclaredInsideAnIfAreKept() {
        // Decision: a route behind a condition is still an endpoint of the project, so it is listed (the condition is not evaluated).
        val result = discover("main.py" to appFile + """
            import os
            if os.getenv("DEBUG"):
                @app.get("/debug")
                def debug(): ...
            else:
                @app.get("/prod")
                def prod(): ...
        """)
        assertEquals(listOf("GET /debug", "GET /prod"), routes(result))
    }

    fun testRouteWithoutMethodsIsGetOnly() {
        // HEAD and OPTIONS are added by Flask itself and deliberately not listed.
        val result = discover("app.py" to appFile + """
            @app.route("/hello")
            def hello(): ...
        """)
        assertEquals(listOf("GET /hello"), routes(result))
        assertEquals(Framework.FLASK, result.endpoints.single().framework)
    }

    fun testRouteMethodsListYieldsOneEndpointPerMethod() {
        val result = discover("app.py" to appFile + """
            @app.route("/login", methods=["GET", "post"])
            def login(): ...
        """)
        assertEquals(listOf("GET /login", "POST /login"), routes(result))
    }

    fun testShortcutDecoratorsFixTheMethod() {
        val result = discover("app.py" to appFile + """
            @app.get("/a")
            def a(): ...
            @app.post("/b")
            def b(): ...
            @app.put("/c")
            def c(): ...
            @app.patch("/d")
            def d(): ...
            @app.delete("/e")
            def e(): ...
        """)
        assertEquals(listOf("DELETE /e", "GET /a", "PATCH /d", "POST /b", "PUT /c"), routes(result))
    }

    fun testConvertersBecomeBracesWithTypes() {
        val result = discover("app.py" to appFile + """
            @app.route("/u/<int:id>/f/<path:p>/<name>/<string(length=2):lang>")
            def u(id, p, name, lang): ...
        """)
        val endpoint = result.endpoints.single()
        assertEquals("/u/{id}/f/{p}/{name}/{lang}", endpoint.path)
        assertEquals(
            listOf(PathParam("id", "int"), PathParam("p", "path"), PathParam("name"), PathParam("lang", "string")),
            endpoint.pathParams,
        )
    }

    fun testEndpointNameDefaultsToTheFunctionAndCanBeOverridden() {
        val result = discover("app.py" to appFile + """
            @app.route("/a")
            def view_a(): ...
            @app.route("/b", endpoint="custom")
            def view_b(): ...
        """)
        assertEquals("view_a", find(result, "GET", "/a").routeName)
        assertEquals("custom", find(result, "GET", "/b").routeName)
    }

    fun testBlueprintOwnUrlPrefixAppliesWhenRegisteredWithoutOverride() {
        val result = discover("app.py" to appFile + """
            bp = Blueprint("auth", __name__, url_prefix="/auth")
            @bp.route("/login")
            def login(): ...
            app.register_blueprint(bp)
        """)
        assertEquals(listOf("GET /auth/login"), routes(result))
        assertEquals("auth.login", result.endpoints.single().routeName)
    }

    fun testRegisterTimePrefixReplacesTheBlueprintPrefix() {
        // Observed: register_blueprint(url_prefix=...) replaces the blueprint's own url_prefix, it does not add to it.
        val result = discover("app.py" to appFile + """
            bp = Blueprint("api", __name__, url_prefix="/v1")
            @bp.route("/x")
            def x(): ...
            app.register_blueprint(bp, url_prefix="/v2")
        """)
        assertEquals(listOf("GET /v2/x"), routes(result))
    }

    fun testBlueprintWithoutAnyPrefixIsMountedAtTheRoot() {
        val result = discover("app.py" to appFile + """
            g = Blueprint("g", __name__)
            @g.route("/g")
            def gg(): ...
            app.register_blueprint(g)
        """)
        assertEquals(listOf("GET /g"), routes(result))
    }

    fun testEmptyRuleOnPrefixedBlueprintIsThePrefixAndSlashKeepsTheSlash() {
        // Observed: "" -> /top, "/" -> /top/ (flask joins with exactly one slash and keeps a trailing one).
        val result = discover("app.py" to appFile + """
            p = Blueprint("p", __name__, url_prefix="/p")
            @p.route("")
            def e(): ...
            @p.route("/")
            def s(): ...
            app.register_blueprint(p, url_prefix="/top")
        """)
        assertEquals(listOf("GET /top", "GET /top/"), routes(result))
    }

    fun testNestedBlueprintPrefixesAreAppendedAndNamesDotJoined() {
        // Observed: /top/c/<int:id>/ with endpoint p.c.ci, and the child registered with url_prefix="/over" gives /top/over/g.
        val result = discover("app.py" to appFile + """
            p = Blueprint("p", __name__, url_prefix="/p")
            c = Blueprint("c", __name__, url_prefix="/c")
            g = Blueprint("g", __name__)
            @c.route("/<int:id>/", methods=["POST"])
            def ci(id): ...
            @g.route("/g")
            def gg(): ...
            p.register_blueprint(c)
            p.register_blueprint(g, url_prefix="/over")
            app.register_blueprint(p, url_prefix="/top")
            app.register_blueprint(g)
        """)
        assertEquals(listOf("GET /g", "GET /top/over/g", "POST /top/c/{id}/"), routes(result))
        assertEquals("p.c.ci", find(result, "POST", "/top/c/{id}/").routeName)
        assertEquals("p.g.gg", find(result, "GET", "/top/over/g").routeName)
        assertEquals("g.gg", find(result, "GET", "/g").routeName)
    }

    fun testSameBlueprintRegisteredTwiceIsExpandedTwice() {
        val result = discover("app.py" to appFile + """
            bp = Blueprint("bp", __name__)
            @bp.route("/x")
            def x(): ...
            app.register_blueprint(bp, url_prefix="/one")
            app.register_blueprint(bp, url_prefix="/two", name="bp2")
        """)
        assertEquals(listOf("GET /one/x", "GET /two/x"), routes(result))
        assertEquals("bp2.x", find(result, "GET", "/two/x").routeName)
    }

    fun testBlueprintRegisteredFromAFactoryInAnotherFile() {
        val result = discover(
            "pkg/__init__.py" to """
                from flask import Flask
                from .auth import bp as auth_bp
                from . import views
                def create_app():
                    app = Flask(__name__)
                    app.register_blueprint(auth_bp, url_prefix="/api")
                    app.register_blueprint(views.bp)
                    @app.route("/health")
                    def health(): ...
                    return app
            """,
            "pkg/auth.py" to """
                from flask import Blueprint
                bp = Blueprint("auth", __name__, url_prefix="/auth")
                @bp.route("/login", methods=["GET", "POST"])
                def login(): ...
                @bp.get("/me")
                def me(): ...
            """,
            "pkg/views.py" to """
                from flask import Blueprint
                bp = Blueprint("views", __name__, url_prefix="/v")
                @bp.route("/")
                def index(): ...
            """,
        )
        assertEquals(
            listOf("GET /api/login", "GET /api/me", "GET /health", "GET /v/", "POST /api/login"),
            routes(result),
        )
        assertEquals("pkg.auth.login", find(result, "GET", "/api/login").handler.qualifiedName)
    }

    fun testAddUrlRuleWithAFunction() {
        val result = discover("app.py" to appFile + """
            def view(): ...
            app.add_url_rule("/rule", view_func=view, methods=["PUT"])
            app.add_url_rule("/named", "my_endpoint", view)
        """)
        assertEquals(listOf("GET /named", "PUT /rule"), routes(result))
        assertEquals("my_endpoint", find(result, "GET", "/named").routeName)
        assertEquals("app.view", find(result, "PUT", "/rule").handler.qualifiedName)
    }

    fun testMethodViewMethodsComeFromTheDefinedVerbHandlers() {
        val result = discover(
            "app.py" to """
                from flask import Flask
                from flask.views import MethodView
                app = Flask(__name__)
                class UserAPI(MethodView):
                    def get(self): ...
                    def post(self): ...
                    def helper(self): ...
                app.add_url_rule("/users/", view_func=UserAPI.as_view("users"))
            """,
        )
        assertEquals(listOf("GET /users/", "POST /users/"), routes(result))
        val endpoint = find(result, "GET", "/users/")
        assertEquals("users", endpoint.routeName)
        assertEquals("app.UserAPI", endpoint.handler.qualifiedName)
    }

    fun testMethodViewInheritedThroughAProjectBaseClass() {
        val result = discover(
            "base.py" to """
                from flask.views import MethodView
                class Base(MethodView):
                    def delete(self): ...
            """,
            "app.py" to """
                from flask import Flask
                import base
                app = Flask(__name__)
                class Item(base.Base):
                    def get(self): ...
                app.add_url_rule("/item", view_func=Item.as_view("item"))
            """,
        )
        assertEquals(listOf("DELETE /item", "GET /item"), routes(result))
    }

    fun testPlainViewUsesItsMethodsAttributeOrGet() {
        val result = discover("app.py" to """
            from flask import Flask
            from flask.views import View
            app = Flask(__name__)
            class A(View):
                methods = ["GET", "POST"]
                def dispatch_request(self): ...
            class B(View):
                def dispatch_request(self): ...
            app.add_url_rule("/a", view_func=A.as_view("a"))
            app.add_url_rule("/b", view_func=B.as_view("b"))
        """)
        assertEquals(listOf("GET /a", "GET /b", "POST /a"), routes(result))
    }

    fun testViewClassThatIsNotAFlaskViewIsReportedNotGuessed() {
        val result = discover("app.py" to appFile + """
            class Other(Mystery):
                def get(self): ...
            app.add_url_rule("/o", view_func=Other.as_view("o"))
        """)
        assertTrue(result.endpoints.isEmpty())
        assertEquals(listOf(DiagnosticCode.UNRESOLVED_VIEW), result.diagnostics.map { it.code })
    }

    fun testViewClassThatCannotBeFoundIsReported() {
        val result = discover("app.py" to appFile + """
            app.add_url_rule("/m", view_func=missing.Thing.as_view("m"))
        """)
        assertTrue(result.endpoints.isEmpty())
        assertEquals(listOf(DiagnosticCode.UNRESOLVED_VIEW), result.diagnostics.map { it.code })
    }

    fun testPrefixFromConfigOmitsTheEndpoint() {
        val result = discover("app.py" to appFile + """
            import os
            bp = Blueprint("b", __name__, url_prefix=os.environ["PFX"])
            @bp.route("/x")
            def x(): ...
            app.register_blueprint(bp)
        """)
        assertOmitted(result, DiagnosticCode.DYNAMIC_PREFIX)
    }

    fun testPrefixFromConstantIsResolved() {
        val result = discover(
            "consts.py" to "API = '/api'\n",
            "app.py" to appFile + """
                from consts import API
                bp = Blueprint("b", __name__, url_prefix=API)
                @bp.route("/x")
                def x(): ...
                app.register_blueprint(bp)
            """,
        )
        assertEquals(listOf("GET /api/x"), routes(result))
        assertFalse(result.endpoints.single().isPartial)
    }

    fun testDynamicRuleIsOmitted() {
        val result = discover("app.py" to appFile + """
            @app.route(rule_for("x"))
            def x(): ...
        """)
        assertOmitted(result, DiagnosticCode.DYNAMIC_VALUE)
    }

    fun testBlueprintThatIsNeverRegisteredIsOmitted() {
        val result = discover("app.py" to appFile + """
            lonely = Blueprint("lonely", __name__, url_prefix="/lonely")
            @lonely.route("/x")
            def x(): ...
        """)
        assertOmitted(result, DiagnosticCode.UNATTACHED_ROUTER)
    }

    fun testRegistrationOnAParameterIsNotGuessedAndTheRoutesAreOmitted() {
        val result = discover(
            "app.py" to """
                from flask import Flask
                from ext import init_blueprints
                app = Flask(__name__)
            """,
            "ext.py" to """
                from flask import Blueprint
                bp = Blueprint("ext", __name__, url_prefix="/ext")
                @bp.route("/x")
                def x(): ...
                def init_blueprints(app):
                    app.register_blueprint(bp)
            """,
        )
        assertOmitted(result, DiagnosticCode.UNRESOLVED_RECEIVER)
    }

    fun testReceiverThatIsAFunctionParameterIsOmitted() {
        val result = discover("app.py" to """
            from flask import Flask
            def setup(app):
                @app.route("/param")
                def p(): ...
        """)
        assertOmitted(result, DiagnosticCode.UNRESOLVED_RECEIVER)
    }

    fun testDecoratorsOnObjectsThatAreNotFlaskAreIgnored() {
        val result = discover("other.py" to """
            import click
            @click.group()
            def cli(): ...
            @cli.route("/not-flask")
            def x(): ...
        """)
        assertTrue(result.endpoints.isEmpty())
    }

    fun testAliasedImportsAndModuleAttributeConstructors() {
        val result = discover("app.py" to """
            import flask
            from flask import Blueprint as BP
            app = flask.Flask(__name__)
            bp = BP("b", __name__, url_prefix="/b")
            @bp.route("/x")
            def x(): ...
            app.register_blueprint(bp)
        """)
        assertEquals(listOf("GET /b/x"), routes(result))
    }

    fun testStackedRouteDecoratorsGiveTwoRulesForOneHandler() {
        val result = discover("app.py" to appFile + """
            @app.route("/", defaults={"page": 1})
            @app.route("/page/<int:page>")
            def index(page): ...
        """)
        assertEquals(listOf("GET /", "GET /page/{page}"), routes(result))
    }

    fun testRegistrationCycleIsCutOff() {
        val result = discover("app.py" to appFile + """
            a = Blueprint("a", __name__, url_prefix="/a")
            b = Blueprint("b", __name__, url_prefix="/b")
            @a.route("/x")
            def x(): ...
            a.register_blueprint(b)
            b.register_blueprint(a)
            app.register_blueprint(a)
        """)
        assertEquals(listOf("GET /a/x"), routes(result))
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.INCLUDE_CYCLE })
    }

    fun testComputedMethodsAreOmitted() {
        val result = discover("app.py" to appFile + """
            @app.route("/m", methods=pick_methods())
            def m(): ...
        """)
        assertOmitted(result, DiagnosticCode.DYNAMIC_VALUE)
    }

    fun testFilesWithoutFrameworkWordsAreNotCandidates() {
        add("plain.py", "def f():\n    return 1\n")
        add("app.py", "from flask import Flask\napp = Flask(__name__)\n@app.route('/x')\ndef x(): ...\n")
        val names = FlaskRouteProvider().findCandidates(project).map { it.name }
        assertTrue("app.py" in names)
        assertFalse("plain.py" in names)
    }

    fun testBlueprintNameFromKeywordAndRouteViewFileWithoutFlaskImport() {
        // A views file only imports the blueprint from elsewhere; it has no flask import but still declares routes.
        val result = discover(
            "bp.py" to """
                from flask import Blueprint
                bp = Blueprint(name="kw", import_name=__name__, url_prefix="/k")
            """,
            "views.py" to """
                from bp import bp
                @bp.route("/x")
                def x(): ...
            """,
            "main.py" to """
                from flask import Flask
                from bp import bp
                import views
                app = Flask(__name__)
                app.register_blueprint(bp)
            """,
        )
        assertEquals(listOf("GET /k/x"), routes(result))
        assertEquals("kw.x", result.endpoints.single().routeName)
    }

    fun testSourcePointsAtTheDecoratorLine() {
        val result = discover("app.py" to appFile + """
            @app.route("/line")
            def line(): ...
        """)
        val source = result.endpoints.single().source
        assertEquals(3, source.line)
        assertTrue(source.path.endsWith("app.py"))
    }
}
