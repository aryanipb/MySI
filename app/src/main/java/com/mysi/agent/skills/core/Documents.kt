package com.mysi.agent.skills.core

import com.mysi.agent.search.Bm25Ranker
import com.mysi.agent.search.MemoryIndex
import com.mysi.agent.search.Tokens
import java.io.File
import java.io.IOException

/** Opens, describes, pages through and searches documents of every supported type. */
object Documents {
    /** Text characters per read_document call; with its header and "next" line it stays within [Budget.MAX]. */
    const val PAGE_CHARS = Budget.MAX - 500

    private val byExtension = mapOf(
        "pdf" to DocType.PDF,
        "docx" to DocType.DOCX, "docm" to DocType.DOCX, "dotx" to DocType.DOCX,
        "xlsx" to DocType.XLSX, "xlsm" to DocType.XLSX, "xltx" to DocType.XLSX,
        "pptx" to DocType.PPTX, "pptm" to DocType.PPTX, "ppsx" to DocType.PPTX, "potx" to DocType.PPTX,
        "csv" to DocType.CSV, "tsv" to DocType.TSV, "tab" to DocType.TSV,
        "md" to DocType.MARKDOWN, "markdown" to DocType.MARKDOWN,
        "txt" to DocType.TEXT, "text" to DocType.TEXT, "log" to DocType.TEXT, "json" to DocType.TEXT,
        "xml" to DocType.TEXT, "yaml" to DocType.TEXT, "yml" to DocType.TEXT, "ini" to DocType.TEXT,
        "srt" to DocType.TEXT, "vtt" to DocType.TEXT,
        "html" to DocType.HTML, "htm" to DocType.HTML, "xhtml" to DocType.HTML,
    )

    private val legacy = mapOf(
        "doc" to "Word 97-2003 (.doc)", "xls" to "Excel 97-2003 (.xls)", "ppt" to "PowerPoint 97-2003 (.ppt)",
        "odt" to "OpenDocument text", "ods" to "OpenDocument spreadsheet", "odp" to "OpenDocument presentation",
        "rtf" to "Rich Text Format", "pages" to "Apple Pages", "numbers" to "Apple Numbers", "key" to "Apple Keynote",
    )

    val supportedExtensions: Set<String> get() = byExtension.keys

    /** The type of [file] by extension, or by its first bytes when the extension is unknown. */
    fun detect(file: File): DocType? {
        byExtension[file.extension.lowercase()]?.let { return it }
        val head = ByteArray(8192)
        val n = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(-1)
        if (n <= 0) return if (n == 0) DocType.TEXT else null
        if (n >= 5 && String(head, 0, 5, Charsets.ISO_8859_1) == "%PDF-") return DocType.PDF
        if (SafeZip.isZip(file)) {
            return runCatching {
                SafeZip(file).use { z ->
                    when {
                        z.has("word/document.xml") -> DocType.DOCX
                        z.has("xl/workbook.xml") -> DocType.XLSX
                        z.has("ppt/presentation.xml") -> DocType.PPTX
                        else -> null
                    }
                }
            }.getOrNull()
        }
        // Text has no NUL bytes; nearly every binary format does.
        if ((0 until n).any { head[it].toInt() == 0 }) return null
        val start = String(head, 0, minOf(n, 512), Charsets.ISO_8859_1).trimStart().lowercase()
        return if (start.startsWith("<!doctype html") || start.startsWith("<html")) DocType.HTML else DocType.TEXT
    }

    /** Explains why [file] cannot be opened, for formats MySI knows but does not read. */
    fun unsupportedReason(file: File): String {
        val ext = file.extension.lowercase()
        legacy[ext]?.let { return "${file.name} is $it, which MySI cannot read. Ask the user to save it as .docx, .xlsx, .pptx or PDF and pick it again." }
        return "${file.name} is not a document MySI can read. Supported: PDF, Word (.docx), Excel (.xlsx), PowerPoint (.pptx), CSV, TSV, Markdown, text and HTML."
    }

