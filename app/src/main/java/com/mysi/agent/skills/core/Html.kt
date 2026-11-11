package com.mysi.agent.skills.core

/** Visible text of an HTML page: no scripts, styles or markup; headings and list items kept. */
object HtmlText {
    data class Result(val title: String?, val blocks: List<Block>)

    // <head> is not skipped wholesale: its <title> is read, and <style>/<script> inside it are skipped.
    private val skipped = setOf("script", "style", "noscript", "template", "svg", "iframe", "object", "canvas", "select")
    private val blockTags = setOf(
        "p", "div", "br", "li", "tr", "section", "article", "header", "footer", "ul", "ol", "table", "blockquote",
        "pre", "hr", "dd", "dt", "dl", "figcaption", "figure", "main", "nav", "aside", "form", "fieldset", "address",
        "h1", "h2", "h3", "h4", "h5", "h6", "caption", "summary", "details", "body", "html",
    )

    /** Picks a charset declared in a meta tag when the bytes are not UTF-8. */
    fun decode(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1).lowercase()
        val declared = Regex("""<meta[^>]+charset\s*=\s*["']?([a-z0-9_-]+)""").find(head)?.groupValues?.get(1)
        val text = TextFiles.decode(bytes)
        if (declared == null || declared == "utf-8" || declared == "utf8") return text
        val strict = runCatching {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes))
        }.isSuccess
        if (strict) return text
        return runCatching { String(bytes, java.nio.charset.Charset.forName(declared)) }.getOrDefault(text)
    }

    fun parse(html: String): Result {
        val blocks = mutableListOf<Block>()
        val sb = StringBuilder()
        var title: String? = null
        var heading = 0
        var pre = 0
        var i = 0
        val n = html.length
        fun flush() {
            val t = sb.toString().let { if (pre > 0) it.trim('\n') else it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
            if (t.isNotEmpty()) blocks += Block(t, heading)
            sb.setLength(0)
        }
        fun appendText(raw: String) {
            val decoded = decodeEntities(raw)
            if (pre > 0) sb.append(decoded) else sb.append(decoded.replace(Regex("\\s+"), " "))
        }
        while (i < n) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                appendText(html.substring(i))
                break
            }
            if (lt > i) appendText(html.substring(i, lt))
            when {
                html.startsWith("<!--", lt) -> {
                    val end = html.indexOf("-->", lt + 4)
                    i = if (end < 0) n else end + 3
                    continue
                }
                html.startsWith("<!", lt) || html.startsWith("<?", lt) -> {
                    val end = html.indexOf('>', lt)
                    i = if (end < 0) n else end + 1
                    continue
                }
            }
            val gt = tagEnd(html, lt)
            if (gt < 0) {
                appendText(html.substring(lt))
                break
            }
            val tag = html.substring(lt + 1, gt)
            val closing = tag.startsWith("/")
            val name = tag.removePrefix("/").trim().takeWhile { it.isLetterOrDigit() }.lowercase()
            i = gt + 1
            if (name.isEmpty()) {
                appendText(html.substring(lt, gt + 1))
                continue
            }
            if (!closing && name == "title" && title == null) {
                val end = html.indexOf("</title", i, ignoreCase = true)
                title = decodeEntities(html.substring(i, if (end < 0) n else end)).replace(Regex("\\s+"), " ").trim().ifEmpty { null }
                i = if (end < 0) n else tagEnd(html, end).let { if (it < 0) n else it + 1 }
                continue
            }
            if (!closing && name in skipped && !tag.trimEnd().endsWith("/")) {
                val end = html.indexOf("</$name", i, ignoreCase = true)
                i = if (end < 0) n else tagEnd(html, end).let { if (it < 0) n else it + 1 }
                continue
            }
            when {
                name in blockTags -> {
                    flush()
                    heading = if (!closing && name.length == 2 && name[0] == 'h' && name[1] in '1'..'6') name[1] - '0' else 0
                    if (name == "pre") pre = (pre + if (closing) -1 else 1).coerceAtLeast(0)
                    if (!closing && name == "li") sb.append("• ")
                }
                name == "td" || name == "th" -> if (!closing && sb.isNotEmpty()) sb.append(" | ")
                name == "img" && !closing -> Regex("""alt\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tag)?.let { sb.append(' ').append(decodeEntities(it.groupValues[1])).append(' ') }
            }
        }
        flush()
        return Result(title, blocks)
    }

    /** Index of the '>' closing the tag at [lt], skipping '>' inside quoted attribute values. */
    private fun tagEnd(html: String, lt: Int): Int {
        var q = 0.toChar()
        var j = lt + 1
        while (j < html.length) {
            val c = html[j]
            if (q != 0.toChar()) {
                if (c == q) q = 0.toChar()
            } else if (c == '"' || c == '\'') q = c
            else if (c == '>') return j
            j++
        }
        return -1
    }

    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to Char(0xA0).toString(), "copy" to "©",
        "reg" to "®", "trade" to "™", "hellip" to "…", "mdash" to "—", "ndash" to "–", "lsquo" to "‘", "rsquo" to "’",
        "ldquo" to "“", "rdquo" to "”", "bull" to "•", "middot" to "·", "deg" to "°", "euro" to "€", "pound" to "£",
        "yen" to "¥", "cent" to "¢", "rupee" to "₹", "sect" to "§", "para" to "¶", "times" to "×", "divide" to "÷",
        "laquo" to "«", "raquo" to "»", "shy" to "", "zwnj" to Char(0x200C).toString(), "zwj" to Char(0x200D).toString(), "plusmn" to "±",
        "frac12" to "½", "frac14" to "¼", "frac34" to "¾", "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓",
        "eacute" to "é", "egrave" to "è", "aacute" to "á", "agrave" to "à", "ouml" to "ö", "uuml" to "ü", "auml" to "ä",
        "ntilde" to "ñ", "ccedil" to "ç", "szlig" to "ß",
    )

    fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        return Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,15});").replace(s) { m ->
            val r = m.groupValues[1]
            when {
                r.startsWith("#x") || r.startsWith("#X") -> cp(r.substring(2).toIntOrNull(16)) ?: m.value
                r.startsWith("#") -> cp(r.substring(1).toIntOrNull()) ?: m.value
                else -> named[r] ?: named[r.lowercase()] ?: m.value
            }
        }
    }

    private fun cp(v: Int?): String? =
        if (v == null || v <= 0 || v > 0x10FFFF || v in 0xD800..0xDFFF) null else String(Character.toChars(v))
}
