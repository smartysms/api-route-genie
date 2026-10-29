package com.routegenie.schema.psi

import com.routegenie.schema.model.Json
import com.routegenie.schema.model.JsonType
import com.routegenie.schema.model.Schema
import java.math.BigDecimal

/**
 * Every fact about Python typing, Pydantic, dataclasses and enums that body extraction relies on: qualified names of the
 * library things it recognizes (by import origin, never by resolving into an installed library) and what each means as
 * a schema. Supporting another alias or type is a one-line change here.
 */
internal object PydanticCatalog {
    private val typingRoots = listOf("typing", "typing_extensions", "builtins", "collections.abc", "collections")
    private val pydanticRoots = listOf("pydantic", "pydantic.types", "pydantic.networks", "pydantic.main", "pydantic.fields")
    private val pydanticV1Roots = listOf("pydantic.v1", "pydantic.v1.types", "pydantic.v1.networks", "pydantic.v1.main", "pydantic.v1.fields")
    private val allPydanticRoots = pydanticRoots + pydanticV1Roots

    private fun names(roots: List<String>, vararg simple: String): Set<String> = roots.flatMap { r -> simple.map { "$r.$it" } }.toSet()

    // --- Class kinds ---------------------------------------------------------------------------------------------------

    val baseModels: Set<String> = names(allPydanticRoots, "BaseModel") + names(listOf("pydantic.generics", "pydantic.v1.generics"), "GenericModel")

    /** Classes of the legacy major version; a model that derives from one of these is validated with v1 rules. */
    val v1Roots: Set<String> = setOf("pydantic.v1")

    val dataclassDecorators: Set<String> = setOf("dataclasses.dataclass", "pydantic.dataclasses.dataclass", "pydantic.v1.dataclasses.dataclass")
    val dataclassField = "dataclasses.field"
    val dataclassSkipTypes: Set<String> = names(listOf("dataclasses"), "InitVar", "KW_ONLY") + names(typingRoots, "ClassVar")

    val typedDictBases: Set<String> = names(listOf("typing", "typing_extensions"), "TypedDict")

    val enumBases: Set<String> = names(listOf("enum"), "Enum", "IntEnum", "StrEnum", "ReprEnum") + names(listOf("strenum"), "StrEnum")
    val enumStringBases: Set<String> = names(listOf("enum", "strenum"), "StrEnum")
    val enumFlagBases: Set<String> = names(listOf("enum"), "Flag", "IntFlag")
    val enumAuto: Set<String> = setOf("enum.auto")

    val fieldFunctions: Set<String> = names(allPydanticRoots, "Field")
    val aliasChoices: Set<String> = names(listOf("pydantic", "pydantic.aliases"), "AliasChoices")
    val aliasPath: Set<String> = names(listOf("pydantic", "pydantic.aliases"), "AliasPath")
    val configDict: Set<String> = names(listOf("pydantic", "pydantic.config"), "ConfigDict")
    val privateAttr: Set<String> = names(allPydanticRoots, "PrivateAttr")

    // --- Typing constructs -----------------------------------------------------------------------------------------------

    val optional: Set<String> = names(typingRoots, "Optional")
    val union: Set<String> = names(typingRoots, "Union")
    val literal: Set<String> = names(typingRoots, "Literal")
    val annotated: Set<String> = names(typingRoots, "Annotated")
    val classVar: Set<String> = names(typingRoots, "ClassVar")
    val transparent: Set<String> = names(typingRoots, "Final", "Required", "NotRequired", "ReadOnly")
    val required: Set<String> = names(typingRoots, "Required")
    val notRequired: Set<String> = names(typingRoots, "NotRequired")
    val newType: Set<String> = names(typingRoots, "NewType")
    val any: Set<String> = names(typingRoots, "Any", "object")

    val lists: Set<String> = names(
        typingRoots, "List", "list", "Sequence", "MutableSequence", "Iterable", "Collection", "Deque", "deque", "Iterator", "Generator",
    )
    val sets: Set<String> = names(typingRoots, "Set", "set", "FrozenSet", "frozenset", "AbstractSet", "MutableSet", "Set")
    val tuples: Set<String> = names(typingRoots, "Tuple", "tuple")
    val maps: Set<String> = names(typingRoots, "Dict", "dict", "Mapping", "MutableMapping", "DefaultDict", "defaultdict", "OrderedDict", "Counter")

    /** `list[int]`-style names that are not subscripted stand for a collection of unknown element type. */
    val bareCollections: Set<String> = lists + sets + tuples + maps