    /** Opens [file]; the caller closes the result. Throws IOException with a user-facing message. */
    fun open(file: File, tempDir: File?, limits: ReadLimits = ReadLimits()): DocSource {
        if (!file.isFile) throw IOException("No such file: ${file.name}")
        val type = detect(file) ?: throw IOException(unsupportedReason(file))
        return when (type) {
            DocType.PDF -> PdfSource(file, tempDir, limits)
            DocType.DOCX -> DocxReader.read(file, limits)
            DocType.XLSX -> XlsxReader.read(file, limits)
            DocType.PPTX -> PptxReader.read(file, limits)
            DocType.CSV, DocType.TSV -> table(file, type, limits)
            DocType.MARKDOWN, DocType.TEXT -> text(file, type, limits)
            DocType.HTML -> html(file, limits)
        }
    }

    private fun capped(file: File, limits: ReadLimits): Pair<String, List<String>> {
        val (bytes, cut) = TextFiles.readCapped(file, limits.maxTextBytes)
        val warn = if (cut) listOf("Only the first ${limits.maxTextBytes / (1024 * 1024)} MB of this file was read.") else emptyList()
        return TextFiles.decode(bytes) to warn
    }

    private fun text(file: File, type: DocType, limits: ReadLimits): ParsedDoc {
        val (s, warn) = capped(file, limits)
        val blocks = textBlocks(s, markdown = type == DocType.MARKDOWN)
        val title = if (type == DocType.MARKDOWN) blocks.firstOrNull { it.heading == 1 }?.text else null
        return ParsedDoc(type, title, Parts.fromBlocks(blocks), warnings = warn)
    }

