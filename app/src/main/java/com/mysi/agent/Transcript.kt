package com.mysi.agent

/**
 * Rough token counts for budgeting the model's 16k-token window without running the tokenizer.
 * Latin text averages about four characters per token; other scripts (Devanagari, Tamil, CJK…)
 * tokenise far more densely, so they are counted at two characters per token. Both are
 * approximations, which is why callers keep a wide margin.
 */
object Tokens {
    fun estimate(s: String): Int {
        if (s.isEmpty()) return 0
        var ascii = 0
        var other = 0
        for (c in s) if (c.code < 128) ascii++ else other++
        return (ascii + 3) / 4 + (other + 1) / 2
    }

    fun estimate(entries: List<Entry>): Int = entries.sumOf { estimate(it) }

    fun estimate(e: Entry): Int =
        // A few tokens per message for the chat template's role markers.
        4 + when (e) {
            is Entry.User -> estimate(e.text)
            is Entry.Model -> estimate(e.text) + e.calls.sumOf { estimate(it.name) + estimate(it.args.toString()) + 4 }
            is Entry.Tool -> e.outs.sumOf { estimate(it.name) + estimate(it.text) + 4 }
        }
}

/** A tool call made by the model. */
data class Call(val name: String, val args: Map<String, Any?>)

/**
 * A tool's answer as the model sees it. [text] is what the model gets in the turn the tool ran;
 * [stub] is the shorter form replayed in later turns, or when the window is tight. Both carry the
 * result handle when the full text is in the [ResultStore].
 */
data class ToolOut(val name: String, val text: String, val stub: String = text)

sealed class Entry {
    data class User(val text: String) : Entry()

    data class Model(val text: String, val calls: List<Call> = emptyList()) : Entry()

    /** The responses to the previous [Model] entry's calls, one per call, in order. */
    data class Tool(val outs: List<ToolOut>) : Entry()
}

/**
 * One request and everything it led to: the user's message, then model messages and tool
 * responses. [summary] is the hook for a model-written summary of the turn; when it is set, it
 * replaces the plain truncation summary once the turn is too old to replay verbatim.
 */
class Turn(first: Entry.User) {
    val entries = mutableListOf<Entry>(first)
    @Volatile var summary: String? = null
    val request get() = (entries.first() as Entry.User).text
}

/**
 * The app-side record of the chat and the single source of truth for it. The LiteRT conversation
 * is a disposable cache that can be rebuilt from this at any time, so the record must always be
 * replayable: every model message with tool calls is followed by exactly one response per call
 * before a rebuild or a new turn can start (see [closeTurn]).
 */
class Transcript(private val maxTurns: Int = 60, private val summarizer: Summarizer = TruncatingSummarizer) {
    val turns = mutableListOf<Turn>()

    /** Summary of turns dropped from memory because the chat grew past [maxTurns]. */
    var earlier = ""
        private set

    /** True while the last turn is still running. */
    var open = false
        private set

    val size get() = turns.sumOf { it.entries.size }

    fun begin(request: String) {
        closeTurn("Not run: the request ended.", "")
        turns += Turn(Entry.User(request))
        open = true
        if (turns.size > maxTurns) {
            val dropped = turns.subList(0, turns.size - maxTurns)
            earlier = listOf(earlier, summarizer.summarize(dropped.toList(), 300)).filter { it.isNotBlank() }.joinToString("\n")
                .let { if (Tokens.estimate(it) > 400) it.takeLast(1600) else it }
            dropped.clear()
        }
    }

    fun add(e: Entry) {
        check(open) { "No turn is open." }
        turns.last().entries += e
    }

    val last: Entry? get() = turns.lastOrNull()?.entries?.lastOrNull()

    /**
     * Ends the open turn so it can be replayed: unanswered calls get [note] as their response and,
     * if the model never answered, [partial] (or a short marker) becomes its final message.
     */
    fun closeTurn(note: String, partial: String) {
        if (!open) return
        val t = turns.last()
        val tail = t.entries.last()
        if (tail is Entry.Model && tail.calls.isNotEmpty()) t.entries += Entry.Tool(tail.calls.map { ToolOut(it.name, note) })
        val end = t.entries.last()
        if (end !is Entry.Model) t.entries += Entry.Model(partial.ifBlank { "(No answer: $note)" })
        open = false
    }

    /** Marks the open turn as answered normally. */
    fun finish() {
        open = false
    }

    fun clear() {
        turns.clear()
        earlier = ""
        open = false
    }
}

