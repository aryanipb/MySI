package com.mysi.agent.skills.core

import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.LocalTime

/** Office package relationships (the .rels parts in each _rels folder). */
object Rels {
    data class Rel(val id: String, val type: String, val target: String)

    fun relsPathFor(part: String): String {
        val p = part.trimStart('/')
        val dir = p.substringBeforeLast('/', "")
        val name = p.substringAfterLast('/')
        return (if (dir.isEmpty()) "" else "$dir/") + "_rels/$name.rels"
    }

    /** Relationships of [part] ("" for the package itself), with targets resolved to part names. */
    fun of(zip: SafeZip, part: String): List<Rel> {
        val relsPath = if (part.isEmpty()) "_rels/.rels" else relsPathFor(part)
        val input = zip.open(relsPath) ?: return emptyList()
        val out = mutableListOf<Rel>()
        input.use {
            val x = XmlPull(it)
            while (true) {
                when (x.next()) {
                    XmlPull.Event.EOF -> break
                    XmlPull.Event.START -> if (x.name == "Relationship" && x.attr("TargetMode")?.equals("External", true) != true) {
                        val target = x.attr("Target") ?: continue
                        out += Rel(x.attr("Id") ?: "", x.attr("Type") ?: "", resolve(part, target))
                    }
                    else -> {}
                }
            }
        }
        return out
    }

    /** Resolves a relationship target against the folder of [source]. */
    fun resolve(source: String, target: String): String {
        if (target.startsWith("/")) return normalize(target.trimStart('/'))
        val dir = source.trimStart('/').substringBeforeLast('/', "")
        return normalize(if (dir.isEmpty()) target else "$dir/$target")
    }

    private fun normalize(path: String): String {
        val out = ArrayDeque<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> out.removeLastOrNull()
                else -> out.addLast(seg)
            }
        }
        return out.joinToString("/")
    }

    /** The main part named by the package relationships, e.g. word/document.xml. */
    fun mainPart(zip: SafeZip, fallback: String): String =
        of(zip, "").firstOrNull { it.type.endsWith("/officeDocument") }?.target?.takeIf { zip.has(it) } ?: fallback

    /** dc:title from docProps/core.xml, if any. */
    fun coreTitle(zip: SafeZip): String? {
        val input = zip.open("docProps/core.xml") ?: return null
        input.use {
            val x = XmlPull(it)
            var inTitle = false
            while (true) {
                when (x.next()) {
                    XmlPull.Event.EOF -> return null
                    XmlPull.Event.START -> inTitle = x.name == "title"
                    XmlPull.Event.TEXT -> if (inTitle && x.text.isNotBlank()) return x.text.trim()
                    XmlPull.Event.END -> inTitle = false
                }
            }
        }
    }
}

/** Word .docx text: paragraphs, headings, list items and table rows in reading order. */
object DocxReader {
    fun read(file: File, limits: ReadLimits = ReadLimits()): ParsedDoc =
        SafeZip(file, limits.zip).use { zip ->
            val main = Rels.mainPart(zip, "word/document.xml")
            val input = zip.open(main) ?: throw IOException("This is not a Word document (no word/document.xml).")
            val headings = headingStyles(zip, main)
            val warnings = mutableListOf<String>()
            val blocks = input.use { parseBody(XmlPull(it), headings, limits.maxChars, warnings) }
            ParsedDoc(DocType.DOCX, Rels.coreTitle(zip), Parts.fromBlocks(blocks), warnings = warnings)
        }

    /** Maps style ids to heading levels by style name or outline level, so localized styles work. */
    private fun headingStyles(zip: SafeZip, main: String): Map<String, Int> {
        val stylesPart = Rels.of(zip, main).firstOrNull { it.type.endsWith("/styles") }?.target ?: "word/styles.xml"
        val input = zip.open(stylesPart) ?: return emptyMap()
        val map = HashMap<String, Int>()
        input.use {
            val x = XmlPull(it)
            var id: String? = null
            var level = 0
            while (true) {
                when (x.next()) {
                    XmlPull.Event.EOF -> break
                    XmlPull.Event.START -> when (x.name) {
                        "style" -> { id = x.attr("styleId"); level = 0 }
                        "name" -> if (id != null) {
                            val n = x.attr("val")?.lowercase() ?: ""
                            Regex("^heading (\\d)$").find(n)?.let { m -> level = m.groupValues[1].toInt() }
                            if (n == "title") level = 1
                        }
                        "outlineLvl" -> if (id != null && level == 0) x.attr("val")?.toIntOrNull()?.let { l -> if (l in 0..8) level = l + 1 }
                    }
                    XmlPull.Event.END -> if (x.name == "style") {
                        if (id != null && level > 0) map[id!!] = level.coerceAtMost(6)
                        id = null
                    }
                    else -> {}
                }
            }
        }
        return map
    }

