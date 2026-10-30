package com.routegenie.discovery.core

import com.routegenie.discovery.SourceFacts
import com.routegenie.model.DiagnosticCode
import com.routegenie.model.SourceRef
import com.routegenie.model.StrValue
import com.routegenie.model.SuggestionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests of settings detection, suggestions and pins in the evaluator, on hand-built facts. */
class SettingsEvaluatorUnitTest {
    private class Source(
        override val imports: List<ImportBinding> = emptyList(),
        override val stars: List<StarBinding> = emptyList(),
        override val consts: List<ConstFact> = emptyList(),
        override val settingClasses: List<SettingsClassFact> = emptyList(),
        override val settingInstances: List<SettingsInstanceFact> = emptyList(),
    ) : ModuleSource, SourceFacts {
        override val definedNames: Set<String> = emptySet()
        override val wantedRefs: List<List<String>> = emptyList()
        override val wantedExprs: List<Expr> = emptyList()
        override val wantedModules: List<String> = emptyList()
        override val weight: Int = 1
    }

    private fun entry(path: String, source: Source) = ModuleEntry(path, ModuleNames.moduleOf(path, ""), ModuleNames.isPackage(path), source)

    private fun ref(vararg parts: String) = Ref(parts.toList())

    private fun attr(name: String, default: Expr?, kind: AttrKind = AttrKind.VALUE, line: Int = 7) = AttrFact(name, kind, default, line * 10, line)

    /** `app/config.py` with `class Settings(BaseSettings)` and `settings = Settings()`; `app/main.py` imports `settings`. */
    private fun template(attrs: List<AttrFact>, bases: List<String> = listOf("BaseSettings")): Pair<ModuleEntry, ModuleEntry> {
        val config = entry(
            "app/config.py",
            Source(settingClasses = listOf(SettingsClassFact("Settings", bases, attrs)), settingInstances = listOf(SettingsInstanceFact("settings", "Settings", false))),
        )
        val main = entry("app/main.py", Source(imports = listOf(ImportBinding("settings", "app.config", "settings"))))
        return config to main
    }

    private fun evaluate(
        entries: List<ModuleEntry>,
        owner: ModuleEntry,
        expr: Expr,
        pins: Map<String, String> = emptyMap(),
        collector: SettingsCollector? = SettingsCollector(),
        settings: Boolean = true,
        assumeEmpty: Boolean = false,
    ): StrValue = ExprEvaluator(ModuleTable(entries), SettingsContext(pins, collector, assumeEmpty)).evaluate(owner, expr, settings)

    @Test
    fun `an attribute of a settings object is unresolved and named by its text`() {
        val (config, main) = template(listOf(attr("API_V1_STR", Lit("/api/v1"))))
        val value = evaluate(listOf(config, main), main, ref("settings", "API_V1_STR"))
        assertTrue(value is StrValue.Unknown)
        assertEquals(listOf("settings.API_V1_STR"), value.unresolved)
    }

    @Test
    fun `a class default is suggested with the place it is written`() {
        val (config, main) = template(listOf(attr("API_V1_STR", Lit("/api/v1"), line = 12)))
        val collector = SettingsCollector()
        evaluate(listOf(config, main), main, ref("settings", "API_V1_STR"), collector = collector)
        val suggestion = collector.suggestions.getValue("settings.API_V1_STR")
        assertEquals("/api/v1", suggestion.value)
        assertEquals(SuggestionKind.CLASS_DEFAULT, suggestion.kind)
        assertEquals(SourceRef("app/config.py", 120, 12), suggestion.origin)
    }

    @Test
    fun `an empty literal default is a valid suggestion`() {
        val (config, main) = template(listOf(attr("BASE", Lit(""))))
        val collector = SettingsCollector()
        evaluate(listOf(config, main), main, ref("settings", "BASE"), collector = collector)
        assertEquals("", collector.suggestions.getValue("settings.BASE").value)
    }

