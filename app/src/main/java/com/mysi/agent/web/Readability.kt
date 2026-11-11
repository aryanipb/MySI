package com.mysi.agent.web

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.IdentityHashMap

/** The readable part of a web page. */
class Article(
    val url: String,
    val title: String,
    val byline: String?,
    val published: String?,
    val siteName: String?,
    val description: String?,
    /** Main text: headings, paragraphs and lists as plain text. */
    val text: String,
    /** True when the page looks like it builds its content with JavaScript. */
    val needsJavaScript: Boolean,
)

/**
 * Readability-style extraction: drop navigation, sidebars, footers, cookie banners and other
 * boilerplate; score text-dense blocks (length, commas, link density, class names) and their
 * ancestors; take the best block plus related siblings. If that finds too little (a listing page,
 * an unusual layout) it relaxes in two steps, the last being all visible body text.
 */
object Readability {
    private val UNLIKELY = Regex(
        "-ad-|ai2html|banner|breadcrumb|combx|comment|community|cover-wrap|disqus|extra|footer|gdpr|header|legends|menu|related|" +
            "remark|replies|rss|shoutbox|sidebar|skyscraper|social|sponsor|supplemental|ad-break|agegate|pagination|pager|popup|" +
            "yom-remote|newsletter|subscribe|signup|share|sharing|promo|outbrain|taboola|toolbar|skip-link|modal|overlay|masthead",
        RegexOption.IGNORE_CASE,
    )
    private val MAYBE = Regex("and|article|body|column|content|main|mathjax|shadow", RegexOption.IGNORE_CASE)

    /** Consent walls and similar overlays: removed even when their class also says "content". */
    private val ALWAYS_NOISE = Regex(
        "cookie|consent|gdpr|ccpa|onetrust|cookiebot|didomi|qc-cmp|truste|cmp-container|sp_message|fc-consent|paywall|adblock",
        RegexOption.IGNORE_CASE,
    )
    private val POSITIVE = Regex("article|body|content|entry|hentry|h-entry|main|page|post|text|blog|story", RegexOption.IGNORE_CASE)
    private val NEGATIVE = Regex(
        "-ad-|hidden|^hid$| hid$| hid |^hid |banner|combx|comment|com-|contact|foot|footer|footnote|gdpr|masthead|media|meta|" +
            "outbrain|promo|related|scroll|share|shoutbox|sidebar|skyscraper|sponsor|shopping|tags|tool|widget|cookie|consent",
        RegexOption.IGNORE_CASE,
    )
    private val NOISE_ROLES = setOf("navigation", "banner", "contentinfo", "complementary", "search", "dialog", "alertdialog", "menu", "menubar", "toolbar", "tablist")
    private val NOISE_TAGS = setOf("nav", "aside", "footer", "menu", "form")
    private val PARAGRAPHS = setOf("p", "pre", "td", "blockquote")
    private val DIV_LIKE = setOf("div", "section", "article")
    private val HIDDEN_STYLE = Regex("display\\s*:\\s*none|visibility\\s*:\\s*hidden", RegexOption.IGNORE_CASE)
    private const val MIN_TEXT = 250

    fun extract(html: String, url: String): Article = extract(HtmlParser.parse(html), url)

    fun extract(doc: Element, url: String): Article {
        val meta = Metadata.read(doc)
        val body = doc.first("body") ?: doc
        val stats = Stats(body)
        val keep = protectedElements(body)
        var best = ""
        for (pass in 1..3) {
            val removed = noise(body, keep, strict = pass == 1)
            val text = if (pass == 3) TextRenderer.render(listOf(body), removed) else mainContent(body, stats, removed, weights = pass == 1)
            if (text.length > best.length) best = text
            if (text.length >= MIN_TEXT) {
                best = text
                break
            }
        }
        val needsJs = best.length < 200 && looksScripted(doc)
        return Article(url, meta.title, meta.byline, meta.published, meta.siteName, meta.description, best, needsJs)
    }

    /** All visible text of the page (scripts, styles and hidden parts removed), for searching. */
    fun fullText(doc: Element): String {
        val body = doc.first("body") ?: doc
        val removed = TextRenderer.identitySet()
        body.descendants().filter { hidden(it) }.forEach { removed += it }
        return TextRenderer.render(listOf(body), removed)
    }

    private fun looksScripted(doc: Element): Boolean {
        val scripts = doc.all("script").size
        val root = doc.descendants().any { it.id in setOf("root", "app", "__next", "__nuxt", "main-app") && it.textLength() < 50 }
        val notice = doc.all("noscript").any { it.text().contains("javascript", ignoreCase = true) }
        return root || notice || scripts >= 8
    }

