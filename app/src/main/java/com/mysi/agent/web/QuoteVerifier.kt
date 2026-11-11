package com.mysi.agent.web

import java.text.Normalizer

/**
 * Checks that a quote the model says it copied really is in a source.
 *
 * Matching rules (the "fuzzy threshold"):
 * 1. Both texts are normalised: Unicode NFKC, lower case, curly quotes and apostrophes made
 *    straight, all dashes made "-", "…" made "...", soft hyphens and zero-width characters removed,
 *    whitespace collapsed. An exact substring match after this is verified.
 * 2. Otherwise both are reduced to word tokens (letters and digits only; punctuation ignored, so
 *    "1,000" is "1000"). The quote's tokens must appear in the source as one contiguous run,
 *    100% of tokens equal and in order. No word may differ: a changed number, name or negation
 *    fails. This tolerates only punctuation, spacing and case differences.
 * 3. A quote containing an ellipsis ("..." or "…") is split there; every part must match by rule 2,
 *    in order, and each part needs at least [MIN_PART_WORDS] words.
 * A quote must have at least [MIN_WORDS] words, so a trivially common phrase cannot "verify" a claim.
 */
object QuoteVerifier {
    const val MIN_WORDS = 5
    const val MIN_PART_WORDS = 3

    enum class Result { VERIFIED, TOO_SHORT, NOT_FOUND }

    fun normalise(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        val sb = StringBuilder(n.length)
        var space = false
        for (c in n) {
            val m = when (c) {
                '‘', '’', '‚', '‛', '′', '`', '´' -> '\''
                '“', '”', '„', '‟', '″', '«', '»' -> '"'
                '‐', '‑', '‒', '–', '—', '―', '−' -> '-'
                '\u00AD', '\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF' -> null
                else -> c
            } ?: continue
            if (m.isWhitespace() || m == '\u00A0') {
                space = true
                continue
            }
            if (space && sb.isNotEmpty()) sb.append(' ')
            space = false
            sb.append(m)
        }
        return sb.toString().replace("…", "...")
    }

    /** Word tokens: runs of letters/digits; "1,000.5" becomes "10005" so number punctuation cannot matter. */
    fun words(s: String): List<String> {
        val n = normalise(s)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < n.length) {
            val c = n[i]
            val digitJoin = (c == ',' || c == '.') && sb.isNotEmpty() && sb.last().isDigit() && n.getOrNull(i + 1)?.isDigit() == true
            if (c.isLetterOrDigit()) sb.append(c)
            else if (!digitJoin && c != '\'') {
                if (sb.isNotEmpty()) out += sb.toString()
                sb.setLength(0)
            }
            i++
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /** Source prepared once for many quote checks. */
    class Source(text: String) {
        val normalised = normalise(text)
        val tokens = words(text)
        private val index: Map<String, List<Int>> = HashMap<String, MutableList<Int>>().apply {
            tokens.forEachIndexed { i, t -> getOrPut(t) { mutableListOf() }.add(i) }
        }

        /** First position at or after [from] where [seq] occurs contiguously, or -1. */
        fun find(seq: List<String>, from: Int = 0): Int {
            if (seq.isEmpty()) return -1
            for (start in index[seq[0]] ?: return -1) {
                if (start < from || start + seq.size > tokens.size) continue
                var ok = true
                for (k in 1 until seq.size) if (tokens[start + k] != seq[k]) {
                    ok = false
                    break
                }
                if (ok) return start
            }
            return -1
        }
    }

    fun verify(quote: String, source: Source): Result {
        val q = quote.trim().trim('"', '\'', '“', '”', '‘', '’').trim()
        val all = words(q)
        if (all.size < MIN_WORDS) return Result.TOO_SHORT
        val nq = normalise(q)
        if (nq.isNotEmpty() && source.normalised.contains(nq)) return Result.VERIFIED
        val parts = nq.split("...").map { words(it) }.filter { it.isNotEmpty() }
        if (parts.size > 1) {
            if (parts.any { it.size < MIN_PART_WORDS }) return Result.NOT_FOUND
            var at = 0
            for (p in parts) {
                val pos = source.find(p, at)
                if (pos < 0) return Result.NOT_FOUND
                at = pos + p.size
            }
            return Result.VERIFIED
        }
        return if (source.find(all) >= 0) Result.VERIFIED else Result.NOT_FOUND
    }

    fun verify(quote: String, sourceText: String) = verify(quote, Source(sourceText))
}
