package com.mysi.agent.web

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Picks a page's character encoding the way browsers do: byte-order mark, then the
 * Content-Type header, then a <meta> or XML declaration in the first bytes, then UTF-8 if the
 * bytes are valid UTF-8, else windows-1252 (which browsers use for "latin1" and "ascii" too).
 */
object TextDecoding {
    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")

    fun charsetFromContentType(contentType: String?): String? =
        contentType?.split(';')?.drop(1)?.map { it.trim() }
            ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')?.trim()?.trim('"', '\'')?.takeIf { it.isNotEmpty() }

    /** Looks for `<meta charset>`, `<meta http-equiv content="…charset=">` or `<?xml encoding>`. */
    fun charsetFromMarkup(bytes: ByteArray): String? {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        Regex("^\\s*<\\?xml[^>]*encoding\\s*=\\s*[\"']([A-Za-z0-9._:-]+)[\"']").find(head)?.let { return it.groupValues[1] }
        Regex("<meta[^>]+charset\\s*=\\s*[\"']?\\s*([A-Za-z0-9._:-]+)", RegexOption.IGNORE_CASE).find(head)?.let { return it.groupValues[1] }
        return null
    }

    private fun lookup(name: String?): Charset? {
        val n = name?.trim()?.lowercase() ?: return null
        val mapped = when (n) {
            "latin1", "latin-1", "iso-8859-1", "iso8859-1", "l1", "ascii", "us-ascii", "cp1252", "x-cp1252" -> "windows-1252"
            "utf8", "unicode-1-1-utf-8" -> "utf-8"
            // A page cannot really be UTF-16 if it declared it in ASCII-compatible markup.
            "utf-16", "utf-16le", "utf-16be" -> return null
            else -> n
        }
        return runCatching { Charset.forName(mapped) }.getOrNull()
    }

    private fun validUtf8(bytes: ByteArray): Boolean =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: CharacterCodingException) {
            false
        }

    /** Decodes [bytes]; [truncated] means a multi-byte character may have been cut at the end. */
    fun decode(bytes: ByteArray, contentType: String?, truncated: Boolean = false): String {
        when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val declared = charsetFromContentType(contentType)?.let(::lookup) ?: charsetFromMarkup(bytes)?.let(::lookup)
        if (declared != null) return String(bytes, declared)
        // When the download was cut short, drop the last (possibly partial) character before testing for UTF-8.
        var end = bytes.size
        if (truncated && end > 0) {
            var i = end - 1
            while (i > 0 && end - i < 4 && (bytes[i].toInt() and 0xC0) == 0x80) i--
            end = i
        }
        val probe = if (end < bytes.size) bytes.copyOf(end) else bytes
        return if (validUtf8(probe)) String(bytes, Charsets.UTF_8) else String(bytes, WINDOWS_1252)
    }
}
