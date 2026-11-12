package com.mysi.agent.web

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.URLDecoder
import java.text.Normalizer

/** A file type web tools may save: documents, spreadsheets, plain text and images only. */
enum class FileKind(val label: String, val extensions: List<String>, val mimes: List<String>, val text: Boolean = false) {
    PDF("PDF", listOf("pdf"), listOf("application/pdf", "application/x-pdf")),
    DOCX("Word document", listOf("docx"), listOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
    XLSX("Excel spreadsheet", listOf("xlsx"), listOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
    PPTX("PowerPoint presentation", listOf("pptx"), listOf("application/vnd.openxmlformats-officedocument.presentationml.presentation")),
    DOC("Word document", listOf("doc"), listOf("application/msword")),
    XLS("Excel spreadsheet", listOf("xls"), listOf("application/vnd.ms-excel")),
    PPT("PowerPoint presentation", listOf("ppt"), listOf("application/vnd.ms-powerpoint")),
    ODT("OpenDocument text", listOf("odt"), listOf("application/vnd.oasis.opendocument.text")),
    ODS("OpenDocument spreadsheet", listOf("ods"), listOf("application/vnd.oasis.opendocument.spreadsheet")),
    ODP("OpenDocument presentation", listOf("odp"), listOf("application/vnd.oasis.opendocument.presentation")),
    RTF("RTF document", listOf("rtf"), listOf("application/rtf", "text/rtf")),
    CSV("CSV file", listOf("csv"), listOf("text/csv", "application/csv", "text/comma-separated-values"), text = true),
    TSV("TSV file", listOf("tsv"), listOf("text/tab-separated-values"), text = true),
    TXT("text file", listOf("txt", "text", "md"), listOf("text/plain", "text/markdown"), text = true),
    PNG("PNG image", listOf("png"), listOf("image/png")),
    JPEG("JPEG image", listOf("jpg", "jpeg"), listOf("image/jpeg", "image/jpg", "image/pjpeg")),
    GIF("GIF image", listOf("gif"), listOf("image/gif")),
    WEBP("WebP image", listOf("webp"), listOf("image/webp")),
    HEIC("HEIC image", listOf("heic", "heif"), listOf("image/heic", "image/heif")),
    ;

    /** True when [head] (the first bytes) really is this kind of file. */
    fun matches(head: ByteArray): Boolean {
        fun starts(vararg b: Int) = head.size >= b.size && b.indices.all { head[it] == b[it].toByte() }
        fun ascii(at: Int, s: String) = head.size >= at + s.length && s.indices.all { head[at + it] == s[it].code.toByte() }
        return when (this) {
            PDF -> String(head, 0, minOf(head.size, 1024), Charsets.ISO_8859_1).contains("%PDF-")
            DOCX, XLSX, PPTX, ODT, ODS, ODP -> starts(0x50, 0x4B, 0x03, 0x04)
            DOC, XLS, PPT -> starts(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)
            RTF -> ascii(0, "{\\rtf")
            PNG -> starts(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            JPEG -> starts(0xFF, 0xD8, 0xFF)
            GIF -> ascii(0, "GIF87a") || ascii(0, "GIF89a")
            WEBP -> ascii(0, "RIFF") && ascii(8, "WEBP")
            HEIC -> ascii(4, "ftyp") && listOf("heic", "heix", "hevc", "heim", "heis", "mif1", "msf1").any { ascii(8, it) }
            CSV, TSV, TXT -> looksLikePlainText(head)
        }
    }

    companion object {
        fun forMime(mime: String): FileKind? = entries.firstOrNull { mime.lowercase() in it.mimes }

        fun forExtension(ext: String): FileKind? = entries.firstOrNull { ext.lowercase() in it.extensions }

        /** No NUL bytes, and not an HTML page pretending to be text. */
        fun looksLikePlainText(head: ByteArray): Boolean {
            if (head.any { it.toInt() == 0 }) return false
            val start = String(head, 0, minOf(head.size, 512), Charsets.ISO_8859_1).trimStart('\uFEFF', '\u00EF', '\u00BB', '\u00BF', ' ', '\n', '\r', '\t').lowercase()
            return !(start.startsWith("<!doctype html") || start.startsWith("<html") || start.startsWith("<head") || start.startsWith("<?xml"))
        }

        /** Content types servers use when they do not say what a file is; the extension decides then. */
        val GENERIC = setOf("", "application/octet-stream", "binary/octet-stream", "application/force-download", "application/x-download", "application/download", "application/unknown")
    }
}

class SavedFile(val relativePath: String, val file: File, val bytes: Long, val kind: FileKind)

/**
 * Saves downloads into one folder (the workspace's Downloads), never outside it and never over an
 * existing file. Only [FileKind] types are accepted, the server's claim is checked against the
 * file's first bytes, and anything over [maxBytes] is refused or deleted part-way.
 */
class Downloader(private val dir: File, private val relativeDir: String = "Downloads", val maxBytes: Long = 50L * 1024 * 1024) {

    /**
     * Streams [body] to a new file. [requestedName] (from the user) wins over the server's
     * Content-Disposition name, which wins over the address's last path segment.
     */
    fun save(body: InputStream, kind: FileKind, contentLength: Long, url: WebUrl, disposition: String?, requestedName: String?): SavedFile {
        if (contentLength > maxBytes) throw IOException("The file is ${mb(contentLength)}, over the ${mb(maxBytes)} limit.")
        val input = PushbackInputStream(body, 4096)
        val head = ByteArray(4096)
        var got = 0
        while (got < head.size) {
            val n = input.read(head, got, head.size - got)
            if (n < 0) break
            got += n
        }
        if (got == 0) throw IOException("The server sent an empty file.")
        val first = head.copyOf(got)
        if (!kind.matches(first)) {
            val looksHtml = !FileKind.looksLikePlainText(first) && first.none { it.toInt() == 0 }
            throw IOException(if (looksHtml) "The address returned a web page, not a ${kind.label}. Use read_web_page on it instead." else "The file's contents are not a real ${kind.label}, so it was not saved.")
        }
        input.unread(first)
        dir.mkdirs()
        val name = sanitize(requestedName?.takeIf { it.isNotBlank() } ?: dispositionName(disposition) ?: url.fileName, kind)
        val (target, part) = reserve(name)
        var written = 0L
        try {
            part.outputStream().use { out ->
                val buf = ByteArray(65_536)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    written += n
                    if (written > maxBytes) throw IOException("The file is over the ${mb(maxBytes)} limit, so the download was stopped and deleted.")
                    out.write(buf, 0, n)
                }
            }
            // A plain move fails instead of replacing a file that appeared meanwhile.
            java.nio.file.Files.move(part.toPath(), target.toPath())
        } finally {
            if (part.exists()) part.delete()
        }
        return SavedFile("$relativeDir/${target.name}", target, written, kind)
    }

    /** Picks a free name ("name (1).pdf" if taken) and claims it by creating its ".part" file. */
    private fun reserve(name: String): Pair<File, File> {
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        synchronized(Downloader::class.java) {
            for (n in 0..10_000) {
                val f = File(dir, if (n == 0) name else "$base ($n)" + if (ext.isEmpty()) "" else ".$ext")
                val part = File(dir, ".${f.name}.part")
                if (!f.exists() && part.createNewFile()) return f to part
            }
        }
        throw IOException("Too many files named $name.")
    }

    companion object {
        /**
         * The kind of file a response carries, from its Content-Type, or from the file name when the
         * type is generic. Null when the type is not on the allowlist.
         */
        fun kindOf(mime: String, url: WebUrl, disposition: String?): FileKind? {
            FileKind.forMime(mime)?.let { return it }
            if (mime !in FileKind.GENERIC) return null
            val name = dispositionName(disposition) ?: url.fileName
            return FileKind.forExtension(name.substringAfterLast('.', ""))
        }

        /** The binary kind (PDF, Office, image) when a response is a file for the document tools, not a web page or plain text. */
        fun documentKind(mime: String, url: WebUrl, disposition: String?): FileKind? = kindOf(mime, url, disposition)?.takeIf { !it.text }

        private const val MAX_NAME = 100
        private val RESERVED = setOf("con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "lpt1", "lpt2", "lpt3")

        fun mb(bytes: Long) = if (bytes >= 1024 * 1024) "${bytes / (1024 * 1024)} MB" else "${(bytes + 1023) / 1024} KB"

        /** File name from a Content-Disposition header (RFC 6266 filename*= wins over filename=). */
        fun dispositionName(header: String?): String? {
            if (header.isNullOrBlank()) return null
            Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(header)?.let { m ->
                val charset = m.groupValues[1].ifBlank { "UTF-8" }
                runCatching { URLDecoder.decode(m.groupValues[2].trim().trim('"').replace("+", "%2B"), charset) }.getOrNull()?.let { return it }
            }
            Regex("filename\\s*=\\s*(\"([^\"]*)\"|[^;]+)", RegexOption.IGNORE_CASE).find(header)?.let { m ->
                return (m.groupValues[2].ifEmpty { m.groupValues[1] }).trim().takeIf { it.isNotEmpty() }
            }
            return null
        }

        /**
         * A safe file name for [kind]: no folders, no control or reserved characters, no leading
         * dots, at most 100 characters, and an extension that matches the real file type.
         */
        fun sanitize(raw: String, kind: FileKind): String {
            var n = Normalizer.normalize(raw, Normalizer.Form.NFC)
            n = n.substringAfterLast('/').substringAfterLast('\\')
            n = n.filter { it.code >= 0x20 && it.code != 0x7F && it !in "<>:\"/\\|?*" && !Character.isISOControl(it) && Character.getType(it) != Character.FORMAT.toInt() }
            n = Element.collapse(n).trim().trimStart('.', ' ', '-').trimEnd('.', ' ')
            var base = n.substringBeforeLast('.', n)
            var ext = n.substringAfterLast('.', "").lowercase()
            if (ext !in kind.extensions) {
                // Keep a misleading extension visible as part of the name ("x.exe.pdf"), never as the real one.
                if (ext.isNotEmpty()) base = n
                ext = kind.extensions.first()
            }
            if (base.isBlank() || base.lowercase() in RESERVED) base = "download" + if (base.isBlank()) "" else "_$base"
            val room = MAX_NAME - ext.length - 1
            if (base.length > room) base = base.take(room).trimEnd('.', ' ')
            return "$base.$ext"
        }
    }
}
