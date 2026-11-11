package com.mysi.agent.skills.core

import com.mysi.agent.search.Ranker
import com.mysi.agent.search.Scored
import com.mysi.agent.skills.core.TestFiles.fixture
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class StoresTest {
    @Before
    fun pdf() = TestFiles.installPdfBoxAssets()

    private fun parts(f: java.io.File) = Documents.open(f, null).use { d -> (0 until d.partCount).map { d.part(it) } }

    // ---------- knowledge base ----------

    @Test
    fun knowledgeBaseIndexesSearchesAndQuotesSources() {
        val kb = KnowledgeBase(FakeKbStore()) { 1_000L }
        val a = kb.add("plan.pdf", "/p/plan.pdf", DocType.PDF, parts(fixture("plan.pdf")))
        assertEquals(3, a.chunks)
        assertFalse(a.replaced)
        kb.add("report.docx", "/p/report.docx", DocType.DOCX, parts(fixture("report.docx")))
        kb.add("deck.pptx", "/p/deck.pptx", DocType.PPTX, parts(fixture("deck.pptx")))
        val hits = kb.search("monsoon launch delay", 5)
        assertEquals("plan.pdf", hits.first().document)
        assertEquals("page 3", hits.first().location)
        val nitrate = kb.search("nitrate wells", 3).first()
        assertEquals("report.docx", nitrate.document)
        assertTrue(nitrate.text.contains("Nitrate levels exceeded"))
        assertEquals("slide 1", kb.search("new hires", 3).first().location)
        val text = KnowledgeBase.format(hits)
        assertTrue(text, text.startsWith("[1] plan.pdf, page 3:\n"))
        assertTrue(kb.search("xylophone", 5).isEmpty())
        assertTrue(KnowledgeBase.format(emptyList()).startsWith("No passages match"))
    }

    @Test
    fun knowledgeBaseReplacesAndRemoves() {
        val store = FakeKbStore()
        val kb = KnowledgeBase(store)
        kb.add("notes.txt", "/a", DocType.TEXT, listOf(Part("part 1", "The wifi password is in the drawer.")))
        val again = kb.add("notes.txt", "/a", DocType.TEXT, listOf(Part("part 1", "Moved: the router sits in the hall.")))
        assertTrue(again.replaced)
        assertEquals(1, kb.list().size)
        assertTrue(kb.search("drawer", 3).isEmpty())
        assertEquals("notes.txt", kb.search("router", 3).single().document)
        kb.add("Notes Archive.md", "/b", DocType.MARKDOWN, listOf(Part("part 1", "old stuff")))
        assertNull(kb.remove("notes"))
        assertEquals("Notes Archive.md", kb.remove("archive"))
        assertEquals("notes.txt", kb.remove("NOTES.TXT"))
        assertTrue(kb.list().isEmpty())
        assertEquals(0, store.totalChunks())
    }

    @Test
    fun knowledgeBaseRefusesEmptyAndOversizedDocuments() {
        val kb = KnowledgeBase(FakeKbStore())
        try {
            kb.add("scan.pdf", "/s", DocType.PDF, listOf(Part("page 1", "  ")))
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("no text to index"))
        }
        val huge = (1..5).map { Part("page $it", "word ".repeat(500_000)) }
        try {
            kb.add("huge.txt", "/h", DocType.TEXT, huge)
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("too long"))
        }
    }

    @Test
    fun rankerCanBeSwappedForAnotherImplementation() {
        val store = FakeKbStore()
        val kb = KnowledgeBase(store)
        kb.add("a.txt", "/a", DocType.TEXT, listOf(Part("part 1", "alpha")))
        kb.add("b.txt", "/b", DocType.TEXT, listOf(Part("part 1", "beta")))
        kb.ranker = object : Ranker {
            override fun rank(query: String, limit: Int) = listOf(Scored(2, 1.0), Scored(1, 0.5))
        }
        assertEquals(listOf("b.txt", "a.txt"), kb.search("anything", 5).map { it.document })
    }

    // ---------- result budgets (the runtime cuts results near 3,500 characters) ----------

    private val cap = 3_500

    /** Follows every "[More: ...(offset=N)]" link and returns all pages. */
    private fun pages(first: String, next: (Int) -> String): List<String> {
        val out = mutableListOf(first)
        while (true) {
            val m = Regex("offset=(\\d+)\\)]$").find(out.last()) ?: return out
            out += next(m.groupValues[1].toInt())
        }
    }

    @Test
    fun memoryListsPageWithinTheCapWithoutSkippingAnything() {
        val book = MemoryBook(FakeMemoryStore())
        repeat(100) { i -> book.remember("Fact $i: " + "x".repeat(MemoryBook.MAX_CHARS - 10), "topic$i") }
        val all = pages(book.page(0)) { book.page(it) }
        assertTrue(all.size > 3)
        for (p in all) assertTrue("${p.length}", p.length <= cap)
        val ids = all.flatMap { p -> Regex("^#(\\d+) ", RegexOption.MULTILINE).findAll(p).map { it.groupValues[1].toInt() }.toList() }
        assertEquals((1..100).toSet(), ids.toSet())
        assertEquals(100, ids.size)
        val recall = book.recallText("fact", 20)
        assertTrue("${recall.length}", recall.length <= cap)
        assertTrue(recall, recall.endsWith("more matches not shown; ask a narrower question.)"))
    }

    @Test
    fun taskListsPageWithinTheCap() {
        val p = Planner(FakePlannerStore(), { zone }, { clockMs })
        repeat(120) { i -> p.addTask("Task $i " + "y".repeat(180), "2026-12-${(i % 28 + 1).toString().padStart(2, '0')} 10:00") }
        val all = pages(p.listTasks("open")) { p.listTasks("open", it) }
        assertTrue(all.size > 3)
        for (page in all) assertTrue("${page.length}", page.length <= cap)
        assertEquals(120, all.sumOf { page -> page.lines().count { it.startsWith("#") } })
        assertTrue(all.first().lines().last().startsWith("[More: list_tasks(status=open, offset="))
        assertTrue(p.listTasks("open", 999).startsWith("There are only 120 open tasks"))
    }

    @Test
    fun knowledgeBaseListPagesWithinTheCap() {
        val kb = KnowledgeBase(FakeKbStore()) { clockMs }
        repeat(150) { i -> kb.add("Document $i " + "z".repeat(100) + ".pdf", "/d/$i", DocType.PDF, listOf(Part("page 1", "text $i"))) }
        val all = pages(kb.listText(0, zone)) { kb.listText(it, zone) }
        assertTrue(all.size > 3)
        for (page in all) assertTrue("${page.length}", page.length <= cap)
        assertEquals(150, all.sumOf { page -> page.lines().count { it.startsWith("- Document") } })
        assertTrue(all.first(), all.first().contains("added 2026-11-11"))
        assertEquals("The knowledge base is empty. Add files with kb_add.", KnowledgeBase(FakeKbStore()).listText(0, zone))
    }

    // ---------- memory ----------

    @Test
    fun memoriesAreSavedRecalledAndDeduplicated() {
        var now = 100L
        val book = MemoryBook(FakeMemoryStore()) { now++ }
        val a = book.remember("  The user's daughter   is called Asha. ", "family")
        assertFalse(a.duplicate)
        book.remember("The user is vegetarian and avoids eggs", "food")
        book.remember("The user works at Finch Labs in Pune", "")
        val dup = book.remember("the user's daughter is called asha.", "kids")
        assertTrue(dup.duplicate)
        assertEquals(a.id, dup.id)
        assertEquals(3, book.count())
        assertEquals("The user's daughter is called Asha.", book.recall("what is my daughter's name").first().text)
        assertEquals("food", book.recall("eggs", 5).single().topic)
        assertEquals("general", book.recall("Pune office").single().topic)
        assertTrue(book.recall("astronomy").isEmpty())
        // Newest change first; the duplicate save refreshed Asha's memory.
        assertEquals(a.id, book.list().first().id)
        assertEquals("#${a.id} [kids] The user's daughter is called Asha.", MemoryBook.format(listOf(book.get(a.id)!!)))
    }

    @Test
    fun memoryScreenFunctionsEditAndDelete() {
        val book = MemoryBook(FakeMemoryStore())
        val id = book.remember("Likes tea", "food").id
        assertTrue(book.update(id, "Likes green tea", "drinks"))
        assertEquals("Likes green tea", book.get(id)!!.text)
        assertFalse(book.update(999, "x", ""))
        assertTrue(book.delete(id))
        assertFalse(book.delete(id))
        assertEquals(0, book.list().size)
    }

    @Test
    fun memoriesMustBeShortAndNonEmpty() {
        val book = MemoryBook(FakeMemoryStore())
        for (bad in listOf("", "   ", "x".repeat(MemoryBook.MAX_CHARS + 1))) {
            try {
                book.remember(bad, "")
                fail("accepted '$bad'")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        val page = (1..40).map { book.remember("fact $it", "") }
        assertEquals(30, book.list(0, 30).size)
        assertEquals(10, book.list(30, 30).size)
        assertTrue(page.isNotEmpty())
    }

    // ---------- dates ----------

    private val now = LocalDateTime.of(2026, 11, 11, 14, 5) // a Wednesday

    private fun d(s: String) = DateText.parse(s, now)

    @Test
    fun parsesIsoAndNaturalDates() {
        assertEquals(DateText.When(LocalDate.of(2026, 11, 20), null), d("2026-11-20"))
        assertEquals(DateText.When(LocalDate.of(2026, 11, 20), LocalTime.of(17, 0)), d("2026-11-20 17:00"))
        assertEquals(DateText.When(LocalDate.of(2026, 11, 20), LocalTime.of(17, 30)), d("2026-11-20T17:30:00"))
        assertEquals(DateText.When(LocalDate.of(2026, 11, 12), LocalTime.of(9, 0)), d("tomorrow 9am"))
        assertEquals(DateText.When(LocalDate.of(2026, 11, 12), LocalTime.of(21, 15)), d("Tomorrow at 9:15 pm"))
        assertEquals(DateText.When(LocalDate.of(2026, 11, 11), LocalTime.of(20, 0)), d("tonight"))
        assertEquals(DateText.When(LocalDate.of(2026, 11, 11), LocalTime.of(22, 0)), d("tonight 10pm"))
        assertEquals(DateText.When(LocalDate.of(2026, 11, 11), LocalTime.NOON), d("today noon"))
        assertEquals(DateText.When(LocalDate.of(2026, 11, 13), null), d("day after tomorrow"))
        assertEquals(DateText.When(LocalDate.of(2026, 11, 13), null), d("Friday"))
        assertEquals(DateText.When(LocalDate.of(2026, 11, 18), LocalTime.of(10, 0)), d("next wed 10am"))
        assertEquals(DateText.When(LocalDate.of(2026, 11, 14), null), d("in 3 days"))
        assertEquals(DateText.When(LocalDate.of(2026, 11, 11), LocalTime.of(16, 5)), d("in 2 hours"))
        assertEquals(DateText.When(LocalDate.of(2026, 12, 25), null), d("25 Dec"))
        assertEquals(DateText.When(LocalDate.of(2027, 1, 5), LocalTime.of(18, 30)), d("January 5th at 6:30 pm"))
        assertEquals(DateText.When(LocalDate.of(2026, 3, 1), null), d("1st March 2026"))
        assertEquals(DateText.When(LocalDate.of(2026, 11, 11), LocalTime.of(0, 30)), d("12:30 am"))
    }

    @Test
    fun ambiguousOrNonsenseDatesAreRefused() {
        for (bad in listOf("03/04", "12/11/2026", "someday", "2026-02-30", "25 Smarch", "13pm", "")) {
            try {
                d(bad)
                fail("accepted '$bad'")
            } catch (e: DateException) {
                assertTrue(e.message!!, e.message!!.isNotBlank())
            }
        }
    }

    // ---------- planner ----------

    private val zone = ZoneId.of("Asia/Kolkata")
    private val clockMs = now.atZone(zone).toInstant().toEpochMilli()

    @Test
    fun tasksAreAddedListedByDueDateAndCompleted() {
        val p = Planner(FakePlannerStore(), { zone }, { clockMs })
        assertEquals("Task #1 added: Pay electricity bill (due 2026-11-20)", p.addTask("Pay   electricity bill", "2026-11-20"))
        p.addTask("Call plumber", "2026-11-11 09:00")
        p.addTask("Read book", "")
        p.addTask("Submit form", "tomorrow 5pm")
        val open = p.listTasks("").lines()
        assertEquals("#2 Call plumber (due 2026-11-11 09:00, OVERDUE)", open[0])
        assertEquals("#4 Submit form (due 2026-11-12 17:00)", open[1])
        assertEquals("#1 Pay electricity bill (due 2026-11-20)", open[2])
        assertEquals("#3 Read book", open[3])
        assertEquals("Done: #2 Call plumber", p.complete(2))
        assertTrue(p.complete(2).contains("already done"))
        assertTrue(p.complete(42).contains("No task #42"))
        assertEquals("#2 [done] Call plumber (due 2026-11-11 09:00)", p.listTasks("done"))
        assertEquals(4, p.listTasks("all").lines().size)
        try {
            p.addTask("  ", "")
            fail()
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun notesAreSavedAndSearched() {
        val p = Planner(FakePlannerStore(), { zone }, { clockMs })
        assertEquals("No notes yet.", p.searchNotes("x"))
        assertEquals("Note #1 saved: Gift ideas", p.addNote("Gift ideas", "Book for Ravi; plant for Meena"))
        p.addNote("", "Recipe: dal tadka with ghee\nSoak lentils first")
        p.addNote("Trip", "Train to Goa on Friday")
        assertTrue(p.searchNotes("plants").startsWith("#1 Gift ideas (2026-11-11)"))
        assertTrue(p.searchNotes("lentils"), p.searchNotes("lentils").startsWith("#2 Recipe: dal tadka with ghee"))
        assertTrue(p.searchNotes("submarine").startsWith("No notes match"))
        assertEquals(3, Regex("^#\\d", RegexOption.MULTILINE).findAll(p.searchNotes("")).count())
    }

    @Test
    fun calendarTimesCoverTimedAllDayAndRelativeEnds() {
        val p = Planner(FakePlannerStore(), { zone }, { clockMs })
        val start = LocalDateTime.of(2026, 11, 14, 10, 30).atZone(zone).toInstant().toEpochMilli()
        val timed = p.eventTimes("2026-11-14 10:30", "11:15")
        assertEquals(start, timed.start)
        assertEquals(start + 45 * 60_000, timed.end)
        assertFalse(timed.allDay)
        assertEquals("2026-11-14 10:30 to 11:15", timed.description)
        assertEquals(start + 60 * 60_000, p.eventTimes("2026-11-14 10:30", "").end)
        assertEquals(start + 2 * 60 * 60_000, p.eventTimes("2026-11-14 10:30", "in 2 hours").end)
        val allDay = p.eventTimes("2026-11-14", "2026-11-15")
        assertTrue(allDay.allDay)
        // All-day events sit on UTC midnight boundaries, as CalendarContract stores them.
        assertEquals(LocalDate.of(2026, 11, 14).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(), allDay.start)
        assertEquals(LocalDate.of(2026, 11, 16).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(), allDay.end)
        assertEquals("2026-11-14 to 2026-11-15 (all day)", allDay.description)
        for ((s, e) in listOf("2026-11-14 10:30" to "09:00", "2026-11-14" to "2026-11-13", "" to "")) {
            try {
                p.eventTimes(s, e)
                fail("accepted $s .. $e")
            } catch (x: IllegalArgumentException) {
                // expected
            }
        }
    }
}