    private class Para {
        val text = StringBuilder()
        var heading = 0
        var listItem = false
    }

    internal fun parseBody(x: XmlPull, headings: Map<String, Int>, maxChars: Int, warnings: MutableList<String>): List<Block> {
        val blocks = mutableListOf<Block>()
        val paras = ArrayDeque<Para>()
        val rows = ArrayDeque<MutableList<String>>()
        val cells = ArrayDeque<StringBuilder>()
        var inText = false
        var chars = 0
        fun emit(text: String, heading: Int = 0) {
            if (text.isBlank()) return
            val outer = cells.lastOrNull()
            if (outer != null) {
                if (outer.isNotEmpty()) outer.append(' ')
                outer.append(text.trim())
            } else blocks += Block(text, heading)
        }
        loop@ while (true) {
            when (x.next()) {
                XmlPull.Event.EOF -> break@loop
                XmlPull.Event.START -> when (x.name) {
                    "Fallback" -> x.skipElement()
                    "p" -> paras.addLast(Para())
                    "t" -> inText = true
                    "tab" -> paras.lastOrNull()?.text?.append('\t')
                    "br", "cr" -> paras.lastOrNull()?.text?.append('\n')
                    "pStyle" -> paras.lastOrNull()?.let { p -> x.attr("val")?.let { headings[it] ?: builtInHeading(it) }?.let { p.heading = it } }
                    "outlineLvl" -> paras.lastOrNull()?.let { p -> x.attr("val")?.toIntOrNull()?.let { if (it in 0..5 && p.heading == 0) p.heading = it + 1 } }
                    "numPr" -> paras.lastOrNull()?.listItem = true
                    "tr" -> rows.addLast(mutableListOf())
                    "tc" -> cells.addLast(StringBuilder())
                }
                XmlPull.Event.TEXT -> if (inText) {
                    val p = paras.lastOrNull()
                    if (p != null) {
                        p.text.append(x.text)
                        chars += x.text.length
                        if (chars > maxChars) {
                            warnings += "Only the first ${maxChars / 1000}k characters were read."
                            break@loop
                        }
                    }
                }
                XmlPull.Event.END -> when (x.name) {
                    "t" -> inText = false
                    "p" -> paras.removeLastOrNull()?.let { p ->
                        val t = p.text.toString()
                        emit(if (p.listItem && t.isNotBlank()) "• $t" else t, p.heading)
                    }
                    "tc" -> cells.removeLastOrNull()?.let { c -> rows.lastOrNull()?.add(c.toString()) }
                    "tr" -> rows.removeLastOrNull()?.let { r -> if (r.any { it.isNotBlank() }) emit(r.joinToString(" | ")) }
                }
            }
        }
        paras.removeLastOrNull()?.let { emit(it.text.toString(), it.heading) }
        return blocks
    }

    private fun builtInHeading(styleId: String): Int? {
        val s = styleId.lowercase()
        if (s == "title") return 1
        return Regex("^heading(\\d)$").find(s)?.groupValues?.get(1)?.toInt()
    }
}

/** Excel .xlsx: sheet list, shared strings, numbers and dates as Excel shows them. */
class XlsxReader(private val zip: SafeZip, private val limits: ReadLimits) {
    data class SheetRef(val name: String, val part: String, val hidden: Boolean)

    /** A sheet as rows of strings; [rowNumbers] are Excel's 1-based row numbers. */
    data class Grid(val rows: List<List<String>>, val rowNumbers: List<Int>, val truncated: Boolean)

