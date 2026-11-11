package com.mysi.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real skill registry: every tool reachable, each tool name and skill name used once. */
class SkillsTest {
    private val ctx get() = TestContext.unsafe

    private fun source(name: String) = File("src/main/java/com/mysi/agent/$name").readText()

    /** Tool names defined with `ToolDef("name"` in Tools.kt and AppTools.kt. */
    private fun builtinToolNames(): Set<String> {
        val def = Regex("""ToolDef\(\s*"([a-z_]+)"""")
        return (source("Tools.kt") + source("AppTools.kt")).let { text -> def.findAll(text).map { it.groupValues[1] }.toSet() }
    }

    private val registry: List<Pair<String, List<String>>> by lazy {
        (listOf(Skills.core) + Skills.all).map { s -> s.name to s.tools(ctx).map { it.name } }
    }

    @Test
    fun everyBuiltInToolBelongsToExactlyOneSkill() {
        val defined = builtinToolNames()
        val grouped = Skills.builtinGroups.values.flatten()
        assertEquals("a tool is listed twice", grouped.size, grouped.toSet().size)
        assertEquals(defined, grouped.toSet())
        // v0.8's 40 hand-off tools, less the old search_internet and read_web_page (now the web skill's).
        assertEquals(38, defined.size)
        // Every group is used by a registered skill, so no built-in tool is orphaned.
        val registered = registry.flatMap { it.second }.toSet()
        assertTrue((defined - registered).toString(), registered.containsAll(defined))
    }

    @Test
    fun toolAndSkillNamesAreUniqueAcrossAllRegisteredSkills() {
        assertEquals(emptyList<String>(), Skills.problems(registry))
        val all = registry.flatMap { it.second }
        assertEquals(all.size, all.toSet().size)
        assertTrue(registry.first().second.containsAll(Skills.metaToolNames))
    }

    @Test
    fun problemsNamesEveryClash() {
        val bad = listOf(
            "core" to listOf("get_datetime", "use_skill"),
            "web" to listOf("search_internet", "read_web_page"),
            "browser" to listOf("search_internet"),
            "Web" to listOf("weather"),
        )
        assertEquals(
            listOf("skill name 'web' is used more than once", "tool 'search_internet' is in web and browser"),
            Skills.problems(bad),
        )
        assertEquals(emptyList<String>(), Skills.problems(listOf("a" to listOf("x"), "b" to listOf("y"))))
    }

    @Test
    fun theOldWebToolsAndHelpersAreGone() {
        val app = source("AppTools.kt")
        assertTrue(!app.contains("\"search_internet\"") && !app.contains("\"read_web_page\""))
        val web = source("Web.kt")
        assertTrue(!web.contains("fun search(") && !web.contains("fun read("))
        assertTrue(!source("web/WebSkill.kt").contains("val basic"))
    }

    @Test
    fun skillInstructionsOnlyNameToolsThatExist() {
        val tools = registry.flatMap { it.second }.toSet()
        // snake_case words followed by "(" or used as tool names in instructions.
        val mentioned = (listOf(Skills.core) + Skills.all).flatMap { s ->
            Regex("""\b([a-z]+_[a-z_]+)\b""").findAll(s.instructions).map { it.groupValues[1] }.toList()
        }.toSet()
        val notTools = setOf("group_by", "count_distinct", "max_chars")
        val unknown = mentioned - tools - notTools
        assertTrue("instructions mention unknown tools: $unknown", unknown.isEmpty())
    }
}
