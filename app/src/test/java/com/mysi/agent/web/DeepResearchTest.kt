package com.mysi.agent.web

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepResearchTest {
    private val question = "How well do heat pumps work in cold climates?"
    private val q1 = "cold climate heat pump performance low temperatures"
    private val q2 = "heat pump energy savings field study"
    private val q3 = "heat pump installation cost Norway"

    private val pages = mapOf(
        "https://alpha.example/heat-pumps" to ResearchPage(
            "https://alpha.example/heat-pumps", "Heat pumps in the cold",
            "Modern cold-climate heat pumps keep working at outdoor temperatures as low as minus 25 degrees Celsius, although their efficiency falls as it gets colder.\n\n" +
                "At minus 15 degrees Celsius, a typical cold-climate model still delivers about two units of heat for every unit of electricity it uses.\n\n" +
                "Defrost cycles briefly reduce output on humid winter days.",
            "2025-01-10",
        ),
        "https://beta.example/study" to ResearchPage(
            "https://beta.example/study", "Maine field study",
            "A 2023 field study of 80 homes in Maine found that heat pumps cut heating energy use by 55 percent compared with oil boilers.\n\n" +
                "Residents reported that the homes stayed comfortable through the coldest weeks of January.\n\nThe study was funded by the state energy office.",
        ),
        "https://gamma.example/costs" to ResearchPage(
            "https://gamma.example/costs", "Heat pump costs in Norway",
            "Installation costs in Norway average 12,000 euros for an air-to-water system, according to the national energy agency.\n\n" +
                "Grants cover part of the cost for households replacing oil heating.\n\nMore than a third of Norwegian homes now use a heat pump.",
        ),
        "https://epsilon.example/thin" to ResearchPage("https://epsilon.example/thin", "Thin", "Too short."),
    )

    private fun hit(url: String) = SearchResult("t", url, "")

    private inner class FakeResearchWeb : ResearchWeb {
        val reads = mutableListOf<String>()
        val searched = mutableListOf<String>()
        val results = mapOf(
            q1 to listOf(
                hit("https://www.youtube.com/watch?v=1"), hit("https://alpha.example/heat-pumps"), hit("https://alpha.example/heat-pumps?utm_source=x"),
                hit("https://alpha.example/other"), hit("https://delta.example/broken"), hit("https://beta.example/study"),
            ),
            q2 to listOf(hit("https://news.google.com/rss/articles/x"), hit("https://beta.example/study"), hit("https://beta.example/report.pdf"), hit("https://epsilon.example/thin")),
            q3 to listOf(hit("https://gamma.example/costs"), hit("https://alpha.example/heat-pumps")),
        )

        override suspend fun search(query: String, count: Int): List<SearchResult> {
            searched += query
            return results[query].orEmpty()
        }

        override suspend fun read(url: String): ResearchPage? {
            synchronized(reads) { reads += url }
            if (url.contains("delta")) throw IOException("connection reset")
            return pages[url]
        }
    }

    private val extraction = mapOf(
        q1 to """Here are the facts:
```json
{"claims": [
 {"claim": "Cold-climate heat pumps keep working down to minus 25 degrees Celsius.", "quote": "Modern cold-climate heat pumps keep working at outdoor temperatures as low as minus 25 degrees Celsius", "source": "S1"},
 {"claim": "Heat pumps stop working below minus 30 degrees Celsius.", "quote": "All heat pumps stop working completely below minus 30 degrees Celsius", "source": "S1"},
 {"claim": "A field study found heat pumps cut heating energy use by 55 percent.", "quote": "heat pumps cut heating energy use by 55 percent compared with oil boilers", "source": "https://made-up.example/report"},
 {"claim": "At minus 15 degrees a cold-climate model delivers about four units of heat per unit of electricity.", "quote": "At minus 15 degrees Celsius, a typical cold-climate model still delivers about two units of heat for every unit of electricity it uses.", "source": "S1"}
]}
```
That's all.""",
        q2 to "Sure! {claims: [ {claim: 'Heat pumps saved energy', quote: 'cut heating energy",
        q3 to """[{"fact": "Installing an air-to-water heat pump in Norway costs about 12,000 euros on average.", "evidence": "Installation costs in Norway average 12,000 euros for an air-to-water system", "url": "https://gamma.example/costs"},
               {"fact": "Grants help.", "evidence": "Grants cover part", "url": "S3"}]""",
    )

    private inner class FakeLlm : Llm {
        val prompts = mutableListOf<Pair<String, String>>()

        override suspend fun complete(system: String, prompt: String): String {
            prompts += system to prompt
            return when {
                system.contains("plan") -> "```json\n{\"questions\": [\"$q1\", \"$q2\", \"$q3\", \"$q1\"]}\n```"
                system.contains("extract") -> extraction.entries.first { prompt.contains("Sub-question: ${it.key}\n") }.value
                else -> "Cold-climate heat pumps keep working down to minus 25 degrees Celsius [1], and a field study found 55 percent energy savings [2][9]. Costs in Norway average 12,000 euros [3]."
            }
        }
    }

    @Test
    fun endToEndOnlyVerifiedClaimsBecomeFindings() = runBlocking {
        val web = FakeResearchWeb()
        val llm = FakeLlm()
        val progress = mutableListOf<ResearchProgress>()
        val report = DeepResearch(llm, web, { progress += it }, today = { "2026-11-11" }).run(question, ResearchDepth.QUICK)

        assertEquals(listOf(q1, q2, q3), report.subQuestions)
        assertEquals(listOf(q1, q2, q3), web.searched)
        // Only addresses from search results were read; excluded sites, documents and same-site extras were not.
        val allResults = web.results.values.flatten().map { it.url }.toSet()
        assertTrue(web.reads.all { it in allResults })
        assertEquals(
            setOf("https://alpha.example/heat-pumps", "https://delta.example/broken", "https://beta.example/study", "https://epsilon.example/thin", "https://gamma.example/costs"),
            web.reads.toSet(),
        )
        assertEquals(setOf("https://delta.example/broken" to "connection reset", "https://epsilon.example/thin" to "too little text"), report.failed.toSet())

        assertEquals(
            listOf(
                "Cold-climate heat pumps keep working down to minus 25 degrees Celsius.",
                "A field study found heat pumps cut heating energy use by 55 percent.",
                "Installing an air-to-water heat pump in Norway costs about 12,000 euros on average.",
            ),
            report.verified.map { it.text },
        )
        // Every finding's quote really is in the page it cites; the made-up address was replaced by the real page.
        for (c in report.verified) {
            val page = pages.getValue(c.sourceUrl!!)
            assertEquals(QuoteVerifier.Result.VERIFIED, QuoteVerifier.verify(c.quote, page.text))
        }
        assertEquals("https://beta.example/study", report.verified[1].sourceUrl)
        assertEquals(listOf("https://alpha.example/heat-pumps", "https://beta.example/study", "https://gamma.example/costs"), report.sources.map { it.url })
        assertEquals(listOf(1, 2, 3), report.sources.map { it.number })

        val unverified = report.unverified.associate { it.text to it.note }
        assertEquals("the quote was not found in the page", unverified["Heat pumps stop working below minus 30 degrees Celsius."])
        assertEquals("the quote does not state this claim", unverified["At minus 15 degrees a cold-climate model delivers about four units of heat per unit of electricity."])
        assertEquals("the quote is too short to check", unverified["Grants help."])
        assertEquals(3, report.unverified.size)

        // The summary keeps real citations and drops the invented [9].
        assertTrue(report.summary.contains("[2]"))
        assertFalse(report.summary.contains("[9]"))
        assertTrue(report.llmCalls <= ResearchDepth.QUICK.maxLlmCalls)
        assertEquals(5, llm.prompts.size)
        // Evidence sent to the model stays within budget.
        assertTrue(llm.prompts.filter { it.first.contains("extract") }.all { it.second.length < DeepResearch.MAX_EVIDENCE_CHARS + 2000 })

        val md = report.markdown
        assertTrue(md.startsWith("# Research: $question"))
        assertTrue(md.contains("## Summary"))
        assertTrue(md.contains("### 1. $q1"))
        assertTrue(md.contains("- Cold-climate heat pumps keep working down to minus 25 degrees Celsius. [1]"))
        assertTrue(md.contains("### 2. $q2\n\n- Nothing verifiable was found for this question."))
        assertTrue(md.contains("1. [Heat pumps in the cold](https://alpha.example/heat-pumps) — 2025-01-10"))
        assertTrue(md.contains("## Could not verify"))
        assertTrue(md.contains("- Heat pumps stop working below minus 30 degrees Celsius. — the quote was not found in the page"))
        assertTrue(md.contains("## Pages that could not be read\n\n- https://delta.example/broken — connection reset"))

        val brief = report.brief(3000)
        assertTrue(brief.length <= 3000)
        assertTrue(brief.startsWith("Verified findings: 3 from 3 sources; 3 statements could not be verified and were left out."))
        assertTrue(brief.contains("[3] Heat pump costs in Norway - https://gamma.example/costs"))

        val steps = progress.map { it.step }.toSet()
        assertEquals(ResearchProgress.Step.entries.toSet(), steps)
    }

    @Test
    fun aBrokenModelNeverProducesFindings() = runBlocking {
        val web = FakeResearchWeb()
        val failing = Llm { _, _ -> throw IllegalStateException("model crashed") }
        val report = DeepResearch(failing, web).run("  $q3  ", ResearchDepth.STANDARD)
        // The plan falls back to the question itself.
        assertEquals(listOf(q3), report.subQuestions)
        assertTrue(report.verified.isEmpty())
        assertTrue(report.summary.startsWith("No statement could be verified"))
        assertEquals(2, report.llmCalls)
    }

    @Test
    fun garbagePlansFallBackAndSummaryFailuresUseTheFindings() = runBlocking {
        val web = FakeResearchWeb()
        val llm = Llm { system, prompt ->
            when {
                system.contains("plan") -> "I'm sorry, I can't do that."
                system.contains("extract") -> extraction.getValue(q3)
                else -> "No citations here at all, just prose that is long enough."
            }
        }
        val report = DeepResearch(llm, web).run(q3, ResearchDepth.QUICK)
        assertEquals(listOf(q3), report.subQuestions)
        assertEquals(1, report.verified.size)
        assertEquals("Installing an air-to-water heat pump in Norway costs about 12,000 euros on average. [1]", report.summary)
    }

    @Test
    fun listsInPlainTextAreAcceptedAsAPlan() = runBlocking {
        val web = FakeResearchWeb()
        val llm = Llm { system, _ -> if (system.contains("plan")) "1. $q1\n2) $q2\n- $q3\n- $q3" else "{\"claims\": []}" }
        val report = DeepResearch(llm, web).run(question, ResearchDepth.QUICK)
        assertEquals(listOf(q1, q2, q3), report.subQuestions)
        assertTrue(report.verified.isEmpty())
    }

    @Test
    fun claimParsingToleratesWhatSmallModelsWrite() {
        val dr = DeepResearch({ _, _ -> "" }, FakeResearchWeb())
        assertEquals(1, dr.parseClaims("```json\n{\"claims\":[{\"claim\":\"a {b}\",\"quote\":\"q } ]\",\"source\":\"S1\"}]}\n```\ntrailing {").size)
        assertEquals("S2", dr.parseClaims("{'claims': [{'claim': 'x', 'quote': 'y', 'source': 'S2'}]}").single().source)
        assertEquals(1, dr.parseClaims("{\"claim\": \"single\", \"quote\": \"q\", \"source\": \"S1\"}").size)
        assertTrue(dr.parseClaims("no json here").isEmpty())
        assertTrue(dr.parseClaims("{\"claims\": \"not a list\"}").isEmpty())
        assertTrue(dr.parseClaims("{\"claims\": [{\"quote\": \"missing claim\"}, 5, null]}").isEmpty())
        assertNull(JsonText.first("{unbalanced"))
    }

    @Test
    fun claimSupportNeedsMatchingNumbersAndWords() {
        val dr = DeepResearch({ _, _ -> "" }, FakeResearchWeb())
        val quote = "A 2023 field study of 80 homes found that heat pumps cut heating energy use by 55 percent."
        assertTrue(dr.supports("Heat pumps cut heating energy use by 55 percent in a 2023 study.", quote))
        assertFalse(dr.supports("Heat pumps cut heating energy use by 65 percent.", quote))
        assertFalse(dr.supports("Heat pumps cut heating energy use by half.", quote))
        assertFalse(dr.supports("Electric cars are cheaper to run than petrol cars.", quote))
    }

    @Test
    fun depthAndSiteHelpers() {
        assertEquals(ResearchDepth.QUICK, ResearchDepth.parse("Quick"))
        assertEquals(ResearchDepth.DEEP, ResearchDepth.parse("thorough"))
        assertEquals(ResearchDepth.STANDARD, ResearchDepth.parse("whatever"))
        assertEquals("bbc.co.uk", DeepResearch.site("news.bbc.co.uk"))
        assertEquals("wikipedia.org", DeepResearch.site("en.wikipedia.org"))
        assertFalse(DeepResearch.usable(WebUrl.parse("https://m.youtube.com/watch?v=1")!!))
        assertFalse(DeepResearch.usable(WebUrl.parse("https://example.org/report.PDF")!!))
        assertTrue(DeepResearch.usable(WebUrl.parse("https://example.org/report")!!))
    }
}
