package com.mysi.agent.skills

import android.content.Context
import android.content.ContextWrapper
import com.mysi.agent.Args
import com.mysi.agent.Skill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillCatalogTest {
    /**
     * Building the tool lists only stores the Context in lambdas, so an uninitialised instance
     * is enough; nothing here touches the Android framework.
     */
    private val ctx: Context by lazy {
        val f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = f.get(null)
        unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, ContextWrapper::class.java) as Context
    }

    private val skills: List<Skill> = listOf(DocumentsSkill.skill, KnowledgeSkill.skill, DataSkill.skill, MemorySkill.skill, PlannerSkill.skill)

    private fun tools(s: Skill) = s.tools(ctx)

    @Test
    fun packsExposeExactlyTheseTools() {
        val expected = mapOf(
            "documents" to listOf("read_document", "document_info", "search_document", "create_docx", "create_xlsx", "create_csv", "create_markdown"),
            "knowledge_base" to listOf("kb_add", "kb_list", "kb_remove", "kb_search"),
            "data_analyst" to listOf("table_info", "table_query", "table_stats", "calculate"),
            "memory" to listOf("remember", "recall", "list_memories", "forget"),
            "planner" to listOf("add_task", "list_tasks", "complete_task", "add_note", "search_notes", "add_calendar_event"),
        )
        assertEquals(expected, skills.associate { s -> s.name to tools(s).map { it.name } })
        val all = skills.flatMap { tools(it) }.map { it.name }
        assertEquals(all.size, all.toSet().size)
        // create_pdf stays in the core tools; the packs must not duplicate it.
        assertFalse("create_pdf" in all)
    }

    @Test
    fun descriptionsAreShortPlainAndUseful() {
        for (s in skills) {
            assertTrue(s.name, s.description.length in 40..200)
            assertTrue(s.name, s.keywords.size >= 8)
            assertTrue(s.name, s.instructions.length in 100..900)
            for (t in tools(s)) {
                assertTrue("${t.name}: ${t.description.length}", t.description.length in 40..330)
                assertTrue(t.name, t.params.all { it.description.isNotBlank() })
            }
        }
    }

    @Test
    fun nothingIsOutboundAndReadersArePrivate() {
        val all = skills.flatMap { tools(it) }.associateBy { it.name }
        assertTrue(all.values.none { it.outbound })
        val readers = listOf("read_document", "document_info", "search_document", "kb_add", "kb_list", "kb_search", "table_info", "table_query", "table_stats", "recall", "list_memories", "list_tasks", "search_notes")
        for (r in readers) assertTrue(r, all.getValue(r).privateData)
        assertFalse(all.getValue("calculate").privateData)
    }

    @Test
    fun memoryChangesAlwaysAskTheUser() {
        val all = skills.flatMap { tools(it) }.associateBy { it.name }
        for (args in listOf(emptyMap(), mapOf("text" to ""), mapOf("text" to "The user likes tea", "topic" to "food"))) {
            val q = all.getValue("remember").confirm!!.invoke(Args(args))
            assertNotNull(q)
            assertTrue(q!!, q.startsWith("Save this to MySI's memory"))
        }
        assertTrue(all.getValue("remember").confirm!!.invoke(Args(mapOf("text" to "Likes tea", "topic" to "food")))!!.contains("Likes tea"))
        assertNotNull(all.getValue("forget").confirm!!.invoke(Args(mapOf("id" to 3))))
        assertNotNull(all.getValue("forget").confirm!!.invoke(Args(emptyMap())))
        assertNotNull(all.getValue("kb_remove").confirm!!.invoke(Args(mapOf("name" to "a.pdf"))))
    }
}
