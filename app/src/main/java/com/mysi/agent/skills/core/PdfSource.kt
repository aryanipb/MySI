package com.mysi.agent.skills.core

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.IOException

/**
 * PDF text by page through PdfBox-Android. Pages are extracted one at a time, and large files
 * spill to [tempDir] instead of the heap. The Android layer must call
 * PDFBoxResourceLoader.init once before the first PDF is opened.
 */
class PdfSource(file: File, tempDir: File?, private val limits: ReadLimits = ReadLimits()) : DocSource {
    private val doc: PDDocument

    init {
        val memory = MemoryUsageSetting.setupMixed(MAIN_MEMORY_BYTES).apply { if (tempDir != null) setTempDir(tempDir) }
        doc = try {
            PDDocument.load(file, "", memory)
        } catch (e: InvalidPasswordException) {
            throw IOException(PASSWORD)
        } catch (e: IOException) {
            if (lockedWithCertificate(file)) throw IOException(CERTIFICATE)
            throw IOException("This PDF is damaged or not a PDF (${e.message}).")
        } catch (e: LinkageError) {
            // PdfBox needs BouncyCastle, which MySI does not ship, only to decrypt certificate-locked
            // PDFs; a missing class there must be a plain answer, not a crash.
            if (lockedWithCertificate(file)) throw IOException(CERTIFICATE)
            throw IOException("This PDF uses a feature MySI cannot read (${e.javaClass.simpleName}).")
        }
    }

    override val type = DocType.PDF

    override val title: String? = runCatching { doc.documentInformation?.title?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()

    override val partCount: Int = doc.numberOfPages.coerceAtMost(limits.maxPdfPages)

    override val facts: List<String> =
        listOfNotNull(
            runCatching { doc.documentInformation?.author?.trim()?.takeIf { it.isNotEmpty() }?.let { "Author: $it" } }.getOrNull(),
        )

    override val warnings: List<String> =
        if (doc.numberOfPages > limits.maxPdfPages) listOf("Only the first ${limits.maxPdfPages} of ${doc.numberOfPages} pages can be read.") else emptyList()

    override fun part(index: Int): Part {
        val stripper = PDFTextStripper().apply {
            startPage = index + 1
            endPage = index + 1
        }
        val text = stripper.getText(doc).trim()
        return Part("page ${index + 1}", text.ifEmpty { NO_TEXT })
    }

    override fun close() = doc.close()

    companion object {
        private const val MAIN_MEMORY_BYTES = 16L * 1024 * 1024
        const val NO_TEXT = "(This page has no text layer; it may be a scanned image.)"
        const val PASSWORD = "This PDF is password-protected, so MySI cannot read it. Ask the user for an unlocked copy."
        const val CERTIFICATE = "This PDF is locked with a certificate and cannot be opened. Ask the user for an unlocked copy."

        private val PUBLIC_KEY_FILTER = "/Adobe.PubSec".toByteArray(Charsets.ISO_8859_1)

        /**
         * True when [file] names the public-key (certificate) security handler. PDF never stores
         * the encryption dictionary in a compressed object stream, so the name is in the raw
         * bytes. Only checked after opening failed.
         */
        internal fun lockedWithCertificate(file: File): Boolean =
            runCatching {
                file.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var matched = 0
                    var n = input.read(buf)
                    while (n >= 0) {
                        for (i in 0 until n) {
                            // '/' occurs only at the start of the name, so a mismatch restarts there.
                            matched = when (buf[i]) {
                                PUBLIC_KEY_FILTER[matched] -> matched + 1
                                PUBLIC_KEY_FILTER[0] -> 1
                                else -> 0
                            }
                            if (matched == PUBLIC_KEY_FILTER.size) return@use true
                        }
                        n = input.read(buf)
                    }
                    false
                }
            }.getOrDefault(false)
    }
}
