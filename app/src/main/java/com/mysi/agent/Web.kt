package com.mysi.agent

import android.net.Uri
import android.text.Html
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Plain HTTP for web search, page reading and AI content reports. Call off the main thread. */
object Web {
    private const val AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    private const val MAX_BYTES = 2_000_000

    private fun connect(url: String): HttpURLConnection {
        val u = URL(url)
        if (u.protocol != "https" && u.protocol != "http") throw IOException("Only web addresses are allowed.")
        return (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", AGENT)
            setRequestProperty("Accept-Language", "en")
        }
    }

    /** GET, or POST when [body] is given, with timeouts and a 2 MB cap so a huge page cannot exhaust memory. */
    fun fetch(url: String, body: String? = null, contentType: String = "application/x-www-form-urlencoded"): String =
        request(url, body, contentType).first

    /** Like [fetch], but also returns the address the response finally came from, after redirects. */
    fun request(url: String, body: String? = null, contentType: String = "application/x-www-form-urlencoded"): Pair<String, URL> {
        val c = connect(url)
        try {
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val bytes = c.inputStream.use { input ->
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(16_384)
                while (buf.size() < MAX_BYTES) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray()
            }
            return String(bytes, Charsets.UTF_8) to c.url
        } finally {
            c.disconnect()
        }
    }

    private fun plain(html: String) =
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()

    /** DuckDuckGo's no-JavaScript endpoint: plain HTML results, no API key, no tracking cookies. */
    fun search(query: String, count: Int): Any {
        val html = fetch("https://html.duckduckgo.com/html/", "q=" + Uri.encode(query))
        val links = Regex("""<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        val snippets = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(html).map { plain(it.groupValues[1]) }.toList()
        val results = links.mapIndexedNotNull { i, m ->
            var href = Html.fromHtml(m.groupValues[1], Html.FROM_HTML_MODE_LEGACY).toString()
            if (href.startsWith("//")) href = "https:$href"
            // Results link through a redirect; the real address is in the uddg parameter.
            Uri.parse(href).getQueryParameter("uddg")?.let { href = it }
            if (href.contains("duckduckgo.com/y.js")) null // sponsored
            else mapOf("title" to plain(m.groupValues[2]), "url" to href, "snippet" to snippets.getOrElse(i) { "" })
        }
        return results.take(count).ifEmpty { "No results, or the search page changed format. Try open_in_chrome instead." }
    }

    fun read(url: String, max: Int): String {
        val full = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        val html = fetch(full)
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(html)?.groupValues?.get(1)?.let(::plain).orEmpty()
        val body = html
            .replace(Regex("<(script|style|noscript|svg|nav|footer|header)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
        val text = plain(body)
        return "Title: $title\n${text.take(max)}" + if (text.length > max) " …(truncated)" else ""
    }
}
