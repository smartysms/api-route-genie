package com.routegenie.schema

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiModificationTracker
import com.jetbrains.python.PythonLanguage
import com.routegenie.Limits
import com.routegenie.client.BodyTemplate
import com.routegenie.client.BodyTemplateSource
import com.routegenie.model.Endpoint
import com.routegenie.schema.model.BodyRenderer
import com.routegenie.schema.model.RequestBodySchema
import com.routegenie.schema.psi.RequestSchemaExtractor
import kotlinx.coroutines.CancellationException

/**
 * The project's body template source: finds the request body an endpoint's code declares (Pydantic models, DRF serializers,
 * Django forms), and renders a sample of it. Returns null when the code does not determine a body; nothing is invented.
 *
 * Results, including "no body", are cached per endpoint and dropped as soon as any Python file of the project changes. The
 * cache holds only schemas and text (no PSI), is bounded by [Limits.SCHEMA_CACHE_MAX_ENTRIES], and goes away with the project.
 */
@Service(Service.Level.PROJECT)
class BodyTemplateService(private val project: Project) : BodyTemplateSource {
    private class Entry(val stamp: Long, val body: RequestBodySchema?, val template: BodyTemplate?)

    private val cache = object : LinkedHashMap<String, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean = size > Limits.SCHEMA_CACHE_MAX_ENTRIES
    }

    override suspend fun templateFor(endpoint: Endpoint): BodyTemplate? = entryOrNull(endpoint)?.template

    override suspend fun randomTemplateFor(endpoint: Endpoint): BodyTemplate? =
        entryOrNull(endpoint)?.body?.let { BodyRenderer.renderRandom(it) }

    /** The declared request body of [endpoint], kept for the validation phase; null when the code declares none. */
    suspend fun schemaFor(endpoint: Endpoint): RequestBodySchema? = entryOrNull(endpoint)?.body

    /** Same as [templateFor] for a caller that already holds a read action in smart mode (tests, other analyses). */
    fun templateNow(endpoint: Endpoint): BodyTemplate? = entryNowOrNull(endpoint)?.template

    fun schemaNow(endpoint: Endpoint): RequestBodySchema? = entryNowOrNull(endpoint)?.body

    fun randomTemplateNow(endpoint: Endpoint, random: kotlin.random.Random = kotlin.random.Random.Default): BodyTemplate? =
        entryNowOrNull(endpoint)?.body?.let { BodyRenderer.renderRandom(it, random) }

    /** Number of cached answers; for tests that check the bound. */
    internal fun cacheSize(): Int = synchronized(cache) { cache.size }

    /** What the cache holds, for leak tests: schemas and templates only, never PSI. */
    internal fun cachedValues(): List<Any?> = synchronized(cache) { cache.values.flatMap { listOf(it.body, it.template) } }

    /** Forgets every cached answer; the next request reads the code again. */
    internal fun clearCache() = synchronized(cache) { cache.clear() }

    private suspend fun entryOrNull(endpoint: Endpoint): Entry? {
        if (!RequestSchemaExtractor.mayHaveBody(endpoint)) return null
        cached(endpoint)?.let { return it }
        return try {
            smartReadAction(project) { compute(endpoint) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Throwable) {
            LOG.warn("Body template for ${endpoint.key} failed", e)
            null
        }
    }

    private fun entryNowOrNull(endpoint: Endpoint): Entry? {
        if (!RequestSchemaExtractor.mayHaveBody(endpoint)) return null
        cached(endpoint)?.let { return it }
        return try {
            compute(endpoint)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Throwable) {
            LOG.warn("Body template for ${endpoint.key} failed", e)
            null
        }
    }

    private fun stamp(): Long = PsiModificationTracker.getInstance(project).forLanguage(PythonLanguage.getInstance()).modificationCount

    private fun cached(endpoint: Endpoint): Entry? = synchronized(cache) {
        cache[endpoint.key]?.takeIf { it.stamp == stamp() }
    }

    /** Runs inside a read action. The stamp is read first, so an edit during the work makes the entry stale, never wrong. */
    private fun compute(endpoint: Endpoint): Entry {
        val stamp = stamp()
        val extraction = RequestSchemaExtractor.extract(project, endpoint)
        extraction.absentReason?.let { LOG.debug("No body template for ${endpoint.key}: $it") }
        val body = extraction.body
        val entry = Entry(stamp, body, body?.let { BodyRenderer.render(it) })
        synchronized(cache) { cache[endpoint.key] = entry }
        return entry
    }

    companion object {
        private val LOG = Logger.getInstance(BodyTemplateService::class.java)
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f

        fun getInstance(project: Project): BodyTemplateService = project.service()
    }
}
