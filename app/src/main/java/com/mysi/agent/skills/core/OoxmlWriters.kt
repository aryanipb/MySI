package com.mysi.agent.skills.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Shared package parts for the Office writers. */
private object Package {
    const val REL_DOC = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
    const val REL_CORE = "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties"
    const val REL_APP = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties"
    const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    fun rootRels(main: String) =
        HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"$REL_DOC\" Target=\"$main\"/>" +
            "<Relationship Id=\"rId2\" Type=\"$REL_CORE\" Target=\"docProps/core.xml\"/>" +
            "<Relationship Id=\"rId3\" Type=\"$REL_APP\" Target=\"docProps/app.xml\"/>" +
            "</Relationships>"

    fun core(title: String, created: LocalDateTime) =
        HEAD + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
            "xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            "<dc:title>${XmlText.escape(title)}</dc:title><dc:creator>MySI</dc:creator>" +
            "<dcterms:created xsi:type=\"dcterms:W3CDTF\">${w3c(created)}</dcterms:created>" +
            "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">${w3c(created)}</dcterms:modified>" +
            "</cp:coreProperties>"

    fun app(application: String) =
        HEAD + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" " +
            "xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\"><Application>$application</Application></Properties>"

    private fun w3c(t: LocalDateTime) = t.truncatedTo(ChronoUnit.SECONDS).toString().let { if (it.length == 16) "$it:00" else it } + "Z"

    fun zip(out: OutputStream, parts: List<Pair<String, String>>) {
        ZipOutputStream(out).use { z ->
            for ((name, xml) in parts) {
                z.putNextEntry(ZipEntry(name))
                z.write(xml.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
    }
}

/**
 * Writes a Word document from light Markdown: "#", "##", "###" headings, "- " bullets, "1. "
 * numbered items, "|" tables, "---" rules, and **bold**, *italic* and `code` inside lines.
 */
object DocxWriter {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    fun write(title: String, body: String, created: LocalDateTime = LocalDateTime.now()): ByteArray {
        val numbered = mutableListOf<Int>()
        val content = StringBuilder()
        if (title.isNotBlank()) content.append(paragraph(title.trim(), "Title"))
        val lines = body.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        var listRun = 0
        var para = mutableListOf<String>()
        fun flushPara() {
            if (para.isNotEmpty()) content.append(paragraph(para.joinToString("\n"), null))
            para = mutableListOf()
        }
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimEnd()
            val trimmed = line.trimStart()
            val heading = Regex("^(#{1,6})\\s+(.+?)\\s*#*$").find(trimmed)
            val bullet = bulletLine.find(trimmed)
            val number = Regex("^(\\d{1,4})[.)]\\s+(.*)$").find(trimmed)
            when {
                trimmed.isEmpty() -> { flushPara(); listRun = 0 }
                heading != null -> {
                    flushPara(); listRun = 0
                    val level = heading.groupValues[1].length.coerceAtMost(3)
                    content.append(paragraph(heading.groupValues[2], "Heading$level"))
                }
                trimmed.startsWith("|") -> {
                    flushPara(); listRun = 0
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                        val cells = lines[i].trim().trim('|').split('|').map { it.trim() }
                        if (!cells.all { it.matches(Regex(":?-{2,}:?")) }) rows += cells
                        i++
                    }
                    content.append(table(rows))
                    continue
                }
                Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(trimmed) -> { flushPara(); listRun = 0; content.append(rule()) }
                bullet != null -> { flushPara(); listRun = 0; content.append(listItem(bullet.groupValues[1], 1)) }
                number != null -> {
                    flushPara()
                    if (listRun == 0) {
                        numbered += number.groupValues[1].toIntOrNull() ?: 1
                        listRun = numbered.size + 1
                    }
                    content.append(listItem(number.groupValues[2], listRun))
                }
                else -> { listRun = 0; para += line.trim() }
            }
            i++
        }
        flushPara()
        val document = Package.HEAD + "<w:document xmlns:w=\"$W\" xmlns:r=\"${Package.NS_R}\"><w:body>" + content +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>" +
            "</w:body></w:document>"
        val out = ByteArrayOutputStream()
        Package.zip(
            out,
            listOf(
                "[Content_Types].xml" to contentTypes(),
                "_rels/.rels" to Package.rootRels("word/document.xml"),
                "word/document.xml" to document,
                "word/_rels/document.xml.rels" to documentRels(),
                "word/styles.xml" to styles(),
                "word/numbering.xml" to numbering(numbered),
                "docProps/core.xml" to Package.core(title.ifBlank { "Document" }, created),
                "docProps/app.xml" to Package.app("MySI"),
            ),
        )
        return out.toByteArray()
    }

    private val BULLET = Char(0x2022).toString()
    private val bulletLine = Regex("^[-*+$BULLET]\\s+(.*)$")

    private fun paragraph(text: String, style: String?): String {
        val ppr = style?.let { "<w:pPr><w:pStyle w:val=\"$it\"/></w:pPr>" } ?: ""
        return "<w:p>$ppr${runs(text)}</w:p>"
    }

    private fun listItem(text: String, numId: Int) =
        "<w:p><w:pPr><w:pStyle w:val=\"ListParagraph\"/><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"$numId\"/></w:numPr></w:pPr>${runs(text)}</w:p>"

    private fun rule() =
        "<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"auto\"/></w:pBdr></w:pPr></w:p>"

    private fun table(rows: List<List<String>>): String {
        if (rows.isEmpty()) return ""
        val cols = rows.maxOf { it.size }
        val width = 9026 / cols
        val sb = StringBuilder("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"0\" w:type=\"auto\"/></w:tblPr><w:tblGrid>")
        repeat(cols) { sb.append("<w:gridCol w:w=\"$width\"/>") }
        sb.append("</w:tblGrid>")
        rows.forEachIndexed { r, cells ->
            sb.append("<w:tr>")
            for (c in 0 until cols) {
                val text = cells.getOrElse(c) { "" }
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"$width\" w:type=\"dxa\"/></w:tcPr><w:p>")
                sb.append(runs(if (r == 0) "**$text**".takeIf { text.isNotEmpty() } ?: "" else text))
                sb.append("</w:p></w:tc>")
            }
            sb.append("</w:tr>")
        }
        // Word requires a paragraph between a table and whatever follows it.
        return sb.append("</w:tbl><w:p/>").toString()
    }

