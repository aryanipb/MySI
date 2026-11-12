package com.mysi.agent.web

import java.net.IDN
import java.net.URI
import java.net.URLDecoder

/**
 * A parsed http(s) address. It is parsed once, strictly, and both the safety check and the
 * connection use these same components, so no second parser can read the address differently.
 *
 * Rejected outright (never "repaired"): other schemes, user info ("user@host" tricks),
 * backslashes, control characters, whitespace in the host, a missing or registry-style host,
 * zone ids, and ports outside 1–65535.
 */
class WebUrl private constructor(
    val scheme: String,
    /** Lower-case ASCII host; IPv6 literals keep their brackets. */
    val host: String,
    /** Explicit port, or -1 for the scheme default. */
    val explicitPort: Int,
    /** Raw (still percent-encoded) path, query and fragment; path is "/" when empty. */
    val rawPath: String,
    val rawQuery: String?,
    val rawFragment: String?,
) {
    val https get() = scheme == "https"
    val port get() = if (explicitPort > 0) explicitPort else if (https) 443 else 80

    /** Host as sent in the Host header: with the port only when it is not the default. */
    val authority get() = if (explicitPort > 0 && explicitPort != (if (https) 443 else 80)) "$host:$explicitPort" else host

    /** Request target: path plus query, never the fragment. */
    val target get() = rawPath + (rawQuery?.let { "?$it" } ?: "")

    val origin get() = "$scheme://$authority"

    /** Host without IPv6 brackets. */
    val bareHost get() = host.removePrefix("[").removeSuffix("]")

    override fun toString() = "$scheme://$authority$target" + (rawFragment?.let { "#$it" } ?: "")

    override fun equals(other: Any?) = other is WebUrl && other.toString() == toString()

    override fun hashCode() = toString().hashCode()

    /** The same address over https (an explicit port 80 becomes the https default). */
    fun upgradedToHttps() = WebUrl("https", host, if (explicitPort == 80) -1 else explicitPort, rawPath, rawQuery, rawFragment)

    /** Resolves [ref] (absolute or relative, as found in a link or Location header) against this URL. */
    fun resolve(ref: String): WebUrl? {
        val r = ref.trim()
        if (r.isEmpty() || hasForbiddenChars(r)) return null
        if (Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(r)) return parse(r)
        if (r.startsWith("//")) return parse("$scheme:$r")
        // Any other scheme (mailto:, javascript:, tel:, data:) is not a web page.
        if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(r)) return null
        // java.net.URI resolves "?q" and "#f" by RFC 2396 rules, which drop the last path segment.
        if (r.startsWith("?")) return parse("$origin$rawPath$r")
        if (r.startsWith("#")) return parse("$origin$target$r")
        val resolved = runCatching { URI("$origin$target").resolve(URI(encodeIllegal(r))) }.getOrNull() ?: return null
        return parse(resolved.toString())
    }

    /** Lower-case path segment after the last slash, percent-decoded; "" for a directory. */
    val fileName: String
        get() = runCatching { URLDecoder.decode(rawPath.substringAfterLast('/').replace("+", "%2B"), "UTF-8") }.getOrDefault("")

    /** Query parameter value, decoded; null when absent. */
    fun query(name: String): String? =
        rawQuery?.split('&')?.firstNotNullOfOrNull { pair ->
            val k = runCatching { URLDecoder.decode(pair.substringBefore('='), "UTF-8") }.getOrNull()
            if (k == name) runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrNull() else null
        }

    /**
     * A key for de-duplicating results that point at the same page: no scheme, no "www.",
     * no fragment, no tracking parameters, no trailing slash, sorted query.
     */
    val canonical: String
        get() {
            val h = host.removePrefix("www.").removePrefix("m.")
            val q = rawQuery?.split('&')
                ?.filter { p -> p.isNotEmpty() && TRACKING.none { t -> p.substringBefore('=').lowercase().let { it == t || (t.endsWith("_") && it.startsWith(t)) } } }
                ?.sorted()
                ?.joinToString("&")
                ?.takeIf { it.isNotEmpty() }
            val p = rawPath.trimEnd('/').ifEmpty { "" }
            return h + (if (explicitPort > 0 && explicitPort != (if (https) 443 else 80)) ":$explicitPort" else "") + p + (q?.let { "?$it" } ?: "")
        }

    companion object {
        private val TRACKING = listOf("utm_", "fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid", "igshid", "ref_src", "_hsenc", "_hsmi", "yclid", "oc")

        private const val HEX = "0123456789ABCDEF"

        /**
         * Percent-encodes characters that browsers send encoded but java.net.URI rejects
         * (space, quotes, brackets, pipes, a stray '%', a second '#'), leaving everything else alone.
         */
        private fun encodeIllegal(rest: String): String {
            val sb = StringBuilder(rest.length + 16)
            var seenHash = false
            rest.forEachIndexed { i, c ->
                val stray = c == '%' && !(i + 2 < rest.length && rest[i + 1].isHex() && rest[i + 2].isHex())
                val extraHash = c == '#' && seenHash
                if (c == '#') seenHash = true
                if (stray || extraHash || c in " \"<>^`{|}[]") sb.append('%').append(HEX[c.code shr 4]).append(HEX[c.code and 15]) else sb.append(c)
            }
            return sb.toString()
        }

        private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        private fun hasForbiddenChars(s: String) = s.any { it == '\\' || it.code < 0x20 || it.code == 0x7F }

        /**
         * Parses an address typed by the user or the model. A bare domain ("example.com/page")
         * gets https://. Spaces in the path or query are percent-encoded; anything else odd fails.
         */
        fun parse(input: String): WebUrl? {
            var s = input.trim()
            if (s.isEmpty() || s.length > 4096 || hasForbiddenChars(s)) return null
            if (s.indexOf("://") < 0) {
                // No scheme: accept only something that starts like a host name ("example.com/page").
                if (!Regex("^[\\p{L}\\p{N}.-]+(:\\d+)?([/?#].*)?$").matches(s) || s.startsWith(".") || !s.substringBefore('/').contains('.')) return null
                s = "https://$s"
            }
            val sep = s.indexOf("://")
            val scheme = s.substring(0, sep).lowercase()
            if (scheme != "http" && scheme != "https") return null
            val authorityEnd = s.indexOfAny(charArrayOf('/', '?', '#'), sep + 3).let { if (it < 0) s.length else it }
            val authority = s.substring(sep + 3, authorityEnd)
            if (authority.isEmpty() || authority.any { it.isWhitespace() } || authority.contains('@') || authority.contains('%')) return null
            // Split host and port ourselves so an internationalised host can be converted first.
            val hostPart: String
            val portPart: String?
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0) return null
                hostPart = authority.substring(0, close + 1)
                val after = authority.substring(close + 1)
                portPart = if (after.isEmpty()) null else if (after.startsWith(":")) after.substring(1) else return null
            } else {
                hostPart = authority.substringBefore(':')
                portPart = if (authority.contains(':')) authority.substringAfter(':') else null
            }
            if (portPart != null && (portPart.isEmpty() || !portPart.all { it.isDigit() } || portPart.length > 5)) return null
            val asciiHost =
                if (hostPart.startsWith("[")) hostPart.lowercase()
                else runCatching { IDN.toASCII(hostPart.trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase() }.getOrNull() ?: return null
            if (asciiHost.isEmpty() || asciiHost.length > 253) return null
            val rest = encodeIllegal(s.substring(authorityEnd))
            val built = scheme + "://" + asciiHost + (portPart?.let { ":$it" } ?: "") + rest
            // toASCIIString percent-encodes any non-ASCII characters in the path, query and fragment.
            val uri = runCatching { URI(URI(built).toASCIIString()) }.getOrNull() ?: return null
            if (uri.rawUserInfo != null) return null
            val host = uri.host?.lowercase() ?: return null
            if (host != asciiHost) return null
            val port = uri.port
            if (port != -1 && port !in 1..65535) return null
            val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
            return WebUrl(scheme, host, port, path, uri.rawQuery, uri.rawFragment)
        }
    }
}
