package com.mysi.agent.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteVerifierTest {
    private val source =
        """
        The distributor said 18,400 homes in the Pune circle had connected rooftop systems by the end of September, up from 9,150 a year earlier.
        “Our bill dropped from about ₹3,200 a month to under ₹600 after the panels went up,” said Anil Deshpande, a retired engineer.
        Engineers warned that some neighbourhood transformers — built decades ago — were not designed for power flowing back into the grid.
        """.trimIndent()

    private fun v(q: String) = QuoteVerifier.verify(q, source)

    @Test
    fun exactQuotesVerify() {
        assertEquals(QuoteVerifier.Result.VERIFIED, v("had connected rooftop systems by the end of September"))
    }

    @Test
    fun whitespaceCaseAndQuoteStylesDoNotMatter() {
        assertEquals(QuoteVerifier.Result.VERIFIED, v("THE DISTRIBUTOR   said 18,400 homes\nin the Pune circle"))
        assertEquals(QuoteVerifier.Result.VERIFIED, v("\"Our bill dropped from about ₹3,200 a month to under ₹600 after the panels went up,\" said Anil Deshpande"))
        assertEquals(QuoteVerifier.Result.VERIFIED, v("some neighbourhood transformers - built decades ago - were not designed"))
        assertEquals(QuoteVerifier.Result.VERIFIED, v("some neighbourhood transformers, built decades ago, were not designed"))
        assertEquals(QuoteVerifier.Result.VERIFIED, v("the distributor said 18400 homes in the pune circle"))
    }

    @Test
    fun ellipsesJoinPartsInOrder() {
        assertEquals(QuoteVerifier.Result.VERIFIED, v("The distributor said 18,400 homes … up from 9,150 a year earlier"))
        assertEquals(QuoteVerifier.Result.VERIFIED, v("The distributor said 18,400 homes ... up from 9,150 a year earlier"))
        assertEquals(QuoteVerifier.Result.NOT_FOUND, v("up from 9,150 a year earlier … The distributor said 18,400 homes"))
        assertEquals(QuoteVerifier.Result.NOT_FOUND, v("The distributor said 18,400 homes … a year"))
    }

    @Test
    fun nearMissesFail() {
        // One changed number.
        assertEquals(QuoteVerifier.Result.NOT_FOUND, v("The distributor said 18,500 homes in the Pune circle"))
        // One changed word, one inserted word, one dropped word, a negation removed.
        assertEquals(QuoteVerifier.Result.NOT_FOUND, v("The distributor said 18,400 houses in the Pune circle"))
        assertEquals(QuoteVerifier.Result.NOT_FOUND, v("The distributor said about 18,400 homes in the Pune circle"))
        assertEquals(QuoteVerifier.Result.NOT_FOUND, v("The distributor said 18,400 in the Pune circle"))
        assertEquals(QuoteVerifier.Result.NOT_FOUND, v("transformers built decades ago were designed for power flowing back"))
        // Words from two different sentences glued together.
        assertEquals(QuoteVerifier.Result.NOT_FOUND, v("a year earlier said Anil Deshpande a retired engineer"))
    }

    @Test
    fun trivialQuotesAreTooShortToCount() {
        assertEquals(QuoteVerifier.Result.TOO_SHORT, v("the Pune circle"))
        assertEquals(QuoteVerifier.Result.TOO_SHORT, v(""))
    }

    @Test
    fun numberPunctuationIsIgnoredButDigitsAreNot() {
        assertEquals(listOf("18400", "homes", "35"), QuoteVerifier.words("18,400 homes 3.5"))
        assertTrue(QuoteVerifier.words("don’t stop").contains("dont"))
        assertFalse(QuoteVerifier.words("1,000").contains("1"))
    }

    @Test
    fun bm25RanksTheRelevantChunkFirst() {
        val docs = listOf(
            "Monsoon rains arrived in Kerala on 30 May, two days early.",
            "Battery storage prices fell sharply as lithium costs dropped.",
            "Charging an electric scooter at home costs less than petrol.",
        )
        val bm = Bm25(docs)
        assertEquals(1, bm.rank("why did batteries get cheaper")[0].first)
        assertEquals(2, bm.rank("scooter charge cost")[0].first)
        assertTrue(bm.rank("volcano eruption").isEmpty())
        assertEquals(listOf("battery", "charg"), Bm25.tokens("the batteries charging").let { listOf(it[0], it[1]) })
    }

    @Test
    fun chunkerSplitsOnParagraphsAndSentences() {
        val text = (1..30).joinToString(" ") { "Sentence number $it is here." } + "\n\nShort para."
        val chunks = Chunker.chunks(text, 200)
        assertTrue(chunks.all { it.length <= 200 })
        assertTrue(chunks.last().endsWith("Short para."))
        assertEquals(Element.collapse(text.replace("\n\n", " ")), Element.collapse(chunks.joinToString(" ")))
    }
}
