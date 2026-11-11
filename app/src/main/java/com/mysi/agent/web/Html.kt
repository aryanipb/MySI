package com.mysi.agent.web

/**
 * A small, forgiving HTML (and XML) parser that builds a DOM good enough for text extraction.
 *
 * It runs in linear time, never recurses, and caps nesting depth, so hostile pages (megabytes of
 * unclosed tags, deep nesting) cannot blow the stack or take quadratic time. It follows the
 * browser rules that matter for content: void elements, raw-text elements (script, style…),
 * implied end tags for p, li, dt/dd, table parts and options, and entity decoding.
 */
sealed class Node {
    var parent: Element? = null
        internal set
}

class TextNode(val text: String) : Node()

class Element(val tag: String, val attrs: Map<String, String> = emptyMap()) : Node() {
    val children = ArrayList<Node>()

    fun attr(name: String): String = attrs[name].orEmpty()

    fun has(name: String) = attrs.containsKey(name)

    val id get() = attr("id")

    val classes: List<String> get() = attr("class").split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }

    fun append(n: Node) {
        n.parent = this
        children.add(n)
    }

    fun remove() {
        parent?.children?.remove(this)
        parent = null
    }

    val elementChildren: List<Element> get() = children.filterIsInstance<Element>()

    /** Every element below this one, in document order (depth-first, iterative). */
    fun descendants(): Sequence<Element> =
        sequence {
            val stack = ArrayDeque<Iterator<Node>>()
            stack.addLast(children.toList().iterator())
            while (stack.isNotEmpty()) {
                val it = stack.last()
                if (!it.hasNext()) {
                    stack.removeLast()
                    continue
                }
                val n = it.next()
                if (n is Element) {
                    yield(n)
                    if (n.children.isNotEmpty()) stack.addLast(n.children.toList().iterator())
                }
            }
        }

    fun all(tag: String): List<Element> = descendants().filter { it.tag == tag }.toList()

    fun first(tag: String): Element? = descendants().firstOrNull { it.tag == tag }

    fun ancestors(): Sequence<Element> = generateSequence(parent) { it.parent }

    fun hasAncestor(tag: String) = ancestors().any { it.tag == tag }

    /** Raw concatenated text of every text node below, with whitespace collapsed. */
    fun text(): String {
        val sb = StringBuilder()
        val stack = ArrayDeque<Node>()
        stack.addLast(this)
        while (stack.isNotEmpty()) {
            when (val n = stack.removeLast()) {
                is TextNode -> sb.append(n.text)
                is Element -> {
                    if (n.tag == "br") sb.append(' ')
                    for (i in n.children.indices.reversed()) stack.addLast(n.children[i])
                }
            }
        }
        return collapse(sb)
    }

    /** Length of [text] without building it twice; used heavily by the scorer. */
    fun textLength(): Int = text().length

    override fun toString() = "<$tag${if (id.isNotEmpty()) "#$id" else ""}>"

    companion object {
        fun collapse(s: CharSequence): String {
            val sb = StringBuilder(s.length)
            var space = false
            for (c in s) {
                if (c.isWhitespace() || c == '\u00A0') {
                    space = true
                } else {
                    if (space && sb.isNotEmpty()) sb.append(' ')
                    space = false
                    sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}

object HtmlParser {
    const val MAX_DEPTH = 400

    private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr", "keygen")
    private val RAW = setOf("script", "style", "textarea", "title", "xmp", "noembed", "noframes", "noscript", "iframe", "template", "plaintext")
    private val DECODED_RAW = setOf("title", "textarea")

    /** Elements whose start tag closes an open <p>. */
    private val CLOSES_P =
        setOf(
            "address", "article", "aside", "blockquote", "details", "dialog", "div", "dl", "fieldset", "figcaption", "figure",
            "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hgroup", "hr", "main", "menu", "nav", "ol", "p",
            "pre", "section", "summary", "table", "ul",
        )

    /** Parses an HTML document. The returned root is a synthetic "#document" element. */
    fun parse(html: String): Element = Builder(html, xml = false).run()

    /** Parses XML (RSS, Atom): no implied tags, CDATA sections kept, every tag may self-close. */
    fun parseXml(xml: String): Element = Builder(xml, xml = true).run()

    private class Builder(val s: String, val xml: Boolean) {
        val root = Element("#document")
        val stack = ArrayList<Element>().apply { add(root) }
        val text = StringBuilder()
        var pos = 0

        val current get() = stack.last()

        fun flushText() {
            if (text.isEmpty()) return
            current.append(TextNode(Entities.decode(text.toString())))
            text.setLength(0)
        }

        fun run(): Element {
            val n = s.length
            while (pos < n) {
                val lt = s.indexOf('<', pos)
                if (lt < 0) {
                    text.append(s, pos, n)
                    break
                }
                text.append(s, pos, lt)
                pos = lt
                when {
                    s.startsWith("<!--", pos) -> {
                        val end = s.indexOf("-->", pos + 4)
                        pos = if (end < 0) n else end + 3
                    }
                    xml && s.startsWith("<![CDATA[", pos) -> {
                        val end = s.indexOf("]]>", pos + 9)
                        flushText()
                        current.append(TextNode(s.substring(pos + 9, if (end < 0) n else end)))
                        pos = if (end < 0) n else end + 3
                    }
                    s.startsWith("<!", pos) || s.startsWith("<?", pos) -> {
                        val end = s.indexOf('>', pos)
                        pos = if (end < 0) n else end + 1
                    }
                    s.startsWith("</", pos) -> {
                        val nameEnd = nameEnd(pos + 2)
                        val name = norm(s.substring(pos + 2, nameEnd))
                        val end = s.indexOf('>', nameEnd)
                        pos = if (end < 0) n else end + 1
                        if (name.isNotEmpty()) {
                            flushText()
                            close(name)
                        }
                    }
                    pos + 1 < n && s[pos + 1].isLetter() -> startTag()
                    else -> {
                        text.append('<')
                        pos++
                    }
                }
            }
            flushText()
            return root
        }

        /** Tag names are matched case-insensitively in both modes (RSS uses pubDate, Atom uses updated…). */
        fun norm(name: String) = name.lowercase()

        fun nameEnd(from: Int): Int {
            var i = from
            while (i < s.length) {
                val c = s[i]
                if (c.isWhitespace() || c == '>' || c == '/') break
                i++
            }
            return i
        }

        fun startTag() {
            val nameEnd = nameEnd(pos + 1)
            val name = norm(s.substring(pos + 1, nameEnd))
            var i = nameEnd
            val attrs = LinkedHashMap<String, String>()
            var selfClosing = false
            while (i < s.length) {
                while (i < s.length && s[i].isWhitespace()) i++
                if (i >= s.length) break
                val c = s[i]
                if (c == '>') {
                    i++
                    break
                }
                if (c == '/') {
                    selfClosing = s.getOrNull(i + 1) == '>'
                    i++
                    continue
                }
                val aStart = i
                while (i < s.length && !s[i].isWhitespace() && s[i] != '=' && s[i] != '>' && !(s[i] == '/' && s.getOrNull(i + 1) == '>')) i++
                val aName = s.substring(aStart, i).lowercase()
                while (i < s.length && s[i].isWhitespace()) i++
                var value = ""
                if (i < s.length && s[i] == '=') {
                    i++
                    while (i < s.length && s[i].isWhitespace()) i++
                    if (i < s.length && (s[i] == '"' || s[i] == '\'')) {
                        val q = s[i]
                        val end = s.indexOf(q, i + 1)
                        value = s.substring(i + 1, if (end < 0) s.length else end)
                        i = if (end < 0) s.length else end + 1
                    } else {
                        val vStart = i
                        while (i < s.length && !s[i].isWhitespace() && s[i] != '>') i++
                        value = s.substring(vStart, i)
                    }
                }
                if (aName.isNotEmpty() && !attrs.containsKey(aName)) attrs[aName] = Entities.decode(value, inAttribute = true)
            }
            pos = i
            flushText()
            if (!xml) impliedEnds(name)
            val el = Element(name, attrs)
            current.append(el)
            if (xml) {
                if (!selfClosing && stack.size < MAX_DEPTH) stack.add(el)
                return
            }
            if (name in VOID) return
            if (name in RAW) {
                // Raw text runs to the matching end tag, whatever it contains.
                val end = indexOfEndTag(name, pos)
                val raw = s.substring(pos, if (end < 0) s.length else end)
                if (raw.isNotEmpty()) el.append(TextNode(if (name in DECODED_RAW) Entities.decode(raw) else raw))
                pos = if (end < 0) s.length else (s.indexOf('>', end).let { if (it < 0) s.length else it + 1 })
                return
            }
            if (stack.size < MAX_DEPTH) stack.add(el)
        }

        fun indexOfEndTag(name: String, from: Int): Int {
            var i = from
            while (true) {
                i = s.indexOf("</", i)
                if (i < 0) return -1
                if (s.regionMatches(i + 2, name, 0, name.length, ignoreCase = true)) {
                    val after = s.getOrNull(i + 2 + name.length)
                    if (after == null || after == '>' || after.isWhitespace() || after == '/') return i
                }
                i += 2
            }
        }

        /** Index in the stack of the nearest open [tag], not crossing any [boundaries]. */
        fun openIndex(tag: String, boundaries: Set<String> = emptySet()): Int {
            for (k in stack.indices.reversed()) {
                val t = stack[k].tag
                if (t == tag) return k
                if (t in boundaries) return -1
            }
            return -1
        }

        fun popTo(index: Int) {
            while (stack.size > index && stack.size > 1) stack.removeAt(stack.size - 1)
        }

        fun impliedEnds(name: String) {
            when {
                name in CLOSES_P -> openIndex("p", setOf("button", "table", "td", "th", "li", "dd", "dt")).let { if (it > 0) popTo(it) }
            }
            when (name) {
                "li" -> openIndex("li", setOf("ul", "ol", "menu", "table")).let { if (it > 0) popTo(it) }
                "dt", "dd" -> {
                    val k = maxOf(openIndex("dt", setOf("dl", "table")), openIndex("dd", setOf("dl", "table")))
                    if (k > 0) popTo(k)
                }
                "tr" -> openIndex("tr", setOf("table")).let { if (it > 0) popTo(it) }
                "td", "th" -> {
                    val k = maxOf(openIndex("td", setOf("tr", "table")), openIndex("th", setOf("tr", "table")))
                    if (k > 0) popTo(k)
                }
                "thead", "tbody", "tfoot" -> {
                    val k = listOf("thead", "tbody", "tfoot").maxOf { openIndex(it, setOf("table")) }
                    if (k > 0) popTo(k)
                }
                "option" -> openIndex("option", setOf("select", "datalist")).let { if (it > 0) popTo(it) }
            }
        }

        fun close(name: String) {
            if (!xml && (name in VOID)) return
            val boundaries = if (xml) emptySet() else when (name) {
                "li" -> setOf("ul", "ol")
                "td", "th", "tr" -> setOf("table")
                "p" -> setOf("button", "table", "td", "th")
                else -> emptySet()
            }
            val k = openIndex(name, boundaries)
            if (k > 0) popTo(k)
            else if (!xml && name == "p") current.append(Element("p")) // A stray </p> makes an empty paragraph, as in browsers.
        }
    }
}
