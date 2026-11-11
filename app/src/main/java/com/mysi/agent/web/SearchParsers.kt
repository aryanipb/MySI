package com.mysi.agent.web

import com.google.gson.JsonObject
import com.google.gson.JsonParser

class SearchResult(val title: String, val url: String, val snippet: String, val date: String? = null, val source: String? = null) {
    fun toMap(): Map<String, String> =
        buildMap {
            put("title", title)
            put("url", url)
            if (snippet.isNotEmpty()) put("snippet", snippet)
            date?.let { put("date", it) }
            source?.let { put("source", it) }
        }
}

/** Parsers for search pages and APIs. They return [] when a page has no results, and throw nothing. */
object SearchParsers {
    /**
     * DuckDuckGo wraps result links as //duckduckgo.com/l/?uddg=<real url>; returns the real
     * address, or null for its ad redirects (y.js) and anything that is not a web page.
     */
    fun unwrapDuckDuckGo(href: String): String? {
        var h = href.trim()
        if (h.startsWith("//")) h = "https:$h"
        if (h.startsWith("/")) h = "https://duckduckgo.com$h"
        val url = WebUrl.parse(h) ?: return null
        if (url.host.endsWith("duckduckgo.com")) {
            if (url.rawPath.startsWith("/y.js")) return null
            if (url.rawPath.startsWith("/l/")) return url.query("uddg")?.let { WebUrl.parse(it) }?.toString()
            return null
        }
        return url.toString()
    }

    /** True when DuckDuckGo answered with its bot check instead of results. */
    fun duckDuckGoBlocked(status: Int, html: String) =
        status == 202 || status == 403 || status == 429 || html.contains("anomaly-modal") || html.contains("challenge-form")

    private fun dedupe(results: List<SearchResult>): List<SearchResult> {
        val seen = HashSet<String>()
        return results.filter { r -> WebUrl.parse(r.url)?.canonical?.let { seen.add(it) } ?: false }
    }

    /** html.duckduckgo.com results. Ads (result--ad, y.js links) are dropped. */
    fun duckDuckGoHtml(html: String): List<SearchResult> {
        val doc = HtmlParser.parse(html)
        val out = mutableListOf<SearchResult>()
        for (block in doc.descendants().filter { el -> el.classes.contains("result") || el.classes.contains("web-result") }) {
            if (block.classes.any { it == "result--ad" || it.startsWith("result--ad") } || block.ancestors().any { a -> a.classes.contains("result") }) continue
            val link = block.descendants().firstOrNull { it.tag == "a" && it.classes.contains("result__a") } ?: continue
            val url = unwrapDuckDuckGo(link.attr("href")) ?: continue
            val snippet = block.descendants().firstOrNull { it.classes.contains("result__snippet") }?.text().orEmpty()
            val date = block.descendants().firstOrNull { it.classes.contains("result__extras__url") }
                ?.children?.filterIsInstance<Element>()?.lastOrNull { it.tag == "span" && !it.classes.contains("result__icon") }
                ?.text()?.trim()?.takeIf { Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(it) }?.take(10)
            val title = link.text()
            if (title.isNotEmpty()) out += SearchResult(title, url, snippet, date)
        }
        return dedupe(out)
    }

    /** lite.duckduckgo.com results: a table of link, snippet and address rows; sponsored rows dropped. */
    fun duckDuckGoLite(html: String): List<SearchResult> {
        val doc = HtmlParser.parse(html)
        val out = mutableListOf<SearchResult>()
        val rows = doc.all("tr")
        for ((i, tr) in rows.withIndex()) {
            val link = tr.descendants().firstOrNull { it.tag == "a" && it.classes.contains("result-link") } ?: continue
            if (tr.classes.any { it.contains("sponsored") } || link.ancestors().any { a -> a.classes.any { it.contains("sponsored") } }) continue
            val url = unwrapDuckDuckGo(link.attr("href")) ?: continue
            val snippet = rows.drop(i + 1).take(2).firstNotNullOfOrNull { r ->
                r.descendants().firstOrNull { it.classes.contains("result-snippet") }?.text()
            }.orEmpty()
            val title = link.text()
            if (title.isNotEmpty()) out += SearchResult(title, url, snippet)
        }
        return dedupe(out)
    }