    private fun hidden(el: Element) =
        el.has("hidden") || el.attr("aria-hidden") == "true" || HIDDEN_STYLE.containsMatchIn(el.attr("style")) ||
            el.attr("type") == "hidden"

    /** Text and link-text lengths for every element, computed bottom-up in one pass. */
    private class Stats(root: Element) {
        private val text = IdentityHashMap<Element, Int>()
        private val links = IdentityHashMap<Element, Int>()
        private val commas = IdentityHashMap<Element, Int>()

        init {
            val stack = ArrayDeque<Pair<Element, Boolean>>()
            stack.addLast(root to false)
            while (stack.isNotEmpty()) {
                val (el, done) = stack.removeLast()
                if (!done) {
                    stack.addLast(el to true)
                    for (c in el.children) if (c is Element) stack.addLast(c to false)
                    continue
                }
                var t = 0
                var l = 0
                var c = 0
                if (el.tag !in TextRenderer.SKIP) {
                    for (ch in el.children) {
                        when (ch) {
                            is TextNode -> {
                                t += Element.collapse(ch.text).length
                                c += ch.text.count { it == ',' || it == '，' }
                            }
                            is Element -> {
                                t += text[ch] ?: 0
                                l += links[ch] ?: 0
                                c += commas[ch] ?: 0
                            }
                        }
                    }
                }
                if (el.tag == "a") l = t
                text[el] = t
                links[el] = l
                commas[el] = c
            }
        }

        fun text(el: Element) = text[el] ?: 0

        fun commas(el: Element) = commas[el] ?: 0

        fun linkDensity(el: Element): Double {
            val t = text(el)
            return if (t == 0) 0.0 else (links[el] ?: 0).toDouble() / t
        }
    }

    private fun signature(el: Element) = el.attr("class") + " " + el.id

    /**
     * Elements that hold the page's main content: main, article, h1, role=main, itemprop=articleBody
     * and all their ancestors. Tag and class-name rules never remove these, so a wrapper such as
     * `<div class="page has-cookie-banner">` cannot take the article with it.
     */
    private fun protectedElements(body: Element): Set<Element> {
        val out = TextRenderer.identitySet()
        for (el in body.descendants()) {
            if (el.tag == "main" || el.tag == "article" || el.tag == "h1" || el.attr("role") == "main" || el.attr("itemprop") == "articleBody") {
                out += el
                for (a in el.ancestors()) if (!out.add(a)) break
            }
        }
        return out
    }

    /**
     * Elements to leave out: hidden parts, scripts and media, and dialogs (consent walls) always;
     * navigation, sidebars, footers, consent classes and (when [strict]) unlikely class names
     * unless they hold the main content ([keep]).
     */
    private fun noise(body: Element, keep: Set<Element>, strict: Boolean): Set<Element> {
        val removed = TextRenderer.identitySet()
        val stack = ArrayDeque<Element>().apply { addAll(body.elementChildren.reversed()) }
        while (stack.isNotEmpty()) {
            val el = stack.removeLast()
            val tag = el.tag
            val sig = signature(el)
            val role = el.attr("role").lowercase()
            val dialog = el.attr("aria-modal") == "true" || role == "dialog" || role == "alertdialog"
            val drop = when {
                tag == "body" || tag == "html" -> false
                hidden(el) -> true
                tag in TextRenderer.SKIP -> true
                dialog -> el !in keep || ALWAYS_NOISE.containsMatchIn(sig)
                el in keep -> false
                role in NOISE_ROLES -> true
                tag in NOISE_TAGS -> true
                tag == "header" && !el.hasAncestor("article") && !el.hasAncestor("main") -> true
                ALWAYS_NOISE.containsMatchIn(sig) -> true
                strict && tag != "a" && UNLIKELY.containsMatchIn(sig) && !MAYBE.containsMatchIn(sig) &&
                    !el.hasAncestor("table") && !el.hasAncestor("code") -> true
                else -> false
            }
            if (drop) removed += el else for (c in el.elementChildren.reversed()) stack.addLast(c)
        }
        return removed
    }

    /** Elements under [root] in document order, not descending into [removed] ones. */
    private fun liveElements(root: Element, removed: Set<Element>): List<Element> {
        val out = ArrayList<Element>()
        val stack = ArrayDeque<Element>().apply { addAll(root.elementChildren.reversed()) }
        while (stack.isNotEmpty()) {
            val el = stack.removeLast()
            if (el in removed) continue
            out += el
            for (c in el.elementChildren.reversed()) stack.addLast(c)
        }
        return out
    }

    private fun classWeight(el: Element): Int {
        var w = 0
        for (s in listOf(el.attr("class"), el.id)) {
            if (s.isEmpty()) continue
            if (NEGATIVE.containsMatchIn(s)) w -= 25
            if (POSITIVE.containsMatchIn(s)) w += 25
        }
        return w
    }

