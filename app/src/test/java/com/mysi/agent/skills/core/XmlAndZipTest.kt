package com.mysi.agent.skills.core

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class XmlAndZipTest {
    private fun events(xml: String, charset: java.nio.charset.Charset = Charsets.UTF_8): List<String> {
        val x = XmlPull(ByteArrayInputStream(xml.toByteArray(charset)))
        val out = mutableListOf<String>()
        while (true) {
            when (x.next()) {
                XmlPull.Event.EOF -> return out
                XmlPull.Event.START -> out += "<${x.name}" + (x.attr("val")?.let { " val=$it" } ?: "") + ">"
                XmlPull.Event.END -> out += "</${x.name}>"
                XmlPull.Event.TEXT -> if (x.text.isNotBlank()) out += x.text
            }
        }
    }

    @Test
    fun tokenizesElementsAttributesAndLocalNames() {
        val e = events("<?xml version=\"1.0\"?><w:p><w:pStyle w:val='Heading1'/><w:t xml:space=\"preserve\">Hi</w:t></w:p>")
        assertEquals(listOf("<p>", "<pStyle val=Heading1>", "</pStyle>", "<t>", "Hi", "</t>", "</p>"), e)
    }

    @Test
    fun decodesPredefinedAndNumericEntitiesOnly() {
        val e = events("<a b=\"x &amp; y\">&lt;tag&gt; &quot;q&quot; &apos;s&apos; &#233; &#x20B9; &unknown; &#xD800;</a>")
        assertEquals("<tag> \"q\" 's' é ₹ &unknown; ${Char(0xFFFD)}", e[1])
    }

    @Test
    fun readsCdataAndSkipsCommentsAndInstructions() {
        val e = events("<a><!-- <b>not</b> --><?pi x?><![CDATA[1 < 2 & <c>]]></a>")
        assertEquals(listOf("<a>", "1 < 2 & <c>", "</a>"), e)
    }

    @Test
    fun neverExpandsDtdEntitiesSoBillionLaughsAndXxeAreInert() {
        val xml = """<?xml version="1.0"?>
            <!DOCTYPE lolz [
              <!ENTITY lol "lol">
              <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
              <!ENTITY xxe SYSTEM "file:///etc/passwd">
            ]>
            <lolz>&lol2;&xxe;</lolz>"""
        val e = events(xml)
        assertEquals(listOf("<lolz>", "&lol2;&xxe;", "</lolz>"), e)
    }

    @Test
    fun honoursByteOrderMarksAndDeclaredEncodings() {
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "<a>Ünï ✓</a>".toByteArray(Charsets.UTF_16LE)
        val x = XmlPull(ByteArrayInputStream(utf16))
        assertEquals(XmlPull.Event.START, x.next())
        assertEquals(XmlPull.Event.TEXT, x.next())
        assertEquals("Ünï ✓", x.text)
        assertEquals(listOf("<a>", "café", "</a>"), events("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>café</a>", Charsets.ISO_8859_1))
    }

    @Test
    fun skipElementJumpsPastNestedContent() {
        val x = XmlPull(ByteArrayInputStream("<r><skip><a><b/></a>text</skip><keep>yes</keep></r>".toByteArray()))
        x.next()
        x.next()
        assertEquals("skip", x.name)
        x.skipElement()
        assertEquals(XmlPull.Event.START, x.next())
        assertEquals("keep", x.name)
    }

    @Test
    fun malformedTagsFailCleanly() {
        for (bad in listOf("<a b=c></a>", "<a b", "<a b=\"x></a>")) {
            try {
                events(bad)
                fail("accepted $bad")
            } catch (e: XmlException) {
                // expected
            }
        }
    }

    @Test
    fun escapeDropsCharactersXmlCannotCarry() {
        val s = "a<b>&\"'" + Char(1) + Char(0xFFFE) + Char(0xD800) + "z" + String(Character.toChars(0x1F600))
        assertEquals("a&lt;b&gt;&amp;&quot;&apos;z" + String(Character.toChars(0x1F600)), XmlText.escape(s))
    }

    private fun zipWith(file: File, entries: Int, bytesEach: Int = 1) = file.also {
        ZipOutputStream(it.outputStream()).use { z ->
            repeat(entries) { i ->
                z.putNextEntry(ZipEntry("p$i.xml"))
                z.write(ByteArray(bytesEach))
                z.closeEntry()
            }
        }
    }

    @Test
    fun rejectsZipBombsByInflatedBytesAndRatio() {
        val dir = TestFiles.tempDir()
        // 30 MB of zeros deflates to about 30 KB: a ratio near 1000.
        val bomb = zipWith(File(dir, "bomb.zip"), 1, 30 * 1024 * 1024)
        assertTrue(bomb.length() < 200_000)
        SafeZip(bomb).use { z ->
            try {
                z.open("p0.xml")!!.use { it.readBytes() }
                fail("bomb was inflated")
            } catch (e: LimitException) {
                assertTrue(e.message!!, e.message!!.contains("zip bomb"))
            }
        }
        // Without the ratio rule, the per-entry byte cap still stops it.
        SafeZip(bomb, ZipLimits(maxEntryBytes = 1024 * 1024, maxRatio = Long.MAX_VALUE)).use { z ->
            try {
                z.open("p0.xml")!!.use { it.readBytes() }
                fail("entry cap ignored")
            } catch (e: LimitException) {
                assertTrue(e.message!!.contains("inflates past 1 MB"))
            }
        }
    }

    @Test
    fun capsTotalInflatedBytesAcrossParts() {
        val f = zipWith(File(TestFiles.tempDir(), "many.zip"), 5, 400 * 1024)
        SafeZip(f, ZipLimits(maxTotalBytes = 1024 * 1024, maxRatio = Long.MAX_VALUE)).use { z ->
            try {
                for (i in 0 until 5) z.open("p$i.xml")!!.use { it.readBytes() }
                fail("total cap ignored")
            } catch (e: LimitException) {
                assertTrue(e.message!!.contains("This file inflates past 1 MB"))
            }
        }
    }

    @Test
    fun rejectsTooManyEntries() {
        val f = zipWith(File(TestFiles.tempDir(), "entries.zip"), 10_001)
        try {
            SafeZip(f).close()
            fail("entry cap ignored")
        } catch (e: LimitException) {
            assertTrue(e.message!!.contains("more than 10000 parts"))
        }
    }

    @Test
    fun rejectsOversizedFilesBeforeOpening() {
        val f = zipWith(File(TestFiles.tempDir(), "big.zip"), 1, 10)
        try {
            SafeZip(f, ZipLimits(maxFileBytes = 10)).close()
            fail("file size cap ignored")
        } catch (e: LimitException) {
            // expected
        }
    }

    @Test
    fun notAZipIsAPlainError() {
        val f = File(TestFiles.tempDir(), "fake.docx").apply { writeText("hello") }
        try {
            SafeZip(f).close()
            fail()
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("damaged"))
        }
        assertFalse(SafeZip.isZip(f))
    }

    @Test
    fun partNamesAreCaseInsensitiveAndSlashTolerant() {
        val f = TestFiles.zip(File(TestFiles.tempDir(), "c.zip"), listOf("xl/SharedStrings.xml" to "<sst/>"))
        SafeZip(f).use { z ->
            assertTrue(z.has("/xl/sharedStrings.xml"))
            assertEquals("<sst/>", z.readText("xl/sharedstrings.xml"))
            assertNull(z.open("xl/missing.xml"))
        }
    }

    @Test
    fun relationshipTargetsResolveRelativeAndAbsolute() {
        assertEquals("xl/worksheets/sheet1.xml", Rels.resolve("xl/workbook.xml", "worksheets/sheet1.xml"))
        assertEquals("xl/worksheets/sheet1.xml", Rels.resolve("xl/workbook.xml", "/xl/worksheets/sheet1.xml"))
        assertEquals("ppt/notesSlides/n1.xml", Rels.resolve("ppt/slides/slide1.xml", "../notesSlides/n1.xml"))
        assertEquals("word/_rels/document.xml.rels", Rels.relsPathFor("word/document.xml"))
    }
}