    private val workbook = Rels.mainPart(zip, "xl/workbook.xml")
    private val workbookRels = Rels.of(zip, workbook)
    private var date1904 = false
    val sheets: List<SheetRef> = readWorkbook()
    private val shared: List<String> by lazy { readSharedStrings() }
    private val styles: List<FormatKind> by lazy { readStyles() }

    enum class FormatKind { NONE, DATE, TIME, DATETIME, BOOLEAN }

    private fun readWorkbook(): List<SheetRef> {
        val input = zip.open(workbook) ?: throw IOException("This is not an Excel workbook (no xl/workbook.xml).")
        val byId = workbookRels.associateBy { it.id }
        val out = mutableListOf<SheetRef>()
        input.use {
            val x = XmlPull(it)
            while (true) {
                when (x.next()) {
                    XmlPull.Event.EOF -> break
                    XmlPull.Event.START -> when (x.name) {
                        "workbookPr" -> date1904 = x.attr("date1904").let { v -> v == "1" || v.equals("true", true) }
                        "sheet" -> {
                            val rel = x.attr("id")?.let { id -> byId[id] }
                            // Chart sheets and dialog sheets have no cell grid.
                            if (rel != null && rel.type.endsWith("/worksheet"))
                                out += SheetRef(x.attr("name") ?: "Sheet${out.size + 1}", rel.target, x.attr("state").let { s -> s == "hidden" || s == "veryHidden" })
                        }
                    }
                    else -> {}
                }
            }
        }
        return out
    }

    private fun readSharedStrings(): List<String> {
        val part = workbookRels.firstOrNull { it.type.endsWith("/sharedStrings") }?.target ?: "xl/sharedStrings.xml"
        val input = zip.open(part) ?: return emptyList()
        val out = ArrayList<String>()
        var chars = 0L
        input.use {
            val x = XmlPull(it)
            var sb: StringBuilder? = null
            var inT = false
            var phonetic = 0
            while (true) {
                when (x.next()) {
                    XmlPull.Event.EOF -> break
                    XmlPull.Event.START -> when (x.name) {
                        "si" -> sb = StringBuilder()
                        "t" -> inT = true
                        "rPh" -> phonetic++
                    }
                    XmlPull.Event.TEXT -> if (inT && phonetic == 0) {
                        sb?.append(x.text)
                        chars += x.text.length
                        if (chars > limits.maxChars) throw LimitException("The workbook's text is larger than MySI reads (${limits.maxChars / 1_000_000}M characters).")
                    }
                    XmlPull.Event.END -> when (x.name) {
                        "t" -> inT = false
                        "rPh" -> phonetic--
                        "si" -> {
                            out += unescape(sb?.toString() ?: "")
                            sb = null
                        }
                    }
                }
            }
        }
        return out
    }

    private fun readStyles(): List<FormatKind> {
        val part = workbookRels.firstOrNull { it.type.endsWith("/styles") }?.target ?: "xl/styles.xml"
        val input = zip.open(part) ?: return emptyList()
        val custom = HashMap<Int, String>()
        val xfs = ArrayList<Int>()
        input.use {
            val x = XmlPull(it)
            var inCellXfs = false
            while (true) {
                when (x.next()) {
                    XmlPull.Event.EOF -> break
                    XmlPull.Event.START -> when (x.name) {
                        "numFmt" -> x.attr("numFmtId")?.toIntOrNull()?.let { id -> custom[id] = x.attr("formatCode") ?: "" }
                        "cellXfs" -> inCellXfs = true
                        "xf" -> if (inCellXfs) {
                            xfs += x.attr("numFmtId")?.toIntOrNull() ?: 0
                            x.skipElement()
                        }
                    }
                    XmlPull.Event.END -> if (x.name == "cellXfs") inCellXfs = false
                    else -> {}
                }
            }
        }
        return xfs.map { id -> custom[id]?.let { formatKind(it) } ?: builtInKind(id) }
    }

