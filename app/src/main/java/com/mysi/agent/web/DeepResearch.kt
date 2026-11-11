package com.mysi.agent.web

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** The language model, injected. The runtime binds it to the on-device model; tests use a fake. */
fun interface Llm {
    suspend fun complete(system: String, prompt: String): String
}

/** What deep research needs from the web, injected so the pipeline can be tested offline. */
interface ResearchWeb {
    suspend fun search(query: String, count: Int): List<SearchResult>

    /** Readable text of [url], or null when it cannot be read (blocked, not a web page, robots.txt). */
    suspend fun read(url: String): ResearchPage?
}

class ResearchPage(val url: String, val title: String, val text: String, val published: String? = null)

/** Fixed budgets per depth: sub-questions, sources per sub-question, total sources, evidence chunks per model call. */
enum class ResearchDepth(val subQuestions: Int, val sourcesPerQuestion: Int, val maxSources: Int, val evidenceChunks: Int) {
    QUICK(3, 2, 6, 5),
    STANDARD(4, 3, 10, 6),
    DEEP(6, 3, 15, 7),
    ;

    /** One planning call, one extraction call per sub-question, one summary call. */
    val maxLlmCalls get() = subQuestions + 2

    companion object {
        fun parse(s: String): ResearchDepth =
            when (s.trim().lowercase()) {
                "quick", "fast", "1", "short" -> QUICK
                "deep", "thorough", "3", "long" -> DEEP
                else -> STANDARD
            }
    }
}

class ResearchProgress(val step: Step, val message: String, val done: Int, val total: Int) {
    enum class Step { PLANNING, SEARCHING, READING, EXTRACTING, WRITING, DONE }

    override fun toString() = "$message ($done/$total)"
}

class ResearchClaim(
    val subQuestion: Int,
    val text: String,
    val quote: String,
    /** The page the quote was found in (verified), or what the model cited (unverified; may be null or made up). */
    val sourceUrl: String?,
    val verified: Boolean,
    val note: String?,
)

class ResearchSource(val number: Int, val url: String, val title: String, val published: String?)

class ResearchReport(
    val question: String,
    val depth: ResearchDepth,
    val date: String,
    val subQuestions: List<String>,
    val summary: String,
    val verified: List<ResearchClaim>,
    val unverified: List<ResearchClaim>,
    /** Sources cited by verified claims, numbered in order of first citation. */
    val sources: List<ResearchSource>,
    val pagesRead: Int,
    val failed: List<Pair<String, String>>,
    val llmCalls: Int,
) {
    private fun cite(c: ResearchClaim) = sources.firstOrNull { it.url == c.sourceUrl }?.let { "[${it.number}]" }.orEmpty()

    val markdown: String by lazy {
        val sb = StringBuilder()
        sb.append("# Research: ").append(question).append("\n\n")
        sb.append("*").append(date).append(" · depth: ").append(depth.name.lowercase()).append(" · ")
            .append(pagesRead).append(" pages read · ").append(verified.size).append(" verified findings · ")
            .append(unverified.size).append(" could not be verified*\n\n")
        sb.append("## Summary\n\n").append(summary).append("\n\n")
        sb.append("## Findings\n\n")
        subQuestions.forEachIndexed { i, q ->
            sb.append("### ").append(i + 1).append(". ").append(q).append("\n\n")
            val mine = verified.filter { it.subQuestion == i }
            if (mine.isEmpty()) sb.append("- Nothing verifiable was found for this question.\n")
            for (c in mine) {
                sb.append("- ").append(c.text).append(' ').append(cite(c)).append('\n')
                sb.append("  > \"").append(c.quote.replace("\n", " ")).append("\"\n")
            }
            sb.append('\n')
        }
        sb.append("## Sources\n\n")
        if (sources.isEmpty()) sb.append("No source supported a verified finding.\n")
        for (s in sources) {
            sb.append(s.number).append(". [").append(s.title.ifBlank { s.url }.replace("]", ")")).append("](").append(s.url).append(")")
            s.published?.let { sb.append(" — ").append(it) }
            sb.append('\n')
        }
        sb.append("\n## Could not verify\n\n")
        if (unverified.isEmpty()) sb.append("Every statement the model proposed was found word for word in a source.\n")
        else sb.append("The model proposed these, but they are left out of the findings because the supporting quote was not found in the pages read:\n\n")
        for (c in unverified) {
            sb.append("- ").append(c.text)
            sb.append(" — ").append(c.note ?: "not verified")
            c.sourceUrl?.let { sb.append(" (cited: ").append(it).append(")") }
            sb.append('\n')
        }
        if (failed.isNotEmpty()) {
            sb.append("\n## Pages that could not be read\n\n")
            for ((u, why) in failed) sb.append("- ").append(u).append(" — ").append(why).append('\n')
        }
        sb.append("\n---\nMade on this phone by MySI deep research. Every finding above has a quote that was checked, word for word, ")
            .append("against the page it cites. The summary is written by the on-device model from those findings; check the sources for anything important.\n")
        sb.toString()
    }

    /** A short version for the chat, essentials first, at most [max] characters. */
    fun brief(max: Int = 3000): String {
        val head = StringBuilder()
        head.append("Verified findings: ").append(verified.size).append(" from ").append(sources.size).append(" sources")
        if (unverified.isNotEmpty()) head.append("; ").append(unverified.size).append(" statements could not be verified and were left out")
        head.append(".\n\nSummary: ").append(summary).append("\n")
        val sourceLines = sources.map { "[${it.number}] ${it.title.take(80)} - ${it.url}" }
        val findings = verified.map { "- ${it.text} ${cite(it)}" }
        val out = StringBuilder(head)
        val tail = StringBuilder("\nSources:\n")
        for (l in sourceLines) tail.append(l).append('\n')
        out.append("\nFindings:\n")
        var shown = 0
        for (f in findings) {
            if (out.length + f.length + 1 + tail.length + 60 > max) break
            out.append(f).append('\n')
            shown++
        }
        if (shown < findings.size) out.append("(${findings.size - shown} more findings in the saved report)\n")
        if (out.length + tail.length <= max) out.append(tail)
        else out.append("\n(Sources are listed in the saved report.)")
        return out.toString().take(max)
    }
}

