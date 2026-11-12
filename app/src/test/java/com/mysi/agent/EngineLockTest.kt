package com.mysi.agent

import com.mysi.agent.web.DeepResearch
import com.mysi.agent.web.Llm
import com.mysi.agent.web.ResearchDepth
import com.mysi.agent.web.ResearchPage
import com.mysi.agent.web.ResearchWeb
import com.mysi.agent.web.SearchResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Deep research runs inside a tool, while the request holds the engine lock, and calls the model
 * through it (Agent.complete). These tests run the real pipeline against a fake model that takes
 * the same [EngineLock] the way Agent.generate does: a call made from outside the request's
 * coroutine tree would wait forever, so it is caught both by a counter and by a timeout.
 */
class EngineLockTest {
    private val page =
        "Cold-climate heat pumps keep working at outdoor temperatures as low as minus 25 degrees Celsius. " +
            "Their efficiency falls as it gets colder, but they still deliver about two units of heat per unit of electricity at minus 15 degrees. " +
            "Installation in Norway costs about 12,000 euros on average for an air-to-water system."

    private val web = object : ResearchWeb {
        override suspend fun search(query: String, count: Int) =
            (1..4).map { SearchResult("Heat pumps $it", "https://site$it.example/heat-pumps", "") }

        override suspend fun read(url: String) = ResearchPage(url, "Heat pumps", page)
    }

    private fun answer(prompt: String): String =
        when {
            "search queries" in prompt -> """{"questions": ["heat pump cold climate performance", "heat pump cost Norway", "heat pump efficiency minus 15"]}"""
            "Sub-question" in prompt ->
                """{"claims": [{"claim": "Cold-climate heat pumps work down to minus 25 degrees Celsius.", "quote": "Cold-climate heat pumps keep working at outdoor temperatures as low as minus 25 degrees Celsius", "source": "S1"}]}"""
            else -> "Cold-climate heat pumps work down to minus 25 degrees Celsius [1]."
        }

    /** A model behind [lock], as Agent.generate is: it takes the lock, or passes through when the caller holds it. */
    private class Model(val lock: EngineLock, val answer: (String) -> String) {
        val calls = AtomicInteger()
        val outsideTheRequest = AtomicInteger()

        suspend fun complete(prompt: String): String {
            calls.incrementAndGet()
            if (!lock.heldHere()) outsideTheRequest.incrementAndGet()
            return lock.withEngine { answer(prompt) }
        }
    }

    @Test
    fun deepResearchCallsTheModelFromInsideTheRequest() = runBlocking {
        val lock = EngineLock()
        val model = Model(lock, ::answer)
        val llm = Llm { _, prompt -> model.complete(prompt) }
        val report = withTimeout(20_000) {
            // The request: holds the lock while the tool (deep research) runs, on another dispatcher as tools do.
            lock.withEngine { withContext(Dispatchers.IO) { DeepResearch(llm, web, parallelReads = 4).run("How well do heat pumps work in the cold?", ResearchDepth.QUICK) } }
        }
        assertEquals(0, model.outsideTheRequest.get())
        // Planning, one extraction per sub-question, the summary.
        assertEquals(ResearchDepth.QUICK.subQuestions + 2, model.calls.get())
        assertEquals(model.calls.get(), report.llmCalls)
        assertTrue(report.verified.isNotEmpty())
    }

    @Test
    fun childCoroutinesOfTheRequestPassThrough() = runBlocking {
        val lock = EngineLock()
        val inside = withTimeout(5_000) {
            lock.withEngine {
                kotlinx.coroutines.coroutineScope {
                    (1..4).map { async(Dispatchers.Default) { lock.withEngine { lock.heldHere() } } }.map { it.await() }
                }
            }
        }
        assertEquals(listOf(true, true, true, true), inside)
        assertFalse(lock.heldHere())
    }

    /** The control: the same model called from a separate scope deadlocks, and the test above would see it. */
    @Test
    fun aCallFromASeparateScopeWouldDeadlock() = runBlocking {
        val lock = EngineLock()
        val model = Model(lock, ::answer)
        val detached = CoroutineScope(Dispatchers.Default)
        val llm = Llm { _, prompt -> detached.async { model.complete(prompt) }.await() }
        try {
            withTimeout(1_000) { lock.withEngine { DeepResearch(llm, web).run("How well do heat pumps work in the cold?", ResearchDepth.QUICK) } }
            fail("expected the detached model call to wait for the request forever")
        } catch (_: TimeoutCancellationException) {
            assertEquals(1, model.outsideTheRequest.get())
        } finally {
            detached.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }
}
