package com.routegenie.schema.psi

import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.PyTokenTypes
import com.jetbrains.python.psi.PyBinaryExpression
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyIfStatement
import com.jetbrains.python.psi.PyRaiseStatement
import com.jetbrains.python.psi.PyReferenceExpression
import com.routegenie.schema.model.FileSpec
import java.math.BigInteger

/** What the code of an upload check looks like; shared by the framework readers so the same check means the same thing everywhere. */
internal object UploadChecks {
    const val FILE_EXTENSION_VALIDATOR = "django.core.validators.FileExtensionValidator"
    const val IMAGE_EXTENSION_VALIDATOR = "django.core.validators.validate_image_file_extension"
    const val KEY_ALLOWED_EXTENSIONS = "allowed_extensions"

    val WHITESPACE = Regex("\\s+")
    private val EXTENSION_SHAPE = Regex("""(rsplit\(["']\.["'],1\)\[(1|-1)]|split\(["']\.["']\)\[-1]|splitext\([^()]*\)\[1])(\.lower\(\))?$""")

    /** `x.rsplit(".", 1)[1]`, `x.rsplit(".", 1)[-1]`, `x.split(".")[-1]`, `os.path.splitext(x)[1]`, with an optional `.lower()` after. */
    fun endsWithExtension(e: PyExpression?): Boolean {
        val text = PyLiterals.unparen(e)?.text?.replace(WHITESPACE, "") ?: return false
        return EXTENSION_SHAPE.containsMatchIn(text)
    }

    /** Lower-case, trimmed, with the leading dot: `PNG` and `.png` both give `.png`. */
    fun extensions(entries: List<String>): List<String> =
        entries.map { it.trim().lowercase() }.filter { it.isNotEmpty() && it != "." }.map { if (it.startsWith(".")) it else ".$it" }.distinct()

    /** A whole number written as a literal, a constant, or a sum, difference, product, power or shift of those; null for anything else. */
    fun integer(expression: PyExpression?, constants: ConstantResolver, depth: Int = 0): BigInteger? {
        if (depth > MAX_DEPTH) return null
        val e = PyLiterals.unparen(constants(expression ?: return null)) ?: return null
        PyLiterals.int(e)?.let { return BigInteger.valueOf(it.toLong()) }
        if (e !is PyBinaryExpression) return null
        val left = integer(e.leftExpression, constants, depth + 1) ?: return null
        val right = integer(e.rightExpression ?: return null, constants, depth + 1) ?: return null
        val op = e.operator
        return when {
            op == PyTokenTypes.MULT -> left.multiply(right)
            op == PyTokenTypes.PLUS -> left.add(right)
            op == PyTokenTypes.MINUS -> left.subtract(right)
            op == PyTokenTypes.EXP && right.bitLength() < EXP_BITS && right.signum() >= 0 -> left.pow(right.toInt())
            op == PyTokenTypes.LTLT && right.bitLength() < EXP_BITS && right.signum() >= 0 -> left.shiftLeft(right.toInt())
            else -> null
        }
    }

    private const val MAX_DEPTH = 6
    private const val EXP_BITS = 7
}

/**
 * The facts a Django or DRF file field states about the upload, from its constructor call: `FileExtensionValidator`, the
 * image validator, and project validator functions whose only checks are a size limit or an extension list. A validator
 * that is anything else is code that cannot be evaluated: the field stays, and the field is recorded in
 * `Session.validators`, which marks the schema as a necessary condition only. `max_length` of a file field is the length of
 * the file NAME, never a size, and nothing else (`upload_to`, `allow_empty_file`, `use_url`) says what a client sends.
 */
internal class FileFields(private val mapper: TypeMapper) {
    private val session = mapper.session
    private val symbols = session.symbols

    private class Facts(var extensions: List<String> = emptyList(), var maxBytes: Long? = null, var image: Boolean = false, var understood: Boolean = true)

