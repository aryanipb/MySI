package com.mysi.agent.skills.core

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

enum class DocType(val label: String, val partKind: String) {
    PDF("PDF", "page"),
    DOCX("Word document", "part"),
    XLSX("Excel workbook", "part"),
    PPTX("PowerPoint presentation", "slide"),
    CSV("CSV table", "part"),
    TSV("TSV table", "part"),
    MARKDOWN("Markdown", "part"),
    TEXT("Text", "part"),
    HTML("Web page", "part"),
}

/** One readable unit of a document: a PDF page, a slide, a run of sheet rows or a text part. */
data class Part(val label: String, val text: String)

/**
 * An opened document. Parts are read on demand so a long PDF never has to be extracted whole;
 * every other format is parsed once, within the caps in [ReadLimits].
 */
interface DocSource : Closeable {
    val type: DocType
    val title: String?
    val partCount: Int

    /** Extra facts for document_info, such as sheet names or the author. */
    val facts: List<String>

    /** Things the user should know, such as "only the first 20 MB was read". */
    val warnings: List<String>

    /** 0-based. */
    fun part(index: Int): Part

    override fun close() {}
}

/** A document already parsed into memory. */
class ParsedDoc(
    override val type: DocType,
    override val title: String?,
    private val parts: List<Part>,
    override val facts: List<String> = emptyList(),
    override val warnings: List<String> = emptyList(),
) : DocSource {
    override val partCount get() = parts.size

    override fun part(index: Int) = parts[index]
}

/** Caps on how much of a document is parsed, so a hostile or huge file cannot exhaust memory. */
data class ReadLimits(
    val maxTextBytes: Long = 20L * 1024 * 1024,
    val maxChars: Int = 8_000_000,
    val maxCells: Int = 2_000_000,
    val maxRows: Int = 1_000_000,
    val maxColumns: Int = 1_000,
    val maxPdfPages: Int = 5_000,
    val zip: ZipLimits = ZipLimits(),
)

/**
 * Tool results page themselves and must never be cut by the runtime, which shows the model at
 * most ResultStore.EXCERPT_CHARS of a result and puts its own read_result handle on the rest.
 * Lists therefore stop at whole lines within [CHARS] and say how to get the rest.
 */
object Budget {
    /** Room for a list's lines; the caller's header and "next page" line fit in the margin up to [MAX]. */
    const val CHARS = com.mysi.agent.ResultStore.PAGED_TOOL_CHARS - 300

    /** The most any skill-pack result may have. */
    const val MAX = com.mysi.agent.ResultStore.PAGED_TOOL_CHARS

    /** How many of [lines] fit in [max] characters joined by new lines; at least one when any exist. */
    fun fit(lines: List<String>, max: Int = CHARS): Int {
        var used = 0
        lines.forEachIndexed { i, line ->
            used += line.length + 1
            if (used > max) return maxOf(i, 1)
        }
        return lines.size
    }
}

/** A paragraph-level block of text; [heading] is 1-6 for headings and 0 for body text. */
data class Block(val text: String, val heading: Int = 0)

object Parts {
    const val PART_CHARS = 3_000

    /**
     * Splits blocks into parts of about [PART_CHARS] characters at paragraph boundaries. Each
     * label names the part and the heading it falls under, so a quote can be located.
     */
    fun fromBlocks(blocks: List<Block>, max: Int = PART_CHARS): List<Part> {
        val out = mutableListOf<Pair<String?, String>>()
        val sb = StringBuilder()
        var heading: String? = null
        var partHeading: String? = null
        fun flush() {
            if (sb.isNotBlank()) out += partHeading to sb.toString().trim()
            sb.setLength(0)
            partHeading = heading
        }
        for (b in blocks) {
            val text = b.text.trim()
            if (text.isEmpty()) continue
            if (b.heading > 0) {
                // Start a new part at a heading when the current one is already well filled.
                if (sb.length > max / 2) flush()
                heading = text.take(80)
                if (sb.isEmpty()) partHeading = heading
            }
            if (sb.isNotEmpty() && sb.length + text.length + 2 > max) flush()
            if (text.length > max) {
                for (piece in splitLong(text, max)) {
                    if (sb.isNotEmpty()) flush()
                    sb.append(piece)
                }
            } else {
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append(text)
            }
        }
        flush()
        return out.mapIndexed { i, (h, t) -> Part("part ${i + 1}" + (h?.let { " (under \"$it\")" } ?: ""), t) }
    }

    /** Cuts text longer than [max] at line, sentence or word boundaries. */
    fun splitLong(text: String, max: Int): List<String> {
        val pieces = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            if (text.length - start <= max) {
                pieces += text.substring(start).trim()
                break
            }
            val end = Chunker.boundary(text, start, start + max)
            pieces += text.substring(start, end).trim()
            start = end
        }
        return pieces.filter { it.isNotEmpty() }
    }

    /**
     * Groups table rows into parts of about [max] characters, labelled with their row numbers.
     * [numbers] holds each row's number as the user sees it (the Excel row, or the data row).
     */
    fun fromRows(prefix: String, header: List<String>?, rows: List<List<String>>, numbers: List<Int>, max: Int = PART_CHARS): List<Part> {
        val out = mutableListOf<Part>()
        val head = header?.let { Csv.line(it) }
        val sb = StringBuilder()
        var from = -1
        var last = -1
        fun flush() {
            if (from >= 0) out += Part("$prefix rows $from-$last", (if (head != null) "$head\n" else "") + sb.toString().trimEnd())
            sb.setLength(0)
            from = -1
        }
        rows.forEachIndexed { i, r ->
            val n = numbers[i]
            val line = "${Csv.line(r)}\n"
            if (from >= 0 && sb.length + line.length > max) flush()
            if (from < 0) from = n
            sb.append(line)
            last = n
        }
        flush()
        if (out.isEmpty()) out += Part("$prefix (empty)", head ?: "")
        return out
    }
}

object TextFiles {
    /**
     * Decodes bytes honouring a byte-order mark; text that is not valid UTF-8 is read as
     * Windows-1252, the usual encoding of older Western files.
     */
    fun decode(bytes: ByteArray): String {
        fun b(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        return when {
            b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            b(0) == 0xFE && b(1) == 0xFF -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            b(0) == 0xFF && b(1) == 0xFE -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            else -> strictUtf8(bytes) ?: String(bytes, Charset.forName("windows-1252"))
        }
    }

    private fun strictUtf8(bytes: ByteArray): String? {
        val dec = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            dec.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            // A multi-byte character cut by a size cap is not a reason to give up on UTF-8.
            if (bytes.size > 4) {
                for (cut in 1..3) {
                    val r = runCatching { dec.reset(); dec.decode(ByteBuffer.wrap(bytes, 0, bytes.size - cut)).toString() }.getOrNull()
                    if (r != null) return r
                }
            }
            null
        }
    }

    /** Reads at most [max] bytes; the flag says whether the file was cut. */
    fun readCapped(file: File, max: Long): Pair<ByteArray, Boolean> {
        val size = file.length()
        val n = minOf(size, max).toInt()
        val bytes = ByteArray(n)
        file.inputStream().use { input ->
            var off = 0
            while (off < n) {
                val r = input.read(bytes, off, n - off)
                if (r < 0) break
                off += r
            }
            return (if (off == n) bytes else bytes.copyOf(off)) to (size > max)
        }
    }
}
