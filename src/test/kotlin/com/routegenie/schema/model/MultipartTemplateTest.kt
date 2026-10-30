package com.routegenie.schema.model

import com.routegenie.Limits
import com.routegenie.testing.PerfBudget
import com.routegenie.testing.SchemaChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal
import kotlin.random.Random

/** The pure side of file uploads: the file description, the multipart template and the rules that refuse what cannot be sent. */
class MultipartTemplateTest {
    private val file = FileSpec().toSchema()
    private fun obj(vararg props: Pair<String, Schema>, required: List<String> = props.map { it.first }) =
        Schema(types = listOf(JsonType.OBJECT), properties = linkedMapOf(*props), required = required)

    private fun multipart(schema: Schema) = RequestBodySchema(BodyEncoding.MULTIPART, schema)

    private fun named(t: MultipartTemplate?) = t?.parts?.map { it.name to (it.text ?: "<file>") }

    // --- FileSpec -----------------------------------------------------------------------------------------------------

    @Test
    fun aFileFieldIsAStringSchemaWithFormatBinaryAndTheExtension() {
        val schema = FileSpec(accept = listOf("image/png")).toSchema("Avatar")
        assertEquals(listOf(JsonType.STRING), schema.types)
        assertEquals("binary", schema.format)
        assertEquals("Avatar", schema.description)
        assertEquals(setOf("x-routegenie-file"), schema.extensions.keys)
    }

    @Test
    fun parsingTakesEveryDeclaredKeyAndNothingElse() {
        val member = Json.obj(
            "accept" to Json.arr(Json.str("image/*"), Json.str("application/pdf")),
            "extensions" to Json.arr(Json.str(".png"), Json.str(".pdf")),
            "image" to Json.Bool(true),
            "maxBytes" to Json.num(5_242_880L),
            "minWidth" to Json.num(10),
            "maxWidth" to Json.num(2000),
            "minHeight" to Json.num(20),
            "maxHeight" to Json.num(3000),
        ) as Json.Obj
        val spec = FileSpec.parse(member)
        assertEquals(listOf("image/*", "application/pdf"), spec.accept)
        assertEquals(listOf(".png", ".pdf"), spec.extensions)
        assertTrue(spec.image)
        assertEquals(5_242_880L, spec.maxBytes)
        assertEquals(10, spec.minWidth)
        assertEquals(2000, spec.maxWidth)
        assertEquals(20, spec.minHeight)
        assertEquals(3000, spec.maxHeight)
        assertEquals(spec, FileSpec.parse(spec.toJson()))
    }

    @Test
    fun anEmptyObjectIsAFileOfWhichNothingIsKnown() {
        val spec = FileSpec.of(file)!!
        assertEquals(emptyList<String>(), spec.accept)
        assertEquals(emptyList<String>(), spec.extensions)
        assertFalse(spec.image)
        assertNull(spec.maxBytes)
        assertNull(spec.minWidth)
        assertEquals(Json.Obj(emptyMap()), spec.toJson())
    }

    @Test
    fun membersOfTheWrongShapeCountAsNotDeclared() {
        val member = Json.obj(
            "accept" to Json.str("image/png"),
            "extensions" to Json.arr(Json.num(1), Json.str(""), Json.str(".gif")),
            "image" to Json.str("yes"),
            "maxBytes" to Json.num(-4),
            "minWidth" to Json.Num(BigDecimal("1.5")),
            "maxWidth" to Json.Str("wide"),
            "maxHeight" to Json.Num(BigDecimal("99999999999")),
        ) as Json.Obj
        val spec = FileSpec.parse(member)
        assertEquals(emptyList<String>(), spec.accept)
        assertEquals(listOf(".gif"), spec.extensions)
        assertFalse(spec.image)
        assertNull(spec.maxBytes)
        assertNull(spec.minWidth)
        assertNull(spec.maxWidth)
        assertNull(spec.maxHeight)
    }

    @Test
    fun bytesOfAJsonBodyIsNotAFile() {
        val bytes = Schema.of(JsonType.STRING, "binary")
        assertNull(FileSpec.of(bytes))
        assertNull(FileSpec.shape(bytes))
        assertNull(FileSpec.of(Schema.STRING.copy(extensions = mapOf(FileSpec.KEY to Json.Obj(emptyMap())))))
        assertNull(FileSpec.of(file.copy(extensions = mapOf(FileSpec.KEY to Json.str("x")))))
    }

