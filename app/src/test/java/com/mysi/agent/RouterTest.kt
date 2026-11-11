package com.mysi.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTest {
    /** The real skills' routing text, with tool names and descriptions read from the sources. */
    private val cards: List<SkillCard> by lazy {
        val tools = toolDescriptions()
        val skills = listOf(
            Skills.communication, Skills.files, Skills.device, Skills.alarmsMedia, Skills.web, Skills.social, Skills.shoppingTravel,
        )
        skills.map { s ->
            val names = Skills.builtinGroups.getValue(s.name)
            SkillCard(s.name, s.description, s.keywords, names, names.map { tools.getValue(it) })
        }
    }

    private val router by lazy { Router(cards) }

    private fun source(name: String) = File("src/main/java/com/mysi/agent/$name").readText()

    /** Tool name to description, parsed from `ToolDef("name", "description"` in Tools.kt and AppTools.kt. */
    private fun toolDescriptions(): Map<String, String> {
        val def = Regex("""ToolDef\(\s*"([a-z_]+)",\s*"((?:[^"\\]|\\.)*)"""")
        return (source("Tools.kt") + source("AppTools.kt")).let { text -> def.findAll(text).associate { it.groupValues[1] to it.groupValues[2] } }
    }

    private fun top(request: String) = router.select(request).firstOrNull()

    @Test
    fun everyBuiltInToolBelongsToExactlyOneSkill() {
        val defined = toolDescriptions().keys
        val grouped = Skills.builtinGroups.values.flatten()
        assertEquals("a tool is listed twice", grouped.size, grouped.toSet().size)
        assertEquals(defined, grouped.toSet())
        assertEquals(40, defined.size)
    }

    @Test
    fun realisticRequestsPickTheRightSkill() {
        val cases = mapOf(
            "WhatsApp mom that I will be late" to "communication",
            "send a message to Rahul on WhatsApp" to "communication",
            "call dad" to "communication",
            "email the report to boss@example.com" to "communication",
            "make a pdf of my notes" to "files",
            "take a photo" to "files",
            "how much battery is left?" to "device",
            "turn on the flashlight" to "device",
            "where am I right now? share my location" to "device",
            "set an alarm for 6 am" to "alarms_media",
            "start a 10 minute timer" to "alarms_media",
            "skip this song" to "alarms_media",
            "search the internet for today's cricket score" to "web",
            "what's the latest news about ISRO" to "web",
            "post this photo on instagram with a caption" to "social",
            "play some lofi videos on youtube" to "social",
            "book an uber to the airport" to "shopping_travel",
            "order a phone charger on amazon" to "shopping_travel",
            "directions to the nearest petrol pump" to "shopping_travel",
        )
        val wrong = cases.filter { (q, want) -> top(q) != want }.map { (q, want) -> "$q → ${router.rank(q)} (want $want)" }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun multiSkillRequestsLoadEachSkill() {
        val picked = router.select("take a photo and send it to mom on WhatsApp")
        assertTrue(router.rank("take a photo and send it to mom on WhatsApp").toString(), "communication" in picked && "files" in picked)
        assertTrue(picked.size <= 3)
    }

    @Test
    fun compoundRequestsAndQuestions() {
        assertEquals(listOf("files", "communication"), router.select("make a pdf of my notes and email it to boss@example.com"))
        assertEquals(listOf("shopping_travel", "web"), router.select("search the web for flight prices and book a cab"))
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
        assertEquals("communication", top("search my contacts for Priya"))
        assertEquals("social", top("search youtube for cooking videos"))
        assertEquals("shopping_travel", top("search amazon for headphones"))
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
