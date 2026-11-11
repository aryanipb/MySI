package com.mysi.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentLoopTest {
    /** Replies from a script; records what it was sent. */
    private class FakeModel(vararg replies: ModelReply) : ModelChat {
        val script = ArrayDeque(replies.toList())
        val sent = mutableListOf<Entry>()
        var invalidated = 0
        var failOnSend: Throwable? = null

        override suspend fun send(input: Entry, onText: (String) -> Unit): ModelReply {
            sent += input
            failOnSend?.let { throw it }
            val r = script.removeFirstOrNull() ?: error("model asked for more turns than scripted")
            if (r.text.isNotEmpty()) onText(r.text)
            return r
        }

        override fun invalidate() {
            invalidated++
        }
    }

    private fun calls(vararg names: String) = ModelReply("", names.map { Call(it, emptyMap()) })

    /** Every model message with calls is followed by one response per call, by name. */
    private fun assertBalanced(t: Transcript) {
        t.turns.forEach { turn ->
            turn.entries.forEachIndexed { i, e ->
                if (e is Entry.Model && e.calls.isNotEmpty()) {
                    val next = turn.entries.getOrNull(i + 1) as? Entry.Tool ?: fail("calls at $i have no responses: ${turn.entries}")
                    assertEquals(e.calls.map { it.name }, (next as Entry.Tool).outs.map { it.name })
                }
            }
            assertTrue("turn must end with a model message", turn.entries.last() is Entry.Model)
        }
    }

    @Test
    fun plainAnswer() = runBlocking {
        val model = FakeModel(ModelReply("Hello!"))
        val t = Transcript()
        val r = AgentLoop().run(model, t, "hi", {}) { error("no tools") }
        assertEquals(LoopResult("Hello!", 0, false), r)
        assertEquals(listOf(Entry.User("hi"), Entry.Model("Hello!")), t.turns.single().entries)
        assertFalse(t.open)
    }

    @Test
    fun runsToolsAndFeedsResultsBack() = runBlocking {
        val model = FakeModel(calls("get_datetime", "device_status"), ModelReply("It is Monday and 80% charged."))
        val t = Transcript()
        val ran = mutableListOf<String>()
        val r = AgentLoop().run(model, t, "status?", {}) { c -> ran += c.name; ToolOut(c.name, "ok ${c.name}") }
        assertEquals(listOf("get_datetime", "device_status"), ran)
        assertEquals(Entry.Tool(listOf(ToolOut("get_datetime", "ok get_datetime"), ToolOut("device_status", "ok device_status"))), model.sent[1])
        assertEquals("It is Monday and 80% charged.", r.answer)
        assertBalanced(t)
    }

    @Test
    fun stepCapAnswersPendingCallsAndGivesOneFinalTurn() = runBlocking {
        val model = FakeModel(calls("a"), calls("b"), calls("c", "d"), ModelReply("Here is what I found."))
        val t = Transcript()
        val ran = mutableListOf<String>()
        val r = AgentLoop(maxSteps = 2).run(model, t, "go", {}) { c -> ran += c.name; ToolOut(c.name, "done") }
        assertEquals(listOf("a", "b"), ran)
        val last = model.sent.last() as Entry.Tool
        assertEquals(listOf("c", "d"), last.outs.map { it.name })
        assertTrue(last.outs.all { it.text == AgentLoop.STEP_LIMIT })
        assertEquals(LoopResult("Here is what I found.", 2, true), r)
        assertEquals(0, model.invalidated)
        assertBalanced(t)
    }

    @Test
    fun modelIgnoringTheCapIsClosedWithoutLooping() = runBlocking {
        val model = FakeModel(calls("a"), calls("b"), calls("c"))
        val t = Transcript()
        val r = AgentLoop(maxSteps = 1).run(model, t, "go", {}) { c -> ToolOut(c.name, "done") }
        assertEquals(3, model.sent.size)
        assertEquals("Stopped after 1 tool steps.", r.answer)
        assertTrue(r.capped)
        assertEquals(1, model.invalidated)
        assertEquals(Entry.Tool(listOf(ToolOut("c", AgentLoop.NOT_RUN))), t.turns.single().entries.last { it is Entry.Tool })
        assertFalse(t.open)
        assertEquals(Entry.Model("Stopped after 1 tool steps."), t.last)
        assertBalanced(t)
    }

    @Test
    fun zeroStepsStillAnswers() = runBlocking {
        val model = FakeModel(calls("a"), ModelReply("Can't run tools now."))
        val t = Transcript()
        val r = AgentLoop(maxSteps = 0).run(model, t, "go", {}) { error("must not run") }
        assertEquals(LoopResult("Can't run tools now.", 0, true), r)
        assertBalanced(t)
    }

    @Test
    fun stopDuringToolLeavesAReplayableTranscript() = runBlocking {
        val model = FakeModel(calls("slow", "other"))
        val t = Transcript()
        try {
            AgentLoop().run(model, t, "go", {}) { throw CancellationException("stop") }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertTrue(t.open)
        t.closeTurn("Stopped by the user.", "")
        assertBalanced(t)
        // The next turn starts cleanly.
        val next = FakeModel(ModelReply("ok"))
        AgentLoop().run(next, t, "again", {}) { error("no tools") }
        assertEquals(2, t.turns.size)
        assertBalanced(t)
    }

    @Test
    fun stopDuringGenerationKeepsPartialText() = runBlocking {
        val model = FakeModel().apply { failOnSend = CancellationException("stop") }
        val t = Transcript()
        try {
            AgentLoop().run(model, t, "write", {}) { error("no tools") }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        t.closeTurn("Stopped by the user.", "Once upon (stopped)")
        assertEquals(listOf(Entry.User("write"), Entry.Model("Once upon (stopped)")), t.turns.single().entries)
    }

    @Test
    fun sendFailureMidTurnCanBeClosed() = runBlocking {
        val model = object : ModelChat {
            var n = 0

            override suspend fun send(input: Entry, onText: (String) -> Unit): ModelReply =
                if (n++ == 0) calls("a") else throw IllegalStateException("engine error")

            override fun invalidate() {}
        }
        val t = Transcript()
        try {
            AgentLoop().run(model, t, "go", {}) { c -> ToolOut(c.name, "done") }
            fail("expected failure")
        } catch (e: IllegalStateException) {
            assertEquals("engine error", e.message)
        }
        t.closeTurn("Failed: engine error", "")
        assertBalanced(t)
    }

    @Test
    fun streamedTextReachesTheCallback() = runBlocking {
        val seen = mutableListOf<String>()
        AgentLoop().run(FakeModel(ModelReply("streamed")), Transcript(), "hi", { seen += it }) { error("no tools") }
        assertEquals(listOf("streamed"), seen)
    }
}
