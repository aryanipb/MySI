package com.mysi.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTest {
    private val ctx get() = TestContext.unsafe

    /** The real registry's routing text, built the way Agent builds it. */
    private val cards: List<SkillCard> by lazy {
        Skills.all.map { s -> s.tools(ctx).let { t -> SkillCard(s.name, s.description, s.keywords, t.map { it.name }, t.map { it.description }) } }
    }

    private val router by lazy { Router(cards) }

    private val toolsBySkill: Map<String, Set<String>> by lazy {
        (listOf(Skills.core) + Skills.all).associate { s -> s.name to s.tools(ctx).map { it.name }.toSet() }
    }

    /** What the model has for [request] in a fresh chat: core plus the routed skills (Agent loads at most three). */
    private fun available(request: String): Set<String> =
        (listOf("core") + router.select(request, max = 3)).flatMap { toolsBySkill.getValue(it) }.toSet()

    private class Case(val request: String, val skills: List<String>, val tools: List<String>)

    private fun case(request: String, skills: List<String>, vararg tools: String) = Case(request, skills, tools.toList())

    /**
     * Realistic requests across every skill. [Case.skills] must all be selected, one of them
     * ranked first, and [Case.tools] must all be available without a use_skill round trip.
     * Where a request fits two skills (a table to Excel, meeting points to a PDF) both are listed.
     */
    private val cases = listOf(
        // documents
        case("summarise the PDF I pick", listOf("documents"), "pick_files", "document_info", "read_document"),
        case("read this Word document and tell me the deadline", listOf("documents"), "pick_files", "read_document"),
        case("make a Word document with my leave letter", listOf("documents"), "create_docx"),
        case("save this table as an Excel file", listOf("documents", "data_analyst"), "create_xlsx"),
        case("turn these meeting points into a PDF", listOf("documents", "planner"), "create_pdf"),
        // knowledge base
        case("add these files to my knowledge base", listOf("knowledge_base"), "pick_files", "kb_add"),
        case("search my documents for the notice period", listOf("knowledge_base"), "kb_search"),
        case("according to the HR handbook, how many leave days do I get?", listOf("knowledge_base"), "kb_search"),
        // data analyst
        case("what is the total sales by region in this spreadsheet", listOf("data_analyst"), "pick_files", "table_info", "table_query"),
        case("calculate 18% GST on 2450", listOf("data_analyst"), "calculate"),
        case("average of the amount column in my expenses CSV", listOf("data_analyst"), "table_stats"),
        // memory
        case("remember that my daughter is called Asha", listOf("memory"), "remember"),
        case("what do you remember about my food preferences?", listOf("memory"), "recall"),
        case("forget my old address", listOf("memory"), "forget"),
        // planner
        case("add a task to pay the electricity bill tomorrow", listOf("planner"), "add_task"),
        case("put a meeting with Ravi in my calendar on Friday at 3pm", listOf("planner"), "add_calendar_event"),
        case("show my to-do list", listOf("planner"), "list_tasks"),
        case("jot down a note with gift ideas for Meena", listOf("planner"), "add_note"),
        // web
        case("research the best heat pumps for cold climates and save a PDF report", listOf("web", "documents"), "deep_research", "create_pdf"),
        case("what's the weather in Pune tomorrow", listOf("web"), "weather"),
        case("convert 250 dollars to rupees", listOf("web"), "currency_rate"),
        case("download the annual report from this link and summarise it", listOf("web", "documents"), "download_file", "read_document"),
        case("latest news about ISRO", listOf("web"), "search_news"),
        case("look up the population of Chennai on the internet", listOf("web"), "search_internet", "read_web_page"),
        case("open the IIT Madras website in Chrome", listOf("browser"), "open_in_chrome"),
        // communication
        case("WhatsApp mom that I will be late", listOf("communication"), "whatsapp_message"),
        case("pick a photo and send it to Rahul on WhatsApp", listOf("communication"), "pick_files", "whatsapp_files"),
        case("email the report to boss@example.com", listOf("communication"), "compose_email"),
        case("call dad", listOf("communication"), "dial_number"),
        // device and the rest
        case("how much battery is left?", listOf("device"), "device_status"),
        case("turn on the flashlight", listOf("device"), "flashlight"),
        case("set an alarm for 6 am", listOf("alarms_media"), "set_alarm"),
        case("skip this song", listOf("alarms_media"), "media_control"),
        case("take a photo", listOf("files"), "take_photo"),
        case("post this photo on instagram with a caption", listOf("social"), "post_to_social"),
        case("book an uber to the airport", listOf("shopping_travel"), "ride_to"),
        case("directions to the nearest petrol pump", listOf("shopping_travel"), "maps"),
    )

    @Test
    fun realisticRequestsLoadTheSkillsAndToolsTheyNeed() {
        assertTrue(cases.size >= 25)
        // Every routable skill is exercised.
        assertEquals(Skills.all.map { it.name }.toSet(), cases.flatMap { it.skills }.toSet())
        val wrong = cases.mapNotNull { c ->
            val picked = router.select(c.request)
            val tools = available(c.request)
            val problems = listOfNotNull(
                "top ${picked.firstOrNull()} is none of ${c.skills}".takeIf { picked.firstOrNull() !in c.skills },
                "missing skills ${c.skills - picked.toSet()}".takeIf { !picked.containsAll(c.skills) },
                "missing tools ${c.tools - tools}".takeIf { !tools.containsAll(c.tools) },
            )
            if (problems.isEmpty()) null else "'${c.request}': ${problems.joinToString("; ")}; ranked ${router.rank(c.request)}"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun selectionNeverExceedsThreeSkills() {
        for (c in cases) assertTrue(c.request, router.select(c.request).size <= 3)
    }

    @Test
    fun multiSkillRequestsLoadEachSkill() {
        val picked = router.select("take a photo and send it to mom on WhatsApp")
        assertTrue(router.rank("take a photo and send it to mom on WhatsApp").toString(), "communication" in picked && "files" in picked)
        assertTrue(picked.size <= 3)
    }

    @Test
    fun compoundRequestsAndQuestions() {
        val pdfMail = router.select("make a pdf of my notes and email it to boss@example.com")
        assertTrue(pdfMail.toString(), "documents" in pdfMail && "communication" in pdfMail)
        val cab = router.select("search the web for flight prices and book a cab")
        assertTrue(cab.toString(), "shopping_travel" in cab && "web" in cab)
        assertEquals(listOf("web"), router.select("what's the weather like"))
    }

    @Test
    fun noMatchReturnsNothing() {
        assertEquals(emptyList<String>(), router.select("yes, do it"))
        assertEquals(emptyList<String>(), router.select("thanks!"))
        assertEquals(emptyList<String>(), router.select(""))
    }

    @Test
    fun genericVerbsDoNotDrownTheTopic() {
        // "search" appears in tool names of several skills; the topic word must still win.
        assertEquals("communication", router.select("search my contacts for Priya").firstOrNull())
        assertEquals("social", router.select("search youtube for cooking videos").firstOrNull())
        assertEquals("shopping_travel", router.select("search amazon for headphones").firstOrNull())
    }

    @Test
    fun tiesKeepRegistryOrder() {
        val r = Router(listOf(SkillCard("skill2", "alpha"), SkillCard("skill1", "alpha"), SkillCard("skill3", "beta")))
        assertEquals(listOf("skill2", "skill1"), r.select("alpha"))
        assertEquals(listOf("skill2"), r.select("alpha", max = 1))
    }

    @Test
    fun selectCapsAtMaxAndDropsWeakMatches() {
        val r = Router(
            listOf(
                SkillCard("strong", "whatsapp message", keywords = listOf("whatsapp")),
                SkillCard("weak", "a long description that mentions message once among many many other words here"),
                SkillCard("none", "unrelated"),
            )
        )
        assertEquals(listOf("strong"), r.select("whatsapp message"))
    }

    @Test
    fun mergeKeepsPreviousSkillsForFollowUpsAndCaps() {
        assertEquals(listOf("files", "communication"), Router.merge(emptyList(), listOf("files", "communication"), 3))
        assertEquals(listOf("web", "files", "communication"), Router.merge(listOf("web"), listOf("files", "communication", "device"), 3))
        assertEquals(listOf("files", "web"), Router.merge(listOf("files"), listOf("web", "files"), 3))
    }

    @Test
    fun stemmingIsConsistent() {
        assertEquals(Router.terms("photos messages alarms"), Router.terms("photo message alarm"))
        assertEquals(listOf("search"), Router.terms("searching"))
    }
}