    /** [call] is the field constructor (null when unknown), [image] true for an `ImageField`, [where] names the field for the record. */
    fun spec(call: PyCallExpression?, file: PyFile, image: Boolean, where: String): FileSpec {
        val facts = Facts(image = image)
        val validators = call?.let { PyLiterals.keyword(it, VALIDATORS) }
        if (validators != null) {
            val items = PyLiterals.elements(mapper.constants(validators))
            if (items == null) facts.understood = false else items.forEach { read(it, file, facts, 0) }
        }
        if (!facts.understood) session.validators += where
        return FileSpec(extensions = facts.extensions, image = facts.image, maxBytes = facts.maxBytes)
    }

    private fun read(item: PyExpression, file: PyFile, facts: Facts, depth: Int) {
        val e = PyLiterals.unparen(item)
        if (e is PyCallExpression) {
            val name = symbols.libName(e.callee, file)
            if (name == UploadChecks.FILE_EXTENSION_VALIDATOR) {
                val argument = PyLiterals.keyword(e, UploadChecks.KEY_ALLOWED_EXTENSIONS) ?: PyLiterals.positional(e).firstOrNull()
                if (argument == null || PyLiterals.isNone(argument)) return
                val list = PyLiterals.strings(mapper.constants(argument))
                if (list == null) facts.understood = false else restrict(facts, UploadChecks.extensions(list))
                return
            }
            facts.understood = false
            return
        }
        if (e is PyReferenceExpression) {
            when (val sym = symbols.resolve(e, file)) {
                is Sym.Lib -> if (sym.name == UploadChecks.IMAGE_EXTENSION_VALIDATOR) facts.image = true else facts.understood = false
                is Sym.Fn -> if (depth == 0) function(sym.fn, facts) else facts.understood = false
                else -> facts.understood = false
            }
            return
        }
        facts.understood = false
    }

    private fun restrict(facts: Facts, extensions: List<String>) {
        facts.extensions = if (facts.extensions.isEmpty()) extensions else facts.extensions.filter { it in extensions }
    }

    /**
     * A project validator function is understood when each of its `raise` statements sits in an `if` whose condition is a size
     * limit on the first parameter (`value.size > LIMIT`, `value.size >= LIMIT`, `LIMIT < value.size`) or an extension test
     * (`<extension of value.name> not in [...]`); anything else it does is unknown.
     */
    private fun function(function: PyFunction, facts: Facts) {
        val parameter = function.parameterList.parameters.firstOrNull()?.name
        val home = function.containingFile as? PyFile
        if (parameter == null || home == null) {
            facts.understood = false
            return
        }
        val raises = PsiTreeUtil.findChildrenOfType(function, PyRaiseStatement::class.java).size
        var recognised = 0
        for (statement in PsiTreeUtil.findChildrenOfType(function, PyIfStatement::class.java)) {
            val part = statement.ifPart
            val condition = part.condition as? PyBinaryExpression ?: continue
            val inside = PsiTreeUtil.findChildrenOfType(part.statementList, PyRaiseStatement::class.java).size
            if (inside == 0) continue
            val before = recognised
            sizeLimit(condition, parameter)?.let { limit ->
                facts.maxBytes = facts.maxBytes?.let { minOf(it, limit) } ?: limit
                recognised += inside
            }
            if (recognised == before) {
                if (condition.text.removePrefix(condition.leftExpression.text).trimStart().startsWith("not") && condition.text.contains(NOT_IN) && UploadChecks.endsWithExtension(condition.leftExpression) && isNameOf(condition.leftExpression, parameter)) {
                    val list = PyLiterals.strings(mapper.constants(condition.rightExpression ?: continue))
                    if (list != null) {
                        restrict(facts, UploadChecks.extensions(list))
                        recognised += inside
                    }
                }
            }
        }
        if (raises == 0 || recognised != raises) facts.understood = false
    }

