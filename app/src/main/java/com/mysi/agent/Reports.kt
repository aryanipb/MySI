package com.mysi.agent

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * In-app reporting of offensive or harmful AI replies, required by Google Play's AI-Generated
 * Content policy. Only what the user sees in the report dialog is sent, and only when they tap
 * Send; nothing is collected in the background.
 *
 * Reports go to a Google Form. BuildConfig.REPORT_URL is the form's pre-filled link, made in
 * Forms with ⋮ → "Get pre-filled link" by typing each field's key as its answer:
 * category, reply, details, prompt, model, app_version. For example
 * https://docs.google.com/forms/d/e/FORM_ID/viewform?usp=pp_url&entry.111=category&entry.222=reply…
 * The app reads which entry id holds which key from that link, so no ids live in the code.
 */
object Reports {
    val categories = listOf("Offensive or hateful", "Sexual content", "Dangerous or harmful", "False or misleading", "Other")

    private val REQUIRED = setOf("category", "reply")

    /** Where to POST, and which form entry id holds each answer key. */
    internal class Form(val action: String, val entries: Map<String, String>)

    private val form: Form? by lazy { parseForm(BuildConfig.REPORT_URL) }

    /** Reads a Google Forms pre-filled link; null unless it is an https form link with every required key. */
    internal fun parseForm(link: String): Form? {
        val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
        val path = uri.rawPath.orEmpty()
        if (uri.scheme != "https" || uri.host != "docs.google.com" || !path.startsWith("/forms/") || !path.endsWith("/viewform")) return null
        val entries = uri.rawQuery.orEmpty().split('&')
            .mapNotNull { pair ->
                val id = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
                val key = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8").trim().lowercase()
                if (id.startsWith("entry.") && key.isNotEmpty()) key to id else null
            }
            .toMap()
        if (!entries.keys.containsAll(REQUIRED)) return null
        return Form("https://docs.google.com" + path.removeSuffix("/viewform") + "/formResponse", entries)
    }

    /** Returns null on success, or a short reason it failed. */
    suspend fun send(category: String, details: String, reply: String, prompt: String?, model: String?): String? {
        val f = form ?: return "Reporting is not set up in this build."
        val answers = buildMap {
            put("category", category)
            put("reply", reply.take(8000))
            put("details", details.take(2000))
            prompt?.let { put("prompt", it.take(4000)) }
            model?.let { put("model", it) }
            put("app_version", BuildConfig.VERSION_NAME)
        }
        val body = answers.mapNotNull { (key, value) ->
            f.entries[key]?.let { "$it=" + URLEncoder.encode(value, "UTF-8") }
        }.joinToString("&")
        return withContext(Dispatchers.IO) {
            runCatching { Web.request(f.action, body).second }.fold(
                { final ->
                    // A closed form, or one that needs sign-in, redirects elsewhere with a 200 page.
                    if (accepted(f, final.toString())) null
                    else "The report form is not accepting responses. Please try again later."
                },
                { "Could not send the report (${it.message ?: "network error"}). Check the connection and try again." },
            )
        }
    }

    /** Only the form's own response page means Google stored the report. */
    internal fun accepted(form: Form, finalUrl: String) = finalUrl.substringBefore('?') == form.action
}