    private fun json(text: String): JsonObject? = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()

    private fun plain(htmlFragment: String) = HtmlParser.parse(htmlFragment).text()

    /** MediaWiki search API (action=query&list=search). */
    fun wikipediaSearch(jsonText: String, lang: String = "en"): List<SearchResult> {
        val arr = json(jsonText)?.getAsJsonObject("query")?.getAsJsonArray("search") ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val title = o.get("title")?.asString ?: return@mapNotNull null
            SearchResult(
                title,
                "https://$lang.wikipedia.org/wiki/" + wikiPath(title),
                plain(o.get("snippet")?.asString.orEmpty()),
                o.get("timestamp")?.asString?.take(10),
                "Wikipedia",
            )
        }
    }

    /**
     * Title as it appears in a Wikipedia URL path: first letter upper case (Wikipedia titles are
     * case-insensitive only there), spaces to underscores, the rest percent-encoded.
     */
    fun wikiPath(title: String): String =
        java.net.URLEncoder.encode(title.trim().replace(' ', '_').replaceFirstChar { it.uppercaseChar() }, "UTF-8")
            .replace("+", "_").replace("%3A", ":")

    class WikiSummary(val title: String, val description: String, val extract: String, val url: String, val disambiguation: Boolean)

    /** REST v1 /page/summary response; null when it is not a usable summary. */
    fun wikipediaSummary(jsonText: String): WikiSummary? {
        val o = json(jsonText) ?: return null
        val title = o.get("title")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val extract = o.get("extract")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val url = o.getAsJsonObject("content_urls")?.getAsJsonObject("desktop")?.get("page")?.asString.orEmpty()
        val type = o.get("type")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (extract.isBlank()) return null
        return WikiSummary(title, o.get("description")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(), extract, url, type == "disambiguation")
    }
}

/** RSS 2.0 and Atom feeds, parsed with the lenient XML mode of [HtmlParser] (no external entities, ever). */
object Feeds {
    class Item(val title: String, val link: String, val date: String?, val source: String?, val sourceUrl: String?, val summary: String)

    fun parse(xml: String): List<Item> {
        val doc = HtmlParser.parseXml(xml)
        val rss = doc.all("item")
        if (rss.isNotEmpty()) {
            return rss.map { it ->
                val source = it.first("source")
                Item(
                    title = Element.collapse(it.first("title")?.text().orEmpty()),
                    link = it.first("link")?.text()?.trim().orEmpty().ifEmpty { it.first("guid")?.text()?.trim().orEmpty() },
                    date = it.first("pubdate")?.text()?.trim() ?: it.first("dc:date")?.text()?.trim(),
                    source = source?.text()?.trim()?.takeIf { s -> s.isNotEmpty() },
                    sourceUrl = source?.attr("url")?.takeIf { s -> s.isNotEmpty() },
                    summary = HtmlParser.parse(it.first("description")?.text().orEmpty()).text(),
                )
            }.filter { it.title.isNotEmpty() }
        }
        return doc.all("entry").map { e ->
            val link = e.all("link").let { links -> links.firstOrNull { it.attr("rel").isEmpty() || it.attr("rel") == "alternate" } ?: links.firstOrNull() }
            Item(
                title = Element.collapse(e.first("title")?.text().orEmpty()),
                link = link?.attr("href").orEmpty(),
                date = (e.first("published") ?: e.first("updated"))?.text()?.trim(),
                source = e.first("author")?.first("name")?.text(),
                sourceUrl = null,
                summary = HtmlParser.parse((e.first("summary") ?: e.first("content"))?.text().orEmpty()).text(),
            )
        }.filter { it.title.isNotEmpty() }
    }

    /** Google News titles end with " - Publisher"; split that off when it matches the source. */
    fun headline(item: Item): String {
        val src = item.source ?: return item.title
        return if (item.title.endsWith(" - $src")) item.title.removeSuffix(" - $src").trim() else item.title
    }
}
