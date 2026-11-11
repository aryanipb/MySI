package com.mysi.agent.skills.core

import com.mysi.agent.skills.core.TestFiles.fixture
import java.io.File
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OoxmlReadersTest {
    private val dir = TestFiles.tempDir()
    private fun all(d: DocSource) = (0 until d.partCount).joinToString("\n") { d.part(it).text }

    // ---------- Word ----------

    @Test
    fun readsWordFromAnOfficeSuite() {
        val d = DocxReader.read(fixture("report.docx"))
        val text = all(d)
        assertTrue(text, text.contains("The survey covered 42 villages near Chennai"))
        assertTrue(text, text.contains("Café owners reported a bitter taste."))
        assertTrue(text, text.contains("Item | Cost"))
        assertTrue(text, text.contains("Testing kits | 12500"))
        assertTrue(text, text.contains("Boil water before drinking"))
        assertTrue(d.part(0).label, d.part(0).label.startsWith("part 1 (under \"Field Report\")"))
    }

    private val wNs = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\""

    @Test
    fun wordSkipsFallbacksDeletionsAndFieldCodes() {
        val doc = """<?xml version="1.0" encoding="UTF-8"?><w:document $wNs><w:body>
            <w:p><w:pPr><w:pStyle w:val="berschrift1"/></w:pPr><w:r><w:t>Einleitung</w:t></w:r></w:p>
            <w:p><w:r><w:t xml:space="preserve">Kept </w:t></w:r><w:del><w:r><w:delText>deleted words</w:delText></w:r></w:del>
              <w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText> HYPERLINK "https://x" </w:instrText></w:r>
              <w:r><w:t>text</w:t><w:tab/><w:t>tabbed</w:t><w:br/><w:t>next line</w:t></w:r></w:p>
            <w:p><w:r><mc:AlternateContent><mc:Choice Requires="wps"><w:drawing><w:txbxContent><w:p><w:r><w:t>Box text</w:t></w:r></w:p></w:txbxContent></w:drawing></mc:Choice>
              <mc:Fallback><w:pict><w:txbxContent><w:p><w:r><w:t>Box text</w:t></w:r></w:p></w:txbxContent></w:pict></mc:Fallback></mc:AlternateContent></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>first item</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>A1</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B1</w:t></w:r></w:p><w:p><w:r><w:t>more</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            </w:body></w:document>"""
        val styles = """<?xml version="1.0"?><w:styles $wNs><w:style w:type="paragraph" w:styleId="berschrift1"><w:name w:val="heading 1"/></w:style></w:styles>"""
        val f = TestFiles.zip(
            File(dir, "traps.docx"),
            listOf(
                "[Content_Types].xml" to "<Types/>",
                "_rels/.rels" to """<Relationships><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="/word/document.xml"/></Relationships>""",
                "word/document.xml" to doc,
                "word/_rels/document.xml.rels" to """<Relationships><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""",
                "word/styles.xml" to styles,
                "docProps/core.xml" to """<cp:coreProperties xmlns:cp="x" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Trap doc</dc:title></cp:coreProperties>""",
            ),
        )
        val d = DocxReader.read(f)
        val text = all(d)
        assertEquals("Trap doc", d.title)
        assertEquals(1, Regex("Box text").findAll(text).count())
        assertFalse(text, text.contains("deleted words"))
        assertFalse(text, text.contains("HYPERLINK"))
        assertTrue(text, text.contains("Kept text\ttabbed\nnext line"))
        assertTrue(text, text.contains("• first item"))
        assertTrue(text, text.contains("A1 | B1 more"))
        assertTrue(d.part(0).label, d.part(0).label.contains("under \"Einleitung\""))
    }

    // ---------- Excel ----------

    @Test
    fun readsExcelFromAnOfficeSuiteWithDatesBooleansAndCachedFormulas() {
        val (zip, r) = XlsxReader.open(fixture("book.xlsx"))
        zip.use {
            assertEquals(listOf("Sales", "Notes & Misc"), r.sheets.map { it.name })
            val g = r.grid(r.sheets[0])
            assertEquals(listOf("Region", "Date", "Units", "Price", "Paid"), g.rows[0])
            assertEquals(listOf("North", "2024-01-15", "10", "2.5", "TRUE"), g.rows[1])
            assertEquals("2024-02-29", g.rows[2][1])
            assertEquals("FALSE", g.rows[2][4])
            // Cached formula results; 0.1 + 0.2 shows as Excel shows it, not 0.30000000000000004.
            assertEquals(listOf("Total", "", "20", "0.3", ""), g.rows[4])
            val g2 = r.grid(r.sheets[1])
            assertEquals("Ünïcode ✓", g2.rows[0][1])
            assertEquals("far <cell>", g2.rows[1][2])
            assertEquals(listOf(1, 2), g2.rowNumbers)
        }
        val d = XlsxReader.read(fixture("book.xlsx"))
        assertEquals("sheet \"Sales\" rows 1-5", d.part(0).label)
        assertTrue(d.facts.first().contains("Sales, Notes & Misc"))
    }

    private val sNs = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""

    private fun syntheticWorkbook(date1904: Boolean): File {
        val workbook = """<workbook $sNs><workbookPr date1904="${if (date1904) 1 else 0}"/><sheets>
            <sheet name="Data" sheetId="1" r:id="rId7"/><sheet name="Secret" sheetId="2" state="hidden" r:id="rId8"/></sheets></workbook>"""
        val rels = """<Relationships>
            <Relationship Id="rId7" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="/xl/worksheets/data.xml"/>
            <Relationship Id="rId8" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/other.xml"/>
            <Relationship Id="rId9" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="strings.xml"/>
            <Relationship Id="rId10" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""
        val strings = """<sst $sNs><si><t>Name</t></si><si><r><t>Ka</t></r><r><t>na</t></r><rPh sb="0" eb="1"><t>KANA</t></rPh></si><si><t>line_x000A_break</t></si></sst>"""
        val styles = """<styleSheet $sNs><numFmts><numFmt numFmtId="164" formatCode="dd/mm/yyyy;@"/><numFmt numFmtId="165" formatCode="[Red]0.00"/><numFmt numFmtId="166" formatCode="[h]:mm"/><numFmt numFmtId="167" formatCode="&quot;TRUE&quot;;&quot;TRUE&quot;;&quot;FALSE&quot;"/></numFmts>
            <cellXfs><xf numFmtId="0"/><xf numFmtId="14"/><xf numFmtId="164"/><xf numFmtId="165"/><xf numFmtId="166"/><xf numFmtId="22"/><xf numFmtId="167"/></cellXfs></styleSheet>"""
        val sheet = """<worksheet $sNs><sheetData>
            <row r="1"><c t="s"><v>0</v></c><c t="inlineStr"><is><t>When</t></is></c><c t="str"><v>Note</v></c></row>
            <row r="3"><c r="A3" t="s"><v>1</v></c><c r="B3" s="1"><v>45306</v></c><c r="C3" t="s"><v>2</v></c><c r="D3" s="3"><v>12.5</v></c></row>
            <row><c r="A4" t="b"><v>1</v></c><c r="B4" s="2"><v>45306.5</v></c><c r="C4" t="e"><v>#DIV/0!</v></c><c r="D4" s="4"><v>0.75</v></c><c r="F4" t="d"><v>2024-03-05T00:00:00Z</v></c></row>
            <row r="5"><c r="A5"><f>1/3</f><v>0.33333333333333331</v></c><c r="B5" s="5"><v>60</v></c><c r="C5" s="6"><v>0</v></c></row>
            </sheetData></worksheet>"""
        return TestFiles.zip(
            File(dir, if (date1904) "w1904.xlsx" else "w.xlsx"),
            listOf(
                "_rels/.rels" to """<Relationships><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
                "xl/workbook.xml" to workbook,
                "xl/_rels/workbook.xml.rels" to rels,
                "xl/strings.xml" to strings,
                "xl/styles.xml" to styles,
                "xl/worksheets/data.xml" to sheet,
                "xl/worksheets/other.xml" to "<worksheet $sNs><sheetData/></worksheet>",
            ),
        )
    }

    @Test
    fun excelHandlesTheSpecCornerCases() {
        val (zip, r) = XlsxReader.open(syntheticWorkbook(date1904 = false))
        zip.use {
            assertEquals(listOf("Data", "Secret"), r.sheets.map { it.name })
            assertTrue(r.sheets[1].hidden)
            val g = r.grid(r.sheets[0])
            assertEquals(listOf(1, 3, 4, 5), g.rowNumbers)
            // Cells without r follow the previous cell; shared strings skip phonetic runs and decode _xHHHH_.
            assertEquals(listOf("Name", "When", "Note", "", "", ""), g.rows[0])
            assertEquals(listOf("Kana", "2024-01-15", "line\nbreak", "12.5", "", ""), g.rows[1])
            assertEquals(listOf("TRUE", "2024-01-15 12:00", "#DIV/0!", "18:00", "", "2024-03-05"), g.rows[2])
            // A plain number keeps 15 significant digits; serial 60 is Excel's fictitious 29 Feb 1900.
            assertEquals(listOf("0.333333333333333", "1900-02-29 00:00", "FALSE", "", "", ""), g.rows[3])
        }
        val (zip2, r2) = XlsxReader.open(syntheticWorkbook(date1904 = true))
        zip2.use { assertEquals("2028-01-16", r2.grid(r2.sheets[0]).rows[1][1]) }
    }

    @Test
    fun excelDateFormatClassification() {
        assertEquals(XlsxReader.FormatKind.DATE, XlsxReader.formatKind("dd/mm/yyyy"))
        assertEquals(XlsxReader.FormatKind.DATE, XlsxReader.formatKind("mmm-yy"))
        assertEquals(XlsxReader.FormatKind.TIME, XlsxReader.formatKind("h:mm AM/PM"))
        assertEquals(XlsxReader.FormatKind.TIME, XlsxReader.formatKind("[h]:mm:ss"))
        assertEquals(XlsxReader.FormatKind.DATETIME, XlsxReader.formatKind("yyyy-mm-dd hh:mm"))
        assertEquals(XlsxReader.FormatKind.NONE, XlsxReader.formatKind("[Red]#,##0.00"))
        assertEquals(XlsxReader.FormatKind.NONE, XlsxReader.formatKind("\"days\" 0"))
        assertEquals(XlsxReader.FormatKind.NONE, XlsxReader.formatKind("General"))
        assertEquals(XlsxReader.FormatKind.BOOLEAN, XlsxReader.formatKind("\"TRUE\";\"TRUE\";\"FALSE\""))
        assertEquals(XlsxReader.FormatKind.DATE, XlsxReader.builtInKind(14))
        assertEquals(XlsxReader.FormatKind.TIME, XlsxReader.builtInKind(20))
        assertEquals(XlsxReader.FormatKind.NONE, XlsxReader.builtInKind(4))
        assertEquals("1900-01-01", XlsxReader.serialToText(BigDecimal.ONE, XlsxReader.FormatKind.DATE, false))
        assertEquals("1900-03-01", XlsxReader.serialToText(BigDecimal(61), XlsxReader.FormatKind.DATE, false))
        assertEquals("1904-01-02", XlsxReader.serialToText(BigDecimal.ONE, XlsxReader.FormatKind.DATE, true))
        assertEquals(54, XlsxReader.columnIndex("BC12"))
        assertEquals("BC", XlsxReader.columnName(54))
        assertEquals("XFD", XlsxReader.columnName(16383))
    }

    @Test
    fun excelSheetBombIsStopped() {
        val f = File(dir, "bomb.xlsx")
        java.util.zip.ZipOutputStream(f.outputStream()).use { z ->
            fun put(n: String, s: String) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
            put("xl/workbook.xml", "<workbook $sNs><sheets><sheet name=\"S\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", "<Relationships><Relationship Id=\"rId1\" Type=\"x/worksheet\" Target=\"worksheets/s.xml\"/></Relationships>")
            z.putNextEntry(java.util.zip.ZipEntry("xl/worksheets/s.xml"))
            z.write("<worksheet><sheetData><row><c><v>1</v></c></row>".toByteArray())
            val spaces = ByteArray(1024 * 1024) { ' '.code.toByte() }
            repeat(40) { z.write(spaces) }
            z.closeEntry()
        }
        try {
            XlsxReader.read(f)
            fail("bomb was read")
        } catch (e: LimitException) {
            assertTrue(e.message!!, e.message!!.contains("zip bomb"))
        }
    }

    @Test
    fun excelCellCapTruncatesInsteadOfExhaustingMemory() {
        val rows = (1..500).joinToString("") { r -> "<row r=\"$r\">" + (0 until 10).joinToString("") { c -> "<c r=\"${XlsxReader.columnName(c)}$r\"><v>$r</v></c>" } + "</row>" }
        val f = TestFiles.zip(
            File(dir, "cells.xlsx"),
            listOf(
                "xl/workbook.xml" to "<workbook $sNs><sheets><sheet name=\"S\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
                "xl/_rels/workbook.xml.rels" to "<Relationships><Relationship Id=\"rId1\" Type=\"x/worksheet\" Target=\"worksheets/s.xml\"/></Relationships>",
                "xl/worksheets/s.xml" to "<worksheet $sNs><sheetData>$rows</sheetData></worksheet>",
            ),
        )
        val (zip, r) = XlsxReader.open(f, ReadLimits(maxCells = 1_000))
        zip.use {
            val g = r.grid(r.sheets[0])
            assertTrue(g.truncated)
            assertEquals(100, g.rows.size)
        }
    }

    // ---------- PowerPoint ----------

    @Test
    fun readsPowerPointFromAnOfficeSuiteWithNotes() {
        val d = PptxReader.read(fixture("deck.pptx"))
        assertEquals(2, d.partCount)
        assertEquals("slide 1", d.part(0).label)
        val s1 = d.part(0).text
        assertTrue(s1, s1.startsWith("Quarterly Review"))
        assertTrue(s1, s1.contains("Costs fell in the south"))
        assertTrue(s1, s1.contains("Speaker notes:\nMention the two new hires."))
        assertTrue(d.part(1).text, d.part(1).text.contains("Hire a data analyst"))
    }

    @Test
    fun powerPointOrderComesFromThePresentationNotFileNames() {
        val pNs = "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
        fun slide(text: String) = """<p:sld $pNs><p:cSld><p:spTree>
            <p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:txBody></p:sp>
            <p:sp><p:nvSpPr><p:nvPr><p:ph type="sldNum"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:fld><a:t>99</a:t></a:fld></a:p></p:txBody></p:sp>
            </p:spTree></p:cSld></p:sld>"""
        val f = TestFiles.zip(
            File(dir, "order.pptx"),
            listOf(
                "ppt/presentation.xml" to "<p:presentation $pNs><p:sldIdLst><p:sldId id=\"256\" r:id=\"rId3\"/><p:sldId id=\"257\" r:id=\"rId2\"/></p:sldIdLst></p:presentation>",
                "ppt/_rels/presentation.xml.rels" to "<Relationships><Relationship Id=\"rId2\" Type=\"x/slide\" Target=\"slides/slide1.xml\"/><Relationship Id=\"rId3\" Type=\"x/slide\" Target=\"slides/slide2.xml\"/></Relationships>",
                "ppt/slides/slide1.xml" to slide("Second"),
                "ppt/slides/slide2.xml" to slide("First"),
            ),
        )
        val d = PptxReader.read(f)
        assertEquals(listOf("First", "Second"), (0 until d.partCount).map { d.part(it).text })
    }

    @Test
    fun aDamagedOfficeFileIsAClearError() {
        val f = File(dir, "broken.docx").apply { writeBytes(byteArrayOf(0x50, 0x4B, 3, 4, 1, 2, 3)) }
        try {
            DocxReader.read(f)
            fail()
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!, e.message!!.isNotBlank())
        }
        val noBody = TestFiles.zip(File(dir, "nobody.docx"), listOf("x.xml" to "<x/>"))
        try {
            DocxReader.read(noBody)
            fail()
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("not a Word document"))
        }
    }
}
