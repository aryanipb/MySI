package com.mysi.agent.skills.core

import com.mysi.agent.ResultStore
import com.mysi.agent.web.Article
import com.mysi.agent.web.ResearchClaim
import com.mysi.agent.web.ResearchDepth
import com.mysi.agent.web.ResearchReport
import com.mysi.agent.web.ResearchSource
import com.mysi.agent.web.WebService
import java.io.File
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One read-more mechanism per result: tools that page themselves (documents, knowledge base,
 * data, memory, planner, web) must stay within ResultStore.PAGED_TOOL_CHARS at their worst, so
 * the runtime never cuts them and never adds a read_result handle next to their own paging hint.
 */
class ResultBudgetTest {
    private val words = "Monsoon rainfall in Kerala reached record levels this year according to the weather department. "

    private fun text(n: Int) = words.repeat(n / words.length + 1).take(n)

    private fun assertWhole(tool: String, result: String) {
        assertTrue("$tool: ${result.length} chars", result.length <= ResultStore.PAGED_TOOL_CHARS)
        val shown = ResultStore().present(tool, result)
        assertEquals(tool, result, shown.text)
        assertFalse(tool, shown.text.contains("[result id="))
    }

    @Test
    fun theBudgetsAreDerivedFromTheRuntimeExcerpt() {
        assertTrue(ResultStore.PAGED_TOOL_CHARS < ResultStore.EXCERPT_CHARS)
        assertEquals(ResultStore.PAGED_TOOL_CHARS, Budget.MAX)
        assertEquals(ResultStore.PAGED_TOOL_CHARS, WebService.OUTPUT_BUDGET)
        assertTrue(Budget.CHARS < Budget.MAX && Documents.PAGE_CHARS < Budget.MAX)
    }

    @Test
    fun readDocumentAtItsLongest() {
        val doc = ParsedDoc(DocType.PDF, "Report", listOf(Part("page 1", text(50_000)), Part("page 2", "x")))
        val name = "a-very-long-file-name-".repeat(10) + ".pdf"
        assertWhole("read_document", Documents.read(name, doc, 1, 0))
        assertWhole("read_document", Documents.read(name, doc, 1, 20_000))
    }

    @Test
    fun kbSearchAtItsMaximumCountWithLongNames() {
        val passages = (1..10).map { KnowledgeBase.Passage("handbook-${"x".repeat(200)}-$it.pdf", "page $it of a very long location label ${"y".repeat(80)}", text(2_000), 1.0) }
        for (n in 1..10) assertWhole("kb_search", KnowledgeBase.format(passages.take(n)))
        assertTrue(KnowledgeBase.format(passages).contains("weaker passages left out"))
    }

    @Test
    fun kbListMemoryAndPlannerListsAtTheirLongest() {
        val kb = KnowledgeBase(FakeKbStore())
        repeat(300) { kb.add("document number $it with a long descriptive file name.pdf", "/x/$it.pdf", DocType.PDF, listOf(Part("page 1", "alpha beta $it"))) }
        assertWhole("kb_list", kb.listText(0, ZoneId.of("UTC")))
        val book = MemoryBook(FakeMemoryStore())
        repeat(200) { book.remember("The user's preference number $it about monsoon travel is " + "z".repeat(400), "travel") }
        assertWhole("list_memories", book.page(0))
        assertWhole("recall", book.recallText("monsoon travel preference", 20))
        val planner = Planner(FakePlannerStore())
        repeat(300) { planner.addTask("Pay the electricity bill and the water bill for flat number $it", "2026-11-20") }
        assertWhole("list_tasks", planner.listTasks("open", 0))
        repeat(20) { planner.addNote("Monsoon note $it ${"t".repeat(110)}", text(20_000)) }
        assertWhole("search_notes", planner.searchNotes("monsoon"))
        assertWhole("search_notes", planner.searchNotes(""))
    }

    @Test
    fun tableToolsAtTheirLongest() {
        val dir = TestFiles.tempDir()
        val wide = File(dir, "wide.csv").apply {
            writeText((0 until 600).joinToString(",") { "Column number $it" } + "\n" + (1..50).joinToString("\n") { r -> (0 until 600).joinToString(",") { "$r$it" } })
        }
        val long = File(dir, "long.csv").apply {
            writeText("Region,Month,Amount,Note\n" + (1..5_000).joinToString("\n") { "Region ${it % 40},Month ${it % 12},$it,${"note ".repeat(30)}" })
        }
        for (f in listOf(wide, long)) {
            val t = TableLoader.load(f, null)
            assertWhole("table_info", Stats.info(t))
            assertWhole("table_query", TableQuery.format(TableQuery.run(t, "", "", "", "", "", TableQuery.MAX_LIMIT), false))
            assertWhole("table_stats", Stats.describe(t, t.columns[0]))
        }
        val t = TableLoader.load(long, null)
        assertWhole("table_query", TableQuery.format(TableQuery.run(t, "", "", "Region, Month", "sum(Amount), count(*)", "", TableQuery.MAX_LIMIT), true))
    }

    @Test
    fun webResultsAtTheirLongest() {
        val web = WebService(com.mysi.agent.web.FakeWeb().client(), null)
        val article = Article("https://example.org/" + "p".repeat(400), "T".repeat(300), "B".repeat(200), "2026-11-11", "S".repeat(100), null, text(60_000), true)
        for (offset in listOf(0, 10_000)) assertWhole("read_web_page", web.formatArticle(article, offset, WebService.OUTPUT_BUDGET, "read_web_page"))
    }

    @Test
    fun deepResearchAnswerAtItsLongest() {
        val sources = (1..15).map { ResearchSource(it, "https://source$it.example/" + "a".repeat(150), "Title ".repeat(30), "2026-01-01") }
        val claims = (1..120).map { ResearchClaim(it % 6, "Finding $it: " + text(300), text(200), sources[it % 15].url, true, null) }
        val report = ResearchReport("q".repeat(300), ResearchDepth.DEEP, "2026-11-11", (1..6).map { "sub $it" }, text(1_500), claims, claims.take(20), sources, 15, emptyList(), 8)
        // The tool's answer: where the report was saved, then the brief, as WebSkill builds it.
        val where = "Full report with quotes and all sources saved to Research/2026-11-11-${"q".repeat(60)}.md (read it with the document tools)."
        assertWhole("deep_research", where + "\n" + report.brief(WebService.OUTPUT_BUDGET - where.length - 1))
    }
}