/**
 * Deterministic research pipeline around a small model:
 * plan sub-questions → search each → pick diverse sources → read them in parallel → chunk →
 * rank chunks per sub-question with BM25 → the model extracts claims, each with an exact quote and
 * a source label, as JSON → every quote is checked against the page text ([QuoteVerifier]) →
 * a cited report. Bad model output (broken JSON, missing fields, made-up sources or quotes) is
 * dropped or listed under "could not verify", never presented as a finding.
 *
 * One instance runs one research at a time; create one per request.
 */
class DeepResearch(
    private val llm: Llm,
    private val web: ResearchWeb,
    private val progress: (ResearchProgress) -> Unit = {},
    private val today: () -> String = { java.time.LocalDate.now().toString() },
    private val parallelReads: Int = 4,
) {
    private var calls = 0

    private suspend fun ask(system: String, prompt: String): String? {
        calls++
        return try {
            llm.complete(system, prompt)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun emit(step: ResearchProgress.Step, message: String, done: Int, total: Int) =
        runCatching { progress(ResearchProgress(step, message, done, total)) }

    private class Read(val label: String, val searchUrl: String, val page: ResearchPage) {
        val verifier by lazy { QuoteVerifier.Source(page.text) }
        val canonicals = setOfNotNull(WebUrl.parse(searchUrl)?.canonical, WebUrl.parse(page.url)?.canonical)
    }

    suspend fun run(question: String, depth: ResearchDepth = ResearchDepth.STANDARD): ResearchReport {
        calls = 0
        val q = question.trim()
        // 1. Plan.
        emit(ResearchProgress.Step.PLANNING, "Planning the research", 0, 1)
        val subQs = plan(q, depth)
        coroutineContext.ensureActive()

        // 2. Search.
        val hits = subQs.mapIndexed { i, sq ->
            emit(ResearchProgress.Step.SEARCHING, "Searching: $sq", i, subQs.size)
            val r = try {
                web.search(sq, 8)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            coroutineContext.ensureActive()
            r
        }

        // 3. Pick sources: de-duplicated, at most one per site per sub-question and two per site overall.
        val picked = pickSources(hits, depth)

        // 4. Read them in parallel.
        val failed = mutableListOf<Pair<String, String>>()
        val sem = Semaphore(parallelReads.coerceAtLeast(1))
        var done = 0
        val pages = coroutineScope {
            picked.map { url ->
                async {
                    sem.withPermit {
                        val r = try {
                            web.read(url) ?: throw java.io.IOException("not a readable web page")
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            synchronized(failed) { failed += url to (e.message ?: "could not be read") }
                            null
                        }
                        val n = synchronized(failed) { ++done }
                        emit(ResearchProgress.Step.READING, "Read $n of ${picked.size} pages", n, picked.size)
                        r?.takeIf { it.text.length >= 200 }.also { if (r != null && it == null) synchronized(failed) { failed += url to "too little text" } }
                    }
                }
            }.awaitAll()
        }
        coroutineContext.ensureActive()
        val reads = picked.zip(pages).mapNotNull { (u, p) -> p?.let { u to it } }
            .mapIndexed { i, (u, p) -> Read("S${i + 1}", u, p) }

        // 5–9. Per sub-question: rank chunks, extract claims, verify quotes.
        val chunks = reads.flatMap { r -> Chunker.chunks(r.page.text, 900).map { r to it } }
        val bm = Bm25(chunks.map { it.second })
        val verified = mutableListOf<ResearchClaim>()
        val unverified = mutableListOf<ResearchClaim>()
        subQs.forEachIndexed { i, sq ->
            coroutineContext.ensureActive()
            emit(ResearchProgress.Step.EXTRACTING, "Finding facts for: $sq", i, subQs.size)
            val evidence = selectEvidence(bm, chunks, sq, depth)
            if (evidence.isEmpty()) return@forEachIndexed
            val raw = ask(EXTRACT_SYSTEM, extractPrompt(q, sq, evidence)) ?: return@forEachIndexed
            for (c in parseClaims(raw).take(8)) {
                val result = verify(i, c, reads)
                if (result.verified) verified += result else unverified += result
            }
        }

        // De-duplicate findings by claim or quote.
        val seen = HashSet<String>()
        val uniqueVerified = verified.filter { seen.add(QuoteVerifier.normalise(it.text)) and seen.add("q:" + QuoteVerifier.normalise(it.quote)) }
        val seenU = HashSet<String>()
        val uniqueUnverified = unverified.filter { seenU.add(QuoteVerifier.normalise(it.text)) }

        // Number sources by first citation.
        val sources = mutableListOf<ResearchSource>()
        for (c in uniqueVerified) {
            if (sources.none { it.url == c.sourceUrl }) {
                val r = reads.first { it.page.url == c.sourceUrl }
                sources += ResearchSource(sources.size + 1, r.page.url, r.page.title, r.page.published)
            }
        }

        // 10. Summary from verified findings only.
        emit(ResearchProgress.Step.WRITING, "Writing the report", 0, 1)
        val summary = summarise(q, uniqueVerified, sources)
        emit(ResearchProgress.Step.DONE, "Research finished", 1, 1)
        return ResearchReport(q, depth, today(), subQs, summary, uniqueVerified, uniqueUnverified, sources, reads.size, failed.toList(), calls)
    }

    private suspend fun plan(q: String, depth: ResearchDepth): List<String> {
        val raw = ask(
            PLAN_SYSTEM,
            "Question: $q\n\nWrite ${depth.subQuestions} short, specific web search queries that together answer the question. " +
                "Each must make sense on its own: repeat names, places and years, and use no pronouns. " +
                "Reply with JSON only, like {\"questions\": [\"first query\", \"second query\"]}",
        ).orEmpty()
        val fromJson = JsonText.first(raw)?.let { strings(it, listOf("questions", "queries", "sub_questions", "subquestions", "searches")) }.orEmpty()
        val list = fromJson.ifEmpty {
            raw.lines().mapNotNull { l ->
                Regex("^\\s*(?:\\d+[.)]|[-*•])\\s+(.+)$").find(l)?.groupValues?.get(1) ?: l.trim().takeIf { it.endsWith("?") }
            }
        }
        val seen = HashSet<String>()
        val clean = list.map { it.trim().trim('"', '\'', '`').trim() }
            .filter { it.length in 5..200 && seen.add(it.lowercase()) }
            .take(depth.subQuestions)
        return clean.ifEmpty { listOf(q) }
    }

    private fun pickSources(hits: List<List<SearchResult>>, depth: ResearchDepth): List<String> {
        val chosen = LinkedHashMap<String, String>() // canonical -> url
        val perSite = HashMap<String, Int>()
        for (list in hits) {
            var taken = 0
            val sitesHere = HashSet<String>()
            val keysHere = HashSet<String>()
            val usable = list.mapNotNull { h -> WebUrl.parse(h.url)?.takeIf { usable(it) } }
            for (u in usable) {
                if (taken >= depth.sourcesPerQuestion) break
                val key = u.canonical
                if (!keysHere.add(key)) continue
                if (chosen.containsKey(key)) {
                    // Already picked for an earlier sub-question: it serves this one too.
                    sitesHere += site(u.host)
                    taken++
                    continue
                }
                if (chosen.size >= depth.maxSources) break
                val site = site(u.host)
                if (site in sitesHere || (perSite[site] ?: 0) >= 2) continue
                chosen[key] = u.toString()
                sitesHere += site
                perSite[site] = (perSite[site] ?: 0) + 1
                taken++
            }
            // A sub-question must not end up with nothing just because of the diversity rules.
            if (taken == 0 && chosen.size < depth.maxSources) usable.firstOrNull()?.let { chosen.putIfAbsent(it.canonical, it.toString()) }
        }
        return chosen.values.toList()
    }

    private fun selectEvidence(bm: Bm25, chunks: List<Pair<Read, String>>, subQ: String, depth: ResearchDepth): List<Pair<Read, String>> {
        val out = mutableListOf<Pair<Read, String>>()
        val perSource = HashMap<Read, Int>()
        var chars = 0
        for ((idx, _) in bm.rank(subQ)) {
            if (out.size >= depth.evidenceChunks) break
            val (r, text) = chunks[idx]
            if ((perSource[r] ?: 0) >= 2 || chars + text.length > MAX_EVIDENCE_CHARS) continue
            out += r to text
            perSource[r] = (perSource[r] ?: 0) + 1
            chars += text.length
        }
        return out
    }

    private fun extractPrompt(q: String, subQ: String, evidence: List<Pair<Read, String>>): String {
        val sb = StringBuilder()
        sb.append("Research question: ").append(q).append("\nSub-question: ").append(subQ).append("\n\nSources:\n")
        for ((r, text) in evidence) {
            sb.append("\n[").append(r.label).append("] ").append(r.page.title.take(120)).append(" (").append(r.page.url).append(")\n")
            sb.append(text).append('\n')
        }
        sb.append(
            "\nWrite up to 5 facts from these sources that answer the sub-question. For each fact give:\n" +
                "- \"claim\": the fact in one plain sentence,\n" +
                "- \"quote\": the exact sentence from the source that states it, copied word for word,\n" +
                "- \"source\": the label of that source, like \"S1\".\n" +
                "Reply with JSON only: {\"claims\": [{\"claim\": \"...\", \"quote\": \"...\", \"source\": \"S1\"}]}\n" +
                "If the sources do not answer the sub-question, reply {\"claims\": []}",
        )
        return sb.toString()
    }

    internal class RawClaim(val claim: String, val quote: String, val source: String)

    /** Reads claims out of whatever the model wrote; anything malformed is skipped. */
    internal fun parseClaims(raw: String): List<RawClaim> {
        val root = JsonText.first(raw) ?: return emptyList()
        val arr: JsonArray = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> listOf("claims", "facts", "findings", "results").firstNotNullOfOrNull { k ->
                root.asJsonObject.get(k)?.takeIf { it.isJsonArray }?.asJsonArray
            } ?: (if (root.asJsonObject.has("claim")) JsonArray().apply { add(root) } else return emptyList())
            else -> return emptyList()
        }
        return arr.mapNotNull { e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val claim = field(o, "claim", "fact", "statement", "finding", "text") ?: return@mapNotNull null
            val quote = field(o, "quote", "evidence", "support", "excerpt", "citation_text").orEmpty()
            val source = field(o, "source", "source_id", "sid", "url", "source_url", "id", "ref").orEmpty()
            RawClaim(claim.take(500), quote.take(1500), source.take(500))
        }.filter { it.claim.isNotBlank() }
    }

    private fun field(o: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k ->
            val v = o.get(k) ?: return@firstNotNullOfOrNull null
            when {
                v.isJsonPrimitive -> v.asString
                v.isJsonArray -> v.asJsonArray.mapNotNull { it.takeIf { x -> x.isJsonPrimitive }?.asString }.joinToString(" ").takeIf { it.isNotEmpty() }
                else -> null
            }
        }?.trim()?.takeIf { it.isNotEmpty() }

    private fun resolveSource(ref: String, reads: List<Read>): Read? {
        val r = ref.trim().trim('[', ']', '(', ')')
        Regex("^[Ss](?:ource)?\\s*#?(\\d{1,3})$").find(r)?.let { m -> return reads.getOrNull(m.groupValues[1].toInt() - 1) }
        r.toIntOrNull()?.let { return reads.getOrNull(it - 1) }
        val canonical = WebUrl.parse(r)?.canonical ?: return null
        return reads.firstOrNull { canonical in it.canonicals }
    }

    private fun verify(subQ: Int, c: RawClaim, reads: List<Read>): ResearchClaim {
        val claimed = resolveSource(c.source, reads)
        fun unverified(note: String) = ResearchClaim(subQ, c.claim, c.quote, claimed?.page?.url ?: c.source.ifBlank { null }, false, note)
        if (c.quote.isBlank()) return unverified("no supporting quote was given")
        var found: Read? = null
        var tooShort = false
        if (claimed != null) {
            when (QuoteVerifier.verify(c.quote, claimed.verifier)) {
                QuoteVerifier.Result.VERIFIED -> found = claimed
                QuoteVerifier.Result.TOO_SHORT -> tooShort = true
                QuoteVerifier.Result.NOT_FOUND -> Unit
            }
        }
        if (found == null && !tooShort) {
            found = reads.firstOrNull { it !== claimed && QuoteVerifier.verify(c.quote, it.verifier) == QuoteVerifier.Result.VERIFIED }
            if (found == null && QuoteVerifier.words(c.quote).size < QuoteVerifier.MIN_WORDS) tooShort = true
        }
        if (found == null) {
            return unverified(
                when {
                    tooShort -> "the quote is too short to check"
                    claimed == null && c.source.isNotBlank() -> "the model cited a page that was not read (${c.source.take(80)}), and the quote is in none of the pages read"
                    else -> "the quote was not found in the page"
                },
            )
        }
        if (!supports(c.claim, c.quote)) return unverified("the quote does not state this claim")
        val note = if (claimed != null && found !== claimed) "quote found in a different page than the one the model named" else null
        return ResearchClaim(subQ, c.claim, c.quote, found.page.url, true, note)
    }

    /**
     * The quote must back the claim: every number in the claim (digits or words like "four",
     * "million", "twice") appears in the quote, and at least a third of the claim's content words
     * do. A real quote paired with an invented claim fails.
     */
    internal fun supports(claim: String, quote: String): Boolean {
        val quoteWords = QuoteVerifier.words(quote).toSet()
        val numbers = QuoteVerifier.words(claim).filter { w -> w.any { it.isDigit() } || w in NUMBER_WORDS }
        if (numbers.any { it !in quoteWords }) return false
        val claimTerms = Bm25.tokens(claim).toSet()
        if (claimTerms.isEmpty()) return false
        val quoteTerms = Bm25.tokens(quote).toSet()
        return claimTerms.count { it in quoteTerms }.toDouble() / claimTerms.size >= 1.0 / 3
    }

    private suspend fun summarise(q: String, verified: List<ResearchClaim>, sources: List<ResearchSource>): String {
        if (verified.isEmpty()) return "No statement could be verified against the pages read, so there is no answer to report. Try a more specific question or a different depth."
        val numbered = verified.take(20).joinToString("\n") { c -> "- ${c.text} [${sources.first { it.url == c.sourceUrl }.number}]" }
        val raw = ask(
            SUMMARY_SYSTEM,
            "Question: $q\n\nVerified findings (the number in brackets is the source):\n$numbered\n\n" +
                "Write a 3 to 5 sentence answer to the question using only these findings. After each sentence put its source numbers " +
                "in brackets, like [1] or [2][3]. If findings disagree, say so. Plain text only, no headings or lists.",
        )
        val valid = sources.map { it.number }.toSet()
        val cleaned = raw?.let { text ->
            Regex("\\[(\\d{1,3})]").replace(text.replace(Regex("[*#`]"), "").trim()) { m -> if (m.groupValues[1].toInt() in valid) m.value else "" }
                .replace(Regex("[ \\t]{2,}"), " ").trim().take(1500)
        }
        if (cleaned != null && cleaned.length >= 40 && Regex("\\[(\\d{1,3})]").containsMatchIn(cleaned)) return cleaned
        // The model failed or cited nothing real: list the first finding of each sub-question instead.
        return verified.groupBy { it.subQuestion }.values.map { it.first() }.take(5)
            .joinToString(" ") { c -> c.text.trimEnd('.') + ". [${sources.first { it.url == c.sourceUrl }.number}]" }
    }

    private fun strings(e: JsonElement, keys: List<String>): List<String> {
        val arr = when {
            e.isJsonArray -> e.asJsonArray
            e.isJsonObject -> keys.firstNotNullOfOrNull { k -> e.asJsonObject.get(k)?.takeIf { it.isJsonArray }?.asJsonArray } ?: return emptyList()
            else -> return emptyList()
        }
        return arr.mapNotNull { x ->
            when {
                x.isJsonPrimitive -> x.asString
                x.isJsonObject -> field(x.asJsonObject, "question", "query", "q", "text")
                else -> null
            }
        }
    }

    companion object {
        const val MAX_EVIDENCE_CHARS = 6000

        private val NUMBER_WORDS =
            // "one" is left out: it is too often not a number ("one of the", "no one").
            ("zero two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen " +
                "eighteen nineteen twenty thirty forty fifty sixty seventy eighty ninety hundred thousand million billion trillion " +
                "lakh lakhs crore crores half third thirds quarter quarters double doubled twice triple tripled halved").split(' ').toSet()

        private val EXCLUDED_SITES =
            setOf(
                "news.google.com", "google.com", "youtube.com", "youtu.be", "facebook.com", "instagram.com", "x.com", "twitter.com",
                "tiktok.com", "pinterest.com", "linkedin.com", "quora.com", "duckduckgo.com", "bing.com", "reddit.com",
            )
        private val DOCUMENT_EXT = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip", "mp4", "mp3", "jpg", "jpeg", "png", "gif")
        private val SECOND_LEVEL = setOf("co", "com", "ac", "gov", "org", "net", "edu", "nic", "gen", "ltd", "plc", "or", "ne", "go")

        /** Approximate registrable domain: "news.bbc.co.uk" → "bbc.co.uk", "en.wikipedia.org" → "wikipedia.org". */
        fun site(host: String): String {
            val labels = host.removePrefix("www.").split('.')
            if (labels.size >= 3 && labels.last().length == 2 && labels[labels.size - 2] in SECOND_LEVEL) return labels.takeLast(3).joinToString(".")
            return labels.takeLast(2).joinToString(".")
        }

        fun usable(u: WebUrl): Boolean {
            if (EXCLUDED_SITES.any { u.host == it || u.host.endsWith(".$it") }) return false
            return u.rawPath.substringAfterLast('/').substringAfterLast('.', "").lowercase() !in DOCUMENT_EXT
        }

        private const val PLAN_SYSTEM = "You plan web research. You reply with JSON only."
        private const val EXTRACT_SYSTEM =
            "You extract facts from web sources for a research report. Use only the sources given, never your own knowledge. " +
                "Quotes must be copied exactly from the source text. You reply with JSON only."
        private const val SUMMARY_SYSTEM = "You write short, accurate research summaries using only the findings you are given."
    }
}

/** Finds the first JSON object or array in model output (code fences, chatter and trailing text tolerated). */
object JsonText {
    fun first(text: String): JsonElement? {
        var from = 0
        var attempts = 0
        while (attempts++ < 20) {
            val start = text.indexOfAny(charArrayOf('{', '['), from)
            if (start < 0) return null
            val end = balancedEnd(text, start)
            if (end > start) {
                val parsed = runCatching { JsonParser.parseString(text.substring(start, end + 1)) }.getOrNull()
                if (parsed != null && (parsed.isJsonObject || parsed.isJsonArray)) return parsed
            }
            from = start + 1
        }
        return null
    }

    /** Index of the bracket closing the one at [start], respecting strings; -1 if unbalanced. */
    private fun balancedEnd(s: String, start: Int): Int {
        var depth = 0
        var inString = false
        var quote = '"'
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == quote -> inString = false
                }
                continue
            }
            when (c) {
                '"', '\'' -> {
                    inString = true
                    quote = c
                }
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }
}