    /** Reads one sheet's cells, keeping Excel's cached formula results. */
    fun grid(sheet: SheetRef): Grid {
        val input = zip.open(sheet.part) ?: throw IOException("Sheet '${sheet.name}' is missing from the workbook.")
        val rows = java.util.TreeMap<Int, MutableMap<Int, String>>()
        var cells = 0
        var truncated = false
        input.use {
            val x = XmlPull(it)
            var rowNum = 0
            var col = -1
            var type = ""
            var style = 0
            var value: StringBuilder? = null
            var inline: StringBuilder? = null
            var inV = false
            var inT = false
            var phonetic = 0
            loop@ while (true) {
                when (x.next()) {
                    XmlPull.Event.EOF -> break@loop
                    XmlPull.Event.START -> when (x.name) {
                        "row" -> {
                            rowNum = x.attr("r")?.toIntOrNull() ?: (rowNum + 1)
                            col = -1
                            if (rows.size >= limits.maxRows) { truncated = true; break@loop }
                        }
                        "c" -> {
                            col = x.attr("r")?.let { ref -> columnIndex(ref) } ?: (col + 1)
                            type = x.attr("t") ?: "n"
                            style = x.attr("s")?.toIntOrNull() ?: 0
                            value = null
                            inline = null
                        }
                        "v" -> { inV = true; value = StringBuilder() }
                        "is" -> inline = StringBuilder()
                        "t" -> inT = true
                        "rPh" -> phonetic++
                        "f", "extLst" -> x.skipElement()
                    }
                    XmlPull.Event.TEXT -> {
                        if (inV) value?.append(x.text)
                        else if (inT && phonetic == 0) inline?.append(x.text)
                    }
                    XmlPull.Event.END -> when (x.name) {
                        "v" -> inV = false
                        "t" -> inT = false
                        "rPh" -> phonetic--
                        "c" -> {
                            val text = cellText(type, style, value?.toString(), inline?.toString())
                            if (text.isNotEmpty() && col >= 0) {
                                if (col >= limits.maxColumns) truncated = true
                                else {
                                    rows.getOrPut(rowNum) { HashMap() }[col] = text
                                    if (++cells >= limits.maxCells) { truncated = true; break@loop }
                                }
                            }
                        }
                    }
                }
            }
        }
        val width = (rows.values.maxOfOrNull { r -> r.keys.maxOrNull() ?: -1 } ?: -1) + 1
        val out = rows.values.map { r -> List(width) { c -> r[c] ?: "" } }
        return Grid(out, rows.keys.toList(), truncated)
    }

    private fun cellText(type: String, style: Int, v: String?, inline: String?): String =
        when (type) {
            "s" -> v?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
            "inlineStr" -> unescape(inline ?: v ?: "")
            "str" -> unescape(v ?: "")
            "b" -> if (v?.trim() == "1") "TRUE" else if (v == null) "" else "FALSE"
            "e" -> v ?: ""
            "d" -> v?.trim()?.replace('T', ' ')?.removeSuffix("Z")?.removeSuffix(" 00:00:00") ?: ""
            else -> v?.trim()?.takeIf { it.isNotEmpty() }?.let { number(it, styles.getOrNull(style) ?: FormatKind.NONE) } ?: ""
        }

    private fun number(v: String, kind: FormatKind): String {
        val d = runCatching { BigDecimal(v) }.getOrNull() ?: return v
        if (kind == FormatKind.BOOLEAN) return if (d.signum() != 0) "TRUE" else "FALSE"
        if (kind != FormatKind.NONE) serialToText(d, kind, date1904)?.let { return it }
        return plain(d.round(MathContext(15)))
    }

