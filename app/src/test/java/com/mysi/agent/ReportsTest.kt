package com.mysi.agent

import com.mysi.agent.web.FakeWeb
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReportsTest {
    private val link =
        "https://docs.google.com/forms/d/e/1FAIpQLSexample/viewform?usp=pp_url" +
            "&entry.111=category&entry.222=reply&entry.333=details&entry.444=prompt&entry.555=model&entry.666=app_version"

    @Test
    fun parsesPrefilledLinkIntoFormResponseAndEntryIds() {
        val form = Reports.parseForm(link)
        assertNotNull(form)
        form!!
        assertEquals("https://docs.google.com/forms/d/e/1FAIpQLSexample/formResponse", form.action)
        assertEquals(
            mapOf(
                "category" to "entry.111",
                "reply" to "entry.222",
                "details" to "entry.333",
                "prompt" to "entry.444",
                "model" to "entry.555",
                "app_version" to "entry.666",
            ),
            form.entries,
        )
    }

    @Test
    fun keysAreCaseAndSpaceInsensitive() {
        val form = Reports.parseForm("https://docs.google.com/forms/d/e/X/viewform?entry.1=%20Category%20&entry.2=REPLY")!!
        assertEquals(mapOf("category" to "entry.1", "reply" to "entry.2"), form.entries)
    }

    @Test
    fun rejectsLinksMissingRequiredKeys() {
        assertNull(Reports.parseForm("https://docs.google.com/forms/d/e/X/viewform?entry.1=category"))
        assertNull(Reports.parseForm("https://docs.google.com/forms/d/e/X/viewform"))
    }

    @Test
    fun rejectsAnythingThatIsNotAnHttpsGoogleForm() {
        assertNull(Reports.parseForm(""))
        assertNull(Reports.parseForm("not a url"))
        assertNull(Reports.parseForm("http://docs.google.com/forms/d/e/X/viewform?entry.1=category&entry.2=reply"))
        assertNull(Reports.parseForm("https://evil.example/forms/d/e/X/viewform?entry.1=category&entry.2=reply"))
        assertNull(Reports.parseForm("https://docs.google.com/forms/d/e/X/formResponse?entry.1=category&entry.2=reply"))
        assertNull(Reports.parseForm("https://docs.google.com/document/d/X/viewform?entry.1=category&entry.2=reply"))
    }

    @Test
    fun onlyTheFormsOwnResponsePageCountsAsDelivered() {
        val form = Reports.parseForm(link)!!
        assertTrue(Reports.accepted(form, "https://docs.google.com/forms/d/e/1FAIpQLSexample/formResponse"))
        assertTrue(Reports.accepted(form, "https://docs.google.com/forms/d/e/1FAIpQLSexample/formResponse?pli=1"))
        // Sign-in-only and closed forms redirect away while still answering 200.
        assertFalse(Reports.accepted(form, "https://accounts.google.com/v3/signin/identifier?continue=x"))
        assertFalse(Reports.accepted(form, "https://docs.google.com/forms/d/e/1FAIpQLSexample/closedform"))
    }

    @Test
    fun ignoresNonEntryParameters() {
        val form = Reports.parseForm("https://docs.google.com/forms/d/e/X/viewform?usp=pp_url&foo=category&entry.1=category&entry.2=reply")!!
        assertEquals(mapOf("category" to "entry.1", "reply" to "entry.2"), form.entries)
    }

    // ---------- delivery through the web skill's HTTP client, over a fake network ----------

    private val action = "https://docs.google.com/forms/d/e/1FAIpQLSexample/formResponse"

    private fun send(fake: FakeWeb) = runBlocking {
        Reports.submit(Reports.parseForm(link)!!, "Other", "too rude", "the reply", "the prompt", "gemma.litertlm", client = fake.client())
    }

    private fun form(body: String) = body.split('&').associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    @Test
    fun postsTheAnswersAsAFormToTheResponsePage() {
        val fake = FakeWeb()
        fake.handler = { FakeWeb.ok("<html>Your response has been recorded.</html>") }
        assertNull(send(fake))
        val r = fake.requests.single()
        assertEquals("POST", r.method)
        assertEquals("/forms/d/e/1FAIpQLSexample/formResponse", r.raw.substringAfter(' ').substringBefore(' '))
        assertEquals("docs.google.com", r.header("Host"))
        assertEquals("application/x-www-form-urlencoded", r.header("Content-Type"))
        assertEquals(r.body.toByteArray(Charsets.ISO_8859_1).size.toString(), r.header("Content-Length"))
        val answers = form(r.body)
        assertEquals("Other", answers["entry.111"])
        assertEquals("the reply", answers["entry.222"])
        assertEquals("too rude", answers["entry.333"])
        assertEquals("the prompt", answers["entry.444"])
        assertEquals("gemma.litertlm", answers["entry.555"])
        assertEquals(BuildConfig.VERSION_NAME, answers["entry.666"])
    }

    @Test
    fun aPostRedirectedWith302Or303IsFollowedWithAGetAndTheFinalAddressDecides() {
        for (status in listOf(302, 303)) {
            val fake = FakeWeb()
            fake.handler = { r ->
                if (r.method == "POST") FakeWeb.redirect(status, "$action?pli=1&submitted=true")
                else FakeWeb.ok("<html>Your response has been recorded.</html>")
            }
            assertNull("HTTP $status", send(fake))
            assertEquals(listOf("POST", "GET"), fake.requests.map { it.method })
            val get = fake.requests[1]
            assertEquals("/forms/d/e/1FAIpQLSexample/formResponse?pli=1&submitted=true", get.raw.substringAfter(' ').substringBefore(' '))
            // As browsers do: the redirected request has no body and no body headers.
            assertEquals("", get.body)
            assertNull(get.header("Content-Type"))
            assertNull(get.header("Content-Length"))
        }
    }

    @Test
    fun aRedirectAwayFromTheResponsePageIsNotADelivery() {
        val fake = FakeWeb()
        fake.handler = { r ->
            when {
                r.url.host == "docs.google.com" && r.method == "POST" -> FakeWeb.redirect(302, "https://accounts.google.com/v3/signin/identifier?continue=$action")
                r.url.host == "accounts.google.com" -> FakeWeb.ok("<html>Sign in</html>")
                else -> FakeWeb.notFound()
            }
        }
        assertEquals("The report form is not accepting responses. Please try again later.", send(fake))
        assertEquals(listOf("POST", "GET"), fake.requests.map { it.method })
        val closed = FakeWeb()
        closed.handler = { r -> if (r.method == "POST") FakeWeb.redirect(302, "/forms/d/e/1FAIpQLSexample/closedform") else FakeWeb.ok("closed") }
        assertEquals("The report form is not accepting responses. Please try again later.", send(closed))
    }

    @Test
    fun a307KeepsThePostAndItsBody() {
        val fake = FakeWeb()
        fake.handler = { r -> if (r.raw.contains("formResponse ")) FakeWeb.redirect(307, "$action?x=1") else FakeWeb.ok("recorded") }
        assertNull(send(fake))
        assertEquals(listOf("POST", "POST"), fake.requests.map { it.method })
        assertEquals(fake.requests[0].body, fake.requests[1].body)
    }

    @Test
    fun serverErrorsAndBlockedAddressesAreReportedNotThrown() {
        val fake = FakeWeb()
        fake.handler = { FakeWeb.response(500, listOf("Content-Length" to "0"), ByteArray(0)) }
        assertEquals("Could not send the report (HTTP 500). Check the connection and try again.", send(fake))
        val local = FakeWeb()
        local.dns["docs.google.com"] = listOf(local.ip(127, 0, 0, 1))
        val error = send(local)!!
        assertTrue(error, error.startsWith("Could not send the report ("))
        assertTrue(local.requests.isEmpty())
    }
}
