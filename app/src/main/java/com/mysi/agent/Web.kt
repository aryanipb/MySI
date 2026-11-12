package com.mysi.agent

import com.mysi.agent.web.HttpClient
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

    /**
     * GET, or POST when [body] is given, with a 2 MB cap so a huge page cannot exhaust memory.
     * Returns the body and the address the response finally came from, after redirects; a POST
     * redirected with 301, 302 or 303 is followed with a GET, as browsers do. Throws IOException
     * for anything but a 2xx answer. [client] is the web skill's client; tests pass a fake one.
     */
    fun request(
        url: String,
        body: String? = null,
        contentType: String = "application/x-www-form-urlencoded",
        client: HttpClient = WebSkill.http,
    ): Pair<String, URL> {
        val u = WebUrl.parse(url) ?: throw IOException("Only web addresses are allowed.")
        val req =
            if (body != null) HttpRequest(u, "POST", body.toByteArray(), contentType)
            else HttpRequest(u)
        val page = client.fetch(req, MAX_BYTES)
        if (!page.ok) throw IOException("HTTP ${page.status}")
        return page.text to URL(page.url.toString())
    }
}
