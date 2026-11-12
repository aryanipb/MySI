package com.mysi.agent.models

import com.mysi.agent.web.FakeWeb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The remote catalogue fetch and which catalogue wins. Nothing here touches the network. */
class CatalogSourceTest {
    private val json = ModelFixtures.builtInJson
    private val web = FakeWeb()
    private val client = web.client()

    private fun failed(o: CatalogFetcher.Outcome) = (o as CatalogFetcher.Outcome.Failed).reason

    @Test
    fun fetchesAndValidatesFromGitHubRaw() {
        web.handler = { FakeWeb.ok(json, "text/plain; charset=utf-8") }
        val o = CatalogFetcher.fetch(client)
        assertTrue(o is CatalogFetcher.Outcome.Fetched)
        assertEquals(ModelFixtures.builtIn, (o as CatalogFetcher.Outcome.Fetched).catalog)
        val req = web.requests.single()
        assertEquals("raw.githubusercontent.com", req.url.host)
        assertEquals("/aryanipb/MySI/main/models.json", req.url.rawPath)
        // Only the client's fixed headers: nothing about the phone or the user.
        assertEquals(listOf("Host", "User-Agent", "Accept", "Accept-Language", "Accept-Encoding", "Connection"), req.headerLines.map { it.substringBefore(':') })
    }

    @Test
    fun notFoundFallsBackCleanly() {
        // Before models.json is on main, GitHub answers 404.
        web.handler = { FakeWeb.notFound() }
        assertEquals("The catalogue server answered 404.", failed(CatalogFetcher.fetch(client)))
    }

    @Test
    fun redirectAwayFromGitHubRawIsRefused() {
        web.handler = { r ->
            if (r.url.host == "raw.githubusercontent.com") FakeWeb.redirect(302, "https://evil.example/models.json") else FakeWeb.ok(json, "application/json")
        }
        assertEquals("The catalogue was redirected away from raw.githubusercontent.com.", failed(CatalogFetcher.fetch(client)))
    }

    @Test
    fun invalidOrHugeBodiesAreRefused() {
        web.handler = { FakeWeb.ok(json.replace("huggingface.co/litert-community/gemma-4-E2B", "evil.example/litert-community/gemma-4-E2B"), "text/plain") }
        assertTrue(failed(CatalogFetcher.fetch(client)).contains("url must be"))
        web.handler = { FakeWeb.ok("x".repeat(CatalogParser.MAX_BYTES + 10), "text/plain") }
        assertEquals("The catalogue is too large.", failed(CatalogFetcher.fetch(client)))
        web.handler = { FakeWeb.ok("<html>rate limited</html>") }
        assertEquals("The catalogue is not valid JSON.", failed(CatalogFetcher.fetch(client)))
    }

    @Test
    fun networkErrorsDoNotThrow() {
        web.handler = { throw java.io.IOException("Network is unreachable") }
        assertEquals("Network is unreachable", failed(CatalogFetcher.fetch(client)))
        // A host that resolves to a private address is blocked by the app's URL safety rules.
        web.dns["raw.githubusercontent.com"] = listOf(web.ip(10, 0, 0, 1))
        assertTrue(failed(CatalogFetcher.fetch(client)).contains("private or local address"))
    }

    // ---------------- which catalogue wins ----------------

    @Test
    fun newerOrEqualRemoteWinsOlderLoses() {
        val built = ModelFixtures.builtIn
        assertEquals(built, CatalogPolicy.choose(built, null))
        val newer = built.copy(version = built.version + 1, models = built.models.take(2))
        assertEquals(newer, CatalogPolicy.choose(built, newer))
        val same = built.copy(updated = "2026-11-13")
        assertEquals(same, CatalogPolicy.choose(built, same))
        // A cached copy older than what ships with this app version is ignored.
        val older = built.copy(version = 0)
        assertEquals(built, CatalogPolicy.choose(built, older))
    }

    @Test
    fun fetchAtMostDailyAndRetryHourly() {
        val day = CatalogPolicy.FRESH_MS
        val hour = CatalogPolicy.RETRY_MS
        assertTrue(CatalogPolicy.shouldFetch(now = day * 10, lastSuccess = 0, lastAttempt = 0))
        assertTrue(!CatalogPolicy.shouldFetch(now = day * 10, lastSuccess = day * 10 - hour, lastAttempt = day * 10 - hour))
        assertTrue(CatalogPolicy.shouldFetch(now = day * 10, lastSuccess = day * 9 - 1, lastAttempt = day * 9 - 1))
        // A failure an hour ago allows a retry; one a minute ago does not.
        assertTrue(CatalogPolicy.shouldFetch(now = day * 10, lastSuccess = 0, lastAttempt = day * 10 - hour))
        assertTrue(!CatalogPolicy.shouldFetch(now = day * 10, lastSuccess = 0, lastAttempt = day * 10 - 60_000))
        // A clock set backwards does not block refreshing forever.
        assertTrue(CatalogPolicy.shouldFetch(now = 1_000, lastSuccess = day * 10, lastAttempt = day * 10))
    }

    @Test
    fun testEntriesAreAddedOnlyIfTestOnlyAndNew() {
        val built = ModelFixtures.builtIn
        val t = built.models[5].copy(id = "test-x", testOnly = true)
        val clash = built.models[0].copy(testOnly = true)
        val notTest = built.models[1].copy(id = "sneaky")
        val merged = CatalogPolicy.withTestEntries(built, listOf(t, clash, notTest))
        assertEquals(built.models + t, merged.models)
    }
}