    @Test
    fun shapeSeesThroughOptionalAndLists() {
        assertEquals(false, FileSpec.shape(file)!!.many)
        assertEquals(false, FileSpec.shape(Schema.nullable(file))!!.many)
        val list = Schema.arrayOf(file).copy(minItems = 1, maxItems = 4)
        val shape = FileSpec.shape(list)!!
        assertTrue(shape.many)
        assertEquals(1, shape.minItems)
        assertEquals(4, shape.maxItems)
        assertTrue(FileSpec.shape(Schema.nullable(Schema.arrayOf(Schema.nullable(file))))!!.many)
        assertNull(FileSpec.shape(Schema.STRING))
        assertNull(FileSpec.shape(Schema.arrayOf(Schema.STRING)))
        assertNull(FileSpec.shape(Schema(anyOf = listOf(file, Schema.STRING))))
    }

    @Test
    fun containsFileLooksIntoNestedSchemas() {
        assertTrue(FileSpec.containsFile(obj("a" to obj("b" to file))))
        assertTrue(FileSpec.containsFile(obj("a" to Schema.arrayOf(obj("b" to file)))))
        assertFalse(FileSpec.containsFile(obj("a" to Schema.STRING)))
    }

    private fun assertFalse(value: Boolean) = org.junit.Assert.assertFalse(value)

    @Test(expected = IllegalArgumentException::class)
    fun aPartIsEitherTextOrAFile() {
        TemplatePart("a", "x", FileSpec())
    }

    @Test(expected = IllegalArgumentException::class)
    fun aPartCannotBeNeither() {
        TemplatePart("a", null, null)
    }

    // --- renderMultipart: the fixed sample -----------------------------------------------------------------------------

    @Test
    fun partsFollowTheDeclarationOrder() {
        val body = multipart(obj("title" to Schema.STRING, "doc" to file, "n" to Schema.INTEGER))
        assertEquals(listOf("title" to "string", "doc" to "<file>", "n" to "0"), named(BodyRenderer.renderMultipart(body)))
    }

    @Test
    fun theFilePartCarriesItsDescription() {
        val spec = FileSpec(accept = listOf("application/pdf"), extensions = listOf(".pdf"), maxBytes = 100)
        val template = BodyRenderer.renderMultipart(multipart(obj("doc" to spec.toSchema())))!!
        assertEquals(spec, template.parts.single().file)
        assertNull(template.parts.single().text)
    }

    @Test
    fun aFileWithNoAcceptListKeepsItEmpty() {
        val template = BodyRenderer.renderMultipart(multipart(obj("doc" to file)))!!
        assertEquals(emptyList<String>(), template.parts.single().file!!.accept)
    }

    @Test
    fun aListOfFilesIsOnePartPerFileWithTheSameName() {
        val body = multipart(obj("docs" to Schema.arrayOf(file)))
        assertEquals(listOf("docs" to "<file>"), named(BodyRenderer.renderMultipart(body)))
        val three = multipart(obj("docs" to Schema.arrayOf(file).copy(minItems = 3)))
        assertEquals(List(3) { "docs" to "<file>" }, named(BodyRenderer.renderMultipart(three)))
    }

    @Test
    fun maxItemsBoundsTheListAndZeroMeansNone() {
        val two = multipart(obj("docs" to Schema.arrayOf(file).copy(minItems = 5, maxItems = 2)))
        assertEquals(2, BodyRenderer.renderMultipart(two)!!.parts.size)
        val none = multipart(obj("docs" to Schema.arrayOf(file).copy(maxItems = 0), "n" to Schema.INTEGER))
        assertEquals(listOf("n" to "0"), named(BodyRenderer.renderMultipart(none)))
    }

    @Test
    fun anOptionalFileIsInTheFixedSample() {
        val body = multipart(obj("doc" to Schema.nullable(file), required = emptyList()))
        assertEquals(listOf("doc" to "<file>"), named(BodyRenderer.renderMultipart(body)))
    }

    @Test
    fun scalarsAreWrittenTheWayABrowserFormSendsThem() {
        val body = multipart(
            obj(
                "flag" to Schema.BOOLEAN,
                "ratio" to Schema.NUMBER.copy(minimum = BigDecimal("1.5")),
                "kind" to Schema(types = listOf(JsonType.STRING), enumValues = listOf(Json.str("a"), Json.str("b"))),
                "when" to Schema.of(JsonType.STRING, "date"),
            ),
        )
        assertEquals(
            listOf("flag" to "true", "ratio" to "1.5", "kind" to "a", "when" to "2024-01-01"),
            named(BodyRenderer.renderMultipart(body)),
        )
    }

    @Test
    fun aListOfScalarsRepeatsTheName() {
        val body = multipart(obj("tags" to Schema.arrayOf(Schema.STRING).copy(minItems = 2), "doc" to file))
        assertEquals(listOf("tags" to "string", "tags" to "string", "doc" to "<file>"), named(BodyRenderer.renderMultipart(body)))
    }