    // --- Scalars -------------------------------------------------------------------------------------------------------------

    private fun dec(v: String) = BigDecimal(v)

    private val scalars: Map<String, Schema> = buildMap {
        fun put(keys: Set<String>, schema: Schema) = keys.forEach { this[it] = schema }
        put(setOf("builtins.str"), Schema.STRING)
        put(setOf("builtins.int"), Schema.INTEGER)
        put(setOf("builtins.float"), Schema.NUMBER)
        put(setOf("builtins.bool"), Schema.BOOLEAN)
        put(setOf("builtins.bytes", "builtins.bytearray"), Schema.of(JsonType.STRING, "binary"))
        put(setOf("builtins.None", "types.NoneType"), Schema.NULL)
        put(any, Schema.EMPTY)
        put(setOf("datetime.datetime"), Schema.of(JsonType.STRING, "date-time"))
        put(setOf("datetime.date"), Schema.of(JsonType.STRING, "date"))
        put(setOf("datetime.time"), Schema.of(JsonType.STRING, "time"))
        put(setOf("datetime.timedelta"), Schema.of(JsonType.STRING, "duration"))
        put(setOf("uuid.UUID") + names(allPydanticRoots, "UUID1", "UUID3", "UUID4", "UUID5"), Schema.of(JsonType.STRING, "uuid"))
        put(setOf("decimal.Decimal"), Schema(anyOf = listOf(Schema.NUMBER, Schema.STRING)))
        put(setOf("pathlib.Path", "pathlib.PurePath", "pathlib.PosixPath", "pathlib.WindowsPath") + names(allPydanticRoots, "FilePath", "DirectoryPath", "NewPath"), Schema.STRING)
        put(setOf("ipaddress.IPv4Address") + names(allPydanticRoots, "IPvAnyAddress"), Schema.of(JsonType.STRING, "ipv4"))
        put(setOf("ipaddress.IPv6Address"), Schema.of(JsonType.STRING, "ipv6"))
        put(setOf("ipaddress.IPv4Network", "ipaddress.IPv6Network", "ipaddress.IPv4Interface", "ipaddress.IPv6Interface"), Schema.STRING)
        put(names(allPydanticRoots, "EmailStr", "NameEmail"), Schema.of(JsonType.STRING, "email"))
        put(
            names(allPydanticRoots, "HttpUrl", "AnyUrl", "AnyHttpUrl", "FileUrl", "FtpUrl", "WebsocketUrl", "AnyWebsocketUrl", "PostgresDsn", "RedisDsn", "MongoDsn", "AmqpDsn", "KafkaDsn", "CockroachDsn", "MySQLDsn", "MariaDBDsn"),
            Schema.of(JsonType.STRING, "uri"),
        )
        put(names(allPydanticRoots, "SecretStr", "SecretBytes"), Schema.of(JsonType.STRING, "password").copy(writeOnly = true))
        put(names(allPydanticRoots, "StrictStr"), Schema.STRING)
        put(names(allPydanticRoots, "StrictInt"), Schema.INTEGER)
        put(names(allPydanticRoots, "StrictFloat"), Schema.NUMBER)
        put(names(allPydanticRoots, "StrictBool"), Schema.BOOLEAN)
        put(names(allPydanticRoots, "StrictBytes"), Schema.of(JsonType.STRING, "binary"))
        put(names(allPydanticRoots, "PositiveInt"), Schema.INTEGER.copy(exclusiveMinimum = dec("0")))
        put(names(allPydanticRoots, "NegativeInt"), Schema.INTEGER.copy(exclusiveMaximum = dec("0")))
        put(names(allPydanticRoots, "NonNegativeInt"), Schema.INTEGER.copy(minimum = dec("0")))
        put(names(allPydanticRoots, "NonPositiveInt"), Schema.INTEGER.copy(maximum = dec("0")))
        put(names(allPydanticRoots, "PositiveFloat"), Schema.NUMBER.copy(exclusiveMinimum = dec("0")))
        put(names(allPydanticRoots, "NegativeFloat"), Schema.NUMBER.copy(exclusiveMaximum = dec("0")))
        put(names(allPydanticRoots, "NonNegativeFloat"), Schema.NUMBER.copy(minimum = dec("0")))
        put(names(allPydanticRoots, "NonPositiveFloat"), Schema.NUMBER.copy(maximum = dec("0")))
    }

    fun scalar(qualified: String): Schema? = scalars[qualified]

    // --- Constrained-type functions: conint(gt=0), constr(min_length=1), conlist(int, min_length=1) -------------------------

