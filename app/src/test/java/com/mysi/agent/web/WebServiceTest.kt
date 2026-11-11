package com.mysi.agent.web

import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class WebServiceTest {
    private val fake = FakeWeb()
    private lateinit var dir: File
    private lateinit var web: WebService

    /** Routes by "host/path"; anything else is a 404. */
    private val routes = HashMap<String, (FakeWeb.Request) -> ByteArray>()

    private fun route(hostPath: String, respond: (FakeWeb.Request) -> ByteArray) {
        routes[hostPath] = respond
    }

    private val pdf = "%PDF-1.4\n".toByteArray() + ByteArray(3000) { 'a'.code.toByte() }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("ws").toFile()
        fake.handler = { r -> routes[r.url.host + r.url.rawPath]?.invoke(r) ?: FakeWeb.notFound() }
        web = WebService(fake.client(), Downloader(File(dir, "Downloads")))
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun form(r: FakeWeb.Request) = r.body.split('&').associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    @Test
    fun searchFallsBackFromBlockedDuckDuckGoToLite() {
        val blocked = Fixtures.text("ddg_blocked.html")
        route("html.duckduckgo.com/html/") { FakeWeb.response(202, listOf("Content-Type" to "text/html"), blocked.toByteArray()) }
        route("lite.duckduckgo.com/lite/") { FakeWeb.ok(Fixtures.text("ddg_lite.html")) }
        val out = web.search("monsoon kerala", 5, "imd.gov.in", "week")
        assertTrue(out, out.startsWith("Results from DuckDuckGo Lite for 'monsoon kerala' (DuckDuckGo: DuckDuckGo asked for a bot check):"))
        assertTrue(out.contains("1. Monsoon Information - India Meteorological Department\n   https://mausam.imd.gov.in/monsoon\n"))
        val posts = fake.requests.filter { it.method == "POST" }
        assertEquals(2, posts.size)
        assertEquals(mapOf("q" to "monsoon kerala site:imd.gov.in", "df" to "w"), form(posts[0]))
        // Same search again within the session: served from memory.
        web.search("monsoon kerala", 5, "imd.gov.in", "week")
        assertEquals(2, fake.requests.size)
    }

    @Test
    fun searchFallsBackToWikipediaWhenDuckDuckGoIsBlockedEverywhere() {
        val blocked = Fixtures.text("ddg_blocked.html")
        route("html.duckduckgo.com/html/") { FakeWeb.response(202, listOf("Content-Type" to "text/html"), blocked.toByteArray()) }
        route("lite.duckduckgo.com/lite/") { FakeWeb.response(202, listOf("Content-Type" to "text/html"), blocked.toByteArray()) }
        route("en.wikipedia.org/w/api.php") { FakeWeb.ok(Fixtures.text("wiki_search.json"), "application/json") }
        val out = web.search("solar panel", 3, null, null)
        assertTrue(out, out.startsWith("Results from Wikipedia for 'solar panel'"))
        assertTrue(out.contains("Showing Wikipedia results instead"))
        assertTrue(out.contains("https://en.wikipedia.org/wiki/Solar_panel"))
        val outcome = web.searchResults("solar panel", 3, "who.int", null)
        assertTrue(outcome.results.isEmpty())
    }

    @Test
    fun duckDuckGoResultsComeFirstWhenAvailable() {
        route("html.duckduckgo.com/html/") { FakeWeb.ok(Fixtures.text("ddg_results.html")) }
        val out = web.search("solar panel efficiency", 2, null, null)
        assertTrue(out.startsWith("Results from DuckDuckGo for 'solar panel efficiency':"))
        assertTrue(out.contains("Date: 2024-03-18"))
        assertFalse(out.contains("3. "))
        assertEquals(1, fake.requests.size)
        assertTrue(web.search("x", 3, "not a site!", null).contains("is not a website address"))
    }

    @Test
    fun readingPagesInPartsCoversTheWholeText() {
        route("cityherald.example/pune/rooftop-solar-doubles") { FakeWeb.ok(Fixtures.text("article.html")) }
        val url = "https://cityherald.example/pune/rooftop-solar-doubles"
        val full = Readability.extract(Fixtures.text("article.html"), url).text
        val first = web.read(url, 0, 600)
        assertTrue(first.startsWith("Title: Rooftop solar adoption doubles in Pune as subsidies reach households\nAddress: $url\nBy: Meera Kulkarni · Published: 2026-10-02T08:30:00+05:30 · Site: City Herald\nPart: characters 0–"))
        val parts = mutableListOf<String>()
        var offset = 0
        var guard = 0
        while (guard++ < 20) {
            val r = web.read(url, offset, 600)
            parts += r.substringAfter("---\n")
            offset = Regex("offset=(\\d+)\\.").find(r)?.groupValues?.get(1)?.toInt() ?: break
        }
        assertTrue(parts.size > 2)
        assertEquals(Element.collapse(full), Element.collapse(parts.joinToString(" ")))
        // One network request for all parts.
        assertEquals(1, fake.requests.size)
        assertTrue(web.read(url, 100_000, 600).contains("past the end"))
    }

    @Test
    fun documentsAreSavedInsteadOfShownAsGarbage() {
        route("files.example/report.pdf") { FakeWeb.ok(pdf, "application/pdf") }
        route("files.example/archive.zip") { FakeWeb.ok(ByteArray(100), "application/zip") }
        val out = web.read("https://files.example/report.pdf", 0, 3000)
        assertEquals("This address is a PDF, not a web page. It was saved to Downloads/report.pdf (3 KB). Read it with the document tools using that path.", out)
        assertTrue(File(dir, "Downloads/report.pdf").readBytes().contentEquals(pdf))
        assertEquals("This address is a application/zip file, which web tools cannot read.", web.read("https://files.example/archive.zip", 0, 3000))
    }

    @Test
    fun onlyReadingSavesADocumentAndOnlyOnce() {
        route("files.example/report.pdf") { FakeWeb.ok(pdf, "application/pdf") }
        val url = "https://files.example/report.pdf"
        for (out in listOf(web.links(url, ""), web.tables(url, null), web.find(url, "x")))
            assertEquals("This address is a PDF, not a web page. Use download_file to save it, then the document tools to read it.", out)
        assertFalse(File(dir, "Downloads").exists() && File(dir, "Downloads").list()!!.isNotEmpty())
        val first = web.read(url, 0, 3000)
        val again = web.read(url, 0, 3000)
        assertEquals(first, again)
        assertEquals(listOf("report.pdf"), File(dir, "Downloads").list()!!.toList())
        // Once the user deletes the file, a new read saves it again.
        File(dir, "Downloads/report.pdf").delete()
        assertTrue(web.read(url, 0, 3000).contains("Downloads/report.pdf"))
    }

    @Test
    fun everyResultFitsTheRuntimeLimit() {
        val longTitle = "A very long headline about solar power and the grid ".repeat(6)
        val manyResults = (1..10).joinToString("") { i ->
            """<div class="result results_links web-result"><a class="result__a" href="https://site$i.example/${"p".repeat(300)}">$longTitle $i</a>
               <a class="result__snippet" href="#">${"snippet text ".repeat(40)}</a></div>"""
        }
        route("html.duckduckgo.com/html/") { FakeWeb.ok("<html><body>$manyResults</body></html>") }
        val search = web.search("solar", 10, null, null)
        assertTrue(search.contains("more results not shown"))
        assertTrue(search.endsWith("Use read_web_page with a result's address to read it."))

        val items = (1..15).joinToString("") { "<item><title>$longTitle $it - Paper</title><link>https://news.google.com/rss/articles/${"x".repeat(300)}</link><source url=\"https://paper.example\">Paper</source></item>" }
        route("news.google.com/rss/search") { FakeWeb.ok("<rss><channel>$items</channel></rss>", "application/xml") }
        val news = web.news("solar", 15)
        assertTrue(news.contains("more stories not shown"))
        assertTrue(news.endsWith("with site set to the publisher."))

        val links = (1..200).joinToString(" ") { "<a href=\"/page/$it/${"q".repeat(50)}\">Link number $it</a>" }
        val paras = (1..60).joinToString("") { "<p>Solar ${"words ".repeat(60)} paragraph $it.</p>" }
        val bigTable = "<table>" + (1..300).joinToString("") { "<tr><td>row $it</td><td>${"cell ".repeat(20)}</td></tr>" } + "</table>"
        route("big.example/") { FakeWeb.ok("<html><head><title>${"T".repeat(500)}</title></head><body>$links $paras $bigTable</body></html>") }
        val outputs = listOf(
            search, news,
            web.read("https://big.example/", 0, 6000),
            web.read("https://big.example/", 2000, 100_000),
            web.links("https://big.example/", ""),
            web.tables("https://big.example/", null),
            web.tables("https://big.example/", 1),
            web.find("https://big.example/", "solar"),
        )
        for (o in outputs) assertTrue("${o.length} chars: ${o.take(120)}", o.length <= WebService.OUTPUT_BUDGET)
        val part = web.read("https://big.example/", 0, 6000)
        val next = Regex("offset=(\\d+)\\.").find(part)!!.groupValues[1].toInt()
        assertTrue(part.substringAfter("---\n").length >= next - 10)
    }

    @Test
    fun duckDuckGoIsLeftAloneForAWhileAfterABotCheck() {
        var clock = 0L
        web = WebService(fake.client(), null, now = { clock })
        val blocked = Fixtures.text("ddg_blocked.html")
        route("html.duckduckgo.com/html/") { FakeWeb.response(202, listOf("Content-Type" to "text/html"), blocked.toByteArray()) }
        route("lite.duckduckgo.com/lite/") { FakeWeb.response(202, listOf("Content-Type" to "text/html"), blocked.toByteArray()) }
        route("en.wikipedia.org/w/api.php") { FakeWeb.ok(Fixtures.text("wiki_search.json"), "application/json") }
        web.search("first", 3, null, null)
        assertEquals(2, fake.requests.count { it.url.host.endsWith("duckduckgo.com") })
        clock += 60_000
        assertTrue(web.search("second", 3, null, null).contains("DuckDuckGo skipped"))
        assertEquals(2, fake.requests.count { it.url.host.endsWith("duckduckgo.com") })
        clock += WebService.DUCKDUCKGO_PAUSE_MS
        web.search("third", 3, null, null)
        assertEquals(4, fake.requests.count { it.url.host.endsWith("duckduckgo.com") })
    }

    @Test
    fun errorsBecomePlainSentences() {
        route("locked.example/") { FakeWeb.response(403, listOf("Content-Length" to "0"), ByteArray(0)) }
        assertTrue(web.read("https://locked.example/", 0, 3000).startsWith("The site refused access to https://locked.example/ (HTTP 403)."))
        assertTrue(web.read("http://192.168.1.1/admin", 0, 3000).startsWith("Blocked: "))
        assertTrue(web.read("http://localhost:8080/", 0, 3000).startsWith("Blocked: "))
        assertTrue(web.read("file:///etc/passwd", 0, 3000).startsWith("Blocked: "))
        assertTrue(web.read("https://missing.example/x", 0, 3000).startsWith("The page https://missing.example/x was not found (HTTP 404)."))
        assertTrue(fake.requests.none { it.url.host == "192.168.1.1" || it.url.host == "localhost" })
    }

    @Test
    fun downloadsCheckTypeAndUseTheRequestedName() {
        route("files.example/report.pdf") { FakeWeb.ok(pdf, "application/pdf") }
        route("files.example/blob") { FakeWeb.response(200, listOf("Content-Type" to "application/octet-stream", "Content-Disposition" to "attachment; filename=\"q3.pdf\""), pdf) }
        route("files.example/login") { FakeWeb.ok("<!doctype html><html><body>Sign in</body></html>") }
        route("files.example/fake.pdf") { FakeWeb.ok("<!doctype html><html>nope</html>".toByteArray(), "application/pdf") }
        assertEquals("Saved the PDF (3 KB) to Downloads/Annual report.pdf. Use that path with the document tools.", web.download("https://files.example/report.pdf", "Annual report"))
        assertEquals("Saved the PDF (3 KB) to Downloads/q3.pdf. Use that path with the document tools.", web.download("https://files.example/blob", null))
        assertTrue(web.download("https://files.example/login", null).contains("(a web page; use read_web_page)"))
        assertTrue(web.download("https://files.example/fake.pdf", null).contains("returned a web page, not a PDF"))
        assertEquals(setOf("Annual report.pdf", "q3.pdf"), File(dir, "Downloads").list()!!.toSet())
    }

    @Test
    fun linksTablesAndFindWorkThroughTheService() {
        route("exams.example.edu/2026/schedule") { FakeWeb.ok(Fixtures.text("tables.html")) }
        route("cityherald.example/a") { FakeWeb.ok(Fixtures.text("article.html")) }
        val url = "https://exams.example.edu/2026/schedule"
        val links = web.links(url, "")
        assertTrue(links.startsWith("3 links on $url:"))
        assertTrue(links.contains("- Results: https://exams.example.edu/2026/results?year=2026&sem=1"))
        val list = web.tables(url, null)
        assertTrue(list.startsWith("3 tables on $url."))
        assertTrue(list.contains("Table 1: Theory exams, December 2026 (4 rows × 3 columns)"))
        val one = web.tables(url, 2)
        assertEquals("Table 2 of 3: Fees (3 rows × 2 columns) from $url\nItem | Amount\nExam fee | ₹1,500\nLate fee | ₹500", one)
        assertTrue(web.tables(url, 9).startsWith("There is no table 9"))
        val found = web.find("https://cityherald.example/a", "payback period")
        assertTrue(found.startsWith("Found 1 passage(s) about 'payback period'"))
        assertEquals(1, fake.requests.count { it.url.host == "exams.example.edu" })
    }

    @Test
    fun newsWikipediaWeatherAndCurrency() {
        route("news.google.com/rss/search") { FakeWeb.ok(Fixtures.text("gnews.rss"), "application/xml") }
        val news = web.news("solar energy", 2)
        assertTrue(news.contains("1. Solar farm plan rejected after local objections — Example Times (exampletimes.example)\n   Tue, 10 Nov 2026 18:27:27 GMT\n"))
        assertFalse(news.contains("3. "))
        assertTrue(news.contains("redirect pages"))

        route("en.wikipedia.org/api/rest_v1/page/summary/Solar_panel") { FakeWeb.ok(Fixtures.text("wiki_summary.json"), "application/json") }
        val wiki = web.wikipedia("solar panel")
        assertTrue(wiki.startsWith("Solar panel"))
        assertTrue(wiki.endsWith("Source: https://en.wikipedia.org/wiki/Solar_panel"))
        assertTrue(web.wikipedia("Nothing like this").startsWith("Could not reach Wikipedia"))
        route("en.wikipedia.org/w/api.php") { FakeWeb.ok(Fixtures.text("wiki_search.json"), "application/json") }
        // Not found directly: search, then the best match's summary.
        assertTrue(web.wikipedia("photovoltaic panel thing").startsWith("Solar panel"))

        route("geocoding-api.open-meteo.com/v1/search") { FakeWeb.ok(Fixtures.text("geocode.json"), "application/json") }
        route("api.open-meteo.com/v1/forecast") { r ->
            assertTrue(r.url.rawQuery!!.contains("latitude=18.51957&longitude=73.85535"))
            FakeWeb.ok(Fixtures.text("forecast.json"), "application/json")
        }
        assertTrue(web.weather("Pune, India", 3).startsWith("Weather for Pune, Maharashtra, India"))

        route("api.frankfurter.dev/v1/latest") { FakeWeb.response(404, listOf("Content-Type" to "application/json"), "{\"message\":\"not found\"}".toByteArray()) }
        route("open.er-api.com/v6/latest/USD") { FakeWeb.ok(Fixtures.text("erapi.json"), "application/json") }
        val fx = web.currency("usd", "kes", 10.0)
        assertTrue(fx, fx.startsWith("10 USD = "))
        assertTrue(fx.contains("open.er-api.com"))
        assertTrue(web.currency("dollar", "INR", 1.0).contains("not a 3-letter currency code"))
    }

    @Test
    fun researchReadsRespectRobotsTxt() = runBlocking {
        route("blog.example/robots.txt") { FakeWeb.ok("User-agent: *\nDisallow: /private/\n", "text/plain") }
        route("blog.example/private/post") { FakeWeb.ok(Fixtures.text("article.html")) }
        route("blog.example/public/post") { FakeWeb.ok(Fixtures.text("article.html")) }
        route("down.example/robots.txt") { FakeWeb.response(503, listOf("Content-Length" to "0"), ByteArray(0)) }
        try {
            web.researchWeb.read("https://blog.example/private/post")
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("robots.txt"))
        }
        val page = web.researchWeb.read("https://blog.example/public/post")!!
        assertTrue(page.text.contains("18,400 homes"))
        assertFalse(web.robotsAllow(WebUrl.parse("https://down.example/a")!!))
        // A missing robots.txt (404) allows everything; robots.txt is fetched once per site.
        assertTrue(web.robotsAllow(WebUrl.parse("https://none.example/a")!!))
        assertEquals(1, fake.requests.count { it.url.host == "blog.example" && it.url.rawPath == "/robots.txt" })
        assertTrue(fake.requests.none { it.url.rawPath == "/private/post" })
    }
}
