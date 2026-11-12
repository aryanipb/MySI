package com.mysi.agent

/**
 * Full tool results for the current chat, kept in memory so the model only ever sees short
 * excerpts and can page through the rest with read_result. Cleared on New chat and model load,
 * together with the chat's private-data flag. Oldest results are dropped once [maxEntries] or
 * [maxChars] is exceeded.
 */
class ResultStore(
    private val maxEntries: Int = 100,
    private val maxChars: Int = 2_000_000,
    private val maxResultChars: Int = 400_000,
) {
    private val results = LinkedHashMap<Int, String>()
    private var nextId = 1
    private var chars = 0

    @Synchronized
    fun put(text: String): Int {
        val t = text.take(maxResultChars)
        val id = nextId++
        results[id] = t
        chars += t.length
        while (results.size > maxEntries || (chars > maxChars && results.size > 1)) {
            val oldest = results.keys.first()
            chars -= results.remove(oldest)!!.length
        }
        return id
    }

    @Synchronized
    fun get(id: Int): String? = results[id]

    @Synchronized
    fun clear() {
        results.clear()
        chars = 0
    }

    /** What the model sees for a tool's [result]: the text itself, or an excerpt with a handle. */
    fun present(name: String, result: String): ToolOut {
        if (result.length <= STUB_CHARS) return ToolOut(name, result)
        val id = put(result)
        val text = if (result.length <= EXCERPT_CHARS) result else result.take(EXCERPT_CHARS) + "\n" + handle(id, result.length - EXCERPT_CHARS, EXCERPT_CHARS)
        val stub = result.take(STUB_CHARS) + "\n" + handle(id, result.length - STUB_CHARS, STUB_CHARS)
        return ToolOut(name, text, stub)
    }

    /** The read_result tool: [length] characters of result [id] from [offset], with a pointer to the rest. */
    fun read(id: Int, offset: Int, length: Int = PAGE_CHARS): String {
        val full = get(id) ?: return "No result with id=$id in this chat. It may have been cleared; run the tool again."
        if (offset < 0 || offset >= full.length) return "Result $id has ${full.length} characters; offset must be from 0 to ${full.length - 1}."
        val end = minOf(full.length, offset + length)
        val page = full.substring(offset, end)
        return page + "\n" + if (end < full.length) handle(id, full.length - end, end) else "[end of result id=$id]"
    }

    companion object {
        /** Results up to this long are given whole and never stored. */
        const val STUB_CHARS = 300

        /**
         * The most of a result the model sees in the turn the tool ran.
         *
         * Tools that page their own output (documents, knowledge base, data, memory, planner,
         * web) keep every result within [PAGED_TOOL_CHARS], so the turn they run in shows the
         * whole result with only their own "page=" or "offset=" hint for continuing. Tools that do
         * not page (list_apps, read_text_file…) are cut here, with a read_result handle. Separately,
         * any result over [STUB_CHARS] is replayed as a stub in later steps and turns (see
         * ContextManager); read_result shows that earlier result again.
         *
         * Window check: 3,500 characters are about 875 tokens of English, or 1,750 of Hindi or
         * Tamil at Tokens' two characters per token. Three such results in one step, plus about
         * 5,000 tokens of system prompt and tool schemas and the 1,536-token output reserve, stay
         * under the 16,384-token window; past that, the conversation is rebuilt with the older
         * results as stubs (see ContextManager.fitOpen).
         */
        const val EXCERPT_CHARS = 3_500

        /** The budget for a self-paging tool's whole result, hints included; a margin under [EXCERPT_CHARS]. */
        const val PAGED_TOOL_CHARS = EXCERPT_CHARS - 200
        const val PAGE_CHARS = 2_500

        fun handle(id: Int, more: Int, offset: Int) = "[result id=$id, $more more chars; call read_result(id=$id, offset=$offset)]"
    }
}
