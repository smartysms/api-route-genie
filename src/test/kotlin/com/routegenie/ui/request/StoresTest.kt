package com.routegenie.ui.request

import com.routegenie.Limits
import com.routegenie.client.RequestHistory
import com.routegenie.client.SecretKeys
import com.routegenie.model.HttpMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stores behind the request panel, without an IDE. */
class StoresTest {
    @Test
    fun `secret reads never wait for the store and writes reach it later and once per key`() {
        val store = MapSecretStore()
        store.set("dev", SecretKeys.TOKEN, "stored-token")
        val queue = ArrayList<Runnable>()
        val secrets = LayeredSecrets(store) { queue += it }
        secrets.useScope("dev", listOf(SecretKeys.TOKEN))
        assertNull("nothing is read before the background task ran", secrets.get(SecretKeys.TOKEN))
        queue.toList().also { queue.clear() }.forEach { it.run() }
        assertEquals("stored-token", secrets.get(SecretKeys.TOKEN))

        secrets.put(SecretKeys.TOKEN, "a")
        secrets.put(SecretKeys.TOKEN, "b")
        assertEquals("memory answers at once", "b", secrets.get(SecretKeys.TOKEN))
        assertEquals("the store is untouched until the background task runs", "stored-token", store.get("dev", SecretKeys.TOKEN))
        while (queue.isNotEmpty()) queue.removeAt(0).run()
        assertEquals("the last value wins", "b", store.get("dev", SecretKeys.TOKEN))

        secrets.put(SecretKeys.TOKEN, "")
        while (queue.isNotEmpty()) queue.removeAt(0).run()
        assertNull("an empty value removes the secret", store.get("dev", SecretKeys.TOKEN))
    }

    @Test
    fun `a secret typed for another environment goes to that environment and not into the current memory`() {
        val store = MapSecretStore()
        val secrets = LayeredSecrets(store, directExecutor)
        secrets.useScope("dev", emptyList())
        secrets.putFor("staging", SecretKeys.TOKEN, "s")
        assertNull(secrets.get(SecretKeys.TOKEN))
        assertEquals("s", store.get("staging", SecretKeys.TOKEN))
        assertNull(store.get("dev", SecretKeys.TOKEN))
    }

    @Test
    fun `switching scope drops the memory of the old one`() {
        val store = MapSecretStore()
        val secrets = LayeredSecrets(store, directExecutor)
        secrets.useScope("a", emptyList())
        secrets.put(SecretKeys.API_KEY, "k")
        secrets.useScope("b", listOf(SecretKeys.API_KEY))
        assertFalse(secrets.has(SecretKeys.API_KEY))
        assertEquals("k", store.get("a", SecretKeys.API_KEY))
    }

    private fun entry(n: Long, body: KeptBody?) = HistoryEntry("k", n, HttpMethod.GET, "http://h/$n", 200, null, 1, 1, null, body)

    @Test
    fun `only the newest responses keep a body and older entries lose it`() {
        val log = HistoryLog(bodiesKept = 3)
        repeat(6) { log.add(entry(it.toLong(), KeptBody.of(jsonResponse("{\"n\":$it}")))) }
        assertEquals(6, log.size)
        assertEquals(listOf(5L, 4L, 3L), log.withBodies().map { it.atMillis })
        assertNull(log.all().last().body)
        assertEquals(4L, log.previousWithBody(log.all().first())?.atMillis)
        log.releaseBodies()
        assertTrue(log.withBodies().isEmpty())
        assertEquals("lines stay", 6, log.size)
    }

    @Test
    fun `bodies that are not JSON or are too large for memory are not kept`() {
        assertNull(KeptBody.of(textResponse("plain")))
        assertNull(KeptBody.of(jsonResponse("")))
        val big = "{\"x\":\"" + "a".repeat(Limits.HISTORY_BODY_KEPT_MAX_BYTES) + "\"}"
        assertNull(KeptBody.of(jsonResponse(big)))
        assertNotNull(KeptBody.of(jsonResponse("{\"x\":1}")))
    }

    @Test
    fun `spilled bodies are limited separately and release their file when dropped`() {
        TestSpill().use { sp ->
            val log = HistoryLog(bodiesKept = 5, spilledKept = 1)
            val files = (0 until 3).map { sp.spilled("{\"n\":$it,\"pad\":\"" + "x".repeat(200) + "\"}") }
            files.forEachIndexed { i, r ->
                log.add(entry(i.toLong(), KeptBody.of(r)))
                r.release()
            }
            assertEquals(1, log.withBodies().size)
            assertEquals(1, sp.liveFiles().size)
            log.releaseBodies()
            assertTrue(sp.liveFiles().isEmpty())
        }
    }

    @Test
    fun `history lines are mirrored into the persisted history and loaded back`() {
        val persisted = RequestHistory()
        val log = HistoryLog(persisted = persisted)
        log.add(entry(1, KeptBody.of(jsonResponse("{}"))))
        assertEquals(1, persisted.snapshot().size)
        val again = HistoryLog(persisted = persisted)
        assertEquals(1, again.size)
        assertNull("loaded lines carry no body and no form", again.all().single().body)
        assertNull(again.all().single().form)
    }
}
