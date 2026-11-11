package com.mysi.agent.skills.core

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset

class XmlException(message: String) : IOException(message)

/**
 * A small streaming XML tokenizer for Office files, identical on the JVM and Android.
 *
 * It never reads a DTD and never expands entities other than the five predefined ones and
 * numeric character references, so external entities (XXE) and entity-expansion bombs are
 * impossible by construction. Element and attribute names are matched by local name, because
 * Office producers do not always use the same namespace prefixes.
 */
class XmlPull(input: InputStream) {
    enum class Event { START, END, TEXT, EOF }

    private val reader: Reader = open(input)
    private val buf = CharArray(16 * 1024)
    private var pos = 0
    private var len = 0
    private var pendingEnd: String? = null
    private val attrs = LinkedHashMap<String, String>()

    /** Local name of the current START or END element. */
    var name: String = ""
        private set

    /** Text of the current TEXT event, entities already decoded. */
    var text: String = ""
        private set

    /** Value of the current element's attribute with this local name (prefix ignored). */
    fun attr(local: String): String? = attrs[local]

    fun next(): Event {
        pendingEnd?.let {
            pendingEnd = null
            name = it
            attrs.clear()
            return Event.END
        }
        while (true) {
            val c = peek()
            if (c < 0) return Event.EOF
            if (c != '<'.code) return readText()
            read()
            when (peek()) {
                '/'.code -> {
                    read()
                    name = localName(readName())
                    skipUntil(">")
                    attrs.clear()
                    return Event.END
                }
                '?'.code -> skipUntil("?>")
                '!'.code -> {
                    read()
                    when {
                        consume("--") -> skipUntil("-->")
                        consume("[CDATA[") -> {
                            text = readUntil("]]>")
                            return Event.TEXT
                        }
                        else -> skipDeclaration()
                    }
                }
                else -> return readStart()
            }
        }
    }

    /** Skips to the END of the element whose START was just returned. */
    fun skipElement() {
        var depth = 1
        while (depth > 0) {
            when (next()) {
                Event.START -> depth++
                Event.END -> depth--
                Event.EOF -> return
                Event.TEXT -> {}
            }
        }
    }

    private fun readStart(): Event {
        val qName = readName()
        if (qName.isEmpty()) throw XmlException("Malformed tag")
        name = localName(qName)
        attrs.clear()
        while (true) {
            skipSpace()
            when (val c = read()) {
                -1 -> throw XmlException("Unterminated tag <$qName")
                '>'.code -> return Event.START
                '/'.code -> {
                    skipSpace()
                    if (read() != '>'.code) throw XmlException("Malformed tag <$qName")
                    pendingEnd = name
                    return Event.START
                }
                else -> {
                    val attrName = c.toChar() + readName()
                    skipSpace()
                    if (read() != '='.code) throw XmlException("Attribute without value in <$qName")
                    skipSpace()
                    val quote = read()
                    if (quote != '"'.code && quote != '\''.code) throw XmlException("Unquoted attribute in <$qName")
                    val sb = StringBuilder()
                    while (true) {
                        val v = read()
                        if (v < 0) throw XmlException("Unterminated attribute in <$qName")
                        if (v == quote) break
                        if (v == '&'.code) entity(sb) else sb.append(v.toChar())
                    }
                    if (attrs.size < MAX_ATTRS) attrs[localName(attrName)] = sb.toString()
                }
            }
        }
    }

    private fun readText(): Event {
        val sb = StringBuilder()
        while (true) {
            val c = peek()
            if (c < 0 || c == '<'.code) break
            read()
            if (c == '&'.code) entity(sb) else sb.append(c.toChar())
        }
        text = sb.toString()
        return Event.TEXT
    }

    /** Decodes one reference after '&'; unknown names are kept literally and never expanded. */
    private fun entity(out: StringBuilder) {
        val ref = StringBuilder()
        while (ref.length < 12) {
            val c = peek()
            if (c < 0 || c == ';'.code || c == '<'.code || c == '&'.code || Character.isWhitespace(c)) break
            ref.append(read().toChar())
        }
        if (peek() != ';'.code) {
            out.append('&').append(ref)
            return
        }
        read()
        val r = ref.toString()
        val decoded: String? = when {
            r == "lt" -> "<"
            r == "gt" -> ">"
            r == "amp" -> "&"
            r == "quot" -> "\""
            r == "apos" -> "'"
            r.startsWith("#x") || r.startsWith("#X") -> codePoint(r.substring(2).toIntOrNull(16))
            r.startsWith("#") -> codePoint(r.substring(1).toIntOrNull())
            else -> null
        }
        if (decoded != null) out.append(decoded) else out.append('&').append(r).append(';')
    }

