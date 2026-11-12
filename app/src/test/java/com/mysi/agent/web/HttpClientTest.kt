package com.mysi.agent.web

import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HttpClientTest {
    private val fake = FakeWeb()

    private fun get(client: HttpClient, url: String, max: Int = 3_000_000) = client.fetch(HttpRequest(WebUrl.parse(url)!!), max)

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            fail("expected ${T::class.simpleName} but got $t")
        }
        fail("expected ${T::class.simpleName}")
        throw AssertionError()
    }

    @Test
    fun chunkedGzipBodiesAreDecoded() {
        val html = "<p>" + "solar ".repeat(5000) + "</p>"
        fake.handler = {
            FakeWeb.response(200, listOf("Content-Type" to "text/html; charset=utf-8", "Transfer-Encoding" to "chunked", "Content-Encoding" to "gzip"), FakeWeb.chunked(FakeWeb.gzip(html.toByteArray()), 777))
        }
        val page = get(fake.client(), "https://example.com/")
        assertEquals(html, page.text)
        assertFalse(page.truncated)
    }

    @Test
    fun contentLengthAndConnectionCloseFramingWork() {
        fake.handler = { FakeWeb.response(200, listOf("Content-Length" to "5", "Content-Type" to "text/plain"), "helloEXTRA-IGNORED".toByteArray()) }
        assertEquals("hello", get(fake.client(), "https://a.example/").text)
        fake.handler = { FakeWeb.response(200, listOf("Content-Type" to "text/plain"), "until close".toByteArray()) }
        assertEquals("until close", get(fake.client(), "https://b.example/").text)
        // An informational 100 Continue before the real answer is skipped.
        fake.handler = { "HTTP/1.1 100 Continue\r\n\r\n".toByteArray() + FakeWeb.ok("after continue", "text/plain") }
        assertEquals("after continue", get(fake.client(), "https://c.example/").text)
    }

    @Test
    fun oversizedOrBrokenHeadersFail() {
        fake.handler = { FakeWeb.response(200, listOf("X-Big" to "a".repeat(10_000)), ByteArray(0)) }
        assertThrows<IOException> { get(fake.client(), "https://a.example/") }
        fake.handler = { FakeWeb.response(200, (1..300).map { "X-$it" to "v" }, ByteArray(0)) }
        assertThrows<IOException> { get(fake.client(), "https://b.example/") }
        fake.handler = { "SSH-2.0-OpenSSH\r\n".toByteArray() }
        assertThrows<IOException> { get(fake.client(), "https://c.example/") }
        fake.handler = { FakeWeb.response(200, listOf("Transfer-Encoding" to "chunked"), "zz\r\nabc".toByteArray()) }
        assertThrows<IOException> { get(fake.client(), "https://d.example/") }
        fake.handler = { FakeWeb.response(200, listOf("Content-Encoding" to "br"), "x".toByteArray()) }
        assertThrows<IOException> { get(fake.client(), "https://e.example/") }
    }

    @Test
    fun bodiesAreCutAtTheByteCap() {
        fake.handler = { FakeWeb.ok("x".repeat(10_000), "text/plain") }
        val page = get(fake.client(), "https://example.com/", max = 1000)
        assertEquals(1000, page.bytes.size)
        assertTrue(page.truncated)
    }

    @Test
    fun aGzipBombIsCutAtTheByteCapToo() {
        val bomb = FakeWeb.gzip(ByteArray(50_000_000))
        fake.handler = { FakeWeb.response(200, listOf("Content-Encoding" to "gzip", "Content-Type" to "text/plain", "Content-Length" to bomb.size.toString()), bomb) }
        val page = get(fake.client(), "https://example.com/", max = 100_000)
        assertEquals(100_000, page.bytes.size)
        assertTrue(page.truncated)
    }

    @Test
    fun aRedirectToAPrivateAddressIsBlocked() {
        fake.handler = { r -> if (r.url.host == "public.example") FakeWeb.redirect(302, "http://127.0.0.1:8080/admin") else FakeWeb.ok("secret") }
        assertThrows<BlockedUrlException> { get(fake.client(cleartext = { true }), "https://public.example/") }
        // A redirect to a name that resolves privately is blocked too, after DNS.
        fake.dns["internal.example"] = listOf(fake.ip(192, 168, 0, 10))
        fake.handler = { r -> if (r.url.host == "public.example") FakeWeb.redirect(301, "https://internal.example/") else FakeWeb.ok("router") }
        assertThrows<BlockedUrlException> { get(fake.client(), "https://public.example/") }
        // Never connected to either private host.
        assertTrue(fake.requests.all { it.url.host == "public.example" })
        // Redirects to metadata endpoints via decimal IPs and to other schemes are refused.
        fake.handler = { FakeWeb.redirect(302, "http://2852039166/latest/meta-data/") }
        assertThrows<BlockedUrlException> { get(fake.client(cleartext = { true }), "https://x.example/") }
        fake.handler = { FakeWeb.redirect(302, "file:///etc/passwd") }
        assertThrows<BlockedUrlException> { get(fake.client(), "https://y.example/") }
    }

    @Test
    fun redirectsAreFollowedUpToFiveTimes() {
        fake.handler = { r ->
            val n = r.url.rawPath.removePrefix("/").toIntOrNull() ?: 0
            if (n < 5) FakeWeb.redirect(302, "/${n + 1}") else FakeWeb.ok("arrived at $n", "text/plain")
        }
        val page = get(fake.client(), "https://example.com/0")
        assertEquals("arrived at 5", page.text)
        assertEquals("https://example.com/5", page.url.toString())
        fake.handler = { r -> FakeWeb.redirect(302, "/" + ((r.url.rawPath.removePrefix("/").toIntOrNull() ?: 0) + 1)) }
        assertThrows<IOException> { get(fake.client(), "https://loop.example/0") }
        assertEquals(6 + 6, fake.requests.size)
    }

    @Test
    fun postRedirectsFollowBrowserRules() {
        fake.handler = { r ->
            when (r.url.rawPath) {
                "/form" -> FakeWeb.redirect(302, "/done")
                "/keep" -> FakeWeb.redirect(307, "/echo")
                else -> FakeWeb.ok("${r.method} ${r.body}", "text/plain")
            }
        }
        val c = fake.client()
        val a = c.fetch(HttpRequest(WebUrl.parse("https://forms.example/form")!!, "POST", "a=1".toByteArray()))
        assertEquals("GET ", a.text)
        assertEquals("https://forms.example/done", a.url.toString())
        val b = c.fetch(HttpRequest(WebUrl.parse("https://forms.example/keep")!!, "POST", "a=1".toByteArray()))
        assertEquals("POST a=1", b.text)
    }

    @Test
    fun plainHttpIsUpgradedWhenTheNetworkPolicyForbidsCleartext() {
        fake.handler = { r -> FakeWeb.ok(r.url.scheme, "text/plain") }
        assertEquals("https", get(fake.client(cleartext = { false }), "http://example.com/").text)
        assertEquals("http", get(fake.client(cleartext = { true }), "http://example.org/").text)
        assertThrows<BlockedUrlException> { get(fake.client(cleartext = { false }), "http://example.net:8080/") }
    }

    @Test
    fun onlyFixedHonestHeadersAreSent() {
        fake.handler = { FakeWeb.ok("ok", "text/plain") }
        val c = fake.client(HttpClient.Config(mobileAgent = "UA-mobile MySI/1", politenessMs = 0))
        get(c, "https://example.com/path?q=1")
        val r = fake.requests.single()
        assertEquals("GET /path?q=1 HTTP/1.1", r.raw.substringBefore("\r\n"))
        assertEquals(
            listOf("host", "user-agent", "accept", "accept-language", "accept-encoding", "connection"),
            r.headerLines.map { it.substringBefore(':').lowercase() },
        )
        assertEquals("example.com", r.header("Host"))
        assertEquals("UA-mobile MySI/1", r.header("User-Agent"))
        assertEquals("gzip", r.header("Accept-Encoding"))
        assertEquals("close", r.header("Connection"))
        assertNull(r.header("Cookie"))
        assertNull(r.header("Referer"))
        // The connection went to the address the safety check approved.
        assertEquals("93.184.216.34", r.address.hostAddress)
    }

    @Test
    fun aTricklingServerHitsTheOverallDeadline() {
        var now = 0L
        val trickle = object : InputStream() {
            val head = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n".toByteArray()
            var i = 0

            override fun read(): Int = if (i < head.size) head[i++].toInt() else 'x'.code

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b[off] = read().toByte()
                return 1
            }
        }
        val transport = Transport { _, _, _, _ ->
            object : Connection {
                override val input = trickle
                override val output = java.io.ByteArrayOutputStream()

                override fun close() = Unit
            }
        }
        val c = HttpClient(UrlSafety(fake.resolver), transport, HttpClient.Config(politenessMs = 0, deadlineMs = 30_000), nanoTime = { now.also { now += 10_000_000 } })
        assertThrows<SocketTimeoutException> { c.fetch(HttpRequest(WebUrl.parse("https://slow.example/")!!)) }
    }

    @Test
    fun politenessSpacesRequestsToTheSameHost() {
        val waits = mutableListOf<Long>()
        fake.handler = { FakeWeb.ok("ok", "text/plain") }
        val c = fake.client(HttpClient.Config(politenessMs = 750), nanoTime = { 0L }, sleeper = { waits += it })
        c.fetch(HttpRequest(WebUrl.parse("https://a.example/1")!!))
        c.fetch(HttpRequest(WebUrl.parse("https://a.example/2")!!))
        c.fetch(HttpRequest(WebUrl.parse("https://b.example/1")!!))
        assertEquals(listOf(750L), waits)
    }

    @Test
    fun successfulGetsAreCachedForTheSession() {
        fake.handler = { FakeWeb.ok("page", "text/html") }
        val c = fake.client()
        get(c, "https://example.com/x")
        get(c, "https://example.com/x#section")
        assertEquals(1, fake.requests.size)
        fake.handler = { FakeWeb.notFound() }
        get(c, "https://example.com/missing")
        get(c, "https://example.com/missing")
        assertEquals(3, fake.requests.size)
        c.cache.clear()
        fake.handler = { FakeWeb.ok("page", "text/html") }
        get(c, "https://example.com/x")
        assertEquals(4, fake.requests.size)
    }

    @Test
    fun theCacheEvictsByCountSizeAndAge() {
        var now = 0L
        val cache = PageCache(maxEntries = 2, maxBytes = 1_000_000, ttlMs = 1000, now = { now })
        fun page(n: Int) = FetchedPage(WebUrl.parse("https://e.example/$n")!!, 200, "text/plain", ByteArray(10), false)
        cache.put("1", page(1))
        cache.put("2", page(2))
        cache.put("3", page(3))
        assertNull(cache.get("1"))
        assertEquals(2, cache.size)
        now = 5000
        assertNull(cache.get("3"))
    }
}
