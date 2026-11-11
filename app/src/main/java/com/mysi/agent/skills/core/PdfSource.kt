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
            throw IOException("This PDF is password-protected, so MySI cannot read it. Ask the user for an unlocked copy.")
        } catch (e: IOException) {
            throw IOException("This PDF is damaged or not a PDF (${e.message}).")
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
    }
}