    private fun baseScore(el: Element, weights: Boolean): Double {
        val tagScore = when (el.tag) {
            "div" -> 5
            "pre", "td", "blockquote" -> 3
            "address", "ol", "ul", "dl", "dd", "dt", "li", "form" -> -3
            "h1", "h2", "h3", "h4", "h5", "h6", "th" -> -5
            else -> 0
        }
        return (tagScore + if (weights) classWeight(el) else 0).toDouble()
    }

    private fun isParagraph(el: Element): Boolean =
        el.tag in PARAGRAPHS || (el.tag in DIV_LIKE && el.elementChildren.none { it.tag in TextRenderer.BLOCK })

    private fun mainContent(body: Element, stats: Stats, removed: Set<Element>, weights: Boolean): String {
        val scores = IdentityHashMap<Element, Double>()
        val live = liveElements(body, removed)
        // Prefer an explicit article body when the page marks one and it holds real text.
        live.firstOrNull { it.attr("itemprop") == "articleBody" && stats.text(it) >= 500 && stats.linkDensity(it) < 0.3 }
            ?.let { return render(listOf(it), stats, removed) }
        for (p in live) {
            if (!isParagraph(p)) continue
            val len = stats.text(p)
            if (len < 25) continue
            val score = 1.0 + stats.commas(p) + minOf(len / 100.0, 3.0)
            var level = 0
            for (a in p.ancestors()) {
                if (level > 2 || a.tag == "#document") break
                if (!scores.containsKey(a)) scores[a] = baseScore(a, weights)
                scores[a] = scores.getValue(a) + when (level) { 0 -> score; 1 -> score / 2; else -> score / (level * 3) }
                level++
            }
        }
        if (scores.isEmpty()) return ""
        val ranked = scores.entries.map { it.key to it.value * (1 - stats.linkDensity(it.key)) }.sortedByDescending { it.second }
        var top = ranked.first().first
        val topScore = ranked.first().second
        // Several near-best candidates under one ancestor means the article is split into blocks: use the ancestor.
        val rivals = ranked.drop(1).take(4).filter { it.second >= topScore * 0.75 }.map { it.first }
        if (rivals.size >= 2) {
            for (a in top.ancestors()) {
                if (a.tag == "#document" || a.tag == "html") break
                if (rivals.count { r -> r === a || r.ancestors().any { it === a } } >= 2) {
                    top = a
                    break
                }
            }
        }
        val finalScores = IdentityHashMap<Element, Double>().apply { ranked.forEach { put(it.first, it.second) } }
        val parent = top.parent
        val parts = mutableListOf<Element>()
        if (parent == null || parent.tag == "#document") {
            parts += top
        } else {
            val threshold = maxOf(10.0, (finalScores[top] ?: topScore) * 0.2)
            for (sib in parent.elementChildren) {
                if (sib in removed) continue
                val include = when {
                    sib === top -> true
                    (finalScores[sib] ?: Double.NEGATIVE_INFINITY) >= threshold -> true
                    sib.tag == "p" -> {
                        val len = stats.text(sib)
                        val ld = stats.linkDensity(sib)
                        (len > 80 && ld < 0.25) || (len in 1..80 && ld == 0.0 && sib.text().trimEnd().lastOrNull() in setOf('.', '!', '?'))
                    }
                    sib.tag in setOf("h2", "h3", "h4") -> true
                    else -> false
                }
                if (include) parts += sib
            }
            // Headings kept as siblings only make sense next to included text.
            while (parts.firstOrNull()?.tag in setOf("h2", "h3", "h4") && parts.size > 1 && parts.first() !== top) parts.removeAt(0)
            while (parts.lastOrNull()?.tag in setOf("h2", "h3", "h4") && parts.size > 1 && parts.last() !== top) parts.removeAt(parts.size - 1)
        }
        return render(parts, stats, removed)
    }

    /** Renders the chosen blocks after dropping link farms (related links, tag lists) inside them. */
    private fun render(parts: List<Element>, stats: Stats, removed: Set<Element>): String {
        val drop = TextRenderer.identitySet().apply { addAll(removed) }
        for (root in parts) {
            for (el in root.descendants()) {
                if (el.tag !in setOf("ul", "ol", "div", "section", "table", "nav", "aside")) continue
                val len = stats.text(el)
                val ld = stats.linkDensity(el)
                val dataTable = el.tag == "table" && el.all("tr").size >= 2
                if (!dataTable && ((ld > 0.5 && len < 2000) || (classWeight(el) < 0 && len < 300))) drop += el
            }
        }
        return TextRenderer.render(parts, drop)
    }
}