    @Test
    fun `defaults that are not literals give no suggestion`() {
        val cases = listOf(
            attr("COMPUTED", Add(Lit("/a"), Lit("/b"))),
            attr("FACTORY", null, AttrKind.FACTORY),
            attr("REQUIRED", null, AttrKind.NONE),
            attr("NAMED", ref("OTHER")),
            attr("OPAQUE", Opaque),
        )
        val (config, main) = template(cases)
        for (case in cases) {
            val collector = SettingsCollector()
            val value = evaluate(listOf(config, main), main, ref("settings", case.name), collector = collector)
            assertEquals(listOf("settings.${case.name}"), value.unresolved)
            assertNull("${case.name} must have no suggestion", collector.suggestions["settings.${case.name}"])
        }
    }

    @Test
    fun `an environment driven default in the class is suggested as an environment default`() {
        val (config, main) = template(listOf(attr("API", SettingRead("os.environ[\"API\"]", Lit("/env-default")))))
        val collector = SettingsCollector()
        evaluate(listOf(config, main), main, ref("settings", "API"), collector = collector)
        val suggestion = collector.suggestions.getValue("settings.API")
        assertEquals("/env-default", suggestion.value)
        assertEquals(SuggestionKind.ENV_DEFAULT, suggestion.kind)
    }

    @Test
    fun `an environment driven default that is not a literal gives no suggestion`() {
        val (config, main) = template(listOf(attr("API", SettingRead("os.environ[\"API\"]", ref("DEFAULT_API")))))
        val collector = SettingsCollector()
        evaluate(listOf(config, main), main, ref("settings", "API"), collector = collector)
        assertNull(collector.suggestions["settings.API"])
    }

    @Test
    fun `os environ get with a literal default is unresolved and suggests that default`() {
        val main = entry("main.py", Source())
        val collector = SettingsCollector()
        val value = evaluate(listOf(main), main, SettingRead("os.environ[\"PREFIX\"]", Lit("/p")), collector = collector)
        assertEquals(listOf("os.environ[\"PREFIX\"]"), value.unresolved)
        val suggestion = collector.suggestions.getValue("os.environ[\"PREFIX\"]")
        assertEquals("/p", suggestion.value)
        assertEquals(SuggestionKind.ENV_DEFAULT, suggestion.kind)
        assertNull("the use site is filled in by the linker", suggestion.origin)
    }

    @Test
    fun `a config get default is a config default`() {
        val main = entry("main.py", Source())
        val collector = SettingsCollector()
        evaluate(listOf(main), main, SettingRead("app.config[\"PREFIX\"]", Lit("/c")), collector = collector)
        assertEquals(SuggestionKind.CONFIG_DEFAULT, collector.suggestions.getValue("app.config[\"PREFIX\"]").kind)
    }

    @Test
    fun `reads without a default or with a computed default have no suggestion`() {
        val main = entry("main.py", Source())
        val collector = SettingsCollector()
        evaluate(listOf(main), main, SettingRead("os.environ[\"A\"]", null), collector = collector)
        evaluate(listOf(main), main, SettingRead("os.environ[\"B\"]", Add(Lit("/"), Lit("x"))), collector = collector)
        assertTrue(collector.suggestions.isEmpty())
    }

    @Test
    fun `two different literal defaults for one expression cancel each other`() {
        val main = entry("main.py", Source())
        val collector = SettingsCollector()
        evaluate(listOf(main), main, SettingRead("os.environ[\"P\"]", Lit("/a")), collector = collector)
        evaluate(listOf(main), main, SettingRead("os.environ[\"P\"]", Lit("/b")), collector = collector)
        assertNull(collector.suggestions["os.environ[\"P\"]"])
        evaluate(listOf(main), main, SettingRead("os.environ[\"P\"]", Lit("/a")), collector = collector)
        assertNull("a conflict stays a conflict", collector.suggestions["os.environ[\"P\"]"])
    }

    @Test
    fun `a pin makes the value exact and marks it as pinned`() {
        val (config, main) = template(listOf(attr("API_V1_STR", Lit("/api/v1"))))
        val value = evaluate(listOf(config, main), main, Add(ref("settings", "API_V1_STR"), Lit("/items")), pins = mapOf("settings.API_V1_STR" to "/custom"))
        assertEquals(StrValue.Known("/custom/items", listOf("settings.API_V1_STR")), value)
        assertTrue(value.isExact)
    }

