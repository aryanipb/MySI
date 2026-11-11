package com.mysi.agent.web

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A non-2xx answer; the message is already a full sentence for the model. */
class HttpStatusException(message: String) : IOException(message)

/**
 * Everything the web tools do, as plain blocking functions returning text for the model.
 * No Android here: the Android layer only supplies the HTTP client, the Downloads folder and the
 * rendered-page HTML. Call from a background thread.
 */
class WebService(
    val http: HttpClient,
    private val downloader: Downloader?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /**
         * Most characters any web tool result may have, header, text and "next part" hints
         * included. The runtime shows the model at most ResultStore.EXCERPT_CHARS of a result and
         * adds its own read_result handle past that, so web results stay within this and page
         * themselves with offset instead.
         */
        const val OUTPUT_BUDGET = com.mysi.agent.ResultStore.PAGED_TOOL_CHARS

        /** How long to leave DuckDuckGo alone after it shows its bot check. */
        const val DUCKDUCKGO_PAUSE_MS = 5 * 60_000L

        private const val BOT_CHECK = "DuckDuckGo asked for a bot check"
    }

    sealed class Loaded {
        class Html(val page: FetchedPage) : Loaded()
        class Text(val page: FetchedPage) : Loaded()
        class Document(val saved: SavedFile) : Loaded()
        class NotSaved(val kind: FileKind) : Loaded()
        class Unsupported(val mime: String) : Loaded()
    }

    /** Extracted articles by final address, so paging through a long page parses it once. */
    private val articles = object : LinkedHashMap<String, Article>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Article>?) = size > 8
    }

    private class Cached(val at: Long, val value: String)

    private val searches = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 32
    }

    /** Documents read_web_page already saved this session, by address, so a repeat read does not save a copy. */
    private val saved = HashMap<String, SavedFile>()

    /** Set when DuckDuckGo shows its bot check; until then searches go straight to the fallback. */
    @Volatile
    private var duckDuckGoPausedUntil = 0L

    fun clearCache() {
        http.cache.clear()
        synchronized(articles) { articles.clear() }
        synchronized(searches) { searches.clear() }
        synchronized(saved) { saved.clear() }
        duckDuckGoPausedUntil = 0L
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Turns failures into one plain sentence the model can relay. */
    private inline fun guard(what: String, block: () -> String): String =
        try {
            block()
        } catch (e: BlockedUrlException) {
            "Blocked: ${e.message}"
        } catch (e: HttpStatusException) {
            e.message.orEmpty()
        } catch (e: SocketTimeoutException) {
            "Could not $what: the site took too long to respond."
        } catch (e: SSLException) {
            "Could not $what: the site's secure connection could not be verified (${e.message?.take(120)})."
        } catch (e: IOException) {
            "Could not $what: ${e.message ?: "network error"}. Check the internet connection, or try another address."
        }

    private fun statusMessage(status: Int, url: WebUrl): String =
        when (status) {
            401, 403 -> "The site refused access to $url (HTTP $status). It may block automated readers; try read_rendered_page, or open_in_chrome to show it to the user."
            404, 410 -> "The page $url was not found (HTTP $status). Check the address, or search for it."
            429 -> "The site is limiting requests (HTTP 429). Try again in a minute, or use another source."
            in 500..599 -> "The site had an error (HTTP $status). Try again later, or use another source."
            else -> "The site answered HTTP $status for $url."
        }

    /** Fetches [url] once: web pages and text are read (and cached); documents are saved when [save] is true. */
    fun load(url: WebUrl, save: Boolean = true): Loaded {
        val req = HttpRequest(url)
        http.cache.get(http.cacheKey(req))?.let { return classify(it) }
        synchronized(saved) { saved[url.toString()]?.takeIf { it.file.isFile }?.let { return Loaded.Document(it) } }
        http.open(req, deadlineMs = if (save) 120_000 else http.config.deadlineMs).use { r ->
            if (r.status !in 200..299) throw HttpStatusException(statusMessage(r.status, r.url))
            val disposition = r.header("content-disposition")
            val docKind = Downloader.documentKind(r.mime, r.url, disposition)
            if (docKind != null) {
                if (!save || downloader == null) return Loaded.NotSaved(docKind)
                val file = downloader.save(r.body, docKind, r.contentLength, r.url, disposition, null)
                synchronized(saved) { saved[url.toString()] = file }
                return Loaded.Document(file)
            }
            if (!isTextual(r.mime)) return Loaded.Unsupported(r.mime.ifEmpty { "unknown" })
            val (bytes, truncated) = http.readBody(r, http.config.maxTextBytes)
            val page = FetchedPage(r.url, r.status, r.contentType, bytes, truncated)
            http.cache.put(http.cacheKey(req), page)
            return classify(page)
        }
    }

    private fun isTextual(mime: String) =
        mime.isEmpty() || mime.startsWith("text/") || mime == "application/xhtml+xml" || mime.endsWith("+xml") ||
            mime == "application/xml" || mime == "application/json" || mime.endsWith("+json")

    private fun classify(page: FetchedPage): Loaded {
        val m = page.mime
        val html = m.isEmpty() || m == "text/html" || m == "application/xhtml+xml" ||
            (m != "text/plain" && page.text.trimStart().take(200).lowercase().let { it.startsWith("<!doctype html") || it.startsWith("<html") })
        return if (html) Loaded.Html(page) else Loaded.Text(page)
    }

    private fun article(page: FetchedPage): Article {
        val key = page.url.toString()
        synchronized(articles) { articles[key]?.let { return it } }
        val a = Readability.extract(page.text, key)
        synchronized(articles) { articles[key] = a }
        return a
    }

    private fun parse(url: String): WebUrl = WebUrl.parse(url) ?: throw BlockedUrlException("'${url.take(200)}' is not a valid http or https web address.")

    private fun documentNote(l: Loaded): String? =
        when (l) {
            is Loaded.Document -> "This address is a ${l.saved.kind.label}, not a web page. It was saved to ${l.saved.relativePath} " +
                "(${Downloader.mb(l.saved.bytes)}). Read it with the document tools using that path."
            is Loaded.NotSaved -> "This address is a ${l.kind.label}, not a web page. Use download_file to save it, then the document tools to read it."
            is Loaded.Unsupported -> "This address is a ${l.mime} file, which web tools cannot read."
            else -> null
        }

    // ---------- reading ----------

    fun read(url: String, offset: Int, maxChars: Int, tool: String = "read_web_page"): String =
        guard("open the page") {
            val a = when (val l = load(parse(url))) {
                is Loaded.Html -> article(l.page)
                is Loaded.Text -> Article(l.page.url.toString(), l.page.url.fileName, null, null, null, null, l.page.text.trim(), false)
                else -> return@guard documentNote(l).orEmpty()
            }
            formatArticle(a, offset, maxChars, tool)
        }

    /** Formats HTML a WebView produced after running the page's scripts. */
    fun readRendered(html: String, finalUrl: String, offset: Int, maxChars: Int): String =
        formatArticle(Readability.extract(html, finalUrl).let { Article(it.url, it.title, it.byline, it.published, it.siteName, it.description, it.text, false) }, offset, maxChars, "read_rendered_page")

    /**
     * Essentials first (title, address, date, how much is left and how to get it), then the text,
     * so the header survives even if the runtime cuts a long result.
     */
    fun formatArticle(a: Article, offset: Int, maxChars: Int, tool: String): String {
        val text = a.text
        val sb = StringBuilder()
        sb.append("Title: ").append(a.title.ifBlank { "(none)" }.take(200)).append('\n')
        sb.append("Address: ").append(a.url.take(500)).append('\n')
        val meta = listOfNotNull(a.byline?.let { "By: ${it.take(120)}" }, a.published?.let { "Published: $it" }, a.siteName?.let { "Site: ${it.take(80)}" })
        if (meta.isNotEmpty()) sb.append(meta.joinToString(" · ")).append('\n')
        if (text.isBlank()) {
            sb.append("The page has no readable text.")
            a.description?.let { sb.append(" Its description: ").append(it.take(500)) }
            if (a.needsJavaScript || tool == "read_web_page") sb.append(" It may build its content with JavaScript: try read_rendered_page.")
            return sb.toString()
        }
        val start = offset.coerceAtLeast(0)
        if (start >= text.length) return sb.append("The offset $start is past the end; the page has ${text.length} characters.").toString()
        // The header, the "Part" line and the text together stay within the result budget.
        val window = minOf(maxChars, OUTPUT_BUDGET - sb.length - 260).coerceAtLeast(200)
        var end = minOf(text.length, start + window)
        if (end < text.length) {
            // End at a paragraph, sentence or word boundary in the last fifth of the window.
            val floor = start + window * 4 / 5
            val para = text.lastIndexOf("\n\n", end).takeIf { it >= floor }
            val sentence = Regex("[.!?]\\s").findAll(text.substring(floor, end)).lastOrNull()?.let { floor + it.range.first + 1 }
            val space = text.lastIndexOf(' ', end).takeIf { it >= floor }
            end = para ?: sentence ?: space ?: end
        }
        if (end < text.length) sb.append("Part: characters $start–$end of ${text.length}. For the next part call $tool with offset=$end.\n")
        else if (start > 0) sb.append("Part: characters $start–$end of ${text.length} (the end of the page).\n")
        if (a.needsJavaScript) sb.append("Note: this page seems to build its content with JavaScript; if text is missing, try read_rendered_page.\n")
        sb.append("---\n").append(text.substring(start, end).trim())
        return sb.toString()
    }

    /** Appends whole [items] while they fit the budget, then an "N more" line from [more]. */
    private fun fit(head: String, items: List<String>, footer: String, more: (Int) -> String): String {
        val sb = StringBuilder(head)
        var shown = 0
        for (item in items) {
            if (sb.length + item.length + footer.length + 80 > OUTPUT_BUDGET) break
            sb.append(item)
            shown++
        }
        if (shown < items.size) sb.append(more(items.size - shown)).append('\n')
        sb.append(footer)
        return sb.toString().trimEnd()
    }

    private fun htmlPage(url: String): Pair<FetchedPage?, String?> {
        val l = load(parse(url), save = false)
        documentNote(l)?.let { return null to it }
        return when (l) {
            is Loaded.Html -> l.page to null
            is Loaded.Text -> null to "This address is plain text, not a web page with links or tables. Use read_web_page."
            else -> null to "Not a web page."
        }
    }

    fun links(url: String, contains: String): String =
        guard("open the page") {
            val (page, note) = htmlPage(url)
            if (page == null) return@guard note.orEmpty()
            val all = PageExtract.links(HtmlParser.parse(page.text), page.url, contains)
            if (all.isEmpty()) return@guard if (contains.isBlank()) "No links found on ${page.url}." else "No link on ${page.url} mentions '$contains'."
            val head = "${all.size}" + (if (contains.isBlank()) " links" else " links matching '${contains.take(80)}'") + " on ${page.url}:\n"
            fit(head, all.map { "- ${it.text.ifBlank { "(no text)" }}: ${it.url.take(400)}\n" }, "") { "($it more; use contains to narrow the list)" }
        }

    fun tables(url: String, index: Int?): String =
        guard("open the page") {
            val (page, note) = htmlPage(url)
            if (page == null) return@guard note.orEmpty()
            val tables = PageExtract.tables(HtmlParser.parse(page.text))
            if (tables.isEmpty()) return@guard "No data tables on ${page.url}. If the page builds tables with JavaScript, try read_rendered_page."
            if (index != null) {
                val t = tables.getOrNull(index - 1) ?: return@guard "There is no table $index; the page has ${tables.size} tables (numbered from 1)."
                val head = "Table $index of ${tables.size}" + (if (t.caption.isNotBlank()) ": ${t.caption.take(200)}" else "") +
                    " (${t.rows.size} rows × ${t.columns} columns) from ${page.url}\n"
                return@guard head + t.compact(OUTPUT_BUDGET - head.length - 10)
            }
            val blocks = tables.mapIndexed { i, t ->
                "\nTable ${i + 1}" + (if (t.caption.isNotBlank()) ": ${t.caption.take(200)}" else "") + " (${t.rows.size} rows × ${t.columns} columns)\n" + t.compact(600, 4) + "\n"
            }
            fit("${tables.size} tables on ${page.url}. Call page_tables with index=N for a whole table.\n", blocks, "") { "\n($it more tables not shown)" }
        }

    fun find(url: String, query: String): String =
        guard("open the page") {
            if (query.isBlank()) return@guard "Say what to look for."
            val (text, at) = when (val l = load(parse(url), save = false)) {
                is Loaded.Html -> Readability.fullText(HtmlParser.parse(l.page.text)) to l.page.url
                is Loaded.Text -> l.page.text to l.page.url
                else -> return@guard documentNote(l).orEmpty()
            }
            val q = query.take(200)
            val found = PageExtract.find(text, q)
            if (found.isEmpty()) return@guard "Nothing on $at mentions '$q'. Try other words, or read_web_page."
            fit("Found ${found.size} passage(s) about '$q' on $at:\n", found.mapIndexed { i, p -> "${i + 1}. $p\n" }, "") { "($it more passages not shown)" }
        }

    // ---------- searching ----------

    class SearchOutcome(val provider: String, val results: List<SearchResult>, val notes: List<String>)

    private fun duckDuckGo(endpoint: String, query: String, recency: String?, lite: Boolean): Pair<List<SearchResult>, String?> {
        val body = "q=" + enc(query) + (recency?.let { "&df=$it" } ?: "")
        val page = http.fetch(HttpRequest(WebUrl.parse(endpoint)!!, "POST", body.toByteArray(), "application/x-www-form-urlencoded", desktop = !lite))
        if (SearchParsers.duckDuckGoBlocked(page.status, page.text)) return emptyList<SearchResult>() to BOT_CHECK
        if (!page.ok) return emptyList<SearchResult>() to "DuckDuckGo answered HTTP ${page.status}"
        val results = if (lite) SearchParsers.duckDuckGoLite(page.text) else SearchParsers.duckDuckGoHtml(page.text)
        return results to if (results.isEmpty()) "DuckDuckGo returned no results" else null
    }

    private fun wikipediaSearch(query: String, count: Int, lang: String = "en"): List<SearchResult> {
        val url = "https://$lang.wikipedia.org/w/api.php?action=query&list=search&format=json&utf8=1&srlimit=$count&srsearch=" + enc(query)
        val page = http.fetch(HttpRequest(WebUrl.parse(url)!!, accept = HttpRequest.ACCEPT_JSON))
        if (!page.ok) throw IOException("Wikipedia answered HTTP ${page.status}")
        return SearchParsers.wikipediaSearch(page.text, lang)
    }

    /**
     * DuckDuckGo's HTML page, then its lite page, then Wikipedia. Each fallback is used only when
     * the previous provider is blocked, fails or finds nothing, and the notes say what happened.
     */
    fun searchResults(query: String, count: Int, site: String?, recency: String?): SearchOutcome {
        val q = query.trim() + (site?.let { " site:$it" } ?: "")
        val df = when (recency?.lowercase()) {
            "day" -> "d"
            "week" -> "w"
            "month" -> "m"
            "year" -> "y"
            else -> null
        }
        val notes = mutableListOf<String>()
        if (now() < duckDuckGoPausedUntil) {
            notes += "DuckDuckGo skipped: it asked for a bot check a few minutes ago"
        } else {
            var botCheck = false
            for ((name, endpoint, lite) in listOf(
                Triple("DuckDuckGo", "https://html.duckduckgo.com/html/", false),
                Triple("DuckDuckGo Lite", "https://lite.duckduckgo.com/lite/", true),
            )) {
                try {
                    val (results, note) = duckDuckGo(endpoint, q, df, lite)
                    if (results.isNotEmpty()) return SearchOutcome(name, results.take(count), notes)
                    note?.let { notes += "$name: $it" }
                    if (note == BOT_CHECK) botCheck = true
                } catch (e: IOException) {
                    notes += "$name failed: ${e.message}"
                }
            }
            // No retries or workarounds: leave the service alone for a while.
            if (botCheck) duckDuckGoPausedUntil = now() + DUCKDUCKGO_PAUSE_MS
        }
        if (site != null && !site.endsWith("wikipedia.org")) return SearchOutcome("none", emptyList(), notes)
        return try {
            val r = wikipediaSearch(query, count)
            notes += "Showing Wikipedia results instead" + if (df != null) " (no date filter)" else ""
            SearchOutcome("Wikipedia", r, notes)
        } catch (e: IOException) {
            notes += "Wikipedia failed: ${e.message}"
            SearchOutcome("none", emptyList(), notes)
        }
    }

    fun search(query: String, count: Int, site: String?, recency: String?): String =
        guard("search") {
            if (query.isBlank()) return@guard "Say what to search for."
            val siteHost = site?.trim()?.takeIf { it.isNotEmpty() }?.let { s ->
                WebUrl.parse(s)?.host?.removePrefix("www.") ?: return@guard "'$s' is not a website address; use a domain like wikipedia.org."
            }
            val key = "s|$query|$count|$siteHost|$recency"
            cachedSearch(key)?.let { return@guard it }
            val out = searchResults(query, count, siteHost, recency)
            val q = query.take(200)
            val notes = if (out.notes.isNotEmpty()) " (${out.notes.joinToString("; ").take(400)})" else ""
            val text = if (out.results.isEmpty()) {
                "No results for '$q'.$notes Try different words, or open_in_chrome to search on screen."
            } else {
                val items = out.results.mapIndexed { i, r ->
                    "${i + 1}. ${r.title.take(200)}\n   ${r.url.take(400)}\n" +
                        (if (r.snippet.isNotBlank()) "   ${r.snippet.take(240)}\n" else "") + (r.date?.let { "   Date: $it\n" } ?: "")
                }
                fit("Results from ${out.provider} for '$q'$notes:\n", items, "Use read_web_page with a result's address to read it.") { "($it more results not shown)" }
            }
            if (out.results.isNotEmpty()) putSearch(key, text)
            text
        }

    private fun cachedSearch(key: String): String? =
        synchronized(searches) { searches[key]?.takeIf { System.currentTimeMillis() - it.at < 10 * 60_000 }?.value }

    private fun putSearch(key: String, value: String) = synchronized(searches) { searches[key] = Cached(System.currentTimeMillis(), value) }

    fun news(query: String, count: Int): String =
        guard("get the news") {
            if (query.isBlank()) return@guard "Say what news to look for."
            val url = "https://news.google.com/rss/search?q=" + enc(query.trim()) + "&hl=en-US&gl=US&ceid=US:en"
            val page = http.fetch(HttpRequest(WebUrl.parse(url)!!, accept = HttpRequest.ACCEPT_FEED))
            if (!page.ok) return@guard "Google News answered HTTP ${page.status}. Try search_internet with recency=day instead."
            val items = Feeds.parse(page.text).take(count)
            val q = query.take(200)
            if (items.isEmpty()) return@guard "No news found for '$q'."
            val lines = items.mapIndexed { i, it ->
                val sb = StringBuilder()
                sb.append(i + 1).append(". ").append(Feeds.headline(it).take(250))
                it.source?.let { s -> sb.append(" — ").append(s.take(80)) }
                it.sourceUrl?.let { u -> WebUrl.parse(u)?.host?.removePrefix("www.")?.let { h -> sb.append(" (").append(h).append(")") } }
                sb.append('\n')
                it.date?.let { d -> sb.append("   ").append(d.take(60)).append('\n') }
                sb.append("   ").append(it.link.take(400)).append('\n')
                sb.toString()
            }
            fit(
                "News for '$q' from Google News (newest stories may be first or mixed):\n",
                lines,
                "The links are Google News redirect pages. To read a story, use read_rendered_page with its link, or search_internet for the headline with site set to the publisher.",
            ) { "($it more stories not shown)" }
        }

    fun wikipedia(topic: String, lang: String = "en"): String =
        guard("reach Wikipedia") {
            val t = topic.trim()
            if (t.isEmpty()) return@guard "Say which topic to look up."
            val l = lang.trim().lowercase().takeIf { Regex("^[a-z]{2,3}(-[a-z]{2,8})?$").matches(it) } ?: return@guard "'$lang' is not a Wikipedia language code like en or hi."
            fun summary(title: String): SearchParsers.WikiSummary? {
                val page = http.fetch(HttpRequest(WebUrl.parse("https://$l.wikipedia.org/api/rest_v1/page/summary/" + SearchParsers.wikiPath(title) + "?redirect=true")!!, accept = HttpRequest.ACCEPT_JSON))
                return if (page.ok) SearchParsers.wikipediaSummary(page.text) else null
            }
            var s = summary(t)
            var others: List<SearchResult> = emptyList()
            if (s == null || s.disambiguation) {
                others = wikipediaSearch(t, 5, l)
                val best = others.firstOrNull { it.title != s?.title }
                if (s == null && best != null) s = summary(best.title)
            }
            if (s == null) return@guard "Wikipedia has no article on '$t'." + if (others.isNotEmpty()) " Close matches: ${others.joinToString(", ") { it.title }}." else ""
            val sb = StringBuilder(s.title)
            if (s.description.isNotBlank()) sb.append(" — ").append(s.description)
            sb.append('\n').append(s.extract.trim().take(OUTPUT_BUDGET - 800)).append('\n')
            if (s.disambiguation && others.isNotEmpty()) sb.append("'$t' has several meanings. Articles: ").append(others.joinToString(", ") { it.title }).append('\n')
            sb.append("Source: ").append(s.url.ifBlank { "https://$l.wikipedia.org/wiki/" + SearchParsers.wikiPath(s.title) })
            sb.toString()
        }

    // ---------- files and lookups ----------

    fun download(url: String, filename: String?): String =
        guard("download the file") {
            val d = downloader ?: return@guard "Downloads are not available here."
            val u = parse(url)
            http.open(HttpRequest(u, accept = HttpRequest.ACCEPT_ANY), deadlineMs = 180_000).use { r ->
                if (r.status !in 200..299) return@guard statusMessage(r.status, r.url)
                val disposition = r.header("content-disposition")
                val kind = Downloader.kindOf(r.mime, r.url, disposition)
                    ?: return@guard "This address is a ${r.mime.ifEmpty { "file of unknown type" }}" +
                        (if (r.mime == "text/html") " (a web page; use read_web_page)" else "") +
                        ". Only PDF, Word, Excel, PowerPoint, OpenDocument, RTF, CSV, text and image files can be downloaded."
                val saved = d.save(r.body, kind, r.contentLength, r.url, disposition, filename)
                "Saved the ${saved.kind.label} (${Downloader.mb(saved.bytes)}) to ${saved.relativePath}. Use that path with the document tools."
            }
        }

    fun weather(place: String, days: Int): String =
        guard("get the weather") {
            if (place.isBlank()) return@guard "Say which place."
            val geo = http.fetch(HttpRequest(WebUrl.parse(Weather.geocodeUrl(place))!!, accept = HttpRequest.ACCEPT_JSON))
            if (!geo.ok) return@guard "The weather service answered HTTP ${geo.status}."
            val p = Weather.pickPlace(geo.text, place) ?: return@guard "No place called '$place' was found. Try the city name, optionally with the country, like 'Pune, India'."
            val f = http.fetch(HttpRequest(WebUrl.parse(Weather.forecastUrl(p, days))!!, accept = HttpRequest.ACCEPT_JSON))
            if (!f.ok) return@guard "The weather service answered HTTP ${f.status}."
            Weather.format(f.text, p)
        }

    fun currency(from: String, to: String, amount: Double): String =
        guard("get the exchange rate") {
            val f = Currency.code(from) ?: return@guard "'$from' is not a 3-letter currency code like USD or INR."
            val t = Currency.code(to) ?: return@guard "'$to' is not a 3-letter currency code like USD or INR."
            if (amount.isNaN() || amount < 0) return@guard "The amount must be a positive number."
            if (f == t) return@guard Currency.format(Currency.Rate(f, t, 1.0, "today", "same currency"), amount)
            val primary = runCatching { http.fetch(HttpRequest(WebUrl.parse(Currency.frankfurterUrl(f, t))!!, accept = HttpRequest.ACCEPT_JSON)) }.getOrNull()
            val rate = primary?.takeIf { it.ok }?.let { Currency.parseFrankfurter(it.text, f, t) }
                ?: http.fetch(HttpRequest(WebUrl.parse(Currency.erApiUrl(f))!!, accept = HttpRequest.ACCEPT_JSON)).takeIf { it.ok }?.let { Currency.parseErApi(it.text, f, t) }
                ?: return@guard "No exchange rate found for $f to $t. Check the currency codes."
            Currency.format(rate, amount)
        }

    // ---------- deep research ----------

    private val robots = object : LinkedHashMap<String, Robots>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Robots>?) = size > 64
    }

    /** robots.txt for automated research reads: missing (4xx) allows everything, a server error (5xx) allows nothing. */
    fun robotsAllow(url: WebUrl): Boolean {
        val rules = synchronized(robots) { robots[url.origin] } ?: run {
            val r = try {
                val page = http.fetch(HttpRequest(WebUrl.parse(url.origin + "/robots.txt")!!, accept = "text/plain,*/*;q=0.5"), 500_000)
                when {
                    page.ok && (page.mime.isEmpty() || page.mime.startsWith("text/")) -> Robots.parse(page.text)
                    page.status in 500..599 -> Robots.DISALLOW_ALL
                    else -> Robots.ALLOW_ALL
                }
            } catch (_: BlockedUrlException) {
                Robots.DISALLOW_ALL
            } catch (_: IOException) {
                Robots.ALLOW_ALL
            }
            synchronized(robots) { robots[url.origin] = r }
            r
        }
        return rules.allowed(url.target)
    }

    /** The web as deep research sees it: search with fallbacks, robots-respecting reads, no document saving. */
    val researchWeb: ResearchWeb =
        object : ResearchWeb {
            override suspend fun search(query: String, count: Int): List<SearchResult> =
                withContext(Dispatchers.IO) { searchResults(query, count, null, null).results }

            override suspend fun read(url: String): ResearchPage? =
                withContext(Dispatchers.IO) {
                    val u = WebUrl.parse(url) ?: return@withContext null
                    if (!robotsAllow(u)) throw IOException("the site's robots.txt asks automated tools not to read it")
                    when (val l = load(u, save = false)) {
                        is Loaded.Html -> article(l.page).let { ResearchPage(l.page.url.toString(), it.title, it.text, it.published) }
                        is Loaded.Text -> if (l.page.mime == "text/plain") ResearchPage(l.page.url.toString(), l.page.url.fileName, l.page.text) else null
                        else -> null
                    }
                }
        }
}
