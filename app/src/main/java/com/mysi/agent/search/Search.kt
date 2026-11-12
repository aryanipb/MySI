package com.mysi.agent.search

import java.text.Normalizer
import kotlin.math.ln

/*
 * Search shared by the whole app: one tokenizer for the user's own text ([Tokens]) and one BM25
 * scorer ([Bm25], [Bm25Ranker]) used by the knowledge base, document and note search, the web
 * skill's page and research ranking, and the skill router. Pure Kotlin, no Android types.
 */

/**
 * Search terms from text: Unicode-aware words (combining marks kept, so Hindi and Tamil words
 * stay whole), lower-cased, accents folded, English stop words dropped and plurals folded.
 * Chinese and Japanese ideographs become one term per character.
 */
object Tokens {
    private val stop = setOf(
        "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "from", "has", "have", "he", "her", "his",
        "i", "if", "in", "into", "is", "it", "its", "me", "my", "of", "on", "or", "our", "she", "so", "that", "the",
        "their", "them", "there", "they", "this", "to", "was", "we", "were", "what", "when", "where", "which",
        "who", "will", "with", "you", "your", "do", "does", "did", "can", "about", "how", "any", "all",
    )

    fun of(text: String, max: Int = Int.MAX_VALUE): List<String> {
        val folded = fold(text)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                val t = stem(sb.toString())
                if ((t.length > 1 || t[0].isDigit()) && t !in stop) out += t
                sb.setLength(0)
            }
        }
        var i = 0
        while (i < folded.length && out.size < max) {
            val cp = folded.codePointAt(i)
            val len = Character.charCount(cp)
            when {
                Character.isIdeographic(cp) -> {
                    flush()
                    out += String(Character.toChars(cp))
                }
                Character.isLetterOrDigit(cp) || isMark(cp) -> sb.appendCodePoint(cp)
                cp == '\''.code || cp == 0x2019 -> {
                    // "user's" -> "user": drop the possessive, keep the word going otherwise.
                    if (i + len < folded.length && folded[i + len] == 's' && (i + len + 1 >= folded.length || !folded[i + len + 1].isLetterOrDigit())) i += 1
                }
                else -> flush()
            }
            i += len
        }
        flush()
        return out
    }

    private fun isMark(cp: Int): Boolean {
        val t = Character.getType(cp)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt()
    }

    /** Lower case with Latin accents removed; marks of other scripts are kept. */
    fun fold(text: String): String {
        val nfd = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        val sb = StringBuilder(nfd.length)
        var prevLatin = false
        for (c in nfd) {
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt() && prevLatin) continue
            sb.append(c)
            prevLatin = c in 'a'..'z'
        }
        return Normalizer.normalize(sb, Normalizer.Form.NFC)
    }

    /** Folds English plurals only; anything more aggressive hurts names and numbers. */
    fun stem(w: String): String =
        when {
            w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
            w.length > 4 && w.endsWith("sses") -> w.dropLast(2)
            w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is") && w[w.length - 2].isLetter() -> w.dropLast(1)
            else -> w
        }

    /** Term frequencies of [text]. */
    fun counts(text: String): Map<String, Int> {
        val m = HashMap<String, Int>()
        for (t in of(text)) m[t] = (m[t] ?: 0) + 1
        return m
    }
}

/** One occurrence list entry: a passage containing a term [tf] times, and its length in terms. */
data class Posting(val id: Long, val tf: Int, val length: Int)

/** Corpus statistics and postings, from SQLite or from memory. */
interface TermIndex {
    val documentCount: Int
    val averageLength: Double

    fun postings(term: String): List<Posting>
}

data class Scored(val id: Long, val score: Double)

/**
 * Orders passages for a query. BM25 is the default; an embedding ranker can implement this
 * interface later and be swapped in, or fused with BM25, without touching the callers.
 */
interface Ranker {
    fun rank(query: String, limit: Int): List<Scored>
}

/** The Okapi BM25 formula, in one place for every ranker in the app. */
object Bm25 {
    const val K1 = 1.2
    const val B = 0.75

    /** Inverse document frequency of a term found in [withTerm] of [documents] documents (always positive). */
    fun idf(documents: Int, withTerm: Int): Double = ln(1.0 + (documents - withTerm + 0.5) / (withTerm + 0.5))

    /** One term's contribution to a document's score. */
    fun weight(tf: Double, idf: Double, length: Double, averageLength: Double, k1: Double = K1, b: Double = B): Double =
        idf * tf * (k1 + 1) / (tf + k1 * (1 - b + b * length / averageLength))
}

/**
 * Okapi BM25 over a [TermIndex]. [terms] must be the tokenizer the index was built with: the
 * user's documents, notes and memories use [Tokens]; web pages use the web skill's own terms.
 */
class Bm25Ranker(
    private val index: TermIndex,
    private val k1: Double = Bm25.K1,
    private val b: Double = Bm25.B,
    private val terms: (String) -> List<String> = Tokens::of,
) : Ranker {
    override fun rank(query: String, limit: Int): List<Scored> {
        val n = index.documentCount
        if (n == 0 || limit <= 0) return emptyList()
        val avg = index.averageLength.coerceAtLeast(1.0)
        val scores = HashMap<Long, Double>()
        for (term in terms(query).distinct().take(MAX_QUERY_TERMS)) {
            val postings = index.postings(term)
            if (postings.isEmpty()) continue
            val idf = Bm25.idf(n, postings.size)
            for (p in postings) scores[p.id] = (scores[p.id] ?: 0.0) + Bm25.weight(p.tf.toDouble(), idf, p.length.toDouble(), avg, k1, b)
        }
        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<Long, Double>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { Scored(it.key, it.value) }
    }

    companion object {
        const val MAX_QUERY_TERMS = 32
    }
}

/** An in-memory index for small collections (memories, notes, one document, a set of web pages). */
class MemoryIndex(private val terms: (String) -> List<String> = Tokens::of) : TermIndex {
    private val docs = HashMap<Long, Map<String, Int>>()
    private val lengths = HashMap<Long, Int>()
    private val inverted = HashMap<String, MutableMap<Long, Int>>()
    private var total = 0L

    fun add(id: Long, text: String) {
        remove(id)
        val c = terms(text).groupingBy { it }.eachCount()
        docs[id] = c
        val len = c.values.sum()
        lengths[id] = len
        total += len
        for ((t, n) in c) inverted.getOrPut(t) { HashMap() }[id] = n
    }

    fun remove(id: Long) {
        val c = docs.remove(id) ?: return
        total -= lengths.remove(id) ?: 0
        for (t in c.keys) inverted[t]?.let { m -> m.remove(id); if (m.isEmpty()) inverted.remove(t) }
    }

    override val documentCount get() = docs.size
    override val averageLength get() = if (docs.isEmpty()) 0.0 else total.toDouble() / docs.size

    override fun postings(term: String): List<Posting> =
        inverted[term]?.map { (id, tf) -> Posting(id, tf, lengths[id] ?: 0) } ?: emptyList()
}
