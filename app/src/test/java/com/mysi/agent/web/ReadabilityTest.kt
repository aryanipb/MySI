package com.mysi.agent.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadabilityTest {
    private val articleUrl = "https://cityherald.example/pune/rooftop-solar-doubles"

    @Test
    fun articleKeepsTheStoryAndDropsTheChrome() {
        val a = Readability.extract(Fixtures.text("article.html"), articleUrl)
        assertEquals("Rooftop solar adoption doubles in Pune as subsidies reach households", a.title)
        assertEquals("Meera Kulkarni", a.byline)
        assertEquals("2026-10-02T08:30:00+05:30", a.published)
        assertEquals("City Herald", a.siteName)
        val t = a.text
        assertTrue(t.startsWith("Household rooftop solar installations in Pune doubled") || t.contains("\nHousehold rooftop solar installations"))
        assertTrue(t.contains("The distributor said 18,400 homes in the Pune circle"))
        assertTrue(t.contains("## Why households are switching"))
        assertTrue(t.contains("## Grid concerns remain"))
        assertTrue(t.contains("- Average system size: 3.2 kilowatts"))
        assertTrue(t.contains("“Our bill dropped from about ₹3,200 a month to under ₹600 after the panels went up,”"))
        assertTrue(t.contains("140 transformer upgrades"))
        for (noise in listOf("We use cookies", "Accept all", "Sport", "Related stories", "Metro Line 3", "Share on X", "daily newsletter", "All rights reserved", "Home ›"))
            assertFalse("should not contain '$noise'", t.contains(noise))
        assertFalse(a.needsJavaScript)
    }

    @Test
    fun listingPageKeepsEveryTeaser() {
        val a = Readability.extract(Fixtures.text("listing.html"), "https://cityherald.example/energy")
        for (h in listOf("Rooftop solar adoption doubles in Pune", "Record wind power auction in Gujarat", "Battery plant to open near Chakan",
            "Coal stocks at power plants improve", "City adds 120 public EV chargers"))
            assertTrue("missing $h", a.text.contains(h))
        assertTrue(a.text.contains("Developers bid for 2 gigawatts of capacity"))
        assertFalse(a.text.contains("Ganesh festival traffic plan"))
        assertEquals("Energy news | City Herald", a.title)
    }

    @Test
    fun cookieWallIsRemovedAndTheContentKept() {
        val a = Readability.extract(Fixtures.text("cookiewall.html"), "https://kitchen.example/onions")
        assertTrue(a.text.contains("Onions keep longest in a cool, dry, dark and well-ventilated place"))
        assertTrue(a.text.contains("frozen flat in a bag for up to eight months"))
        assertFalse(a.text.contains("847 partners"))
        assertFalse(a.text.contains("I Accept"))
        assertFalse(a.text.contains("Your privacy, your choice"))
    }

    @Test
    fun aJavaScriptShellIsFlagged() {
        val shell = """<html><head><title>App</title></head><body><div id="root"></div><noscript>You need to enable JavaScript to run this app.</noscript>
            <script src="/static/js/main.js"></script></body></html>"""
        val a = Readability.extract(shell, "https://app.example/")
        assertTrue(a.needsJavaScript)
        assertTrue(a.text.length < 50)
    }

    @Test
    fun windows1252PagesAreDecodedFromTheMetaTag() {
        val bytes = Fixtures.bytes("windows1252.html")
        val text = TextDecoding.decode(bytes, "text/html")
        assertTrue(text.contains("Café crème costs €3 — “best in town”."))
        val a = Readability.extract(text, "https://cafe.example/")
        assertEquals("Café crème – menú", a.title)
    }

    @Test
    fun charsetPrecedenceIsBomThenHeaderThenMetaThenUtf8Sniffing() {
        val utf8 = Fixtures.bytes("utf8_nometa.html")
        assertTrue(TextDecoding.decode(utf8, "text/html").contains("Naïve résumé — नमस्ते"))
        // Not valid UTF-8 and nothing declared: windows-1252.
        assertEquals("café €", TextDecoding.decode(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte(), 0x20, 0x80.toByte()), null))
        // The header wins over the meta tag.
        val latin = Fixtures.bytes("windows1252.html")
        assertTrue(TextDecoding.decode(latin, "text/html; charset=UTF-8").contains("�"))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "é".toByteArray()
        assertEquals("é", TextDecoding.decode(bom, "text/html; charset=iso-8859-1"))
        // A multi-byte character cut by the byte cap does not flip a UTF-8 page to windows-1252.
        val cut = "résumé नमस्ते".toByteArray().let { it.copyOf(it.size - 1) }
        assertTrue(TextDecoding.decode(cut, null, truncated = true).startsWith("résumé"))
    }

    @Test
    fun linksAreResolvedDeduplicatedAndFiltered() {
        val doc = HtmlParser.parse(Fixtures.text("tables.html"))
        val links = PageExtract.links(doc, WebUrl.parse("https://exams.example.edu/2026/schedule")!!)
        assertEquals(
            listOf(
                "https://exams.example.edu/2026/hall-tickets.pdf",
                "https://exams.example.edu/contact",
                "https://exams.example.edu/2026/results?year=2026&sem=1",
            ),
            links.map { it.url },
        )
        assertEquals(listOf("Hall tickets (PDF)", "Contact the exam cell", "Results"), links.map { it.text })
        assertEquals(listOf("https://exams.example.edu/2026/hall-tickets.pdf"), PageExtract.links(doc, WebUrl.parse("https://exams.example.edu/")!!, "PDF").map { it.url })
    }

    @Test
    fun tablesComeOutAsAlignedRowsWithCaptions() {
        val tables = PageExtract.tables(HtmlParser.parse(Fixtures.text("tables.html")))
        assertEquals(3, tables.size)
        val schedule = tables[0]
        assertEquals("Theory exams, December 2026", schedule.caption)
        assertEquals(
            listOf(
                listOf("Date", "Course", "Time"),
                listOf("1 Dec", "Physics | Mechanics", "09:00"),
                listOf("1 Dec", "Chemistry", "14:00"),
                listOf("3 Dec", "Mathematics (all day)", ""),
            ),
            schedule.rows,
        )
        assertTrue(schedule.compact().contains("1 Dec | Physics / Mechanics | 09:00"))
        val fees = tables[1]
        assertEquals("Fees", fees.caption)
        assertEquals(listOf("Exam fee", "₹1,500"), fees.rows[1])
        assertEquals(listOf(listOf("nested", "x"), listOf("inner", "y")), tables[2].rows)
    }

    @Test
    fun findInPageReturnsMatchingPassagesInOrder() {
        val text = Readability.fullText(HtmlParser.parse(Fixtures.text("article.html")))
        val exact = PageExtract.find(text, "transformer")
        assertEquals(2, exact.size)
        assertTrue(exact[0].contains("neighbourhood transformers") || exact[0].contains("transformer upgrades"))
        val words = PageExtract.find(text, "payback years savings")
        assertTrue(words.first().contains("payback period"))
        assertTrue(PageExtract.find(text, "zebra crossing").isEmpty())
        assertTrue(PageExtract.find(text, "   ").isEmpty())
    }

    @Test
    fun textRendererKeepsStructure() {
        val html = "<h2>Steps</h2><ol start=3><li>One</li><li>Two<ul><li>Sub</li></ul></li></ol><p>a<br>b</p><pre>  x  y\n z</pre><table><tr><td>k</td><td>v</td></tr></table>"
        val t = TextRenderer.render(listOf(HtmlParser.parse(html)))
        assertEquals("## Steps\n\n3. One\n4. Two\n  - Sub\n\na\nb\n\n  x  y\n z\n\nk | v", t)
    }
}
