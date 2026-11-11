package com.mysi.agent.web

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Turns DOM nodes into plain text a small model reads well: one paragraph per block, headings
 * as "## Heading", list items as "- item" or "1. item", table cells joined with " | ", <pre>
 * kept verbatim. Iterative, so nesting depth does not matter.
 */
object TextRenderer {
    val BLOCK =
        setOf(
            "address", "article", "aside", "blockquote", "body", "center", "details", "dialog", "dd", "div", "dl", "dt", "fieldset",
            "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hgroup", "hr", "li", "main",
            "nav", "ol", "p", "pre", "section", "summary", "table", "tbody", "thead", "tfoot", "tr", "ul", "caption", "html",
        )

    /** Never rendered: code, media, form controls and document metadata. */
    val SKIP =
        setOf(
            "script", "style", "noscript", "template", "svg", "canvas", "iframe", "object", "embed", "video", "audio", "map",
            "button", "input", "select", "option", "textarea", "head", "title", "meta", "link", "base", "math", "datalist",
        )

    private class Out {
        val sb = StringBuilder()
        var pendingBreak = 0
        var pendingSpace = false

        private fun flushBreaks() {
            if (pendingBreak > 0 && sb.isNotEmpty()) {
                while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
                val have = sb.length - sb.trimEnd('\n').length
                repeat(maxOf(0, pendingBreak - have)) { sb.append('\n') }
                pendingSpace = false
            }
            pendingBreak = 0
        }

        fun brk(level: Int) {
            pendingBreak = maxOf(pendingBreak, level)
        }

        fun inline(s: String) {
            flushBreaks()
            if (pendingSpace && sb.isNotEmpty() && sb.last() != ' ' && sb.last() != '\n') sb.append(' ')
            pendingSpace = false
            sb.append(s)
        }

        fun text(t: String, pre: Boolean) {
            if (pre) {
                flushBreaks()
                sb.append(t)
                return
            }
            if (t.isEmpty()) return
            val collapsed = Element.collapse(t)
            if (collapsed.isEmpty()) {
                pendingSpace = true
                return
            }
            val leading = t.first().isWhitespace() || t.first() == '\u00A0'
            val trailing = t.last().isWhitespace() || t.last() == '\u00A0'
            if (leading) pendingSpace = true
            inline(collapsed)
            if (trailing) pendingSpace = true
        }

        fun result(): String = sb.toString().replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    /** Renders [roots] in order, skipping anything in [removed]. */
    fun render(roots: List<Node>, removed: Set<Element> = emptySet()): String {
        val out = Out()
        // Event stack: a node to enter, or an element to exit.
        val stack = ArrayDeque<Pair<Node, Boolean>>()
        for (r in roots.reversed()) stack.addLast(r to false)
        val lists = ArrayDeque<IntArray>() // [ordered(0/1), counter]
        val rowCells = ArrayDeque<IntArray>()
        var preDepth = 0
        while (stack.isNotEmpty()) {
            val (node, exiting) = stack.removeLast()
            if (node is TextNode) {
                out.text(node.text, preDepth > 0)
                continue
            }
            val el = node as Element
            val tag = el.tag
            if (exiting) {
                when (tag) {
                    "ul", "ol", "menu" -> lists.removeLastOrNull()
                    "tr" -> rowCells.removeLastOrNull()
                    "pre" -> preDepth--
                }
                if (tag in BLOCK) out.brk(if (tag == "li" || tag == "tr" || tag == "dt" || tag == "dd") 1 else 2)
                continue
            }
            if (tag in SKIP || el in removed) continue
            if (el.has("hidden") || el.attr("aria-hidden") == "true" && tag != "body") continue
            when (tag) {
                "br" -> {
                    out.brk(1)
                    continue
                }
                "hr" -> {
                    out.brk(2)
                    continue
                }
                "img" -> continue
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    out.brk(2)
                    out.inline("#".repeat(tag[1] - '0') + " ")
                }
                "ul", "menu" -> {
                    out.brk(if (lists.isEmpty()) 2 else 1)
                    lists.addLast(intArrayOf(0, 0))
                }
                "ol" -> {
                    out.brk(if (lists.isEmpty()) 2 else 1)
                    lists.addLast(intArrayOf(1, (el.attr("start").toIntOrNull() ?: 1) - 1))
                }
                "li" -> {
                    out.brk(1)
                    val l = lists.lastOrNull()
                    val indent = "  ".repeat(maxOf(0, lists.size - 1))
                    if (l != null && l[0] == 1) {
                        l[1]++
                        out.inline("$indent${l[1]}. ")
                    } else {
                        out.inline("$indent- ")
                    }
                }
                "tr" -> {
                    out.brk(1)
                    rowCells.addLast(intArrayOf(0))
                }
                "td", "th" -> {
                    val row = rowCells.lastOrNull()
                    if (row != null) {
                        if (row[0] > 0) out.inline(" | ")
                        row[0]++
                    }
                }
                "pre" -> {
                    out.brk(2)
                    preDepth++
                }
                "dd" -> {
                    out.brk(1)
                    out.inline("  ")
                }
                else -> if (tag in BLOCK) out.brk(if (tag == "dt") 1 else 2)
            }
            stack.addLast(el to true)
            for (i in el.children.indices.reversed()) stack.addLast(el.children[i] to false)
        }
        return out.result()
    }

    fun identitySet(): MutableSet<Element> = Collections.newSetFromMap(IdentityHashMap())
}