    @Test
    fun `a pin applies to an environment read too`() {
        val main = entry("main.py", Source())
        val value = evaluate(listOf(main), main, SettingRead("os.environ[\"P\"]", Lit("/p")), pins = mapOf("os.environ[\"P\"]" to ""))
        assertEquals(StrValue.Known("", listOf("os.environ[\"P\"]")), value)
    }

    @Test
    fun `the pin wins over the written default, which is only ever a suggestion`() {
        val (config, main) = template(listOf(attr("A", Lit("/default"))))
        assertEquals("/pinned", evaluate(listOf(config, main), main, ref("settings", "A"), pins = mapOf("settings.A" to "/pinned")).text)
    }

    @Test
    fun `without a pin the written default is never applied`() {
        val (config, main) = template(listOf(attr("A", Lit("/default"))))
        val value = evaluate(listOf(config, main), main, ref("settings", "A"))
        assertFalse(value.isExact)
        assertEquals("{?}", value.text)
    }

    @Test
    fun `two settings in one prefix are both reported and both need a pin`() {
        val (config, main) = template(listOf(attr("ROOT", Lit("/r")), attr("VERSION", Lit("/v1"))))
        val expr = Add(ref("settings", "ROOT"), ref("settings", "VERSION"))
        val none = evaluate(listOf(config, main), main, expr)
        assertEquals(listOf("settings.ROOT", "settings.VERSION"), none.unresolved)
        val one = evaluate(listOf(config, main), main, expr, pins = mapOf("settings.ROOT" to "/r"))
        assertFalse(one.isExact)
        assertEquals(listOf("settings.VERSION"), one.unresolved)
        assertEquals(listOf("settings.ROOT"), one.pins)
        val both = evaluate(listOf(config, main), main, expr, pins = mapOf("settings.ROOT" to "/r", "settings.VERSION" to "/v2"))
        assertEquals(StrValue.Known("/r/v2", listOf("settings.ROOT", "settings.VERSION")), both)
    }

    @Test
    fun `a settings factory function resolves to the class`() {
        val config = entry(
            "app/config.py",
            Source(
                settingClasses = listOf(SettingsClassFact("Settings", listOf("BaseSettings"), listOf(attr("API", Lit("/f"))))),
                settingInstances = listOf(SettingsInstanceFact("get_settings", "Settings", true)),
            ),
        )
        val main = entry(
            "app/main.py",
            Source(
                imports = listOf(ImportBinding("get_settings", "app.config", "get_settings")),
                settingInstances = listOf(SettingsInstanceFact("settings", "get_settings", false)),
            ),
        )
        val collector = SettingsCollector()
        evaluate(listOf(config, main), main, ref("settings", "API"), collector = collector)
        assertEquals("/f", collector.suggestions.getValue("settings.API").value)
    }

    @Test
    fun `a factory that cannot be followed is still a setting but has no suggestion`() {
        val main = entry(
            "app/main.py",
            Source(
                imports = listOf(ImportBinding("get_settings", "vendor.lib", "get_settings")),
                settingInstances = listOf(SettingsInstanceFact("settings", "get_settings", false)),
            ),
        )
        val collector = SettingsCollector()
        val value = evaluate(listOf(main), main, ref("settings", "API"), collector = collector)
        assertEquals(listOf("settings.API"), value.unresolved)
        assertTrue(collector.suggestions.isEmpty())
    }

    @Test
    fun `an attribute inherited from a base settings class is found`() {
        val config = entry(
            "app/config.py",
            Source(
                settingClasses = listOf(
                    SettingsClassFact("BaseAppSettings", listOf("BaseSettings"), listOf(attr("API", Lit("/base")))),
                    SettingsClassFact("Settings", listOf("BaseAppSettings"), listOf(attr("OTHER", Lit("/o")))),
                ),
                settingInstances = listOf(SettingsInstanceFact("settings", "Settings", false)),
            ),
        )
        val main = entry("app/main.py", Source(imports = listOf(ImportBinding("settings", "app.config", "settings"))))
        val collector = SettingsCollector()
        evaluate(listOf(config, main), main, ref("settings", "API"), collector = collector)
        assertEquals("/base", collector.suggestions.getValue("settings.API").value)
    }

