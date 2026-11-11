package com.mysi.agent.skills.core

import com.mysi.agent.web.Entities

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
                name == "img" && !closing -> Regex("""alt\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tag)?.let { sb.append(' ').append(Entities.decode(it.groupValues[1], inAttribute = true)).append(' ') }
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

    /** Character references, decoded by the web skill's full HTML decoder so both read pages the same way. */
    fun decodeEntities(s: String): String = Entities.decode(s)
}
