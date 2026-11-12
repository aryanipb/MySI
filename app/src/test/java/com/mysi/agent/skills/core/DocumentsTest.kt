package com.mysi.agent.skills.core

import com.mysi.agent.skills.core.TestFiles.fixture
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class DocumentsTest {
    private val dir = TestFiles.tempDir()

    @Before
    fun pdf() = TestFiles.installPdfBoxAssets()

    @Test
    fun detectsByExtensionAndBySniffing() {
        assertEquals(DocType.PDF, Documents.detect(fixture("plan.pdf")))
        assertEquals(DocType.DOCX, Documents.detect(fixture("report.docx")))
        assertEquals(DocType.XLSX, Documents.detect(fixture("book.xlsx")))
        assertEquals(DocType.PPTX, Documents.detect(fixture("deck.pptx")))
        assertEquals(DocType.HTML, Documents.detect(fixture("report.html")))
        // Picked files sometimes lose their extension.
        assertEquals(DocType.PDF, Documents.detect(File(dir, "noext").apply { writeBytes(fixture("plan.pdf").readBytes()) }))
        assertEquals(DocType.XLSX, Documents.detect(File(dir, "sheet.bin").apply { writeBytes(fixture("book.xlsx").readBytes()) }))
        assertEquals(DocType.TEXT, Documents.detect(TestFiles.write(dir, "notes", "plain words")))
        assertNull(Documents.detect(File(dir, "img.dat").apply { writeBytes(byteArrayOf(1, 0, 2, 0)) }))
    }

    @Test
    fun legacyFormatsGetAHelpfulRefusal() {
        val f = File(dir, "old.doc").apply { writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0)) }
        try {
            Documents.open(f, null)
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("save it as .docx"))
        }
    }

    @Test
    fun readsPdfPageByPageWithNavigationHints() {
        Documents.open(fixture("plan.pdf"), null).use { d ->
            val p1 = Documents.read("plan.pdf", d, 1, 0)
            assertTrue(p1, p1.startsWith("plan.pdf, page 1 (page 1 of 3):"))
            assertTrue(p1, p1.contains("hiring two engineers"))
            assertTrue(p1, p1.endsWith("[Next: read_document(page=2)]"))
            assertTrue(Documents.read("plan.pdf", d, 3, 0).endsWith("[End of document]"))
            assertTrue(Documents.read("plan.pdf", d, 9, 0).contains("ask for page 1 to 3"))
            val info = Documents.info(fixture("plan.pdf"), d)
            assertTrue(info, info.contains("Type: PDF, 3 pages"))
            assertTrue(info, info.contains("Title: Annual Plan 2026"))
        }
    }

    @Test
    fun longPartsContinueAtTheOffsetGiven() {
        val para = (1..400).joinToString(" ") { "word$it." }
        val f = TestFiles.write(dir, "long.txt", para)
        Documents.open(f, null).use { d ->
            val first = Documents.read("long.txt", d, 1, 0, max = 500)
            val offset = Regex("offset=(\\d+)").find(first)!!.groupValues[1].toInt()
            assertTrue(offset in 250..500)
            val second = Documents.read("long.txt", d, 1, offset, max = 500)
            assertTrue(second, second.contains("from character $offset"))
            // The cut lands between sentences, so no word is split across calls.
            val firstWords = first.substringAfter(":\n").substringBefore("\n[").split(' ')
            val secondWords = second.substringAfter(":\n").substringBefore("\n[").split(' ')
            assertEquals("word${firstWords.size + 1}.", secondWords.first())
        }
    }

    @Test
    fun searchFindsExactPhrasesWithPages() {
        Documents.open(fixture("plan.pdf"), null).use { d ->
            val (hits, ranked) = Documents.search(d, "TRAVEL budget")
            assertFalse(ranked)
            assertEquals(1, hits.size)
            assertEquals(2, hits[0].page)
            assertTrue(hits[0].snippet, hits[0].snippet.contains("travel budget is 8300"))
        }
    }

    @Test
    fun searchFallsBackToRankedPassagesWhenNoExactMatch() {
        val f = TestFiles.write(
            dir, "policy.md",
            "# Leave\nEmployees get 24 days of paid leave each year.\n\n# Travel\nTrain tickets are reimbursed in second class.\n\n# Notice\nThe notice period is two months for managers.",
        )
        Documents.open(f, null).use { d ->
            val (hits, ranked) = Documents.search(d, "how long is the notice for a manager")
            assertTrue(ranked)
            assertTrue(hits.first().snippet, hits.first().snippet.contains("notice period is two months"))
        }
        Documents.open(f, null).use { d -> assertTrue(Documents.search(d, "zebra").first.isEmpty()) }
    }

    @Test
    fun markdownHeadingsLabelParts() {
        val text = "# Intro\n" + "Alpha beta. ".repeat(200) + "\n\n## Results\n" + "Gamma delta. ".repeat(200)
        val f = TestFiles.write(dir, "r.md", text)
        Documents.open(f, null).use { d ->
            assertEquals(DocType.MARKDOWN, d.type)
            assertEquals("Intro", d.title)
            assertTrue(d.partCount >= 2)
            assertTrue(d.part(0).label, d.part(0).label.contains("under \"Intro\""))
            assertTrue((0 until d.partCount).any { d.part(it).label.contains("under \"Results\"") })
            assertTrue((0 until d.partCount).all { d.part(it).text.length <= Parts.PART_CHARS })
        }
    }

    @Test
    fun htmlKeepsVisibleTextOnly() {
        Documents.open(fixture("report.html"), null).use { d ->
            assertEquals("Field Report", d.title)
            val text = d.part(0).text
            assertTrue(text, text.contains("Café owners reported a bitter taste."))
            assertTrue(text, text.contains("• Boil water before drinking"))
            assertTrue(text, text.contains("Testing kits | 12500"))
        }
        val r = HtmlText.parse(
            "<html><head><title>T &amp; C</title><style>p{color:red}</style><script>var a='<p>x</p>';</script></head>" +
                "<body><h2>Terms</h2><p>Price: &#8377;500&nbsp;only &mdash; <b>firm</b></p><!-- hidden --><img alt=\"chart\" src=x>" +
                "<pre>  a\n  b</pre><p onclick=\"if(a>b)go()\">after attr</p></body></html>",
        )
        assertEquals("T & C", r.title)
        assertEquals(Block("Terms", 2), r.blocks[0])
        assertEquals("Price: ₹500 only — firm", r.blocks[1].text)
        assertTrue(r.blocks.none { it.text.contains("color") || it.text.contains("var a") || it.text.contains("hidden") })
        assertTrue(r.blocks.any { it.text.contains("chart") })
        assertTrue(r.blocks.any { it.text == "  a\n  b" })
        assertTrue(r.blocks.any { it.text == "after attr" })
        // The same decoder as the web skill: entities without a semicolon, windows-1252 numeric
        // references and the full HTML entity list.
        assertEquals("Fish & chips – 5 km ♥", HtmlText.parse("<p>Fish &amp chips &#150; 5&nbsp;km &hearts;</p>").blocks.single().text)
    }

    @Test
    fun csvReadsAsRowGroupsWithTheHeaderRepeated() {
        val rows = listOf("Name,Amount") + (1..300).map { "Person $it,${it * 10}" }
        val f = TestFiles.write(dir, "pay.csv", rows.joinToString("\n"))
        Documents.open(f, null).use { d ->
            assertEquals(DocType.CSV, d.type)
            assertTrue(d.partCount > 1)
            assertTrue(d.part(0).label.startsWith("data rows 1-"))
            assertTrue(d.part(1).text.startsWith("Name,Amount\n"))
            assertTrue(d.facts.contains("Data rows: 300"))
        }
    }

    @Test
    fun textDecodingHonoursBomsAndFallsBackToWindows1252() {
        assertEquals("héllo", TextFiles.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "héllo".toByteArray()))
        assertEquals("héllo", TextFiles.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "héllo".toByteArray(Charsets.UTF_16LE)))
        assertEquals("café ‘quoted’", TextFiles.decode("café ‘quoted’".toByteArray(charset("windows-1252"))))
        assertEquals("नमस्ते", TextFiles.decode("नमस्ते".toByteArray()))
        // A UTF-8 character cut by the size cap does not flip the whole file to Windows-1252.
        val cut = "abc€".toByteArray().let { it.copyOf(it.size - 1) }
        assertEquals("abc", TextFiles.decode(cut + byteArrayOf()).take(3))
        assertTrue(TextFiles.decode("aaaa€".toByteArray().let { it.copyOf(it.size - 1) }).startsWith("aaaa"))
    }

    @Test
    fun hugeTextFilesAreCapped() {
        val f = File(dir, "big.txt")
        f.writeText("x ".repeat(600_000))
        Documents.open(f, null, ReadLimits(maxTextBytes = 100_000)).use { d ->
            assertTrue(d.warnings.first().contains("Only the first"))
            val total = (0 until d.partCount).sumOf { d.part(it).text.length }
            assertTrue(total <= 100_000)
        }
    }

    @Test
    fun scannedPagesSayTheyHaveNoText() {
        // A page with no content stream at all.
        val pdf = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n" +
            "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n"
        val f = File(dir, "scan.pdf").apply { writeText(pdf) }
        Documents.open(f, null).use { d -> assertEquals(PdfSource.NO_TEXT, d.part(0).text) }
    }

    @Test
    fun damagedPdfIsAClearError() {
        val f = File(dir, "bad.pdf").apply { writeText("%PDF-1.4\nnot really") }
        try {
            Documents.open(f, null).close()
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("damaged"))
        }
    }
}
