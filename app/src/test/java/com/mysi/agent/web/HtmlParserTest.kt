package com.mysi.agent.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlParserTest {
    @Test
    fun namedNumericAndLegacyEntitiesDecode() {
        assertEquals("Tom & Jerry © 2024 — “quoted” café", Entities.decode("Tom &amp; Jerry &copy; 2024 &mdash; &ldquo;quoted&rdquo; caf&eacute;"))
        assertEquals("A€B😀C", Entities.decode("A&#128;B&#x1F600;C"))
        assertEquals("��", Entities.decode("&#0;&#xD800;"))
        assertEquals("a & b", Entities.decode("a &amp b"))
        assertEquals("&unknown; &", Entities.decode("&unknown; &"))
        // In attributes, a legacy entity followed by '=' or a letter is part of a URL query.
        assertEquals("?a=1&copy=2&lang=en", Entities.decode("?a=1&copy=2&lang=en", inAttribute = true))
        assertEquals("?a=1&b=2", Entities.decode("?a=1&amp;b=2", inAttribute = true))
    }

    @Test
    fun impliedEndTagsBuildTheTreeBrowsersBuild() {
        val doc = HtmlParser.parse("<ul><li>one<li>two</ul><p>a<p>b<div>c</div><table><tr><td>1<td>2<tr><td>3</table>")
        val lis = doc.all("li")
        assertEquals(listOf("one", "two"), lis.map { it.text() })
        assertEquals(2, doc.all("p").size)
        assertEquals("a", doc.all("p")[0].text())
        assertEquals(listOf(2, 1), doc.all("tr").map { it.all("td").size })
    }

    @Test
    fun rawTextElementsAreNotParsedAsMarkup() {
        val doc = HtmlParser.parse("<script>if (a < b) { document.write('<p>no</p>') }</script><title>A &amp; B</title><p>yes</p>")
        assertEquals(1, doc.all("p").size)
        assertEquals("A & B", doc.first("title")!!.text())
        assertTrue(doc.first("script")!!.text().contains("<p>no</p>"))
    }

    @Test
    fun attributesInEveryQuotingStyle() {
        val a = HtmlParser.parse("<a href=\"/x?a=1&amp;b=2\" data-x='y z' hidden title=plain>t</a>").first("a")!!
        assertEquals("/x?a=1&b=2", a.attr("href"))
        assertEquals("y z", a.attr("data-x"))
        assertTrue(a.has("hidden"))
        assertEquals("plain", a.attr("title"))
    }

    @Test
    fun pathologicalNestingNeitherOverflowsNorHangs() {
        val deep = "<div>".repeat(200_000) + "deep text" + "</div>".repeat(10)
        val start = System.nanoTime()
        val doc = HtmlParser.parse(deep)
        assertTrue(doc.text().contains("deep text"))
        val unclosed = "<b><i><span>".repeat(50_000) + "x"
        assertTrue(HtmlParser.parse(unclosed).text().endsWith("x"))
        assertTrue("parsing took too long", System.nanoTime() - start < 10_000_000_000L)
    }

    @Test
    fun xmlModeKeepsCdataAndSelfClosingTags() {
        val doc = HtmlParser.parseXml("<rss><item><title><![CDATA[A <b>bold</b> & title]]></title><enclosure url=\"u\"/><pubDate>Mon</pubDate></item></rss>")
        val item = doc.first("item")!!
        assertEquals("A <b>bold</b> & title", item.first("title")!!.text())
        assertEquals("Mon", item.first("pubdate")!!.text())
        assertEquals("u", item.first("enclosure")!!.attr("url"))
    }
}