    @Test
    fun aNullMemberAndAnEmptyListWriteNoPart() {
        val body = multipart(
            obj(
                "a" to Schema.NULL,
                "b" to Schema.arrayOf(Schema.STRING).copy(maxItems = 0),
                "doc" to file,
                required = listOf("doc"),
            ),
        )
        assertEquals(listOf("doc" to "<file>"), named(BodyRenderer.renderMultipart(body)))
    }

    @Test
    fun aReferenceToTheRootObjectIsFollowed() {
        val model = obj("name" to Schema.STRING, "doc" to file)
        val body = multipart(Schema.ref("Upload").copy(defs = mapOf("Upload" to model)))
        assertEquals(listOf("name" to "string", "doc" to "<file>"), named(BodyRenderer.renderMultipart(body)))
    }

    @Test
    fun aTextMemberThatIsAReferenceUsesTheDefinitions() {
        val color = Schema(types = listOf(JsonType.STRING), enumValues = listOf(Json.str("red")))
        val root = obj("color" to Schema.ref("Color"), "doc" to file).copy(defs = mapOf("Color" to color))
        assertEquals(listOf("color" to "red", "doc" to "<file>"), named(BodyRenderer.renderMultipart(multipart(root))))
    }

    // --- Refusals ------------------------------------------------------------------------------------------------------

    @Test
    fun onlyAMultipartBodyHasAMultipartTemplate() {
        assertNull(BodyRenderer.renderMultipart(RequestBodySchema(BodyEncoding.JSON, obj("a" to Schema.STRING))))
        assertNull(BodyRenderer.renderMultipart(RequestBodySchema(BodyEncoding.FORM_URLENCODED, obj("a" to Schema.STRING))))
    }

    @Test
    fun aNestedObjectIsRefusedNotGuessed() {
        val nested = obj("inner" to obj("x" to Schema.STRING))
        assertNull(BodyRenderer.renderMultipart(multipart(obj("doc" to file, "n" to nested.properties.getValue("inner")))))
    }

    @Test
    fun aListOfObjectsIsRefused() {
        assertNull(BodyRenderer.renderMultipart(multipart(obj("doc" to file, "rows" to Schema.arrayOf(obj("x" to Schema.STRING))))))
    }

    @Test
    fun aFileBelowTheTopLevelIsRefused() {
        assertNull(BodyRenderer.renderMultipart(multipart(obj("child" to obj("doc" to file)))))
        assertNull(BodyRenderer.renderMultipart(multipart(obj("children" to Schema.arrayOf(obj("doc" to file))))))
    }

    @Test
    fun aRootThatIsNotAnObjectIsRefused() {
        assertNull(BodyRenderer.renderMultipart(multipart(Schema.arrayOf(obj("doc" to file)))))
        assertNull(BodyRenderer.renderMultipart(multipart(Schema(types = listOf(JsonType.OBJECT)))))
        assertNull(BodyRenderer.renderMultipart(multipart(Schema.ref("Missing"))))
    }

    @Test
    fun aRequiredTextMemberWithoutASampleRefusesTheBodyAnOptionalOneIsLeftOut() {
        val noSample = Schema.of(JsonType.STRING, "binary")
        assertNull(BodyRenderer.renderMultipart(multipart(obj("doc" to file, "raw" to noSample))))
        val optional = obj("doc" to file, "raw" to noSample, required = listOf("doc"))
        assertEquals(listOf("doc" to "<file>"), named(BodyRenderer.renderMultipart(multipart(optional))))
    }

    @Test
    fun aFileListThatCannotFitTheLimitOfPartsIsRefused() {
        val huge = multipart(obj("docs" to Schema.arrayOf(file).copy(minItems = 1_000_000)))
        assertNull(BodyRenderer.renderMultipart(huge))
        val limit = multipart(obj("docs" to Schema.arrayOf(file).copy(minItems = Limits.MULTIPART_MAX_PARTS)))
        assertEquals(Limits.MULTIPART_MAX_PARTS, BodyRenderer.renderMultipart(limit)!!.parts.size)
    }

    // --- The text renderers never write a file ---------------------------------------------------------------------------

    @Test
    fun theTextRenderersGiveNothingForAMultipartBody() {
        val body = multipart(obj("name" to Schema.STRING, "doc" to file))
        assertNull(BodyRenderer.render(body))
        assertNull(BodyRenderer.renderRandom(body, Random(1)))
    }