    /** Paragraphs split on blank lines; Markdown "#" lines become headings. */
    fun textBlocks(s: String, markdown: Boolean): List<Block> {
        val blocks = mutableListOf<Block>()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) blocks += Block(para.toString().trimEnd())
            para.setLength(0)
        }
        for (line in s.lineSequence()) {
            val h = if (markdown) Regex("^(#{1,6})\\s+(.+?)\\s*#*\\s*$").find(line) else null
            when {
                h != null -> {
                    flush()
                    blocks += Block(h.groupValues[2], h.groupValues[1].length)
                }
                line.isBlank() -> flush()
                else -> {
                    if (para.isNotEmpty()) para.append('\n')
                    para.append(line.trimEnd())
                }
            }
        }
        flush()
        return blocks
    }

    private fun html(file: File, limits: ReadLimits): ParsedDoc {
        val (bytes, cut) = TextFiles.readCapped(file, limits.maxTextBytes)
        val r = HtmlText.parse(HtmlText.decode(bytes))
        val warn = if (cut) listOf("Only the first ${limits.maxTextBytes / (1024 * 1024)} MB of this page was read.") else emptyList()
        return ParsedDoc(DocType.HTML, r.title, Parts.fromBlocks(r.blocks), warnings = warn)
    }

    private fun table(file: File, type: DocType, limits: ReadLimits): ParsedDoc {
        val (s, warn) = capped(file, limits)
        val parsed = Csv.parse(s, if (type == DocType.TSV) '\t' else null, limits.maxRows, limits.maxCells, limits.maxColumns)
        val rows = parsed.rows
        val warnings = warn + if (parsed.truncated) listOf("The table is larger than MySI reads; only the first ${rows.size} rows were loaded.") else emptyList()
        if (rows.isEmpty()) return ParsedDoc(type, null, listOf(Part("table (empty)", "")), warnings = warnings)
        val header = rows.first()
        val data = rows.drop(1)
        val parts = Parts.fromRows("data", header, data, List(data.size) { it + 1 })
        return ParsedDoc(type, null, parts, listOf("Columns: ${header.joinToString(", ")}", "Data rows: ${data.size}"), warnings)
    }

    /** One-paragraph description for document_info. */
    fun info(file: File, doc: DocSource): String {
        val sb = StringBuilder()
        sb.append("File: ${file.name} (${size(file.length())})\n")
        sb.append("Type: ${doc.type.label}, ${doc.partCount} ${doc.type.partKind}${if (doc.partCount == 1) "" else "s"}\n")
        doc.title?.let { sb.append("Title: $it\n") }
        doc.facts.forEach { sb.append(it.take(600)).append('\n') }
        doc.warnings.forEach { sb.append("Note: ").append(it).append('\n') }
        if (doc.partCount > 0) sb.append("Read it with read_document(path, page=1). ")
        if (doc.type == DocType.CSV || doc.type == DocType.TSV || doc.type == DocType.XLSX)
            sb.append("For sums, filters and averages use table_info and table_query instead of reading rows.")
        return sb.toString().trim()
    }

    /**
     * Text of part [page] (1-based) from character [offset], at most [max] characters cut at a
     * sentence or line, with a line telling the model how to continue.
     */
    fun read(name: String, doc: DocSource, page: Int, offset: Int, max: Int = PAGE_CHARS): String {
        if (doc.partCount == 0) return "$name has no readable text."
        if (page < 1 || page > doc.partCount) return "$name has ${doc.partCount} ${doc.type.partKind}s; ask for page 1 to ${doc.partCount}."
        val part = doc.part(page - 1)
        val text = part.text
        val from = offset.coerceIn(0, text.length)
        val end = if (text.length - from <= max) text.length else Chunker.boundary(text, from, from + max)
        val sb = StringBuilder()
        sb.append("$name, ${part.label} (${doc.type.partKind} $page of ${doc.partCount})")
        if (from > 0) sb.append(", from character $from")
        sb.append(":\n").append(text.substring(from, end).trim()).append('\n')
        when {
            end < text.length -> sb.append("[More of this ${doc.type.partKind}: read_document(page=$page, offset=$end)]")
            page < doc.partCount -> sb.append("[Next: read_document(page=${page + 1})]")
            else -> sb.append("[End of document]")
        }
        return sb.toString()
    }

    data class Hit(val location: String, val page: Int, val snippet: String)

    /**
     * Exact (case-insensitive) matches of [query] first; when there are none, the best BM25
     * passages for its words. Searches at most [maxParts] parts of a long document.
     */
    fun search(doc: DocSource, query: String, maxHits: Int = 6, maxParts: Int = 400): Pair<List<Hit>, Boolean> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList<Hit>() to false
        val parts = (0 until minOf(doc.partCount, maxParts)).map { doc.part(it) }
        val needle = Tokens.fold(q)
        val exact = mutableListOf<Hit>()
        parts.forEachIndexed { i, p ->
            val hay = Tokens.fold(p.text)
            // Folding can change lengths for some scripts; only trust offsets when it did not.
            if (hay.length != p.text.length) {
                if (hay.contains(needle) && exact.size < maxHits) exact += Hit(p.label, i + 1, snippetAround(p.text, 0, p.text.length.coerceAtMost(SNIPPET * 2)))
                return@forEachIndexed
            }
            var at = hay.indexOf(needle)
            while (at >= 0 && exact.size < maxHits) {
                exact += Hit(p.label, i + 1, snippetAround(p.text, at, at + needle.length))
                at = hay.indexOf(needle, at + needle.length + SNIPPET)
            }
        }
        if (exact.isNotEmpty()) return exact to false
        val index = MemoryIndex()
        val passages = mutableListOf<Pair<Int, String>>()
        parts.forEachIndexed { i, p ->
            for (c in Chunker.split(p.text, 500, 80)) {
                index.add(passages.size.toLong(), c)
                passages += i to c
            }
        }
        val ranked = Bm25Ranker(index).rank(q, maxHits).filter { it.score > 0 }
        return ranked.map { s ->
            val (i, c) = passages[s.id.toInt()]
            Hit(parts[i].label, i + 1, c.replace(Regex("\\s+"), " ").take(SNIPPET * 2))
        } to true
    }

    private const val SNIPPET = 160

    private fun snippetAround(text: String, start: Int, end: Int): String {
        var a = (start - SNIPPET).coerceAtLeast(0)
        var b = (end + SNIPPET).coerceAtMost(text.length)
        while (a > 0 && !text[a - 1].isWhitespace() && start - a < SNIPPET + 30) a--
        while (b < text.length && !text[b].isWhitespace() && b - end < SNIPPET + 30) b++
        return (if (a > 0) "…" else "") + text.substring(a, b).replace(Regex("\\s+"), " ").trim() + (if (b < text.length) "…" else "")
    }

    fun size(bytes: Long): String =
        when {
            bytes >= 1024 * 1024 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024))
            bytes >= 1024 -> "${bytes / 1024} KB"
            else -> "$bytes bytes"
        }
}
