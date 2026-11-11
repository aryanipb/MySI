package com.mysi.agent.search

import com.mysi.agent.web.WebTerms
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one BM25 implementation: the formula, the ranker over an index, and pluggable terms. */
class Bm25Test {
    @Test
    fun theFormulaIsOkapiBm25() {
        // 10 documents, the term in 2; tf 3 in a document of length 12 where the average is 8.
        val idf = ln(1.0 + (10 - 2 + 0.5) / (2 + 0.5))
        assertEquals(idf, Bm25.idf(10, 2), 1e-12)
        val expected = idf * 3 * (1.2 + 1) / (3 + 1.2 * (1 - 0.75 + 0.75 * 12 / 8.0))
        assertEquals(expected, Bm25.weight(3.0, idf, 12.0, 8.0), 1e-12)
        assertTrue(Bm25.idf(10, 10) > 0)
    }

    @Test
    fun theRankerSumsTheFormulaOverQueryTerms() {
        val index = MemoryIndex()
        index.add(1, "monsoon rain kerala")
        index.add(2, "rain rain rain in chennai today")
        index.add(3, "stock market news")
        val ranked = Bm25Ranker(index).rank("rain kerala", 10)
        assertEquals(listOf(1L, 2L), ranked.map { it.id })
        val avg = (3 + 5 + 3) / 3.0 // "in" is a stop word
        val rain = Bm25.idf(3, 2)
        val kerala = Bm25.idf(3, 1)
        assertEquals(Bm25.weight(1.0, rain, 3.0, avg) + Bm25.weight(1.0, kerala, 3.0, avg), ranked[0].score, 1e-12)
        assertEquals(Bm25.weight(3.0, rain, 5.0, avg), ranked[1].score, 1e-12)
    }

    @Test
    fun eachCallerKeepsItsOwnTerms() {
        val docs = listOf("charging an electric scooter at home", "monsoon rain in kerala")
        // Web pages use the web skill's suffix stemmer, so "charge" finds "charging"…
        assertEquals(listOf(0), WebTerms.rank(docs, "charge scooter cheaply").map { it.first }.take(1))
        assertEquals(0, WebTerms.rank(docs, "charge").single().first)
        // …while the user's documents keep plural-only folding, as their stored index does.
        val index = MemoryIndex()
        docs.forEachIndexed { i, d -> index.add(i.toLong(), d) }
        assertTrue(Bm25Ranker(index).rank("charge", 5).isEmpty())
        assertEquals(listOf(1L), Bm25Ranker(index).rank("rains", 5).map { it.id })
    }
}