    companion object {
        fun open(file: File, limits: ReadLimits = ReadLimits()) = SafeZip(file, limits.zip).let { z ->
            try {
                z to XlsxReader(z, limits)
            } catch (t: Throwable) {
                z.close()
                throw t
            }
        }

        fun read(file: File, limits: ReadLimits = ReadLimits()): ParsedDoc {
            val (zip, reader) = open(file, limits)
            zip.use {
                val parts = mutableListOf<Part>()
                val warnings = mutableListOf<String>()
                for (s in reader.sheets) {
                    val g = reader.grid(s)
                    if (g.truncated) warnings += "Sheet '${s.name}' is larger than MySI reads; only part of it was loaded."
                    val label = "sheet \"${s.name}\""
                    if (g.rows.isEmpty()) parts += Part("$label (empty)", "")
                    else {
                        // Each line starts with Excel's row number so answers can cite exact cells.
                        val numbered = g.rows.mapIndexed { i, r -> listOf("${g.rowNumbers[i]}") + r }
                        parts += Parts.fromRows(label, null, numbered, g.rowNumbers)
                    }
                }
                val facts = listOf("Sheets: " + reader.sheets.joinToString(", ") { it.name + if (it.hidden) " (hidden)" else "" })
                return ParsedDoc(DocType.XLSX, Rels.coreTitle(zip), parts, facts, warnings)
            }
        }

        /** "BC12" -> 54 (0-based column). */
        fun columnIndex(ref: String): Int? {
            var n = 0
            var letters = 0
            for (ch in ref) {
                if (ch in 'A'..'Z' || ch in 'a'..'z') {
                    n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
                    if (++letters > 3) return null
                } else break
            }
            return if (letters == 0) null else n - 1
        }

        fun columnName(index: Int): String {
            var n = index + 1
            val sb = StringBuilder()
            while (n > 0) {
                val r = (n - 1) % 26
                sb.append('A' + r)
                n = (n - 1) / 26
            }
            return sb.reverse().toString()
        }

        /** Excel escapes control characters in strings as _xHHHH_. */
        fun unescape(s: String): String =
            if (!s.contains("_x")) s
            else Regex("_x([0-9A-Fa-f]{4})_").replace(s) { m -> m.groupValues[1].toInt(16).toChar().toString() }

        fun plain(d: BigDecimal): String {
            val s = d.stripTrailingZeros()
            return if (s.signum() == 0) "0" else s.toPlainString()
        }

        /** Built-in number formats that display dates or times (ECMA-376 18.8.30, plus East Asian ids). */
        fun builtInKind(id: Int): FormatKind =
            when (id) {
                in 14..17, in 27..31, in 34..36, in 50..58 -> FormatKind.DATE
                in 18..21, 45, 46, 47, 32, 33 -> FormatKind.TIME
                22 -> FormatKind.DATETIME
                else -> FormatKind.NONE
            }

        /**
         * Classifies a custom format code by its date and time letters outside quotes and
         * brackets. LibreOffice stores TRUE/FALSE as numbers formatted "TRUE";"TRUE";"FALSE".
         */
        fun formatKind(code: String): FormatKind {
            val sections = code.split(';')
            if (sections.size >= 2 && sections.all { it.trim().matches(Regex("^\"[^\"]*\"$")) } && sections.any { it.contains("TRUE", ignoreCase = true) })
                return FormatKind.BOOLEAN
            val section = sections.first()
            val sb = StringBuilder()
            var i = 0
            while (i < section.length) {
                val c = section[i]
                when (c) {
                    '"' -> { i = section.indexOf('"', i + 1).let { if (it < 0) section.length else it } }
                    '[' -> {
                        val end = section.indexOf(']', i + 1).let { if (it < 0) section.length else it }
                        val inner = section.substring(i + 1, end).lowercase()
                        // Elapsed-time codes like [h] count as time; colours and locales do not.
                        if (inner.isNotEmpty() && inner.all { it == 'h' || it == 'm' || it == 's' }) sb.append(inner)
                        i = end
                    }
                    '\\', '_', '*' -> i++
                    else -> sb.append(c.lowercaseChar())
                }
                i++
            }
            val s = sb.toString()
            if (s.contains("general")) return FormatKind.NONE
            val hasDate = s.contains('y') || s.contains('d') || (s.contains('m') && !s.contains('h') && !s.contains('s'))
            val hasTime = s.contains('h') || s.contains('s')
            return when {
                hasDate && hasTime -> FormatKind.DATETIME
                hasDate -> FormatKind.DATE
                hasTime -> FormatKind.TIME
                else -> FormatKind.NONE
            }
        }

        /** Converts an Excel serial date to ISO text, keeping Excel's fictitious 29 Feb 1900. */
        fun serialToText(serial: BigDecimal, kind: FormatKind, date1904: Boolean): String? {
            if (serial.signum() < 0 || serial > BigDecimal(2_958_465)) return null
            val days = serial.toBigInteger().toLong()
            val seconds = serial.subtract(BigDecimal(days)).multiply(BigDecimal(86_400)).setScale(0, java.math.RoundingMode.HALF_UP).toInt()
            val time = LocalTime.ofSecondOfDay((seconds % 86_400).toLong())
            val dayCarry = if (seconds >= 86_400) 1L else 0L
            val timeText = if (time.second == 0) "%02d:%02d".format(time.hour, time.minute) else "%02d:%02d:%02d".format(time.hour, time.minute, time.second)
            if (kind == FormatKind.TIME) return timeText
            val date = when {
                date1904 -> LocalDate.of(1904, 1, 1).plusDays(days + dayCarry).toString()
                days + dayCarry == 60L -> "1900-02-29"
                days + dayCarry < 60L -> LocalDate.of(1899, 12, 31).plusDays(days + dayCarry).toString()
                else -> LocalDate.of(1899, 12, 30).plusDays(days + dayCarry).toString()
            }
            return if (kind == FormatKind.DATETIME || (kind == FormatKind.DATE && seconds % 86_400 != 0)) "$date $timeText" else date
        }
    }
}

