package com.mysi.agent

import kotlin.math.ln

/** The parts of a [Skill] the router reads, kept free of Android types so it can be unit-tested. */
data class SkillCard(
    val name: String,
    val description: String,
    val keywords: List<String> = emptyList(),
    val toolNames: List<String> = emptyList(),
    val toolDescriptions: List<String> = emptyList(),
)

/**
 * Picks the skills that fit a request with BM25 over weighted fields: the skill's name and
 * keywords count most, then its description and tool names, then its tools' descriptions.
 * Deterministic: equal scores keep the order of [cards]. No match means an empty list; deciding
 * what to load then (for example keeping the previous skills on a follow-up) is the caller's job.
 */
class Router(private val cards: List<SkillCard>) {
    private class Doc(val name: String, val tf: Map<String, Double>, val length: Double)

    private val docs = cards.map { c ->
        val tf = HashMap<String, Double>()
        fun add(text: String, weight: Double) = terms(text).forEach { tf[it] = (tf[it] ?: 0.0) + weight }
        add(c.name, 3.0)
        c.keywords.forEach { add(it, 3.0) }
        add(c.description, 2.0)
        c.toolNames.forEach { add(it, 1.5) }
        c.toolDescriptions.forEach { add(it, 0.5) }
        Doc(c.name, tf, tf.values.sum())
    }
    private val avgLength = docs.map { it.length }.average().takeIf { !it.isNaN() && it > 0 } ?: 1.0
    private val df: Map<String, Int> = docs.flatMap { it.tf.keys }.groupingBy { it }.eachCount()

    /** Every skill with a positive score, best first. */
    fun rank(request: String): List<Pair<String, Double>> {
        val q = terms(request).distinct()
        if (q.isEmpty()) return emptyList()
        val n = docs.size.toDouble()
        return docs
            .map { d ->
                d.name to q.sumOf { t ->
                    val f = d.tf[t] ?: return@sumOf 0.0
                    val nt = df.getValue(t).toDouble()
                    val idf = ln(1 + (n - nt + 0.5) / (nt + 0.5))
                    idf * f * (K1 + 1) / (f + K1 * (1 - B + B * d.length / avgLength))
                }
            }
            .filter { it.second > 0.0 }
            // sortedByDescending is stable, so ties keep registry order.
            .sortedByDescending { it.second }
    }

    /** The best skill plus any others scoring at least [ratio] of it, at most [max]. */
    fun select(request: String, max: Int = 3, ratio: Double = 0.4): List<String> {
        val ranked = rank(request)
        val top = ranked.firstOrNull()?.second ?: return emptyList()
        return ranked.filter { it.second >= top * ratio }.take(max).map { it.first }
    }

    companion object {
        private const val K1 = 1.2
        private const val B = 0.5

        /**
         * The skills to load for the next request: the newly [wanted] ones first, then the most
         * recently used [previous] ones, so a follow-up such as "send it to her" keeps its tools
         * and the conversation is not rebuilt (a full re-prefill) for nothing.
         */
        fun merge(wanted: List<String>, previous: List<String>, max: Int): List<String> =
            (wanted + previous).distinct().take(max)

        private val STOP = setOf(
            "a", "an", "the", "and", "or", "but", "to", "of", "in", "on", "at", "for", "with", "by", "from", "about", "as",
            "is", "are", "was", "were", "be", "been", "am", "do", "does", "did", "have", "has", "had", "it", "its", "this",
            "that", "these", "those", "i", "me", "my", "mine", "you", "your", "we", "our", "he", "him", "his", "she", "her",
            "they", "them", "their", "what", "which", "who", "whom", "when", "where", "why", "how", "can", "could", "would",
            "should", "will", "shall", "may", "might", "must", "please", "pls", "plz", "kindly", "just", "also", "some",
            "any", "all", "now", "then", "there", "here", "so", "if", "not", "no", "yes", "ok", "okay", "hey", "hi", "hello",
            "want", "need", "like", "let", "get", "got", "give", "make", "use", "using", "open", "show", "tell", "find",
            "help", "one", "up", "out", "into", "than", "too", "very", "only", "e", "g", "eg", "etc", "default",
            // Pieces of contractions such as what's, don't, I'll.
            "s", "t", "d", "ll", "re", "ve", "m",
        )

        /** Lower-cased words with stop words removed and a light, consistent suffix strip. */
        fun terms(text: String): List<String> =
            text.lowercase()
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.isNotEmpty() && it !in STOP }
                .map(::stem)
                .filter { it !in STOP }

        internal fun stem(w: String): String =
            when {
                w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
                w.length > 4 && w.endsWith("es") && (w.dropLast(2).endsWith("s") || w.dropLast(2).endsWith("x") || w.dropLast(2).endsWith("ch") || w.dropLast(2).endsWith("sh")) -> w.dropLast(2)
                w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") -> w.dropLast(1)
                w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
                w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
                else -> w
            }
    }
}
