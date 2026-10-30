package com.routegenie.frameworks.flask

import com.routegenie.discovery.LinkResult
import com.routegenie.frameworks.common.RouteProviderTestCase
import com.routegenie.model.DiagnosticCode
import com.routegenie.model.Framework
import com.routegenie.model.SuggestionKind
import com.routegenie.model.UnresolvedSetting
import com.routegenie.pins.PinRules

/** Settings and pins for Flask: `app.config[...]`, `current_app.config`, `os.environ`, nested blueprints, and what stays unchanged. */
class FlaskPinsTest : RouteProviderTestCase(FlaskRouteProvider()) {
    private fun run(pins: Map<String, String>, vararg files: Pair<String, String>): LinkResult {
        files.forEach { (path, text) -> add(path, text) }
        return discoverAll(pins)
    }

    private fun LinkResult.setting(expression: String): UnresolvedSetting =
        settings.singleOrNull { it.expression == expression } ?: error("no $expression in ${settings.map { it.expression }}")

    private val usersBlueprint = "users.py" to """
        from flask import Blueprint

        bp = Blueprint("users", __name__)

        @bp.route("/list")
        def list_users(): ...

        @bp.post("/create")
        def create_user(): ...
    """

    fun testRegisterBlueprintPrefixFromAppConfigIsHiddenUntilPinned() {
        val files = arrayOf(
            usersBlueprint,
            "app.py" to """
                from flask import Flask
                from users import bp

                app = Flask(__name__)
                app.config["API_PREFIX"] = "/api"
                app.register_blueprint(bp, url_prefix=app.config["API_PREFIX"])
            """,
        )
        val hidden = run(emptyMap(), *files)
        assertTrue(hidden.endpoints.isEmpty())
        val setting = hidden.setting("app.config[\"API_PREFIX\"]")
        assertEquals(2, setting.routeCount)
        assertEquals(Framework.FLASK, setting.framework)
        assertEquals("app.py", setting.use.path)
        assertNull("an assignment elsewhere is not a default the plugin may use", setting.suggestion)
        assertEquals(listOf(DiagnosticCode.DYNAMIC_PREFIX), hidden.diagnostics.map { it.code })

        val pinned = discoverAll(mapOf("app.config[\"API_PREFIX\"]" to "/api"))
        assertEquals(listOf("GET /api/list", "POST /api/create"), routes(pinned))
        assertEquals(listOf("app.config[\"API_PREFIX\"]"), find(pinned, "GET", "/api/list").pins)
        assertTrue(pinned.settings.isEmpty())
    }

    fun testBlueprintConstructorPrefixFromAppConfigGetWithADefaultIsSuggested() {
        val result = run(
            emptyMap(),
            "users.py" to """
                from flask import Blueprint, current_app

                bp = Blueprint("users", __name__, url_prefix=current_app.config.get("USERS_PREFIX", "/users"))

                @bp.route("/list")
                def list_users(): ...
            """,
            "app.py" to """
                from flask import Flask
                from users import bp
                app = Flask(__name__)
                app.register_blueprint(bp)
            """,
        )
        val setting = result.setting("current_app.config[\"USERS_PREFIX\"]")
        assertEquals("/users", setting.suggestion?.value)
        assertEquals(SuggestionKind.CONFIG_DEFAULT, setting.suggestion?.kind)
        assertEquals(1, setting.routeCount)
    }

    fun testOsEnvironGetDefaultInABlueprintPrefixIsSuggestedAtTheUseSite() {
        val result = run(
            emptyMap(),
            "users.py" to """
                import os
                from flask import Blueprint

                bp = Blueprint("users", __name__, url_prefix=os.environ.get("USERS_PREFIX", "/u"))

                @bp.route("/list")
                def list_users(): ...
            """,
            "app.py" to "from flask import Flask\nfrom users import bp\napp = Flask(__name__)\napp.register_blueprint(bp)\n",
        )
        val setting = result.setting("os.environ[\"USERS_PREFIX\"]")
        assertEquals("/u", setting.suggestion?.value)
        assertEquals(SuggestionKind.ENV_DEFAULT, setting.suggestion?.kind)
        assertEquals("app.py", setting.use.path)
    }

    fun testARouteRuleBuiltFromTheConfigIsHiddenAndPinnable() {
        val files = arrayOf(
            "app.py" to """
                from flask import Flask
                app = Flask(__name__)

                @app.route(f'{app.config["BASE"]}/ping')
                def ping(): ...
            """,
        )
        val hidden = run(emptyMap(), *files)
        assertTrue(hidden.endpoints.isEmpty())
        assertEquals(1, hidden.setting("app.config[\"BASE\"]").routeCount)
        assertEquals(listOf("GET /v1/ping"), routes(discoverAll(mapOf("app.config[\"BASE\"]" to "/v1"))))
    }

