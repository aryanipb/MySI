package com.mysi.agent

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
}
