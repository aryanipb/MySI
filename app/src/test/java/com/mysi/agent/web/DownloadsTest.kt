package com.mysi.agent.web

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class DownloadsTest {
    private lateinit var dir: File
    private val url = WebUrl.parse("https://files.example/reports/Annual%20Report%202025.pdf")!!
    private val pdf = "%PDF-1.7\n%âãÏÓ\n1 0 obj\n<<>>\nendobj\n".toByteArray(Charsets.ISO_8859_1) + ByteArray(5000) { 'a'.code.toByte() }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("dl").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun fileNamesAreSanitised() {
        assertEquals("report.pdf", Downloader.sanitize("../../etc/report.pdf", FileKind.PDF))
        assertEquals("report.pdf", Downloader.sanitize("C:\\Windows\\report.pdf", FileKind.PDF))
        assertEquals("passwd.txt", Downloader.sanitize("/etc/passwd", FileKind.TXT))
        assertEquals("secret.pdf", Downloader.sanitize("...secret.pdf", FileKind.PDF))
        assertEquals("abc.pdf", Downloader.sanitize("a\u0000b\u202Ec\n.pdf", FileKind.PDF))
        assertEquals("invoice.exe.pdf", Downloader.sanitize("invoice.exe", FileKind.PDF))
        assertEquals("photo.jpeg", Downloader.sanitize("photo.JPEG", FileKind.JPEG))
        assertEquals("data.csv", Downloader.sanitize("data", FileKind.CSV))
        assertEquals("download.pdf", Downloader.sanitize("", FileKind.PDF))
        assertEquals("download.pdf", Downloader.sanitize("...", FileKind.PDF))
        assertEquals("download_con.docx", Downloader.sanitize("con.docx", FileKind.DOCX))
        assertEquals("whatisthis.pdf", Downloader.sanitize("what<is>this?.pdf", FileKind.PDF))
        assertEquals("Q3 results.xlsx", Downloader.sanitize("  Q3   results.XLSX  ", FileKind.XLSX))
        val long = Downloader.sanitize("x".repeat(300) + ".pdf", FileKind.PDF)
        assertEquals(100, long.length)
        assertTrue(long.endsWith(".pdf"))
        assertFalse(Downloader.sanitize("a\u202Efdp.exe", FileKind.PDF).contains('\u202E'))
    }

    @Test
    fun contentDispositionNames() {
        assertEquals("résumé final.pdf", Downloader.dispositionName("attachment; filename*=UTF-8''r%C3%A9sum%C3%A9%20final.pdf; filename=\"resume.pdf\""))
        assertEquals("plain name.pdf", Downloader.dispositionName("attachment; filename=\"plain name.pdf\""))
        assertEquals("bare.csv", Downloader.dispositionName("inline; filename=bare.csv"))
        assertNull(Downloader.dispositionName("inline"))
        assertNull(Downloader.dispositionName(null))
    }

    @Test
    fun onlyAllowlistedTypesAreAccepted() {
        assertEquals(FileKind.PDF, Downloader.kindOf("application/pdf", url, null))
        assertEquals(FileKind.XLSX, Downloader.kindOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", url, null))
        assertEquals(FileKind.CSV, Downloader.kindOf("text/csv", url, null))
        assertEquals(FileKind.PNG, Downloader.kindOf("image/png", url, null))
        // Generic types fall back to the file name.
        assertEquals(FileKind.PDF, Downloader.kindOf("application/octet-stream", url, null))
        assertEquals(FileKind.DOCX, Downloader.kindOf("application/octet-stream", url, "attachment; filename=\"a.docx\""))
        assertNull(Downloader.kindOf("application/octet-stream", WebUrl.parse("https://x.example/setup.exe")!!, null))
        assertNull(Downloader.kindOf("application/zip", url, null))
        assertNull(Downloader.kindOf("application/x-msdownload", url, null))
        assertNull(Downloader.kindOf("text/html", url, null))
        assertNull(Downloader.kindOf("image/svg+xml", url, null))
        // read_web_page treats binary documents as files, but plain text as text.
        assertEquals(FileKind.PDF, Downloader.documentKind("application/pdf", url, null))
        assertNull(Downloader.documentKind("text/plain", url, null))
    }

    @Test
    fun fileSignaturesAreChecked() {
        assertTrue(FileKind.PDF.matches(pdf))
        assertTrue(FileKind.PNG.matches(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)))
        assertTrue(FileKind.JPEG.matches(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertTrue(FileKind.DOCX.matches(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14)))
        assertTrue(FileKind.WEBP.matches("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertTrue(FileKind.CSV.matches("a,b\n1,2\n".toByteArray()))
        assertFalse(FileKind.PDF.matches("<!DOCTYPE html><html>".toByteArray()))
        assertFalse(FileKind.CSV.matches("<!doctype html><title>Login</title>".toByteArray()))
        assertFalse(FileKind.TXT.matches(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00)))
    }

    @Test
    fun savingStreamsToAUniqueFileAndNeverOverwrites() {
        val d = Downloader(dir)
        val a = d.save(ByteArrayInputStream(pdf), FileKind.PDF, pdf.size.toLong(), url, null, null)
        assertEquals("Downloads/Annual Report 2025.pdf", a.relativePath)
        assertEquals(pdf.size.toLong(), a.bytes)
        assertTrue(a.file.readBytes().contentEquals(pdf))
        val b = d.save(ByteArrayInputStream(pdf), FileKind.PDF, -1, url, null, null)
        assertEquals("Downloads/Annual Report 2025 (1).pdf", b.relativePath)
        val c = d.save(ByteArrayInputStream(pdf), FileKind.PDF, -1, url, null, "my copy")
        assertEquals("Downloads/my copy.pdf", c.relativePath)
        assertEquals(setOf("Annual Report 2025.pdf", "Annual Report 2025 (1).pdf", "my copy.pdf"), dir.list()!!.toSet())
        assertTrue(a.file.canonicalPath.startsWith(dir.canonicalPath))
    }

    @Test
    fun aWebPageServedAsPdfIsRejected() {
        val d = Downloader(dir)
        try {
            d.save(ByteArrayInputStream("<!DOCTYPE html><html><body>Sign in</body></html>".toByteArray()), FileKind.PDF, -1, url, null, null)
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("web page"))
        }
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun theSizeCapIsEnforcedUpFrontAndWhileStreaming() {
        val d = Downloader(dir, maxBytes = 10_000)
        try {
            d.save(ByteArrayInputStream(pdf), FileKind.PDF, 50_000, url, null, null)
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("limit"))
        }
        // The server lies about (or omits) the length: the download stops at the cap and is deleted.
        val endless = object : InputStream() {
            var i = 0

            override fun read(): Int = if (i < pdf.size) pdf[i++].toInt() and 0xFF else 'x'.code
        }
        try {
            d.save(endless, FileKind.PDF, -1, url, null, null)
            fail()
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("deleted"))
        }
        assertTrue("partial file left: ${dir.list()!!.toList()}", dir.list()!!.isEmpty())
    }
}
