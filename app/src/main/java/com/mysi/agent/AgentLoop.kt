package com.mysi.agent

/** A model reply: its text and any tool calls it wants run. */
data class ModelReply(val text: String, val calls: List<Call> = emptyList())

/** The chat model as the tool loop sees it, so the loop can be tested with a scripted fake. */
interface ModelChat {
    /**
     * Sends [input] and returns the model's complete reply, streaming its text so far through
     * [onText]. The caller has already added [input] as the last entry of the transcript, so an
     * implementation that rebuilds its conversation replays everything before it.
     */
    suspend fun send(input: Entry, onText: (String) -> Unit): ModelReply

    /** The live conversation no longer matches the transcript; rebuild it before the next send. */
    fun invalidate()
}

data class LoopResult(val answer: String, val steps: Int, val capped: Boolean)

/**
 * The tool-calling loop. Runs at most [maxSteps] rounds of tool calls. If the model still asks for
 * tools after that, every pending call gets [STEP_LIMIT] as its response and the model gets one
 * final turn to answer; calls in that final reply are closed in the transcript without running,
 * so no call is ever left without a response.
 */
class AgentLoop(private val maxSteps: Int = DEFAULT_MAX_STEPS) {
    init {
        require(maxSteps >= 0)
    }

    suspend fun run(
        chat: ModelChat,
        transcript: Transcript,
        request: String,
        onText: (String) -> Unit,
        execute: suspend (Call) -> ToolOut,
    ): LoopResult {
        transcript.begin(request)
        var reply = send(chat, transcript, Entry.User(request), onText, recorded = true)
        var steps = 0
        var capped = false
        while (reply.calls.isNotEmpty()) {
            if (capped) {
                transcript.add(Entry.Tool(reply.calls.map { ToolOut(it.name, NOT_RUN) }))
                // The live conversation still holds these calls unanswered.
                chat.invalidate()
                break
            }
            val outs =
                if (steps < maxSteps) {
                    steps++
                    reply.calls.map { execute(it) }
                } else {
                    capped = true
                    reply.calls.map { ToolOut(it.name, STEP_LIMIT) }
                }
            reply = send(chat, transcript, Entry.Tool(outs), onText)
        }
        val answer = reply.text.trim().ifEmpty { if (capped) "Stopped after $maxSteps tool steps." else "Done." }
        // Only when the model ignored the cap: the turn must still end with an answer.
        if (transcript.last !is Entry.Model) transcript.add(Entry.Model(answer))
        transcript.finish()
        return LoopResult(answer, steps, capped)
    }

    /** [recorded] is true when [input] is already the transcript's last entry (the turn's request). */
    private suspend fun send(chat: ModelChat, transcript: Transcript, input: Entry, onText: (String) -> Unit, recorded: Boolean = false): ModelReply {
        if (!recorded) transcript.add(input)
        val reply = chat.send(input, onText)
        transcript.add(Entry.Model(reply.text, reply.calls))
        return reply
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 12
        const val STEP_LIMIT = "Step limit reached; this tool was not run. Answer the user now with what you have."
        const val NOT_RUN = "Not run: the step limit was reached."
    }
}
