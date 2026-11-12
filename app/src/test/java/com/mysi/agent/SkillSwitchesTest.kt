package com.mysi.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Skills screen's switches, and the router, use_skill and tool lookup honouring them. */
class SkillSwitchesTest {
    private val ctx get() = TestContext.unsafe
    private val toolsOf: (Skill) -> List<ToolDef> = { it.tools(ctx) }

    @After
    fun allOn() {
        Skills.switches = SkillSwitches()
    }

    /** Routes the way Agent does: only between the cards [Skills.routerCards] gives. */
    private fun route(request: String) = Router(Skills.routerCards(toolsOf)).select(request, max = 3)

    @Test
    fun coreCanNeverBeSwitchedOff() {
        val s = SkillSwitches(setOf("core", "Core ", "memory")).with("core", false)
        assertEquals(setOf("memory"), s.disabled)
        assertTrue(s.allows("core"))
        assertTrue(s.allows(" CORE"))
        assertFalse(s.allows("Memory"))
        assertTrue(s.with("memory", true).allows("memory"))
        assertEquals(SkillSwitches(), s.with("MEMORY", true))
    }

    @Test
    fun theRouterNeverPicksASkillThatIsOff() {
        val request = "remember that my daughter is called Asha"
        assertEquals("memory", route(request).first())
        Skills.switches = SkillSwitches(setOf("memory"))
        assertFalse(route(request).contains("memory"))
        assertTrue(Skills.routerCards(toolsOf).none { it.name == "memory" })
        // Every other skill is still offered.
        assertEquals(Skills.all.size - 1, Skills.routerCards(toolsOf).size)
        assertEquals("web", route("what's the weather in Pune tomorrow").first())
    }

    @Test
    fun noRequestReachesASkillThatIsOff() {
        val off = setOf("web", "communication", "hearing")
        Skills.switches = SkillSwitches(off)
        val requests = listOf(
            "search the web for flights to Goa", "what's the weather in Pune tomorrow", "send a WhatsApp message to Ravi",
            "call mom", "transcribe this recording", "listen to my voice note", "research heat pumps and save a PDF",
        )
        for (r in requests) assertTrue(r, route(r).none { it in off })
    }

    @Test
    fun useSkillRefusesASkillThatIsOffAndSaysWhy() {
        Skills.switches = SkillSwitches(setOf("web"))
        val off = Skills.lookup("Web")
        assertTrue(off is Skills.Lookup.Off)
        assertTrue(Skills.offAnswer((off as Skills.Lookup.Off).skill).contains("turned off the web skill"))
        assertEquals(Skills.Lookup.Found(Skills.named("memory")!!), Skills.lookup("memory"))
        assertEquals(Skills.Lookup.Unknown, Skills.lookup("core"))
        assertEquals(Skills.Lookup.Unknown, Skills.lookup("nonsense"))
        assertNull(Skills.usable("web"))
        assertSame(Skills.core, Skills.usable("core"))
    }

    @Test
    fun aToolOfASkillThatIsOffIsNeverFound() {
        assertEquals("memory", Skills.owner("remember", toolsOf)?.name)
        assertSame(Skills.core, Skills.owner("pick_files", toolsOf))
        Skills.switches = SkillSwitches(setOf("memory"))
        assertNull(Skills.owner("remember", toolsOf))
        assertEquals("memory", Skills.offOwner("remember", toolsOf)?.name)
        // Core's tools are always found; nothing is "off" about them.
        assertSame(Skills.core, Skills.owner("pick_files", toolsOf))
        assertNull(Skills.offOwner("pick_files", toolsOf))
        assertNull(Skills.offOwner("no_such_tool", toolsOf))
    }

    @Test
    fun theSkillsScreenShowsPlainNamesAndOneLineTools() {
        assertEquals("Alarms and media", SkillSwitches.title("alarms_media"))
        assertEquals("Knowledge base", SkillSwitches.title("knowledge_base"))
        assertEquals("Web", SkillSwitches.title("web"))
        assertEquals(
            "Save one fact the user wants MySI to keep for later chats; the user is asked to approve it.",
            SkillSwitches.oneLine("Save one fact the user wants MySI to keep for later chats; the user is asked to approve it. Example: remember(text='x')."),
        )
        // "e.g. web" is not the end of a sentence.
        assertEquals("Skill name, e.g. web or documents", SkillSwitches.oneLine("Skill name, e.g. web or documents"))
        assertEquals(
            "Open an installed app by its name, e.g. WhatsApp or Maps.",
            SkillSwitches.oneLine("Open an installed app by its name, e.g. WhatsApp or Maps. Returns what opened."),
        )
        assertEquals("Read one part, i.e. A page.", SkillSwitches.oneLine("Read one part, i.e. A page. More here."))
        val long = SkillSwitches.oneLine("word ".repeat(60), max = 40)
        assertTrue(long, long.length <= 40 && long.endsWith("word…"))
        // Every real tool has a summary that fits.
        for (s in listOf(Skills.core) + Skills.all) for (t in s.tools(ctx)) {
            val line = SkillSwitches.oneLine(t.description)
            assertTrue("${t.name}: $line", line.isNotBlank() && line.length <= 140)
            assertFalse("${t.name}: $line", line.endsWith("e.g.") || line.endsWith("i.e."))
        }
    }

    @Test
    fun theSystemPromptCatalogOnlyOffersSkillsThatAreOn() {
        Skills.switches = SkillSwitches(setOf("memory", "social"))
        val loaded = listOf(Skills.core, Skills.named("web")!!)
        val text = Skills.catalog(loaded)
        assertFalse(text, text.contains("- memory:"))
        assertFalse(text, text.contains("- social:"))
        assertFalse(text, text.contains("- web:"))
        assertTrue(text, text.contains("- documents:"))
        assertTrue(text, text.contains("not available: memory, social."))
        assertEquals(Skills.all.size - 2, Skills.enabled.size)
        Skills.switches = SkillSwitches()
        assertFalse(Skills.catalog(loaded).contains("Turned off"))
    }
}
