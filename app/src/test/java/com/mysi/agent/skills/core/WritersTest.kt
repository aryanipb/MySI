package com.mysi.agent.skills.core

import java.io.File
import java.time.LocalDateTime
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class WritersTest {
    private val dir = TestFiles.tempDir()
    private val created = LocalDateTime.of(2026, 11, 11, 9, 30)

    private fun save(name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }

    /**
     * Checks a package the way Office does: every part is well-formed namespaced XML, every part
     * has a content type, and every relationship target exists.
     */
    private fun checkPackage(f: File, required: List<String>) {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        ZipFile(f).use { z ->
            val names = z.entries().toList().map { it.name }.toSet()
            assertEquals("[Content_Types].xml", z.entries().nextElement().name)
            for (r in required) assertTrue("missing $r in $names", r in names)
            val docs = names.associateWith { n -> z.getInputStream(z.getEntry(n)).use { factory.newDocumentBuilder().parse(it) } }
            val types = docs.getValue("[Content_Types].xml").documentElement
            val overrides = types.getElementsByTagName("Override").let { l -> (0 until l.length).map { (l.item(it) as Element).getAttribute("PartName") } }
            val defaults = types.getElementsByTagName("Default").let { l -> (0 until l.length).map { (l.item(it) as Element).getAttribute("Extension") } }
            for (n in names.filter { it != "[Content_Types].xml" }) {
                assertTrue("no content type for $n", "/$n" in overrides || n.substringAfterLast('.') in defaults)
            }
            for (o in overrides) assertTrue("override for missing part $o", o.removePrefix("/") in names)
            for ((n, doc) in docs.filter { it.key.endsWith(".rels") }) {
                val base = if (n == "_rels/.rels") "" else n.replace("_rels/", "").removeSuffix(".rels")
                val rels = doc.getElementsByTagName("Relationship")
                for (i in 0 until rels.length) {
                    val target = (rels.item(i) as Element).getAttribute("Target")
                    assertTrue("$n points to missing $target", Rels.resolve(base, target) in names)
                }
            }
        }
    }

    @Test
    fun wordDocumentHasEveryRequiredPartAndReadsBack() {
        val body = """
            Dear team,
            Thanks for the **great** work & effort on Q3 <results>.

            # Highlights
            - Revenue up 12%
            - Two new hires

            ## Next steps
            1. Hire an analyst
            2. Open the Pune office

            | Item | Cost |
            |---|---|
            | Kits | 12500 |

            ---
            Regards,
            Asha
        """.trimIndent()
        val f = save("memo.docx", DocxWriter.write("Q3 Memo", body, created))
        checkPackage(f, listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/_rels/document.xml.rels", "word/styles.xml", "word/numbering.xml", "docProps/core.xml", "docProps/app.xml"))
        val d = DocxReader.read(f)
        assertEquals("Q3 Memo", d.title)
        val text = (0 until d.partCount).joinToString("\n") { d.part(it).text }
        assertTrue(text, text.contains("Q3 Memo"))
        assertTrue(text, text.contains("Dear team,\nThanks for the great work & effort on Q3 <results>."))
        assertTrue(text, text.contains("• Revenue up 12%"))
        assertTrue(text, text.contains("• Hire an analyst"))
        assertTrue(text, text.contains("Item | Cost"))
        assertTrue(text, text.contains("Kits | 12500"))
        assertFalse(text, text.contains("**"))
        assertFalse(text, text.contains("|---"))
        // Headings come back as headings, so the reader labels parts by them.
        val blocks = ZipFile(f).use { z -> DocxReader.parseBody(XmlPull(z.getInputStream(z.getEntry("word/document.xml"))), mapOf("Heading1" to 1, "Heading2" to 2, "Title" to 1), Int.MAX_VALUE, mutableListOf()) }
        assertTrue(blocks.contains(Block("Highlights", 1)))
        assertTrue(blocks.contains(Block("Next steps", 2)))
    }

    @Test
    fun wordInlineFormattingAndEscaping() {
        val runs = DocxWriter.runs("a **b** *c* `d<e>` 2*3*4 snake_case_name **open")
        assertTrue(runs, runs.contains("<w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">b</w:t>"))
        assertTrue(runs, runs.contains("<w:rPr><w:i/></w:rPr><w:t xml:space=\"preserve\">c</w:t>"))
        assertTrue(runs, runs.contains("Consolas"))
        assertTrue(runs, runs.contains("d&lt;e&gt;"))
        // Arithmetic, identifiers and unclosed markers stay as typed.
        assertTrue(runs, runs.contains("2*3*4 snake_case_name **open"))
        // Schema order inside w:rPr: rFonts before b before i.
        assertTrue(DocxWriter.runs("***`x`***").contains("<w:rPr><w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/><w:b/><w:i/></w:rPr>"))
        val withControl = DocxWriter.write("T", "bad" + Char(7) + "char", created)
        checkPackage(save("ctl.docx", withControl), listOf("word/document.xml"))
    }

    @Test
    fun numberedListsRestartForEachList() {
        val f = save("lists.docx", DocxWriter.write("", "1. a\n2. b\n\nBetween\n\n3. c\n4. d", created))
        ZipFile(f).use { z ->
            val doc = z.getInputStream(z.getEntry("word/document.xml")).readBytes().decodeToString()
            val numbering = z.getInputStream(z.getEntry("word/numbering.xml")).readBytes().decodeToString()
            assertEquals(2, Regex("<w:numId w:val=\"2\"/>").findAll(doc).count())
            assertEquals(2, Regex("<w:numId w:val=\"3\"/>").findAll(doc).count())
            assertTrue(numbering.contains("<w:num w:numId=\"3\"><w:abstractNumId w:val=\"1\"/><w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"3\"/>"))
        }
    }

    @Test
    fun workbookHasEveryRequiredPartAndReadsBack() {
        val sheets = SheetText.parse(
            """
            ## Sales: Q3/Q4
            Month,Amount,Date,Code,Phone,Paid,Total
            Jan,1200.50,2024-01-15,007,9845012345678901,true,=SUM(B2:B3)
            Feb,-950,2024-02-29 13:45,A&B <x>,"Line one
            line two",FALSE,
            ## Notes
            | Who | What |
            |-----|------|
            | Asha | _x0041_ literal |
            """.trimIndent(),
        )
        assertEquals(listOf("Sales: Q3/Q4", "Notes"), sheets.map { it.name })
        val f = save("book.xlsx", XlsxWriter.write(sheets, "Book", created))
        checkPackage(f, listOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/sharedStrings.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml", "docProps/core.xml"))
        val (zip, r) = XlsxReader.open(f)
        zip.use {
            // ":" and "/" are not allowed in sheet names.
            assertEquals(listOf("Sales Q3 Q4", "Notes"), r.sheets.map { it.name })
            val g = r.grid(r.sheets[0])
            assertEquals(listOf("Month", "Amount", "Date", "Code", "Phone", "Paid", "Total"), g.rows[0])
            // Numbers and dates are real cells (dates come back through the date style).
            assertEquals(listOf("Jan", "1200.5", "2024-01-15", "007", "9845012345678901", "TRUE", ""), g.rows[1])
            assertEquals(listOf("Feb", "-950", "2024-02-29 13:45", "A&B <x>", "Line one\nline two", "FALSE", ""), g.rows[2])
            assertEquals(listOf("Who", "What"), r.grid(r.sheets[1]).rows[0])
            assertEquals("_x0041_ literal", r.grid(r.sheets[1]).rows[1][1])
        }
        val sheetXml = ZipFile(f).use { zf -> zf.getInputStream(zf.getEntry("xl/worksheets/sheet1.xml")).readBytes().decodeToString() }
        assertTrue(sheetXml.contains("<f>SUM(B2:B3)</f>"))
        assertTrue(sheetXml.contains("state=\"frozen\""))
    }

    @Test
    fun sheetNamesFollowExcelRules() {
        assertEquals(
            listOf("Sheet1", "a b", "Data", "data (2)", "x".repeat(31), "History 1", "quoted"),
            XlsxWriter.sheetNames(listOf("", "a[b]", "Data", "data", "x".repeat(40), "History", "'quoted'")),
        )
    }

    @Test
    fun numbersKeepTextThatExcelWouldMangle() {
        assertTrue(XlsxWriter.isNumber("12.5"))
        assertTrue(XlsxWriter.isNumber("-0.25"))
        assertTrue(XlsxWriter.isNumber("123456789012345"))
        assertFalse(XlsxWriter.isNumber("1234567890123456"))
        assertFalse(XlsxWriter.isNumber("007"))
        assertFalse(XlsxWriter.isNumber("1e5"))
        assertFalse(XlsxWriter.isNumber("1,200"))
        assertFalse(XlsxWriter.isNumber("+91 98450"))
        assertEquals("45306", XlsxWriter.isoDate("2024-01-15")!!.toPlainString())
        assertEquals(null, XlsxWriter.isoDate("2024-02-30"))
        assertEquals(null, XlsxWriter.isoDate("15/01/2024"))
    }

    @Test
    fun csvRoundTripsQuotesCommasAndNewLines() {
        val rows = listOf(listOf("Name", "Note"), listOf("Asha, R", "said \"hi\""), listOf("Ravi", "two\nlines"), listOf(" padded ", ""))
        val text = Csv.write(rows)
        assertTrue(text.endsWith("\r\n"))
        assertEquals(rows, Csv.parse(text).rows)
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), Csv.parse("a\tb\n1\t2").rows)
        assertEquals(';', Csv.detect("a;b;c\n1;2;3"))
        assertEquals(listOf(listOf("x", "y")), Csv.parse("${Csv.BOM}x,y\n\n").rows)
        val capped = Csv.parse((1..100).joinToString("\n") { "$it,$it" }, maxRows = 10)
        assertEquals(10, capped.rows.size)
        assertTrue(capped.truncated)
    }

    @Test
    fun fileNamesAreSafe() {
        assertEquals("Q3 report.docx", FileNames.safe("Q3 report.docx", "x", "docx"))
        assertEquals("etc passwd.docx", FileNames.safe("../../etc/passwd", "x", "docx"))
        assertEquals("Leave letter.docx", FileNames.safe("", "Leave letter", "docx"))
        assertEquals("document.docx", FileNames.safe("///", "///", "docx"))
        assertEquals("रिपोर्ट 2026.md", FileNames.safe("रिपोर्ट 2026", "x", "md"))
        assertEquals(80 + ".csv".length, FileNames.safe("a".repeat(300), "x", "csv").length)
    }
}
