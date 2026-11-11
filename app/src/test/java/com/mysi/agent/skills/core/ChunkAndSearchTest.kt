package com.mysi.agent.skills.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkAndSearchTest {
    @Test
    fun shortTextIsOneChunk() {
        assertEquals(listOf("Hello there."), Chunker.split("  Hello there.  "))
        assertEquals(emptyList<String>(), Chunker.split("   "))
    }

    @Test
    fun chunksStayNearTheTargetSizeAndOverlap() {
        val text = (1..300).joinToString(" ") { "Sentence number $it ends here." }
        val chunks = Chunker.split(text)
        assertTrue(chunks.size > 5)
        for (c in chunks) assertTrue("${c.length}", c.length <= Chunker.SIZE)
        for (c in chunks.dropLast(1)) assertTrue("${c.length}", c.length >= Chunker.SIZE / 2)
        // Each chunk ends at a sentence and the next one repeats some of its end.
        for (i in 0 until chunks.size - 1) {
            assertTrue(chunks[i], chunks[i].endsWith("."))
            val tail = chunks[i].takeLast(40)
            assertTrue("no overlap between $i and ${i + 1}", chunks[i + 1].contains(tail.substringAfter(' ').take(20)))
        }
        // Nothing is lost: every sentence is in some chunk.
        for (n in listOf(1, 150, 300)) assertTrue(chunks.any { it.contains("Sentence number $n ends") })
    }

    @Test
    fun cutsPreferParagraphsThenLines() {
        val a = "A".repeat(500)
        val b = "B".repeat(500)
        val chunks = Chunker.split("$a\n\n$b", size = 800, overlap = 50)
        assertEquals(a, chunks[0])
        assertTrue(chunks[1].endsWith(b))
        val lines = Chunker.split("x".repeat(450) + "\n" + "y".repeat(450), size = 800, overlap = 50)
        assertEquals("x".repeat(450), lines[0])
    }

    @Test
    fun chunksNeverStartMidWord() {
        val text = (1..400).joinToString(" ") { "alpha$it" }
        val chunks = Chunker.split(text, 300, 60)
        for (c in chunks) assertTrue(c, c.startsWith("alpha"))
    }

    @Test
    fun textWithoutSpacesIsCutHardButNeverSplitsASurrogatePair() {
        val emoji = String(Character.toChars(0x1F600))
        val text = "a" + emoji.repeat(1000)
        val chunks = Chunker.split(text, 100, 10)
        for (c in chunks) {
            assertTrue(c.length <= 100)
            assertTrue(!Character.isLowSurrogate(c.first()))
            assertTrue(!Character.isHighSurrogate(c.last()))
        }
    }

    @Test
    fun chunksKeepTheirPartLocation() {
        val chunks = Chunker.chunk(listOf(Part("page 1", "One. ".repeat(300)), Part("page 2", "Two.")))
        assertTrue(chunks.first().location == "page 1")
        assertEquals(Chunk("page 2", "Two."), chunks.last())
    }

    @Test
    fun tokensFoldCaseAccentsPluralsAndStopWords() {
        assertEquals(listOf("cafe", "owner", "reported", "bitter", "taste"), Tokens.of("The Café owners reported a bitter taste!"))
        assertEquals(listOf("company", "policy", "user", "class"), Tokens.of("companies' policies: the user's class"))
        assertEquals(listOf("2024", "q3", "7"), Tokens.of("2024 Q3 7 a"))
    }

    @Test
    fun indianScriptsKeepTheirVowelSigns() {
        assertEquals(listOf("नमस्ते", "दुनिया"), Tokens.of("नमस्ते, दुनिया"))
        assertEquals(listOf("வணக்கம்"), Tokens.of("வணக்கம்"))
    }

    @Test
    fun chineseAndJapaneseIdeographsAreSingleTerms() {
        assertEquals(listOf("东", "京", "tokyo"), Tokens.of("东京 Tokyo"))
    }

    private fun rank(docs: List<String>, query: String): List<Long> {
        val idx = MemoryIndex()
        docs.forEachIndexed { i, d -> idx.add(i.toLong(), d) }
        return Bm25Ranker(idx).rank(query, 10).map { it.id }
    }

    @Test
    fun bm25PrefersRareTermsAndHigherFrequency() {
        val docs = listOf(
            "The cat sat on the mat.",
            "A dog and a cat played.",
            "Quantum entanglement in the lab; quantum effects measured.",
            "Quantum computing is new.",
        )
        assertEquals(listOf(2L, 3L), rank(docs, "quantum"))
        // "entanglement" is rare, so it outweighs the common word "cat".
        assertEquals(2L, rank(docs, "cat entanglement").first())
        assertEquals(emptyList<Long>(), rank(docs, "zebra"))
    }

    @Test
    fun bm25NormalisesForLength() {
        val docs = listOf(
            "budget " + "filler words that say nothing at all ".repeat(30),
            "budget approved",
        )
        assertEquals(listOf(1L, 0L), rank(docs, "budget"))
    }

    @Test
    fun memoryIndexRemovesCleanly() {
        val idx = MemoryIndex()
        idx.add(1, "apple banana")
        idx.add(2, "banana cherry")
        idx.remove(1)
        assertEquals(1, idx.documentCount)
        assertEquals(listOf(2L), Bm25Ranker(idx).rank("banana apple", 5).map { it.id })
        idx.add(2, "replaced text")
        assertEquals(emptyList<Posting>(), idx.postings("cherry"))
    }

    @Test
    fun rankingIsDeterministicForTies() {
        val docs = listOf("same words", "same words", "same words")
        assertEquals(listOf(0L, 1L, 2L), rank(docs, "same"))
    }
}