    @Test
    fun `a star import of the settings module is followed`() {
        val config = entry(
            "app/config.py",
            Source(
                settingClasses = listOf(SettingsClassFact("Settings", listOf("BaseSettings"), listOf(attr("API", Lit("/star"))))),
                settingInstances = listOf(SettingsInstanceFact("settings", "Settings", false)),
            ),
        )
        val main = entry("app/main.py", Source(stars = listOf(StarBinding("app.config"))))
        val collector = SettingsCollector()
        evaluate(listOf(config, main), main, ref("settings", "API"), collector = collector)
        assertEquals("/star", collector.suggestions.getValue("settings.API").value)
    }

    @Test
    fun `a settings object reached through a module import is followed`() {
        val config = entry(
            "app/core/config.py",
            Source(
                settingClasses = listOf(SettingsClassFact("Settings", listOf("BaseSettings"), listOf(attr("API", Lit("/mod"))))),
                settingInstances = listOf(SettingsInstanceFact("settings", "Settings", false)),
            ),
        )
        val main = entry("app/main.py", Source(imports = listOf(ImportBinding("config", "app.core", "config"))))
        val collector = SettingsCollector()
        val value = evaluate(listOf(config, main), main, ref("config", "settings", "API"), collector = collector)
        assertEquals(listOf("config.settings.API"), value.unresolved)
        assertEquals("/mod", collector.suggestions.getValue("config.settings.API").value)
    }

    // --- what is not a setting ------------------------------------------------------------------------------------------

    @Test
    fun `a module constant with the same shape is not a setting`() {
        val consts = entry("app/consts.py", Source(consts = listOf(ConstFact("API", Lit("/v1")))))
        val main = entry("app/main.py", Source(imports = listOf(ImportBinding("consts", "app", "consts"))))
        val value = evaluate(listOf(consts, main), main, ref("consts", "API"))
        assertEquals(StrValue.Known("/v1"), value)
    }

    @Test
    fun `a settings module in the project resolves its constants as before`() {
        val settingsModule = entry("proj/settings.py", Source(consts = listOf(ConstFact("BASE_PATH", Lit("netbox/")))))
        val urls = entry("proj/urls.py", Source(imports = listOf(ImportBinding("settings", "proj", "settings"))))
        assertEquals(StrValue.Known("netbox/"), evaluate(listOf(settingsModule, urls), urls, ref("settings", "BASE_PATH")))
    }

    @Test
    fun `an unknown attribute of an ordinary object is only unknown`() {
        val main = entry("main.py", Source())
        val value = evaluate(listOf(main), main, ref("thing", "attr"))
        assertTrue(value is StrValue.Unknown)
        assertTrue(value.unresolved.isEmpty())
        assertEquals(DiagnosticCode.DYNAMIC_VALUE, value.reason)
    }

    @Test
    fun `a plain unresolved name is not a setting`() {
        val main = entry("main.py", Source(imports = listOf(ImportBinding("PREFIX", "vendor", "PREFIX"))))
        assertTrue(evaluate(listOf(main), main, ref("PREFIX")).unresolved.isEmpty())
    }

    @Test
    fun `a constant that is dynamic stays unknown and is not offered as a setting`() {
        val main = entry("main.py", Source(consts = listOf(ConstFact("PREFIX", Opaque))))
        val value = evaluate(listOf(main), main, ref("PREFIX"))
        assertTrue(value.unresolved.isEmpty())
        assertFalse(value.isExact)
    }

    @Test
    fun `a constant built from a setting carries the setting up`() {
        val (config, main) = template(listOf(attr("API", Lit("/x"))))
        val user = entry("app/routes.py", Source(imports = listOf(ImportBinding("settings", "app.config", "settings")), consts = listOf(ConstFact("PREFIX", ref("settings", "API")))))
        val value = evaluate(listOf(config, main, user), user, Add(ref("PREFIX"), Lit("/items")))
        assertEquals(listOf("settings.API"), value.unresolved)
    }