    /** Inline **bold**, *italic*, _italic_ and `code`; unmatched markers stay as typed. */
    internal fun runs(text: String): String {
        val sb = StringBuilder()
        var bold = false
        var italic = false
        var code = false
        val buf = StringBuilder()
        fun emit() {
            if (buf.isEmpty()) return
            // ECMA-376 fixes the order inside w:rPr: rFonts, then b, then i.
            val rpr = StringBuilder()
            if (code) rpr.append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/>")
            if (bold) rpr.append("<w:b/>")
            if (italic) rpr.append("<w:i/>")
            val pieces = buf.toString().split('\n')
            sb.append("<w:r>")
            if (rpr.isNotEmpty()) sb.append("<w:rPr>").append(rpr).append("</w:rPr>")
            pieces.forEachIndexed { k, piece ->
                if (k > 0) sb.append("<w:br/>")
                val tabs = piece.split('\t')
                tabs.forEachIndexed { j, t ->
                    if (j > 0) sb.append("<w:tab/>")
                    if (t.isNotEmpty()) sb.append("<w:t xml:space=\"preserve\">").append(XmlText.escape(t)).append("</w:t>")
                }
            }
            sb.append("</w:r>")
            buf.setLength(0)
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '`' && (code || text.indexOf('`', i + 1) > i) -> { emit(); code = !code; i++ }
                code -> { buf.append(c); i++ }
                text.startsWith("**", i) && (bold || text.indexOf("**", i + 2) > i + 2) -> { emit(); bold = !bold; i += 2 }
                (c == '*' || c == '_') && (italic || closes(text, i, c)) -> { emit(); italic = !italic; i++ }
                else -> { buf.append(c); i++ }
            }
        }
        emit()
        return sb.toString()
    }

    /**
     * A single * or _ opens emphasis only at the start of a word and with a closing marker later,
     * so 2*3*4 and snake_case stay as typed.
     */
    private fun closes(text: String, i: Int, c: Char): Boolean {
        if (i + 1 >= text.length || text[i + 1].isWhitespace() || text[i + 1] == c) return false
        if (i > 0 && text[i - 1].isLetterOrDigit()) return false
        val j = text.indexOf(c, i + 1)
        return j > i + 1 && !text[j - 1].isWhitespace()
    }

    private fun contentTypes() =
        Package.HEAD + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
            "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>" +
            "</Types>"

    private fun documentRels() =
        Package.HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/>" +
            "</Relationships>"

    private fun heading(id: String, name: String, size: Int, level: Int) =
        "<w:style w:type=\"paragraph\" w:styleId=\"$id\"><w:name w:val=\"$name\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:uiPriority w:val=\"9\"/><w:qFormat/>" +
            "<w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"80\"/><w:outlineLvl w:val=\"$level\"/></w:pPr>" +
            "<w:rPr><w:b/><w:color w:val=\"1F3864\"/><w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/></w:rPr></w:style>"

    private fun styles() =
        Package.HEAD + "<w:styles xmlns:w=\"$W\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Calibri\"/>" +
            "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"en-GB\"/></w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"160\" w:line=\"259\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
            "<w:pPr><w:spacing w:after=\"240\"/></w:pPr><w:rPr><w:sz w:val=\"48\"/><w:szCs w:val=\"48\"/></w:rPr></w:style>" +
            heading("Heading1", "heading 1", 32, 0) + heading("Heading2", "heading 2", 28, 1) + heading("Heading3", "heading 3", 24, 2) +
            "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/>" +
            "<w:pPr><w:spacing w:after=\"60\"/><w:ind w:left=\"720\"/><w:contextualSpacing/></w:pPr></w:style>" +
            "<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\"><w:name w:val=\"Normal Table\"/><w:semiHidden/>" +
            "<w:tblPr><w:tblInd w:w=\"0\" w:type=\"dxa\"/><w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"108\" w:type=\"dxa\"/>" +
            "<w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>" +
            "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/><w:basedOn w:val=\"TableNormal\"/>" +
            "<w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr><w:tblPr><w:tblBorders>" +
            listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") { "<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" } +
            "</w:tblBorders></w:tblPr></w:style>" +
            "</w:styles>"

    /** numId 1 is the bullet list; each numbered list gets its own numId so it restarts. */
    private fun numbering(starts: List<Int>): String {
        val sb = StringBuilder(Package.HEAD + "<w:numbering xmlns:w=\"$W\">")
        sb.append("<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"singleLevel\"/><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/>")
        sb.append("<w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"").append(BULLET).append("\"/><w:lvlJc w:val=\"left\"/>")
        sb.append("<w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr></w:lvl></w:abstractNum>")
        sb.append("<w:abstractNum w:abstractNumId=\"1\"><w:multiLevelType w:val=\"singleLevel\"/><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/>")
        sb.append("<w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1.\"/><w:lvlJc w:val=\"left\"/>")
        sb.append("<w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr></w:lvl></w:abstractNum>")
        sb.append("<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>")
        starts.forEachIndexed { k, start ->
            sb.append("<w:num w:numId=\"${k + 2}\"><w:abstractNumId w:val=\"1\"/><w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"$start\"/></w:lvlOverride></w:num>")
        }
        return sb.append("</w:numbering>").toString()
    }
}