/** Condenses old turns into a short note for the system prompt. */
fun interface Summarizer {
    fun summarize(turns: List<Turn>, maxTokens: Int): String
}

/**
 * Keeps the start of each old exchange: the request, the final answer and which tools ran. Tool
 * output is deliberately left out: the summary lives in the system prompt, and web or file text
 * there would gain the authority of system instructions.
 */
object TruncatingSummarizer : Summarizer {
    override fun summarize(turns: List<Turn>, maxTokens: Int): String {
        if (turns.isEmpty() || maxTokens <= 0) return ""
        val lines = turns.map { t ->
            t.summary?.let { return@map "- $it" }
            val answer = (t.entries.lastOrNull { it is Entry.Model } as Entry.Model?)?.text.orEmpty()
            val tools = t.entries.filterIsInstance<Entry.Model>().flatMap { m -> m.calls.map { it.name } }.distinct()
            "- User asked: ${clip(t.request, 160)} | MySI answered: ${clip(answer, 200)}" +
                if (tools.isEmpty()) "" else " | tools used: ${tools.joinToString(", ")}"
        }
        // Newest first, so the most recent context survives when the budget runs out.
        val kept = ArrayDeque<String>()
        var used = 0
        for (line in lines.asReversed()) {
            val cost = Tokens.estimate(line) + 1
            if (used + cost > maxTokens) break
            kept.addFirst(line)
            used += cost
        }
        val omitted = lines.size - kept.size
        return (if (omitted > 0) "($omitted older exchanges omitted)\n" else "") + kept.joinToString("\n")
    }

    private fun clip(s: String, n: Int) = s.replace(Regex("\\s+"), " ").trim().let { if (it.length <= n) it else it.take(n) + "…" }
}

/** What to replay into a rebuilt conversation. */
data class Replay(val summary: String, val entries: List<Entry>, val tokens: Int)

/**
 * Chooses what fits in the window when a conversation is rebuilt. The open turn is always kept
 * whole (older tool results in it fall back to their stubs if it is too big); finished turns are
 * kept newest first with tool results as stubs, and the rest collapse into a running summary.
 * Turns are only ever cut at their boundaries, so tool calls and responses stay paired.
 */
class ContextManager(private val summarizer: Summarizer = TruncatingSummarizer, private val summaryTokens: Int = 400) {
    /**
     * [holdBack] leaves out the open turn's last entries: the message about to be sent, which the
     * caller sends itself after the rebuild.
     */
    fun select(transcript: Transcript, budget: Int, holdBack: Int = 0): Replay {
        val turns = transcript.turns
        val current = if (transcript.open) turns.lastOrNull() else null
        val done = if (current != null) turns.dropLast(1) else turns.toList()
        val currentEntries = current?.let { fitOpen(it.entries.dropLast(holdBack), budget) }.orEmpty()
        var used = Tokens.estimate(currentEntries)

        val compacted = done.map { compact(it.entries) }
        val all = compacted.sumOf { Tokens.estimate(it) }
        val needSummary = transcript.earlier.isNotBlank() || used + all > budget
        val room = budget - used - if (needSummary) summaryTokens else 0
        var keptFrom = done.size
        var keptTokens = 0
        for (i in done.indices.reversed()) {
            val cost = Tokens.estimate(compacted[i])
            if (keptTokens + cost > room) break
            keptTokens += cost
            keptFrom = i
        }
        used += keptTokens
        val collapsed = done.subList(0, keptFrom)
        val summary = listOf(transcript.earlier, summarizer.summarize(collapsed, summaryTokens - Tokens.estimate(transcript.earlier)))
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val entries = compacted.subList(keptFrom, compacted.size).flatten() + currentEntries
        return Replay(summary, entries, used + Tokens.estimate(summary))
    }

    /** Finished turns replay tool results as stubs; the full text stays reachable via read_result. */
    internal fun compact(entries: List<Entry>): List<Entry> =
        entries.map { e -> if (e is Entry.Tool) Entry.Tool(e.outs.map { it.copy(text = it.stub) }) else e }

    /** Stubs the open turn's tool results, oldest first, until it fits; the latest step stays whole. */
    internal fun fitOpen(entries: List<Entry>, budget: Int): List<Entry> {
        val out = entries.toMutableList()
        val toolIdx = out.indices.filter { out[it] is Entry.Tool }.dropLast(1)
        for (i in toolIdx) {
            if (Tokens.estimate(out) <= budget) break
            out[i] = Entry.Tool((out[i] as Entry.Tool).outs.map { it.copy(text = it.stub) })
        }
        return out
    }
}