    private fun codePoint(cp: Int?): String =
        if (cp == null || cp < 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) REPLACEMENT else String(Character.toChars(cp))

    private fun readName(): String {
        val sb = StringBuilder()
        while (true) {
            val c = peek()
            if (c < 0 || Character.isWhitespace(c) || c == '>'.code || c == '/'.code || c == '='.code) break
            sb.append(read().toChar())
            if (sb.length > MAX_NAME) throw XmlException("Name too long")
        }
        return sb.toString()
    }

    private fun localName(q: String) = q.substringAfter(':')

    private fun skipSpace() {
        while (peek() >= 0 && Character.isWhitespace(peek())) read()
    }

    private fun consume(s: String): Boolean {
        fill(s.length)
        if (len - pos < s.length) return false
        for (i in s.indices) if (buf[pos + i] != s[i]) return false
        pos += s.length
        return true
    }

    private fun skipUntil(end: String) {
        while (true) {
            if (consume(end)) return
            if (read() < 0) return
        }
    }

    private fun readUntil(end: String): String {
        val sb = StringBuilder()
        while (true) {
            if (consume(end)) return sb.toString()
            val c = read()
            if (c < 0) return sb.toString()
            sb.append(c.toChar())
        }
    }

    /** Skips <!DOCTYPE ...> and similar, including an internal subset, without interpreting it. */
    private fun skipDeclaration() {
        var depth = 0
        var quote = -1
        while (true) {
            val c = read()
            if (c < 0) return
            when {
                quote >= 0 -> if (c == quote) quote = -1
                c == '"'.code || c == '\''.code -> quote = c
                c == '['.code -> depth++
                c == ']'.code -> depth--
                c == '>'.code && depth <= 0 -> return
            }
        }
    }

    private fun fill(need: Int) {
        if (len - pos >= need) return
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, len - pos)
            len -= pos
            pos = 0
        }
        while (len < need) {
            val n = reader.read(buf, len, buf.size - len)
            if (n < 0) return
            len += n
        }
    }

    private fun peek(): Int {
        if (pos >= len) fill(1)
        return if (pos < len) buf[pos].code else -1
    }

    private fun read(): Int {
        val c = peek()
        if (c >= 0) pos++
        return c
    }

    companion object {
        private const val MAX_NAME = 512
        private const val MAX_ATTRS = 64
        private val REPLACEMENT = Char(0xFFFD).toString()

        /** Picks the charset from a byte-order mark or the XML declaration; UTF-8 otherwise. */
        internal fun open(input: InputStream): Reader {
            val bis = BufferedInputStream(input, 8192)
            bis.mark(512)
            val head = ByteArray(512)
            var n = 0
            while (n < head.size) {
                val r = bis.read(head, n, head.size - n)
                if (r < 0) break
                n += r
            }
            bis.reset()
            fun b(i: Int) = if (i < n) head[i].toInt() and 0xFF else -1
            val (charset, skip) = when {
                b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF -> Charsets.UTF_8 to 3
                b(0) == 0xFE && b(1) == 0xFF -> Charsets.UTF_16BE to 2
                b(0) == 0xFF && b(1) == 0xFE -> Charsets.UTF_16LE to 2
                b(0) == 0x00 && b(1) == 0x3C -> Charsets.UTF_16BE to 0
                b(0) == 0x3C && b(1) == 0x00 -> Charsets.UTF_16LE to 0
                else -> declared(String(head, 0, n, Charsets.ISO_8859_1)) to 0
            }
            repeat(skip) { bis.read() }
            return InputStreamReader(bis, charset)
        }

        private fun declared(head: String): Charset {
            if (!head.startsWith("<?xml")) return Charsets.UTF_8
            val decl = head.substringBefore("?>")
            val m = Regex("""encoding\s*=\s*["']([A-Za-z0-9._-]+)["']""").find(decl) ?: return Charsets.UTF_8
            return runCatching { Charset.forName(m.groupValues[1]) }.getOrDefault(Charsets.UTF_8)
        }
    }
}

/** Escapes text for XML content or attributes, dropping characters XML 1.0 cannot carry. */
object XmlText {
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '&' -> sb.append("&amp;")
                c == '"' -> sb.append("&quot;")
                c == '\'' -> sb.append("&apos;")
                c == '\t' || c == '\n' || c == '\r' -> sb.append(c)
                c < ' ' || c.code == 0xFFFE || c.code == 0xFFFF -> {}
                Character.isHighSurrogate(c) ->
                    if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                        sb.append(c).append(s[i + 1])
                        i++
                    }
                Character.isLowSurrogate(c) -> {}
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
