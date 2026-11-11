package com.mysi.agent.web

import com.mysi.agent.search.Bm25Ranker
import com.mysi.agent.search.MemoryIndex
import java.text.Normalizer

/**
 * Search terms for web page text. Lower-case letters and digits, English stop words removed, and
 * a light suffix stemmer, so "batteries" matches "battery" and "charging" matches "charge".
 * Web ranking and DeepResearch's claim-support check are tuned to these terms; the user's own
 * documents use [com.mysi.agent.search.Tokens]. Scoring is the app's one BM25 ([rank]).
 */
object WebTerms {
    private val STOP =
        ("a an and are as at be been but by can could did do does for from had has have how i if in into is it its may " +
            "might more most of on or our so than that the their them then there these they this those to was we were what " +
            "when where which who whom why will with would you your about after also any before between both each few he her " +
            "him his just me my no nor not now only other over same she should some such too under up very what's").split(' ').toSet()

    fun stem(w: String): String {
        if (w.length <= 3 || w.all { it.isDigit() }) return w
        return when {
            w.endsWith("ies") && w.length > 4 -> w.dropLast(3) + "y"
            w.endsWith("sses") -> w.dropLast(2)
            w.endsWith("ing") && w.length > 5 -> w.dropLast(3).let { if (it.length > 2 && it.last() == it[it.length - 2]) it.dropLast(1) else it }
            w.endsWith("ed") && w.length > 4 -> w.dropLast(2).let { if (it.endsWith("i")) it.dropLast(1) + "y" else it }
            w.endsWith("es") && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes") || w.endsWith("zes")) -> w.dropLast(2)
            w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is") -> w.dropLast(1)
            else -> w
        }.let { if (it.endsWith("e") && it.length > 4) it.dropLast(1) else it }
    }

    fun of(text: String): List<String> {
        val s = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                val w = sb.toString()
                if (w !in STOP && (w.length > 1 || w[0].isDigit())) out += stem(w)
                sb.setLength(0)
            }
        }
        for (c in s) if (c.isLetterOrDigit()) sb.append(c) else flush()
        flush()
        return out
    }

    /** BM25 ([Bm25Ranker]) over [docs] with these terms: every document index with a positive score, best first. */
    fun rank(docs: List<String>, query: String): List<Pair<Int, Double>> {
        val index = MemoryIndex(::of)
        docs.forEachIndexed { i, d -> index.add(i.toLong(), d) }
        return Bm25Ranker(index, terms = ::of).rank(query, docs.size).filter { it.score > 0 }.map { it.id.toInt() to it.score }
    }
}

/** Splits page text into passages or chunks along paragraph and sentence boundaries. */
object Chunker {
    private val SENTENCE_END = Regex("(?<=[.!?。！？])\\s+(?=[\\p{Lu}\\p{N}\"“(\\[])")

    /** Paragraphs of [text]; any paragraph longer than [max] is split into sentence groups. */
    fun passages(text: String, max: Int): List<String> {
        val out = mutableListOf<String>()
        for (para in text.split(Regex("\\n\\s*\\n|\\n(?=[-#]|\\d+\\. )")).map { it.trim() }.filter { it.isNotEmpty() }) {
            if (para.length <= max) {
                out += para
                continue
            }
            val sb = StringBuilder()
            for (s in para.split(SENTENCE_END)) {
                if (sb.isNotEmpty() && sb.length + s.length + 1 > max) {
                    out += sb.toString()
                    sb.setLength(0)
                }
                if (s.length > max) {
                    // A run-on "sentence" (a table row dump): hard-split on spaces.
                    var rest = s
                    while (rest.length > max) {
                        val cut = rest.lastIndexOf(' ', max).let { if (it < max / 2) max else it }
                        out += rest.substring(0, cut).trim()
                        rest = rest.substring(cut).trim()
                    }
                    if (rest.isNotEmpty()) sb.append(rest)
                    continue
                }
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(s)
            }
            if (sb.isNotEmpty()) out += sb.toString()
        }
        return out
    }

    /** Chunks of roughly [size] characters made of whole passages, for ranking against a question. */
    fun chunks(text: String, size: Int = 900): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (p in passages(text, size)) {
            if (sb.isNotEmpty() && sb.length + p.length + 2 > size) {
                out += sb.toString()
                sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(p)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }
}