/** Writes an Excel workbook: numbers, ISO dates, TRUE/FALSE and "=" formulas become real cells. */
object XlsxWriter {
    private const val S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private val illegal = Regex("[\\[\\]:*?/\\\\]")
    private val plainNumber = Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$")

    class Sheet(val name: String, val rows: List<List<String>>)

    /** Excel's sheet-name rules: 1-31 characters, none of []:*?/\, unique, no edge apostrophes. */
    fun sheetNames(wanted: List<String>): List<String> {
        val used = HashSet<String>()
        return wanted.mapIndexed { i, w ->
            var base = illegal.replace(w, " ").trim().trim('\'').replace(Regex("\\s+"), " ").take(31).trim()
            if (base.isEmpty() || base.equals("History", true)) base = if (base.isEmpty()) "Sheet${i + 1}" else "$base 1"
            var name = base
            var n = 2
            while (!used.add(name.lowercase())) {
                val suffix = " ($n)"
                name = base.take(31 - suffix.length).trimEnd() + suffix
                n++
            }
            name
        }
    }

    fun write(sheets: List<Sheet>, title: String = "", created: LocalDateTime = LocalDateTime.now()): ByteArray {
        require(sheets.isNotEmpty()) { "A workbook needs at least one sheet." }
        val names = sheetNames(sheets.map { it.name })
        val shared = LinkedHashMap<String, Int>()
        var sharedCount = 0
        val sheetXml = sheets.map { sheet ->
            val sb = StringBuilder(Package.HEAD + "<worksheet xmlns=\"$S\" xmlns:r=\"${Package.NS_R}\">")
            val cols = sheet.rows.maxOfOrNull { it.size } ?: 0
            if (sheet.rows.size > 1) sb.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
            else sb.append("<sheetViews><sheetView workbookViewId=\"0\"/></sheetViews>")
            sb.append("<sheetFormatPr defaultRowHeight=\"15\"/>")
            if (cols > 0) {
                sb.append("<cols>")
                for (c in 0 until cols) {
                    val longest = sheet.rows.maxOf { r -> r.getOrElse(c) { "" }.length.coerceAtMost(60) }
                    val width = (longest + 2).coerceIn(8, 62)
                    sb.append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"$width\" customWidth=\"1\"/>")
                }
                sb.append("</cols>")
            }
            sb.append("<sheetData>")
            sheet.rows.forEachIndexed { r, row ->
                sb.append("<row r=\"${r + 1}\">")
                row.forEachIndexed { c, raw ->
                    val v = raw.trim()
                    if (v.isEmpty()) return@forEachIndexed
                    val ref = XlsxReader.columnName(c) + (r + 1)
                    val header = r == 0 && sheet.rows.size > 1
                    val style = if (header) " s=\"1\"" else ""
                    val date = if (header) null else isoDate(v)
                    when {
                        !header && v.length > 1 && v.startsWith("=") ->
                            sb.append("<c r=\"$ref\"><f>").append(XmlText.escape(v.substring(1))).append("</f></c>")
                        !header && isNumber(v) -> sb.append("<c r=\"$ref\"><v>$v</v></c>")
                        date != null -> sb.append("<c r=\"$ref\" s=\"2\"><v>${XlsxReader.plain(date)}</v></c>")
                        !header && (v.equals("true", true) || v.equals("false", true)) ->
                            sb.append("<c r=\"$ref\" t=\"b\"><v>${if (v.equals("true", true)) 1 else 0}</v></c>")
                        else -> {
                            val idx = shared.getOrPut(v) { shared.size }
                            sharedCount++
                            sb.append("<c r=\"$ref\" t=\"s\"$style><v>$idx</v></c>")
                        }
                    }
                }
                sb.append("</row>")
            }
            sb.append("</sheetData><pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/></worksheet>")
            sb.toString()
        }
        val parts = mutableListOf<Pair<String, String>>()
        parts += "[Content_Types].xml" to contentTypes(sheets.size)
        parts += "_rels/.rels" to Package.rootRels("xl/workbook.xml")
        parts += "xl/workbook.xml" to workbook(names)
        parts += "xl/_rels/workbook.xml.rels" to workbookRels(sheets.size)
        parts += "xl/styles.xml" to styles()
        parts += "xl/sharedStrings.xml" to sharedStrings(shared.keys, sharedCount)
        sheetXml.forEachIndexed { i, xml -> parts += "xl/worksheets/sheet${i + 1}.xml" to xml }
        parts += "docProps/core.xml" to Package.core(title.ifBlank { names.first() }, created)
        parts += "docProps/app.xml" to Package.app("MySI")
        val out = ByteArrayOutputStream()
        Package.zip(out, parts)
        return out.toByteArray()
    }

    /** Plain decimals within Excel's 15 significant digits; leading zeros and long ids stay text. */
    fun isNumber(v: String): Boolean {
        if (!plainNumber.matches(v)) return false
        val digits = v.trimStart('-').replace(".", "").trimStart('0')
        return digits.length <= 15
    }

    /** ISO yyyy-MM-dd or yyyy-MM-dd HH:mm[:ss] as an Excel serial number, else null. */
    fun isoDate(v: String): BigDecimal? {
        if (v.length < 10 || !v[0].isDigit()) return null
        val epoch = LocalDate.of(1899, 12, 30)
        return try {
            if (v.length == 10) {
                val d = LocalDate.parse(v)
                if (d.year < 1900) null else BigDecimal(ChronoUnit.DAYS.between(epoch, d))
            } else {
                val t = LocalDateTime.parse(v.replace(' ', 'T'))
                if (t.year < 1900) return null
                val days = ChronoUnit.DAYS.between(epoch, t.toLocalDate())
                val secs = t.toLocalTime().toSecondOfDay()
                BigDecimal(days).add(BigDecimal(secs).divide(BigDecimal(86_400), 10, java.math.RoundingMode.HALF_UP)).stripTrailingZeros()
            }
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private fun contentTypes(sheets: Int) =
        Package.HEAD + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            (1..sheets).joinToString("") { "<Override PartName=\"/xl/worksheets/sheet$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>" +
            "</Types>"

    private fun workbook(names: List<String>) =
        Package.HEAD + "<workbook xmlns=\"$S\" xmlns:r=\"${Package.NS_R}\"><bookViews><workbookView/></bookViews><sheets>" +
            names.mapIndexed { i, n -> "<sheet name=\"${XmlText.escape(n)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") +
            "</sheets><calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>"

    private fun workbookRels(sheets: Int) =
        Package.HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            (1..sheets).joinToString("") { "<Relationship Id=\"rId$it\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet$it.xml\"/>" } +
            "<Relationship Id=\"rId${sheets + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
            "<Relationship Id=\"rId${sheets + 2}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>" +
            "</Relationships>"

    /** Style 0 is default, 1 the bold header, 2 a yyyy-mm-dd date. */
    private fun styles() =
        Package.HEAD + "<styleSheet xmlns=\"$S\">" +
            "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"yyyy-mm-dd\"/></numFmts>" +
            "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font></fonts>" +
            "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"3\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
            "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/></cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
            "</styleSheet>"

    private fun sharedStrings(values: Collection<String>, count: Int) =
        Package.HEAD + "<sst xmlns=\"$S\" count=\"$count\" uniqueCount=\"${values.size}\">" +
            values.joinToString("") { v ->
                val esc = XmlText.escape(escapeControl(v))
                val space = if (v != v.trim() || v.contains('\n')) " xml:space=\"preserve\"" else ""
                "<si><t$space>$esc</t></si>"
            } + "</sst>"

    /** Excel stores control characters as _xHHHH_; a literal "_x0041_" must itself be escaped. */
    private val escapeLike = Regex("_x[0-9A-Fa-f]{4}_")

    private fun escapeControl(v: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            when {
                c == '_' && escapeLike.matchesAt(v, i) -> { sb.append("_x005F_"); i++; continue }
                c < ' ' && c != '\n' && c != '\t' && c != '\r' -> sb.append("_x%04X_".format(c.code))
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}

/** File names the model asks for, made safe for every file system and share target. */
object FileNames {
    fun safe(wanted: String, fallback: String, extension: String): String {
        var n = wanted.trim().ifBlank { fallback }
        if (n.endsWith(".$extension", ignoreCase = true)) n = n.dropLast(extension.length + 1)
        n = n.replace(Regex("[^\\p{L}\\p{M}\\p{N} _().,-]"), " ").replace(Regex("\\s+"), " ").trim().trim('.', ' ')
        if (n.isEmpty()) n = fallback.replace(Regex("[^\\p{L}\\p{M}\\p{N} _-]"), "").trim().ifEmpty { "document" }
        return n.take(80).trim() + "." + extension
    }
}

/**
 * Sheets typed by the model for create_xlsx: a "## Name" line starts each sheet, then CSV rows
 * (comma, tab, semicolon or pipe) or a Markdown table.
 */
object SheetText {
    fun parse(text: String): List<XlsxWriter.Sheet> {
        val sheets = mutableListOf<Pair<String, MutableList<String>>>()
        for (line in text.replace("\r\n", "\n").split('\n')) {
            val h = Regex("^#{1,3}\\s+(.+)$").find(line.trim())
            if (h != null) sheets += h.groupValues[1].trim() to mutableListOf()
            else {
                if (sheets.isEmpty()) sheets += "Sheet1" to mutableListOf()
                sheets.last().second += line
            }
        }
        return sheets.map { (name, lines) ->
            val body = lines.joinToString("\n").trim('\n')
            val rows = if (body.lineSequence().filter { it.isNotBlank() }.all { it.trimStart().startsWith("|") }) {
                body.lineSequence().filter { it.isNotBlank() }
                    .map { l -> l.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() } }
                    .filterNot { r -> r.all { it.matches(Regex(":?-{2,}:?")) } }
                    .toList()
            } else Csv.parse(body).rows
            XlsxWriter.Sheet(name, rows)
        }.filter { it.rows.isNotEmpty() || sheets.size == 1 }
    }
}
