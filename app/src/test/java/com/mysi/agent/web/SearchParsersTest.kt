package com.mysi.agent.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchParsersTest {
    @Test
    fun duckDuckGoHtmlResultsAreUnwrappedDeduplicatedAndAdsDropped() {
        val r = SearchParsers.duckDuckGoHtml(Fixtures.text("ddg_results.html"))
        assertEquals(
            listOf(
                "https://en.wikipedia.org/wiki/Solar_cell_efficiency",
                "https://www.energy.gov/eere/solar/solar-performance-and-efficiency?utm_source=ddg",
                "https://www.nrel.gov/pv/cell-efficiency.html",
            ),
            r.map { it.url },
        )
        assertEquals("Solar-cell efficiency - Wikipedia", r[0].title)
        assertTrue(r[0].snippet.startsWith("Solar-cell efficiency is the portion of energy"))
        assertEquals("2024-03-18", r[0].date)
        assertTrue(r[2].snippet.contains("research cells & modules"))
        assertTrue(r.none { it.title.contains("Cheap Solar") })
    }

    @Test
    fun duckDuckGoLiteResultsAreParsedAndSponsoredRowsDropped() {
        val r = SearchParsers.duckDuckGoLite(Fixtures.text("ddg_lite.html"))
        assertEquals(listOf("https://mausam.imd.gov.in/monsoon", "https://en.wikipedia.org/wiki/Monsoon_of_South_Asia"), r.map { it.url })
        assertEquals("Monsoon Information - India Meteorological Department", r[0].title)
        assertTrue(r[0].snippet.startsWith("Southwest monsoon set in over Kerala"))
    }

    @Test
    fun theRealBotCheckPageIsRecognisedAsBlocked() {
        val html = Fixtures.text("ddg_blocked.html")
        assertTrue(SearchParsers.duckDuckGoBlocked(200, html))
        assertTrue(SearchParsers.duckDuckGoBlocked(202, ""))
        assertTrue(SearchParsers.duckDuckGoHtml(html).isEmpty())
        assertFalse(SearchParsers.duckDuckGoBlocked(200, Fixtures.text("ddg_results.html")))
    }

    @Test
    fun redirectUnwrapping() {
        assertEquals("https://example.com/a?b=1&c=2", SearchParsers.unwrapDuckDuckGo("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%3Fb%3D1%26c%3D2&rut=x"))
        assertEquals("https://example.com/page", SearchParsers.unwrapDuckDuckGo("/l/?uddg=https%3A%2F%2Fexample.com%2Fpage"))
        assertEquals("https://direct.example/", SearchParsers.unwrapDuckDuckGo("https://direct.example/"))
        assertNull(SearchParsers.unwrapDuckDuckGo("https://duckduckgo.com/y.js?ad_domain=x"))
        assertNull(SearchParsers.unwrapDuckDuckGo("//duckduckgo.com/l/?uddg=javascript%3Aalert(1)"))
        assertNull(SearchParsers.unwrapDuckDuckGo("https://duckduckgo.com/settings"))
    }

    @Test
    fun wikipediaSearchAndSummaryFromRealResponses() {
        val r = SearchParsers.wikipediaSearch(Fixtures.text("wiki_search.json"))
        assertEquals(3, r.size)
        assertEquals("Solar panel", r[0].title)
        assertEquals("https://en.wikipedia.org/wiki/Solar_panel", r[0].url)
        assertFalse(r[0].snippet.contains("<span"))
        assertTrue(r[0].snippet.contains("A solar panel is a device"))
        val s = SearchParsers.wikipediaSummary(Fixtures.text("wiki_summary.json"))!!
        assertEquals("Solar panel", s.title)
        assertTrue(s.extract.startsWith("A solar panel is a device that converts sunlight into electricity"))
        assertEquals("https://en.wikipedia.org/wiki/Solar_panel", s.url)
        assertFalse(s.disambiguation)
        assertNull(SearchParsers.wikipediaSummary("""{"type":"https://mediawiki.org/wiki/HyperSwitch/errors/not_found","title":"Not found."}"""))
        assertTrue(SearchParsers.wikipediaSearch("not json").isEmpty())
        assertEquals("Gandhi_(film)", SearchParsers.wikiPath("Gandhi (film)").replace("%28", "(").replace("%29", ")"))
    }

    @Test
    fun googleNewsRssIsParsed() {
        val items = Feeds.parse(Fixtures.text("gnews.rss"))
        assertEquals(3, items.size)
        assertEquals("Solar farm plan rejected after local objections", Feeds.headline(items[0]))
        assertEquals("Example Times", items[0].source)
        assertEquals("https://www.exampletimes.example", items[0].sourceUrl)
        assertEquals("Tue, 10 Nov 2026 18:27:27 GMT", items[0].date)
        assertEquals("https://news.google.com/rss/articles/CBMiAAAexampleOne?oc=5", items[0].link)
        assertEquals("Nations pledge more rooftop solar at climate talks & summit", Feeds.headline(items[1]))
        assertEquals("Battery prices fall 20% in a year — report", items[2].title)
        assertNull(items[2].source)
    }

    @Test
    fun atomFeedsAreParsed() {
        val items = Feeds.parse(Fixtures.text("feed.atom"))
        assertEquals(2, items.size)
        assertEquals("Faster builds with <em>incremental</em> caching", items[0].title)
        assertEquals("https://blog.example.org/2026/faster-builds", items[0].link)
        assertEquals("2026-11-01T10:00:00Z", items[0].date)
        assertEquals("Asha Rao", items[0].source)
        assertEquals("We cut build times by 60%.", items[0].summary)
        assertEquals("2026-10-20T08:00:00Z", items[1].date)
    }

    @Test
    fun xmlExternalEntitiesAreNeverExpanded() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss><channel><item><title>&x;safe</title><link>https://a.example/</link></item></channel></rss>"""
        val items = Feeds.parse(xxe)
        assertEquals("&x;safe", items.single().title)
    }
}
