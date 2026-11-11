package com.mysi.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelinesTest {
    @Test
    fun mapsEachChunkThenReducesOnce() = runBlocking {
        val prompts = mutableListOf<String>()
        val progress = mutableListOf<String>()
        val out = Pipelines.mapReduce(
            listOf("alpha", "beta", " "),
            "Summarise:",
            "Combine:",
            { p -> prompts += p; if (p.startsWith("Summarise:")) "S(" + p.substringAfterLast("\n") + ")" else "FINAL" },
            progress = { progress += it },
        )
        assertEquals("FINAL", out)
        assertEquals(listOf("Summarise:\n\n---\nalpha", "Summarise:\n\n---\nbeta", "Combine:\n\n---\nPart 1:\nS(alpha)\n\nPart 2:\nS(beta)"), prompts)
        assertEquals(listOf("Reading part 1 of 2…", "Reading part 2 of 2…", "Writing the final result…"), progress)
    }

    @Test
    fun emptyInputNeedsNoModel() = runBlocking {
        assertEquals("", Pipelines.mapReduce(emptyList(), "m", "r", { error("no calls") }))
    }

    @Test
    fun longPartialResultsAreReducedInRounds() = runBlocking {
        var reduces = 0
        val out = Pipelines.mapReduce(
            List(8) { "chunk $it" },
            "Map:",
            "Reduce:",
            { p ->
                if (p.startsWith("Map:")) "m".repeat(400) // ~100 tokens each
                else { reduces++; "r".repeat(400) }
            },
            budgetTokens = 250,
        )
        assertEquals("r".repeat(400), out)
        assertTrue("expected intermediate rounds, got $reduces reduces", reduces > 1)
    }

    @Test
    fun oversizedPartsTerminate() = runBlocking {
        var calls = 0
        val out = Pipelines.mapReduce(
            listOf("a", "b"),
            "Map:",
            "Reduce:",
            { p -> calls++; if (p.startsWith("Map:")) "x".repeat(4_000) else "final" },
            budgetTokens = 100,
        )
        assertEquals("final", out)
        assertEquals(3, calls)
    }

    @Test
    fun batchesRespectTheBudget() {
        val parts = List(5) { "p".repeat(200) } // 50 tokens each, 56 with overhead
        assertEquals(listOf(2, 2, 1), Pipelines.batch(parts, 120).map { it.size })
        assertEquals(listOf(1, 1), Pipelines.batch(listOf("x".repeat(1_000), "y".repeat(1_000)), 50).map { it.size })
    }
}