    enum class Constrained(val base: Schema) {
        INT(Schema.INTEGER), FLOAT(Schema.NUMBER), STR(Schema.STRING), BYTES(Schema.of(JsonType.STRING, "binary")),
        DECIMAL(Schema(anyOf = listOf(Schema.NUMBER, Schema.STRING))), LIST(Schema.arrayOf(null)), SET(Schema.arrayOf(null).copy(uniqueItems = true)),
    }

    val constrainedFunctions: Map<String, Constrained> = buildMap {
        fun put(simple: String, kind: Constrained) = names(allPydanticRoots, simple).forEach { this[it] = kind }
        put("conint", Constrained.INT)
        put("confloat", Constrained.FLOAT)
        put("constr", Constrained.STR)
        put("conbytes", Constrained.BYTES)
        put("condecimal", Constrained.DECIMAL)
        put("conlist", Constrained.LIST)
        put("conset", Constrained.SET)
        put("confrozenset", Constrained.SET)
    }

    // --- Constraint keywords of Field(...), Body(...) and friends -------------------------------------------------------

    object Keys {
        const val DEFAULT = "default"
        const val DEFAULT_FACTORY = "default_factory"
        const val ALIAS = "alias"
        const val VALIDATION_ALIAS = "validation_alias"
        const val DESCRIPTION = "description"
        const val EXAMPLES = "examples"
        const val EXAMPLE = "example"
        const val DEPRECATED = "deprecated"
        const val DISCRIMINATOR = "discriminator"
        const val GT = "gt"
        const val GE = "ge"
        const val LT = "lt"
        const val LE = "le"
        const val MULTIPLE_OF = "multiple_of"
        const val MIN_LENGTH = "min_length"
        const val MAX_LENGTH = "max_length"
        const val PATTERN = "pattern"
        const val REGEX = "regex"
        const val MIN_ITEMS = "min_items"
        const val MAX_ITEMS = "max_items"
        const val UNIQUE_ITEMS = "unique_items"
        const val INIT = "init"
        const val CONFIG_EXTRA = "extra"
        const val CONFIG_ALIAS_GENERATOR = "alias_generator"
        const val CONFIG_POPULATE_BY_NAME = "populate_by_name"
        const val CONFIG_POPULATE_V1 = "allow_population_by_field_name"
        const val CONFIG_VALIDATE_BY_NAME = "validate_by_name"
        const val ATTR_MODEL_CONFIG = "model_config"
        const val NESTED_CONFIG = "Config"
    }

    /** `annotated_types` objects usable inside `Annotated[...]`: class name to the keyword they set. */
    val annotatedTypes: Map<String, String> = buildMap {
        for ((simple, key) in listOf("Gt" to Keys.GT, "Ge" to Keys.GE, "Lt" to Keys.LT, "Le" to Keys.LE, "MultipleOf" to Keys.MULTIPLE_OF, "MinLen" to Keys.MIN_LENGTH, "MaxLen" to Keys.MAX_LENGTH)) {
            this["annotated_types.$simple"] = key
        }
    }
    val stringConstraints: Set<String> = names(allPydanticRoots, "StringConstraints")
    val annotatedTypesInterval: Set<String> = setOf("annotated_types.Interval", "annotated_types.Len")

    // --- Config values -------------------------------------------------------------------------------------------------------

    const val EXTRA_FORBID = "forbid"
    const val EXTRA_ALLOW = "allow"

    /** Alias generators that ship with Pydantic; any other generator (a lambda, a project function) is not evaluated. */
    val aliasGenerators: Map<String, (String) -> String> = mapOf(
        "pydantic.alias_generators.to_camel" to AliasGenerators::toCamel,
        "pydantic.alias_generators.to_pascal" to AliasGenerators::toPascal,
        "pydantic.alias_generators.to_snake" to AliasGenerators::toSnake,
    )

    // --- Validators ---------------------------------------------------------------------------------------------------------

    val validatorDecorators: Set<String> = names(allPydanticRoots, "field_validator", "model_validator", "validator", "root_validator", "computed_field")
        .filterNot { it.endsWith("computed_field") }.toSet()
    val validatorMarkers: Set<String> = names(allPydanticRoots, "BeforeValidator", "AfterValidator", "PlainValidator", "WrapValidator")

    /** Annotated metadata that removes the field from the schema altogether. */
    val skipSchema: Set<String> = names(allPydanticRoots, "SkipJsonSchema")

    fun jsonOf(text: String): Json = Json.Str(text)
}
