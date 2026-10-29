package com.routegenie.schema.psi

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.routegenie.model.Endpoint
import com.routegenie.model.Framework
import com.routegenie.model.HttpMethod
import com.routegenie.schema.model.Confidence
import com.routegenie.schema.model.RequestBodySchema

/** The result of reading one endpoint's code: the declared request body, or the reason there is none. */
class Extraction(val body: RequestBodySchema?, val absentReason: String? = null)

/**
 * Reads the request body an endpoint's code declares. Correct or absent: a schema is returned only when the code determines
 * it; fields that cannot be determined are left out and recorded, and anything else ends in [Extraction.absentReason].
 *
 * Must run inside a read action in smart mode (it looks up module files); the work is bounded by the limits in
 * [com.routegenie.Limits] and checks for cancellation, so it can be called from a background coroutine.
 */
object RequestSchemaExtractor {
    private val LOG = Logger.getInstance(RequestSchemaExtractor::class.java)

    /** Methods that can carry a declared body. */
    private val bodyMethods = setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)

    /** False for the methods that do not carry a declared body; such endpoints are not analysed at all. */
    fun mayHaveBody(endpoint: Endpoint): Boolean = endpoint.method in bodyMethods

    fun extract(project: Project, endpoint: Endpoint): Extraction {
        if (!mayHaveBody(endpoint)) return Extraction(null, "${endpoint.method} has no declared body")
        val session = Session(project)
        return try {
            val target = HandlerLocator.locate(session, endpoint) ?: return Extraction(null, "handler ${endpoint.handler.qualifiedName} not found in the project")
            val mapper = TypeMapper(session)
            val result = when (endpoint.framework) {
                Framework.FASTAPI -> (target as? Target.Function)?.let { FastApiBodies(mapper, pathNames(endpoint)).extract(it.fn) } ?: BodyResult.NoBody
                Framework.FLASK -> flask(mapper, endpoint, target)
                Framework.DRF -> DrfBodies(mapper).extract(target, endpoint.method)
                Framework.DJANGO -> DjangoForms(mapper).extract(target, endpoint.method)
            }
            finish(session, mapper, result)
        } catch (e: BudgetExceeded) {
            LOG.info("Body extraction for ${endpoint.key} exceeded its budget")
            Extraction(null, "extraction budget exceeded")
        }
    }

    private fun flask(mapper: TypeMapper, endpoint: Endpoint, target: Target): BodyResult {
        val function = when (target) {
            is Target.Function -> target.fn
            is Target.Member -> target.method ?: mapper.hierarchy.findMethod(target.cls, endpoint.method.name.lowercase())
        } ?: return BodyResult.NoBody
        return FlaskBodies(mapper).extract(function)
    }

    private fun finish(session: Session, mapper: TypeMapper, result: BodyResult): Extraction = when (result) {
        BodyResult.NoBody -> Extraction(null, "the code declares no request body")
        is BodyResult.Unsupported -> {
            session.undetermined(result.reason, result.detail)
            Extraction(null, "${result.reason}: ${result.detail}")
        }
        is BodyResult.Found -> {
            val confidence = if (session.diagnostics.isEmpty() && session.validators.isEmpty()) Confidence.HIGH else Confidence.MEDIUM
            val schema = pruneDefs(result.schema, mapper.defs)
            Extraction(RequestBodySchema(result.encoding, schema, confidence, session.diagnostics.toList(), session.validators.toList()))
        }
    }

    /** Names of the path parameters: the model's own list plus any `{name}` or `{name:converter}` in the template. */
    private fun pathNames(endpoint: Endpoint): Set<String> {
        val names = endpoint.pathParams.mapTo(LinkedHashSet()) { it.name }
        PATH_PARAMETER.findAll(endpoint.path).forEach { names += it.groupValues[1] }
        return names
    }

    private val PATH_PARAMETER = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)[^}]*}")
}