    @Test
    fun `evaluation without the settings machinery ignores pins and notices nothing`() {
        val (config, main) = template(listOf(attr("API", Lit("/x"))))
        val collector = SettingsCollector()
        val value = evaluate(listOf(config, main), main, ref("settings", "API"), pins = mapOf("settings.API" to "/p"), collector = collector, settings = false)
        assertTrue(value is StrValue.Unknown)
        assertTrue(value.unresolved.isEmpty())
        assertTrue(collector.suggestions.isEmpty())
    }

    @Test
    fun `counting mode assumes an unresolved setting is empty and leaves the rest alone`() {
        val (config, main) = template(listOf(attr("API", Lit("/x"))))
        assertEquals(StrValue.Known("/items"), evaluate(listOf(config, main), main, Add(ref("settings", "API"), Lit("/items")), assumeEmpty = true))
        val other = evaluate(listOf(config, main), main, Add(ref("thing", "attr"), Lit("/items")), assumeEmpty = true)
        assertFalse(other.isExact)
    }

    // --- values and the tally ---------------------------------------------------------------------------------------------

    @Test
    fun `adding values unites pins and unresolved settings without repeats`() {
        val a = StrValue.Known("/a", listOf("p1"))
        val b = StrValue.Known("/b", listOf("p1", "p2"))
        assertEquals(StrValue.Known("/a/b", listOf("p1", "p2")), a + b)
        val u = StrValue.Unknown(DiagnosticCode.DYNAMIC_VALUE, listOf("s1"))
        val p = StrValue.Partial("/x/{?}", DiagnosticCode.DYNAMIC_VALUE, listOf("p1"), listOf("s1", "s2"))
        val sum = a + u + p
        assertEquals(listOf("s1", "s2"), sum.unresolved)
        assertEquals(listOf("p1"), sum.pins)
        assertFalse(sum.isExact)
    }

    @Test
    fun `values without pins keep the equality they always had`() {
        assertEquals(StrValue.Known("x"), StrValue.Known("x", emptyList()))
        assertEquals(StrValue.Unknown(), StrValue.Unknown(DiagnosticCode.DYNAMIC_VALUE, emptyList()))
    }

    @Test
    fun `the tally counts routes per expression, most first, and fills the use site into an environment suggestion`() {
        val where = SourceRef("app/main.py", 10, 2)
        val other = SourceRef("app/other.py", 20, 3)
        val collector = SettingsCollector()
        collector.note("os.environ[\"B\"]", com.routegenie.model.SettingSuggestion("/b", SuggestionKind.ENV_DEFAULT, null))
        val tally = SettingTally(collector.suggestions)
        tally.hit("settings.A", where, 3)
        tally.hit("os.environ[\"B\"]", other, 5)
        tally.hit("settings.A", other, 2)
        tally.hitAll(listOf("settings.C" to where, "settings.C" to other))
        val result = tally.result()
        assertEquals(listOf("os.environ[\"B\"]", "settings.A", "settings.C"), result.map { it.expression })
        assertEquals(listOf(5, 5, 1), result.map { it.routeCount })
        assertEquals(where, result.first { it.expression == "settings.A" }.use)
        assertEquals(other, result.first { it.expression == "os.environ[\"B\"]" }.suggestion?.origin)
    }

    @Test
    fun `pin environments compare by value so an unchanged pass can be skipped`() {
        assertEquals(PinEnv(mapOf("a" to "/a")), PinEnv(mapOf("a" to "/a")))
        assertFalse(PinEnv(mapOf("a" to "/a")) == PinEnv(mapOf("a" to "/b")))
        assertEquals(PinEnv.EMPTY, PinEnv.of(emptyList()))
        assertEquals(PinEnv(mapOf("a" to "/a")), PinEnv.of(listOf(PinEnv(mapOf("a" to "/a")))))
    }
}