/** Title, byline, date and site name from meta tags, JSON-LD and common markup. */
internal object Metadata {
    class Meta(val title: String, val byline: String?, val published: String?, val siteName: String?, val description: String?)

    private val ARTICLE_TYPES = setOf("article", "newsarticle", "blogposting", "reportagenewsarticle", "techarticle", "scholarlyarticle", "analysisnewsarticle", "report", "webpage", "medicalwebpage")

    fun read(doc: Element): Meta {
        val metas = HashMap<String, String>()
        for (m in doc.all("meta")) {
            val key = (m.attr("property").ifEmpty { m.attr("name") }.ifEmpty { m.attr("itemprop") }).lowercase()
            val content = m.attr("content").trim()
            if (key.isNotEmpty() && content.isNotEmpty() && key !in metas) metas[key] = Element.collapse(content)
        }
        val ld = jsonLd(doc)
        val h1s = doc.all("h1").map { it.text() }.filter { it.isNotEmpty() }
        val docTitle = doc.first("title")?.text().orEmpty()
        val title = metas["og:title"] ?: metas["twitter:title"] ?: ld?.str("headline") ?: docTitle.ifEmpty { h1s.firstOrNull().orEmpty() }
        val byline = listOfNotNull(
            metas["author"], metas["article:author"], metas["parsely-author"], metas["sailthru.author"], metas["dc.creator"],
            ld?.let { authors(it) },
            doc.descendants().firstOrNull { it.attr("rel") == "author" || it.attr("itemprop") == "author" }?.text(),
            doc.descendants().firstOrNull { el -> el.classes.any { it.contains("byline", true) } }?.text(),
        ).map { it.trim() }.firstOrNull { it.isNotEmpty() && it.length < 120 && !it.startsWith("http") }
        val published = listOfNotNull(
            metas["article:published_time"], metas["og:published_time"], metas["datepublished"], metas["date"], metas["pubdate"],
            metas["publishdate"], metas["publish-date"], metas["dc.date"], metas["dc.date.issued"], metas["dcterms.date"],
            metas["dcterms.created"], metas["parsely-pub-date"], metas["sailthru.date"], ld?.str("datePublished"),
            doc.descendants().firstOrNull { it.attr("itemprop") == "datePublished" }?.let { it.attr("datetime").ifEmpty { it.attr("content") }.ifEmpty { it.text() } },
            doc.first("time")?.let { it.attr("datetime").ifEmpty { it.text() } },
        ).map { it.trim() }.firstOrNull { it.isNotEmpty() && it.length < 60 }
        val siteName = metas["og:site_name"] ?: metas["application-name"] ?: ld?.obj("publisher")?.str("name")
        val description = metas["og:description"] ?: metas["description"] ?: metas["twitter:description"]
        return Meta(Element.collapse(title), byline?.let(Element::collapse), published, siteName, description)
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.let { if (it.isJsonObject) it.asJsonObject else if (it.isJsonArray) it.asJsonArray.firstOrNull { e -> e.isJsonObject }?.asJsonObject else null }

    private fun JsonObject.type(): String {
        val t = get("@type") ?: return ""
        return (if (t.isJsonArray) t.asJsonArray.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString } else listOf(t.asString))
            .map { it.lowercase() }.firstOrNull { it in ARTICLE_TYPES } ?: t.toString().lowercase()
    }

    private fun authors(o: JsonObject): String? {
        val a = o.get("author") ?: return null
        val list: List<JsonElement> = if (a.isJsonArray) a.asJsonArray.toList() else listOf(a)
        return list.mapNotNull { e ->
            when {
                e.isJsonPrimitive -> e.asString
                e.isJsonObject -> e.asJsonObject.str("name")
                else -> null
            }
        }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /** The first JSON-LD object describing an article (or, failing that, the page). */
    private fun jsonLd(doc: Element): JsonObject? {
        val found = mutableListOf<JsonObject>()
        for (s in doc.all("script")) {
            if (!s.attr("type").contains("ld+json", ignoreCase = true)) continue
            val root = runCatching { JsonParser.parseString(s.text()) }.getOrNull() ?: continue
            val queue = ArrayDeque<JsonElement>().apply { add(root) }
            var guard = 0
            while (queue.isNotEmpty() && guard++ < 500) {
                val e = queue.removeFirst()
                when {
                    e.isJsonArray -> (e as JsonArray).forEach { queue.add(it) }
                    e.isJsonObject -> {
                        val o = e.asJsonObject
                        if (o.has("@type")) found += o
                        o.get("@graph")?.let { queue.add(it) }
                    }
                }
            }
        }
        return found.firstOrNull { it.type() in ARTICLE_TYPES && it.type() != "webpage" } ?: found.firstOrNull { it.type() == "webpage" }
    }
}
