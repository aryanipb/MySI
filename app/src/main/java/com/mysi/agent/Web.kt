package com.mysi.agent

import com.mysi.agent.web.HttpRequest
import com.mysi.agent.web.WebSkill
import com.mysi.agent.web.WebUrl
import java.io.IOException
import java.net.URL

/**
 * Plain HTTP for callers outside the web skill (AI content reports). Everything goes through the
 * web skill's client, so the same safety rules apply: http(s) only, no private or local
 * addresses, at most five redirects, time and size limits. Call off the main thread.
 */
object Web {
    private const val MAX_BYTES = 2_000_000

    /** GET, or POST when [body] is given, with timeouts and a 2 MB cap so a huge page cannot exhaust memory. */
    fun fetch(url: String, body: String? = null, contentType: String = "application/x-www-form-urlencoded"): String =
        request(url, body, contentType).first

    /**
     * Like [fetch], but also returns the address the response finally came from, after redirects.
     * Throws IOException for anything but a 2xx answer.
     */
    fun request(url: String, body: String? = null, contentType: String = "application/x-www-form-urlencoded"): Pair<String, URL> {
        val u = WebUrl.parse(url) ?: throw IOException("Only web addresses are allowed.")
        val req =
            if (body != null) HttpRequest(u, "POST", body.toByteArray(), contentType)
            else HttpRequest(u)
        val page = WebSkill.http.fetch(req, MAX_BYTES)
        if (!page.ok) throw IOException("HTTP ${page.status}")
        return page.text to URL(page.url.toString())
    }

    /** Kept for callers not yet moved to the web skill's search_internet tool. */
    fun search(query: String, count: Int): Any = WebSkill.basic.search(query, count, null, null)

    /** Kept for callers not yet moved to the web skill's read_web_page tool. */
    fun read(url: String, max: Int): String = WebSkill.basic.read(url, 0, max)
}
