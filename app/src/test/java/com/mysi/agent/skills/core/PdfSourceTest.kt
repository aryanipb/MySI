package com.mysi.agent.skills.core

import com.mysi.agent.skills.core.TestFiles.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PdfSourceTest {
    @Before
    fun assets() = TestFiles.installPdfBoxAssets()

    @Test
    fun readsStandardFontPdfPageByPage() {
        PdfSource(fixture("plan.pdf"), null).use { pdf ->
            assertEquals(3, pdf.partCount)
            assertEquals("Annual Plan 2026", pdf.title)
            val p2 = pdf.part(1)
            assertEquals("page 2", p2.label)
            assertTrue(p2.text, p2.text.contains("travel budget is 8300"))
            assertTrue(!p2.text.contains("Monsoon"))
        }
    }

    @Test
    fun aPdfThatNeedsAPasswordIsAClearRefusal() {
        try {
            PdfSource(fixture("locked.pdf"), null).close()
            org.junit.Assert.fail("opened without a password")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!, e.message!!.contains("password-protected"))
        }
    }

    @Test
    fun aPdfWithOnlyAnOwnerPasswordStillReads() {
        // Encrypted (RC4) with permissions only, like many bank statements: opens without a password.
        PdfSource(fixture("protected.pdf"), null).use { pdf ->
            assertTrue(pdf.part(0).text.contains("Nitrate levels exceeded"))
        }
    }

    @Test
    fun aCertificateLockedPdfIsAClearRefusalAndLeavesLaterPdfsReadable() {
        // Made with PdfBox's public-key security handler (/Filter /Adobe.PubSec, a test certificate).
        val locked = fixture("certificate.pdf")
        assertTrue(PdfSource.lockedWithCertificate(locked))
        repeat(2) {
            try {
                PdfSource(locked, null).close()
                org.junit.Assert.fail("opened a certificate-locked PDF")
            } catch (e: java.io.IOException) {
                assertEquals(PdfSource.CERTIFICATE, e.message)
            }
        }
        // A failed attempt must not break PdfBox's encryption support for the next file.
        PdfSource(fixture("protected.pdf"), null).use { pdf -> assertTrue(pdf.part(0).text.contains("Nitrate levels exceeded")) }
        try {
            PdfSource(fixture("locked.pdf"), null).close()
            org.junit.Assert.fail("opened without a password")
        } catch (e: java.io.IOException) {
            assertEquals(PdfSource.PASSWORD, e.message)
        }
        for (other in listOf("plan.pdf", "locked.pdf", "protected.pdf", "report.pdf")) assertFalse(other, PdfSource.lockedWithCertificate(fixture(other)))
    }

    @Test
    fun readsEmbeddedFontPdfFromAnOfficeSuite() {
        PdfSource(fixture("report.pdf"), null).use { pdf ->
            val text = pdf.part(0).text
            assertTrue(text, text.contains("Nitrate levels exceeded"))
            assertTrue(text, text.contains("Café"))
        }
    }
}