    @Test
    fun aFileLeafHasNoSampleInTheFixedAndTheRandomGenerator() {
        val fixed = SampleGenerator.generate(obj("name" to Schema.STRING, "doc" to file))
        assertEquals(Json.obj("name" to Json.str("string")), fixed.value)
        assertEquals(listOf(SampleGap("$.doc", GapReason.BINARY)), fixed.gaps)
        val random = RandomSampleGenerator.generate(obj("name" to Schema.STRING, "doc" to file), Random(3))
        assertEquals(setOf("name"), (random.value as Json.Obj).fields.keys)
        assertEquals(listOf(SampleGap("$.doc", GapReason.BINARY)), random.gaps)
    }

    @Test
    fun bytesInAJsonBodyIsUnchanged() {
        val body = RequestBodySchema(BodyEncoding.JSON, obj("name" to Schema.STRING, "blob" to Schema.of(JsonType.STRING, "binary"), required = listOf("name")))
        assertEquals("{\n  \"name\": \"string\"\n}", BodyRenderer.render(body)!!.text)
        assertNull(BodyRenderer.renderMultipart(body))
    }

    @Test
    fun theContentTypeOfTheEncodingIsMultipartFormData() {
        assertEquals("multipart/form-data", BodyEncoding.MULTIPART.contentType)
    }

    // --- Random --------------------------------------------------------------------------------------------------------

    private fun mixed() = multipart(
        obj(
            "title" to Schema.STRING.copy(minLength = 3, maxLength = 9),
            "count" to Schema.INTEGER.copy(minimum = BigDecimal.ONE, maximum = BigDecimal(50)),
            "tags" to Schema.arrayOf(Schema.STRING).copy(minItems = 1),
            "avatar" to FileSpec(image = true).toSchema(),
            "docs" to Schema.arrayOf(file).copy(minItems = 1),
            "cover" to Schema.nullable(file),
            required = listOf("title", "count", "avatar", "docs"),
        ),
    )

    @Test
    fun theSameSeedGivesTheSameParts() {
        val a = BodyRenderer.renderMultipart(mixed(), Random(42))
        val b = BodyRenderer.renderMultipart(mixed(), Random(42))
        assertNotNull(a)
        assertEquals(a, b)
    }

    @Test
    fun differentSeedsGiveDifferentText() {
        val texts = (1..20).map { BodyRenderer.renderMultipart(mixed(), Random(it))!!.parts.filter { p -> p.name == "title" }.map { p -> p.text } }.toSet()
        assertTrue("random titles were all equal", texts.size > 5)
    }

    @Test
    fun requiredFilesAreAlwaysThereAndOptionalOnesFollowTheChance() {
        val never = RandomOptions(optionalChance = 0.0)
        val always = RandomOptions(optionalChance = 1.0)
        for (seed in 1..30) {
            val without = BodyRenderer.renderMultipart(mixed(), Random(seed), never)!!
            assertTrue(without.parts.any { it.name == "avatar" })
            assertTrue(without.parts.any { it.name == "docs" })
            assertFalse(without.parts.any { it.name == "cover" })
            val with = BodyRenderer.renderMultipart(mixed(), Random(seed), always)!!
            assertTrue(with.parts.any { it.name == "cover" })
        }
    }

    @Test
    fun aRandomListOfFilesStaysWithinItsLimits() {
        val body = multipart(obj("docs" to Schema.arrayOf(file).copy(minItems = 2, maxItems = 3)))
        val sizes = (1..60).map { BodyRenderer.renderMultipart(body, Random(it))!!.parts.size }.toSet()
        assertTrue(sizes.all { it in 2..3 })
        assertEquals(setOf(2, 3), sizes)
        val unbounded = (1..60).map { BodyRenderer.renderMultipart(multipart(obj("docs" to Schema.arrayOf(file))), Random(it))!!.parts.size }.toSet()
        assertTrue(unbounded.all { it in 1..(1 + Limits.MULTIPART_RANDOM_EXTRA_FILES) })
    }

    @Test
    fun randomRefusalsAreTheSameAsFixedOnes() {
        assertNull(BodyRenderer.renderMultipart(multipart(obj("doc" to file, "n" to obj("x" to Schema.STRING))), Random(1)))
        assertNull(BodyRenderer.renderMultipart(RequestBodySchema(BodyEncoding.JSON, obj("a" to Schema.STRING)), Random(1)))
    }

    // --- Property: the text parts always satisfy the schema ---------------------------------------------------------------