    fun testNestedBlueprintsWithASettingAtEachLevelAreAllReported() {
        val files = arrayOf(
            "inner.py" to """
                from flask import Blueprint
                inner = Blueprint("inner", __name__)
                @inner.route("/leaf")
                def leaf(): ...
            """,
            "app.py" to """
                from flask import Flask, Blueprint
                from inner import inner
                app = Flask(__name__)
                outer = Blueprint("outer", __name__)
                outer.register_blueprint(inner, url_prefix=app.config["INNER"])
                app.register_blueprint(outer, url_prefix=app.config["OUTER"])
            """,
        )
        val hidden = run(emptyMap(), *files)
        assertTrue(hidden.endpoints.isEmpty())
        assertEquals(setOf("app.config[\"INNER\"]", "app.config[\"OUTER\"]"), hidden.settings.map { it.expression }.toSet())
        assertTrue(hidden.settings.all { it.routeCount == 1 })
        val pinned = discoverAll(mapOf("app.config[\"INNER\"]" to "/in", "app.config[\"OUTER\"]" to "/out"))
        assertEquals(listOf("GET /out/in/leaf"), routes(pinned))
    }

    fun testEmptyPinGivesTheBlueprintAtTheRoot() {
        val result = run(
            mapOf("app.config[\"API_PREFIX\"]" to ""),
            usersBlueprint,
            "app.py" to """
                from flask import Flask
                from users import bp
                app = Flask(__name__)
                app.register_blueprint(bp, url_prefix=app.config["API_PREFIX"])
            """,
        )
        assertEquals(listOf("GET /list", "POST /create"), routes(result))
    }

    fun testAConfigClassLoadedWithFromObjectIsNotReadSoThereIsNoSuggestion() {
        val result = run(
            emptyMap(),
            usersBlueprint,
            "app.py" to """
                from flask import Flask
                from users import bp

                class Config:
                    API_PREFIX = "/cfg"

                app = Flask(__name__)
                app.config.from_object(Config)
                app.register_blueprint(bp, url_prefix=app.config["API_PREFIX"])
            """,
        )
        assertNull(result.setting("app.config[\"API_PREFIX\"]").suggestion)
    }

    fun testASettingNameThatLooksLikeASecretIsStillDetectedButCannotBePinned() {
        val result = run(
            emptyMap(),
            usersBlueprint,
            "app.py" to """
                from flask import Flask
                from users import bp
                app = Flask(__name__)
                app.register_blueprint(bp, url_prefix=app.config["SECRET_KEY"])
            """,
        )
        val setting = result.setting("app.config[\"SECRET_KEY\"]")
        assertTrue(PinRules.looksSecret(setting.expression))
    }

    fun testConfigReadsOutsideAnyPathAreIgnored() {
        val result = run(
            emptyMap(), usersBlueprint,
            "app.py" to """
                from flask import Flask
                from users import bp
                app = Flask(__name__)
                app.config["DEBUG"] = True
                debug = app.config["DEBUG"]
                app.register_blueprint(bp, url_prefix="/fixed")
            """,
        )
        assertEquals(listOf("GET /fixed/list", "POST /fixed/create"), routes(result))
        assertTrue(result.settings.isEmpty())
    }

    fun testALiteralPrefixAndRegisteredBlueprintsAreUntouched() {
        val result = run(
            emptyMap(), usersBlueprint,
            "app.py" to "from flask import Flask\nfrom users import bp\napp = Flask(__name__)\napp.register_blueprint(bp, url_prefix='/v1')\n",
        )
        assertEquals(listOf("GET /v1/list", "POST /v1/create"), routes(result))
        assertTrue(result.settings.isEmpty())
        assertTrue(result.diagnostics.isEmpty())
    }

    fun testAPinForAnotherExpressionChangesNothing() {
        val result = run(
            mapOf("app.config[\"OTHER\"]" to "/x"), usersBlueprint,
            "app.py" to """
                from flask import Flask
                from users import bp
                app = Flask(__name__)
                app.register_blueprint(bp, url_prefix=app.config["API_PREFIX"])
            """,
        )
        assertTrue(result.endpoints.isEmpty())
        assertEquals(2, result.setting("app.config[\"API_PREFIX\"]").routeCount)
    }

    fun testBlueprintRegisteredTwiceWithDifferentPinnedPrefixesListsBothCopies() {
        val result = run(
            mapOf("app.config[\"A\"]" to "/a", "app.config[\"B\"]" to "/b"),
            usersBlueprint,
            "app.py" to """
                from flask import Flask
                from users import bp
                app = Flask(__name__)
                app.register_blueprint(bp, url_prefix=app.config["A"])
                app.register_blueprint(bp, url_prefix=app.config["B"], name="second")
            """,
        )
        assertEquals(listOf("GET /a/list", "GET /b/list", "POST /a/create", "POST /b/create"), routes(result))
    }
}
