package com.mysi.agent.web

/** A link found on a page. */
class PageLink(val text: String, val url: String)

/** A data table: optional caption and rows of cell text, already aligned for colspan/rowspan. */
class PageTable(val caption: String, val rows: List<List<String>>) {
    val columns get() = rows.maxOfOrNull { it.size } ?: 0

    /** Compact rows "a | b | c", cut to [maxChars]. */
    fun compact(maxChars: Int = 3000, maxRows: Int = Int.MAX_VALUE): String {
        val sb = StringBuilder()
        for ((i, r) in rows.withIndex()) {
            if (i >= maxRows) {
                sb.append("… ${rows.size - i} more rows\n")
                break
            }
            val line = r.joinToString(" | ") { it.replace('|', '/') }
            if (sb.length + line.length + 1 > maxChars) {
                sb.append("… ${rows.size - i} more rows (cut to fit)\n")
                break
            }
            sb.append(line).append('\n')
        }
        return sb.toString().trimEnd()
    }
}

/** Links, tables and passage search over a parsed page. Pure functions over the DOM. */
object PageExtract {
    /**
     * Every http(s) link on the page, resolved against the page address (or its <base href>),
     * de-duplicated by canonical address, in page order. [contains] filters on link text or address.
     */
    fun links(doc: Element, page: WebUrl, contains: String = ""): List<PageLink> {
        val base = doc.first("base")?.attr("href")?.takeIf { it.isNotBlank() }?.let { page.resolve(it) } ?: page
        val seen = HashMap<String, Int>()
        val out = mutableListOf<PageLink>()
        val needle = contains.trim().lowercase()
        for (a in doc.all("a")) {
            val href = a.attr("href").trim()
            if (href.isEmpty() || href.startsWith("#")) continue
            val url = base.resolve(href) ?: continue
            val text = a.text().ifEmpty { a.attr("aria-label") }.ifEmpty { a.attr("title") }
                .ifEmpty { a.all("img").firstNotNullOfOrNull { it.attr("alt").takeIf { alt -> alt.isNotBlank() } }.orEmpty() }
                .take(150)
            val key = url.canonical
            val at = seen[key]
            if (at != null) {
                // Keep the first occurrence, but take a later, better label (e.g. after an image link).
                if (out[at].text.isEmpty() && text.isNotEmpty()) out[at] = PageLink(text, out[at].url)
                continue
            }
            seen[key] = out.size
            out += PageLink(text, url.toString())
        }
        if (needle.isEmpty()) return out
        return out.filter { it.text.lowercase().contains(needle) || it.url.lowercase().contains(needle) }
    }

    /**
     * Data tables (at least two rows and two columns; layout tables are skipped). Nested tables are
     * returned separately and not mixed into their parent's cells' rows.
     */
    fun tables(doc: Element): List<PageTable> {
        val out = mutableListOf<PageTable>()
        for (t in doc.all("table")) {
            val rows = mutableListOf<List<String>>()
            // rowspan carry-over: column -> (text, rows left)
            val carry = HashMap<Int, Pair<String, Int>>()
            for (tr in t.descendants().filter { it.tag == "tr" && nearest(it, "table") === t }) {
                val row = mutableListOf<String>()
                var col = 0
                fun fillCarry() {
                    while (true) {
                        val c = carry[col] ?: break
                        row += c.first
                        if (c.second <= 1) carry.remove(col) else carry[col] = c.first to c.second - 1
                        col++
                    }
                }
                for (cell in tr.descendants().filter { (it.tag == "td" || it.tag == "th") && nearest(it, "tr") === tr }) {
                    fillCarry()
                    val text = cellText(cell)
                    val span = (cell.attr("colspan").toIntOrNull() ?: 1).coerceIn(1, 50)
                    val down = (cell.attr("rowspan").toIntOrNull() ?: 1).coerceIn(1, 500)
                    repeat(span) { k ->
                        row += if (k == 0) text else ""
                        if (down > 1) carry[col] = (if (k == 0) text else "") to down - 1
                        col++
                    }
                }
                fillCarry()
                if (row.any { it.isNotEmpty() }) rows += row
            }
            val table = PageTable(caption(t), rows)
            if (rows.size >= 2 && table.columns >= 2) out += table
        }
        return out
    }

    private fun nearest(el: Element, tag: String): Element? = el.ancestors().firstOrNull { it.tag == tag }

    private fun cellText(cell: Element): String {
        // Text of the cell itself, leaving out any nested table.
        val nested = cell.all("table")
        if (nested.isEmpty()) return cell.text()
        val removed = TextRenderer.identitySet().apply { addAll(nested) }
        return Element.collapse(TextRenderer.render(listOf(cell), removed))
    }

    private fun caption(t: Element): String {
        t.first("caption")?.text()?.takeIf { it.isNotEmpty() }?.let { return it }
        // Otherwise the nearest heading before the table among its preceding siblings (or its parent's).
        var node: Element? = t
        repeat(3) {
            val p = node?.parent ?: return ""
            val idx = p.children.indexOf(node)
            for (i in idx - 1 downTo 0) {
                val sib = p.children[i] as? Element ?: continue
                if (sib.tag.length == 2 && sib.tag[0] == 'h' && sib.tag[1].isDigit()) return sib.text()
                if (sib.tag == "table") return ""
            }
            node = p
        }
        return ""
    }

    /**
     * Passages of [text] that match [query]: the exact phrase first, otherwise passages holding
     * the most query words (BM25). Returned in page order, each trimmed around the match.
     */
    fun find(text: String, query: String, max: Int = 8, window: Int = 420): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val passages = Chunker.passages(text, 600)
        if (passages.isEmpty()) return emptyList()
        val phrase = q.lowercase()
        val exact = passages.withIndex().filter { it.value.lowercase().contains(phrase) }
        val chosen: List<IndexedValue<String>> =
            if (exact.isNotEmpty()) exact.take(max)
            else {
                val terms = Bm25.tokens(q).toSet()
                if (terms.isEmpty()) return emptyList()
                val need = if (terms.size <= 2) terms.size else (terms.size + 1) / 2
                val bm = Bm25(passages)
                passages.indices
                    .filter { i -> Bm25.tokens(passages[i]).toSet().count { it in terms } >= need }
                    .map { it to bm.score(q, it) }
                    .sortedByDescending { it.second }
                    .take(max)
                    .map { IndexedValue(it.first, passages[it.first]) }
                    .sortedBy { it.index }
            }
        return chosen.map { trimAround(it.value, phrase, window) }
    }

    private fun trimAround(p: String, phrase: String, window: Int): String {
        if (p.length <= window) return p
        val at = p.lowercase().indexOf(phrase).let { if (it < 0) 0 else it }
        var start = maxOf(0, at - window / 3)
        var end = minOf(p.length, start + window)
        start = maxOf(0, end - window)
        // Snap to word boundaries.
        while (start > 0 && !p[start - 1].isWhitespace()) start++
        while (end < p.length && end > start && !p[end - 1].isWhitespace()) end--
        return (if (start > 0) "…" else "") + p.substring(start, end).trim() + (if (end < p.length) "…" else "")
    }
}
