package com.mysi.agent.skills

import android.content.Context
import com.mysi.agent.Param
import com.mysi.agent.Skill
import com.mysi.agent.ToolDef
import com.mysi.agent.skills.core.MemoryBook
import com.mysi.agent.skills.core.MemoryItem

/** Facts the user asked MySI to keep. Every save and every deletion is approved by the user. */
object MemorySkill {
    private const val PAGE = 30

    val skill = Skill(
        name = "memory",
        description = "Remember facts the user wants kept, such as preferences, people and details, and recall them in later chats.",
        keywords = listOf(
            "remember", "memory", "memories", "forget", "recall", "you know", "my name", "i prefer", "my preference",
            "don't forget", "keep in mind", "last time", "my birthday", "my family",
        ),
        instructions = """
            Memory: save only what the user asks you to remember or clearly wants kept, one short fact per memory, written in the third person (for example "The user's daughter is called Asha"). The user approves every save.
            Before answering a question about the user's own life or preferences, call recall. Never invent memories; if recall finds nothing, say so.
        """.trimIndent(),
        tools = ::tools,
    )

    private fun book(ctx: Context) = MemoryBook(SqlMemoryStore(SkillDb.get(ctx)))

    /** All memories, newest first, for a Memory screen. */
    fun memories(ctx: Context, offset: Int = 0, limit: Int = 200): List<MemoryItem> = book(ctx).list(offset, limit)

    /** Edits a memory from a Memory screen; false when it no longer exists. */
    fun updateMemory(ctx: Context, id: Long, text: String, topic: String): Boolean = book(ctx).update(id, text, topic)

    /** Deletes a memory from a Memory screen; false when it no longer exists. */
    fun deleteMemory(ctx: Context, id: Long): Boolean = book(ctx).delete(id)

    private fun tools(ctx: Context): List<ToolDef> = listOf(
        ToolDef(
            "remember",
            "Save one fact the user wants MySI to keep for later chats; the user is asked to approve it. Example: remember(text='The user is vegetarian', topic='food').",
            listOf(
                Param("text", "string", "One short fact, in the third person"),
                Param("topic", "string", "One or two words, e.g. family, work, food", false),
            ),
            // Never null: every memory is shown to the user before it is saved.
            confirm = { a ->
                val text = a.str("text").trim().ifEmpty { "(empty)" }
                val topic = a.str("topic").trim()
                "Save this to MySI's memory on this phone?\n\n\"$text\"" + if (topic.isNotEmpty()) "\n\nTopic: $topic" else ""
            },
        ) { a ->
            SkillFiles.io {
                val saved = book(ctx).remember(a.str("text"), a.str("topic"))
                if (saved.duplicate) "Already remembered as memory #${saved.id}." else "Saved as memory #${saved.id}."
            }
        },
        ToolDef(
            "recall",
            "Look up saved memories about the user that match a question. Example: recall(query='daughter school').",
            listOf(
                Param("query", "string", "What to look for"),
                Param("count", "integer", "How many, 1-20. Default 5", false),
            ),
            privateData = true,
        ) { a ->
            SkillFiles.io {
                val found = book(ctx).recall(a.str("query"), a.int("count", 5))
                if (found.isEmpty()) "No saved memory matches '${a.str("query").trim()}'." else MemoryBook.format(found)
            }
        },
        ToolDef(
            "list_memories",
            "List the memories MySI has saved, newest first, $PAGE at a time with their numbers. Use offset to see more.",
            listOf(Param("offset", "integer", "How many to skip. Default 0", false)),
            privateData = true,
        ) { a ->
            SkillFiles.io {
                val b = book(ctx)
                val total = b.count()
                val offset = a.int("offset", 0).coerceAtLeast(0)
                val page = b.list(offset, PAGE)
                when {
                    total == 0 -> "No memories saved yet."
                    page.isEmpty() -> "There are only $total memories."
                    else -> "Memories ${offset + 1}-${offset + page.size} of $total:\n" + MemoryBook.format(page) +
                        if (offset + page.size < total) "\n[More: list_memories(offset=${offset + page.size})]" else ""
                }
            }
        },
        ToolDef(
            "forget",
            "Delete one saved memory by its number from recall or list_memories; the user is asked to approve it. Example: forget(id=12).",
            listOf(Param("id", "integer", "Memory number")),
            // Never null: deleting a memory always asks first.
            confirm = { a ->
                val id = a.int("id", -1).toLong()
                val m = runCatching { book(ctx).get(id) }.getOrNull()
                if (m != null) "Forget this memory?\n\n\"${m.text}\"" else "Forget memory #$id?"
            },
        ) { a ->
            SkillFiles.io {
                val id = a.int("id", -1).toLong()
                if (book(ctx).delete(id)) "Forgot memory #$id." else "No memory #$id. Use list_memories to see the numbers."
            }
        },
    )
}