    private fun isNameOf(e: PyExpression?, parameter: String): Boolean {
        val text = PyLiterals.unparen(e)?.text?.replace(Regex("\\s+"), "") ?: return false
        return text.contains("$parameter.name") || text.contains("$parameter.file.name")
    }

    /** The largest size the condition lets through, when it is `p.size > N` and the like; null when it is not that shape. */
    private fun sizeLimit(condition: PyBinaryExpression, parameter: String): Long? {
        val left = PyLiterals.unparen(condition.leftExpression)
        val right = PyLiterals.unparen(condition.rightExpression)
        fun isSize(e: PyExpression?): Boolean {
            val text = e?.text?.replace(Regex("\\s+"), "") ?: return false
            return text == "$parameter.size" || text == "$parameter.file.size"
        }
        val op = condition.operator
        val (limit, strict) = when {
            isSize(left) && op == PyTokenTypes.GT -> UploadChecks.integer(right, mapper.constants) to false
            isSize(left) && op == PyTokenTypes.GE -> UploadChecks.integer(right, mapper.constants) to true
            isSize(right) && op == PyTokenTypes.LT -> UploadChecks.integer(left, mapper.constants) to false
            isSize(right) && op == PyTokenTypes.LE -> UploadChecks.integer(left, mapper.constants) to true
            else -> return null
        }
        val value = limit ?: return null
        if (value.signum() < 0 || value.bitLength() >= Long.SIZE_BITS - 1) return null
        return if (strict) value.toLong() - 1 else value.toLong()
    }

    /**
     * True when a form field class stands for several files: it names a widget class that sets `allow_multiple_selected = True`
     * (Django's documented `MultipleFileField` recipe), or its constructor call gives `widget=...(attrs={"multiple": True})`.
     */
    fun acceptsSeveral(call: PyCallExpression, cls: PyClass?): Boolean {
        val widget = PyLiterals.keyword(call, WIDGET)
        if (widget is PyCallExpression) {
            val attrs = PyLiterals.json(PyLiterals.keyword(widget, ATTRS)) as? com.routegenie.schema.model.Json.Obj
            if ((attrs?.fields?.get(MULTIPLE) as? com.routegenie.schema.model.Json.Bool)?.value == true) return true
        }
        val classes = ArrayList<PyClass>()
        widget?.let { (symbols.resolve((PyLiterals.unparen(it) as? PyCallExpression)?.callee ?: it, it.containingFile as? PyFile) as? Sym.Cls)?.cls?.let(classes::add) }
        if (cls != null) {
            for (c in mapper.hierarchy.baseFirst(cls)) {
                val home = c.containingFile as? PyFile ?: continue
                for (reference in PsiTreeUtil.findChildrenOfType(c, PyReferenceExpression::class.java)) {
                    (symbols.resolve(reference, home) as? Sym.Cls)?.cls?.let { if (it !== c && it !in classes) classes += it }
                    if (classes.size > MAX_WIDGET_CANDIDATES) break
                }
            }
        }
        return classes.any { setsMultiple(it) }
    }

    private fun setsMultiple(cls: PyClass): Boolean = mapper.hierarchy.baseFirst(cls).any { c ->
        c.statementList.statements.any { s ->
            val a = s as? com.jetbrains.python.psi.PyAssignmentStatement
            (a?.targets?.singleOrNull() as? com.jetbrains.python.psi.PyTargetExpression)?.name == ALLOW_MULTIPLE && PyLiterals.bool(a?.assignedValue) == true
        }
    }

    private companion object {
        val NOT_IN = Regex("""\snot\s+in\s""")
        const val VALIDATORS = "validators"
        const val WIDGET = "widget"
        const val ATTRS = "attrs"
        const val MULTIPLE = "multiple"
        const val ALLOW_MULTIPLE = "allow_multiple_selected"
        const val MAX_WIDGET_CANDIDATES = 40
    }
}