/** PowerPoint .pptx: slide text in presentation order, with speaker notes. */
object PptxReader {
    private val skippedPlaceholders = setOf("sldNum", "sldImg", "dt", "ftr", "hdr")

    fun read(file: File, limits: ReadLimits = ReadLimits()): ParsedDoc =
        SafeZip(file, limits.zip).use { zip ->
            val pres = Rels.mainPart(zip, "ppt/presentation.xml")
            val input = zip.open(pres) ?: throw IOException("This is not a PowerPoint presentation (no ppt/presentation.xml).")
            val rels = Rels.of(zip, pres).associateBy { it.id }
            val order = mutableListOf<String>()
            input.use {
                val x = XmlPull(it)
                while (true) {
                    when (x.next()) {
                        XmlPull.Event.EOF -> break
                        XmlPull.Event.START -> if (x.name == "sldId") x.attr("id")?.let { id -> rels[id]?.target?.let { t -> order += t } }
                        else -> {}
                    }
                }
            }
            val warnings = mutableListOf<String>()
            var chars = 0
            val parts = mutableListOf<Part>()
            for ((i, slide) in order.withIndex()) {
                val text = zip.open(slide)?.use { s -> shapesText(XmlPull(s)) } ?: ""
                val notesPart = Rels.of(zip, slide).firstOrNull { it.type.endsWith("/notesSlide") }?.target
                val notes = notesPart?.let { n -> zip.open(n)?.use { s -> shapesText(XmlPull(s)) } }.orEmpty()
                val body = if (notes.isBlank()) text else "$text\n\nSpeaker notes:\n$notes"
                parts += Part("slide ${i + 1}", body.trim())
                chars += body.length
                if (chars > limits.maxChars) {
                    warnings += "Only the first ${i + 1} slides were read."
                    break
                }
            }
            ParsedDoc(DocType.PPTX, Rels.coreTitle(zip), parts, listOf("Slides: ${order.size}"), warnings)
        }

    /** Paragraph text of every shape, skipping slide numbers, dates, footers and fallbacks. */
    internal fun shapesText(x: XmlPull): String {
        val lines = mutableListOf<String>()
        var para: StringBuilder? = null
        var inT = false
        var shapeSkipped = false
        var shapeDepth = 0
        loop@ while (true) {
            when (x.next()) {
                XmlPull.Event.EOF -> break@loop
                XmlPull.Event.START -> when (x.name) {
                    "Fallback" -> x.skipElement()
                    "sp" -> { shapeDepth++; shapeSkipped = false }
                    "ph" -> if (x.attr("type") in skippedPlaceholders) shapeSkipped = true
                    "p" -> para = StringBuilder()
                    "t" -> inT = true
                    "br" -> para?.append('\n')
                    "tab" -> para?.append('\t')
                }
                XmlPull.Event.TEXT -> if (inT && !shapeSkipped) para?.append(x.text)
                XmlPull.Event.END -> when (x.name) {
                    "t" -> inT = false
                    "p" -> {
                        para?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { lines += it }
                        para = null
                    }
                    "sp" -> { shapeDepth--; shapeSkipped = false }
                }
            }
        }
        return lines.joinToString("\n")
    }
}
