package com.mysi.agent.skills.core

/** A passage of a document for the knowledge base, with where it came from. */
data class Chunk(val location: String, val text: String)

/**
 * Splits document parts into overlapping passages of about [SIZE] characters. Cuts prefer
 * paragraph ends, then line ends, then sentence ends, then spaces, and a chunk never crosses a
 * part, so every passage keeps an exact page, slide or row location.
 */
object Chunker {
    const val SIZE = 800
    const val OVERLAP = 120

    fun chunk(parts: List<Part>, size: Int = SIZE, overlap: Int = OVERLAP): List<Chunk> =
        parts.flatMap { p -> split(p.text, size, overlap).map { Chunk(p.label, it) } }

    fun split(text: String, size: Int = SIZE, overlap: Int = OVERLAP): List<String> {
        require(size > 0 && overlap in 0 until size)
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var start = 0
        while (start < t.length) {
            if (t.length - start <= size) {
                out += t.substring(start).trim()
                break
            }
            val end = boundary(t, start, start + size)
            out += t.substring(start, end).trim()
            var next = end - overlap
            if (next <= start) next = end
            // Start the overlap at a word, never in the middle of one.
            while (next < end && !t[next - 1].isWhitespace()) next++
            start = next
            while (start < t.length && t[start].isWhitespace()) start++
        }
        return out.filter { it.isNotEmpty() }
    }

    /** The best cut in (start + half, limit]; a hard cut at [limit] only when there is no space. */
    fun boundary(text: String, start: Int, limit: Int): Int {
        val end = limit.coerceAtMost(text.length)
        val floor = start + (end - start) / 2
        fun lastIn(pred: (Int) -> Boolean): Int? {
            var i = end
            while (i > floor) {
                if (pred(i)) return i
                i--
            }
            return null
        }
        lastIn { i -> i >= 2 && text[i - 1] == '\n' && text[i - 2] == '\n' }?.let { return it }
        lastIn { i -> text[i - 1] == '\n' }?.let { return it }
        lastIn { i -> i < text.length && text[i].isWhitespace() && text[i - 1] in ".!?;:" }?.let { return it }
        lastIn { i -> i < text.length && text[i].isWhitespace() }?.let { return it }
        // No space at all: cut hard, but never between the two halves of a surrogate pair.
        return if (end < text.length && Character.isLowSurrogate(text[end])) end - 1 else end
    }
}