    private fun leaf(random: Random): Schema = when (random.nextInt(8)) {
        0 -> Schema.STRING.copy(minLength = random.nextInt(0, 4), maxLength = random.nextInt(4, 12))
        1 -> Schema.INTEGER.copy(minimum = BigDecimal(random.nextInt(-5, 5)), maximum = BigDecimal(random.nextInt(5, 100)))
        2 -> Schema.NUMBER.copy(minimum = BigDecimal(random.nextInt(0, 5)), maximum = BigDecimal(random.nextInt(6, 20)))
        3 -> Schema.BOOLEAN
        4 -> Schema(types = listOf(JsonType.STRING), enumValues = listOf("x", "y", "z").map { Json.str(it) })
        5 -> Schema.of(JsonType.STRING, "email")
        6 -> Schema.of(JsonType.STRING, "uuid")
        else -> Schema.of(JsonType.STRING, "date")
    }

    private fun property(random: Random): Schema = when (random.nextInt(6)) {
        0 -> Schema.arrayOf(leaf(random)).copy(minItems = random.nextInt(0, 3), maxItems = random.nextInt(3, 6))
        1 -> Schema.nullable(leaf(random))
        else -> leaf(random)
    }

    /** The values a form parser would give back: parts of one name grouped, typed from the schema. */
    private fun parsed(template: MultipartTemplate, schema: Schema): Json {
        val members = LinkedHashMap<String, Json>()
        for ((name, prop) in schema.properties) {
            val texts = template.parts.filter { it.name == name && it.text != null }.map { it.text!! }
            if (texts.isEmpty()) continue
            fun scalar(text: String, s: Schema): Json = when (s.types.firstOrNull { it != JsonType.NULL }) {
                JsonType.INTEGER, JsonType.NUMBER -> Json.Num(BigDecimal(text))
                JsonType.BOOLEAN -> Json.Bool(text == "true")
                else -> Json.Str(text)
            }
            val core = prop.anyOf.firstOrNull { it.types != listOf(JsonType.NULL) } ?: prop
            members[name] = if (core.types.contains(JsonType.ARRAY)) Json.Arr(texts.map { scalar(it, core.items!!) }) else scalar(texts.single(), core)
        }
        return Json.Obj(members)
    }

    @Test
    fun theTextPartsOfRandomBodiesAlwaysSatisfyTheSchema() {
        var checked = 0
        for (seed in 1..400) {
            val random = Random(seed)
            val props = LinkedHashMap<String, Schema>()
            repeat(random.nextInt(1, 7)) { props["p$it"] = property(random) }
            val textSchema = Schema(types = listOf(JsonType.OBJECT), properties = props, required = props.keys.filter { random.nextBoolean() })
            val full = LinkedHashMap(props)
            full["upload"] = file
            full["many"] = Schema.arrayOf(file)
            val body = multipart(textSchema.copy(properties = full, required = textSchema.required + "upload"))
            val template = BodyRenderer.renderMultipart(body, Random(seed + 1000))
            val fixed = BodyRenderer.renderMultipart(body)
            for (candidate in listOf(template, fixed)) {
                if (candidate == null) fail("seed $seed: a body of scalars was refused")
                val value = parsed(candidate!!, textSchema)
                val violations = SchemaChecker.violations(textSchema, value)
                assertTrue("seed $seed: $violations in ${candidate.parts}", violations.isEmpty())
                assertTrue(candidate.parts.any { it.file != null && it.name == "upload" })
                checked++
            }
        }
        assertEquals(800, checked)
    }

    // --- Speed ---------------------------------------------------------------------------------------------------------

    @Test
    fun aSchemaOf500FieldsRendersWithinTheBudget() {
        val props = LinkedHashMap<String, Schema>()
        for (i in 0 until 500) props["f$i"] = if (i % 10 == 0) file else if (i % 3 == 0) Schema.INTEGER else Schema.STRING
        val body = multipart(Schema(types = listOf(JsonType.OBJECT), properties = props, required = props.keys.toList()))
        PerfBudget.assertWithin("multipart-render-500-fixed", Limits.BUDGET_MULTIPART_RENDER_500_FIELDS_MS, warmups = 5, runs = 15) {
            assertNotNull(BodyRenderer.renderMultipart(body))
        }
        val random = Random(7)
        PerfBudget.assertWithin("multipart-render-500-random", Limits.BUDGET_MULTIPART_RENDER_500_FIELDS_MS, warmups = 5, runs = 15) {
            assertNotNull(BodyRenderer.renderMultipart(body, random))
        }
    }

    @Test
    fun differentRandomSeedsGiveDifferentFilesCounts() {
        val counts = (1..40).map { BodyRenderer.renderMultipart(multipart(obj("docs" to Schema.arrayOf(file))), Random(it))!!.parts.size }
        assertNotEquals(1, counts.toSet().size)
    }
}
