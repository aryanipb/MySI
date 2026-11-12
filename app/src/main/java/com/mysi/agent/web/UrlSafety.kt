package com.mysi.agent.web

import java.io.IOException
import java.net.InetAddress

/** Resolves a host name to addresses. Injected so tests never touch DNS. */
fun interface Resolver {
    fun resolve(host: String): List<InetAddress>

    companion object {
        val SYSTEM = Resolver { host -> InetAddress.getAllByName(host).toList() }
    }
}

/** Thrown when an address must not be fetched; the message is safe to show the model. */
class BlockedUrlException(message: String) : IOException(message)

/**
 * Keeps web tools on the public internet. A page (or a prompt injection inside one) must never
 * make the phone talk to the user's router, other devices on the Wi-Fi, the phone itself, or
 * carrier-internal addresses.
 *
 * Host names are checked before resolution (localhost-style and single-label names, numeric hosts
 * in any notation) and every resolved address is checked after; the caller connects to exactly
 * the address that passed, so DNS cannot change its answer in between.
 */
class UrlSafety(private val resolver: Resolver = Resolver.SYSTEM) {

    /** Validates [url] and returns the public addresses it may connect to. */
    fun check(url: WebUrl): List<InetAddress> {
        checkStatic(url)
        literal(url.bareHost)?.let { ip ->
            if (blocked(ip)) throw BlockedUrlException("${url.host} is a private or local address, which web tools may not open.")
            return listOf(InetAddress.getByAddress(ip))
        }
        val addresses = try {
            resolver.resolve(url.host)
        } catch (e: IOException) {
            throw IOException("Could not find the site ${url.host}.", e)
        }
        if (addresses.isEmpty()) throw IOException("Could not find the site ${url.host}.")
        // One private answer is enough to refuse: mixed answers are a rebinding trick.
        if (addresses.any { blocked(it.address) }) throw BlockedUrlException("${url.host} points to a private or local address, which web tools may not open.")
        return addresses
    }

    companion object {
        /** Ports browsers refuse to fetch (WHATWG "bad ports"): mail, shell, file sharing… */
        private val BAD_PORTS =
            setOf(
                1, 7, 9, 11, 13, 15, 17, 19, 20, 21, 22, 23, 25, 37, 42, 43, 53, 69, 77, 79, 87, 95, 101, 102, 103, 104, 109, 110, 111, 113,
                115, 117, 119, 123, 135, 137, 139, 143, 161, 179, 389, 427, 465, 512, 513, 514, 515, 526, 530, 531, 532, 540, 548, 554, 556,
                563, 587, 601, 636, 989, 990, 993, 995, 1719, 1720, 1723, 2049, 3659, 4045, 4190, 5060, 5061, 6000, 6566, 6665, 6666, 6667,
                6668, 6669, 6679, 6697, 10080,
            )

        private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".localdomain", ".internal", ".lan", ".home", ".home.arpa", ".intranet", ".corp", ".private", ".onion", ".test", ".invalid")
        private val LOCAL_NAMES = setOf("localhost", "localhost.localdomain", "ip6-localhost", "ip6-loopback", "broadcasthost", "wpad")

        /** Checks that need no network: scheme, port, host name shape, numeric hosts. */
        fun checkStatic(url: WebUrl) {
            if (url.scheme != "http" && url.scheme != "https") throw BlockedUrlException("Only http and https addresses can be opened.")
            if (url.port in BAD_PORTS) throw BlockedUrlException("Port ${url.port} is not allowed for web pages.")
            val host = url.bareHost
            literal(host)?.let { ip ->
                if (blocked(ip)) throw BlockedUrlException("${url.host} is a private or local address, which web tools may not open.")
                return
            }
            if (looksNumeric(host)) throw BlockedUrlException("${url.host} is not a valid address.")
            if (host in LOCAL_NAMES || LOCAL_SUFFIXES.any { host.endsWith(it) })
                throw BlockedUrlException("${url.host} is a local network name, which web tools may not open.")
            if (!host.contains('.')) throw BlockedUrlException("${url.host} is not a public web address (no domain).")
            if (!Regex("^[a-z0-9]([a-z0-9-_]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-_]*[a-z0-9])?)*$").matches(host))
                throw BlockedUrlException("${url.host} is not a valid host name.")
        }

        /**
         * A host whose last label is numeric is an IPv4 address to browsers and to inet_aton
         * ("1.2.3.0x10", "2130706433"), so it must never reach the DNS resolver as a name.
         */
        fun looksNumeric(host: String): Boolean {
            val last = host.trimEnd('.').substringAfterLast('.')
            return last.isNotEmpty() && (last.all { it.isDigit() } || Regex("^0x[0-9a-f]*$", RegexOption.IGNORE_CASE).matches(last))
        }

        /** Parses an IP literal in any notation browsers or inet_aton accept; null if [host] is not one. */
        fun literal(host: String): ByteArray? = if (host.contains(':')) parseIpv6(host) else parseIpv4(host)

        /**
         * inet_aton rules: 1–4 parts, each decimal, octal (leading 0) or hex (0x); the last part
         * fills the remaining bytes. So 127.1, 0x7f.0.0.1, 0177.0.0.1 and 2130706433 are all
         * 127.0.0.1. Returns null for anything that is not entirely numeric.
         */
        fun parseIpv4(host: String): ByteArray? {
            val h = host.removeSuffix(".")
            val parts = h.split('.')
            if (parts.isEmpty() || parts.size > 4) return null
            val values = parts.map { p ->
                when {
                    p.isEmpty() -> return null
                    p.startsWith("0x") || p.startsWith("0X") -> if (p.length == 2) 0L else p.substring(2).toLongOrNull(16) ?: return null
                    p.length > 1 && p.startsWith("0") -> p.substring(1).toLongOrNull(8) ?: return null
                    else -> p.toLongOrNull() ?: return null
                }
            }
            if (values.any { it < 0 }) return null
            val lastMax = when (values.size) { 1 -> 0xFFFFFFFFL; 2 -> 0xFFFFFFL; 3 -> 0xFFFFL; else -> 0xFFL }
            if (values.dropLast(1).any { it > 255 } || values.last() > lastMax) return null
            var n = values.last()
            values.dropLast(1).forEachIndexed { i, v -> n = n or (v shl (8 * (3 - i))) }
            return byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
        }

        /** Parses an IPv6 literal, including "::" and a dotted IPv4 tail; zone ids are rejected. */
        fun parseIpv6(host: String): ByteArray? {
            val h = host.removePrefix("[").removeSuffix("]")
            if (h.contains('%') || h.isEmpty()) return null
            var groups = mutableListOf<Int>()
            var tail = h
            // A dotted IPv4 tail fills the last 32 bits.
            var v4: ByteArray? = null
            val lastColon = h.lastIndexOf(':')
            if (h.substring(lastColon + 1).contains('.')) {
                val dotted = h.substring(lastColon + 1)
                if (!Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(dotted)) return null
                v4 = dotted.split('.').map { it.toInt().takeIf { b -> b <= 255 } ?: return null }.map { it.toByte() }.toByteArray()
                tail = h.substring(0, lastColon + 1) + "0:0"
            }
            val doubleColon = tail.indexOf("::")
            if (doubleColon >= 0 && tail.indexOf("::", doubleColon + 1) >= 0) return null
            fun parseGroups(s: String): List<Int>? =
                if (s.isEmpty()) emptyList()
                else s.split(':').map { g -> if (g.isEmpty() || g.length > 4) return null else g.toIntOrNull(16) ?: return null }
            if (doubleColon >= 0) {
                val left = parseGroups(tail.substring(0, doubleColon)) ?: return null
                val right = parseGroups(tail.substring(doubleColon + 2)) ?: return null
                if (left.size + right.size > 7) return null
                groups.addAll(left)
                repeat(8 - left.size - right.size) { groups.add(0) }
                groups.addAll(right)
            } else {
                groups = parseGroups(tail)?.toMutableList() ?: return null
                if (groups.size != 8) return null
            }
            val bytes = ByteArray(16)
            groups.forEachIndexed { i, g ->
                bytes[2 * i] = (g shr 8).toByte()
                bytes[2 * i + 1] = g.toByte()
            }
            v4?.copyInto(bytes, 12)
            return bytes
        }

        private fun u(b: Byte) = b.toInt() and 0xFF

        private fun inV4(ip: ByteArray, a: Int, b: Int, c: Int, d: Int, bits: Int): Boolean {
            val addr = (u(ip[0]) shl 24) or (u(ip[1]) shl 16) or (u(ip[2]) shl 8) or u(ip[3])
            val net = (a shl 24) or (b shl 16) or (c shl 8) or d
            val mask = if (bits == 0) 0 else (-1 shl (32 - bits))
            return (addr and mask) == (net and mask)
        }

        private fun blockedV4(ip: ByteArray) =
            inV4(ip, 0, 0, 0, 0, 8) || // "this network", including 0.0.0.0
                inV4(ip, 10, 0, 0, 0, 8) || // private
                inV4(ip, 100, 64, 0, 0, 10) || // carrier-grade NAT
                inV4(ip, 127, 0, 0, 0, 8) || // loopback
                inV4(ip, 169, 254, 0, 0, 16) || // link-local, cloud metadata
                inV4(ip, 172, 16, 0, 0, 12) || // private
                inV4(ip, 192, 0, 0, 0, 24) || // IETF protocol assignments
                inV4(ip, 192, 0, 2, 0, 24) || // documentation
                inV4(ip, 192, 88, 99, 0, 24) || // 6to4 relay anycast
                inV4(ip, 192, 168, 0, 0, 16) || // private
                inV4(ip, 198, 18, 0, 0, 15) || // benchmarking
                inV4(ip, 198, 51, 100, 0, 24) || // documentation
                inV4(ip, 203, 0, 113, 0, 24) || // documentation
                inV4(ip, 224, 0, 0, 0, 4) || // multicast
                inV4(ip, 240, 0, 0, 0, 4) // reserved and broadcast

        private fun prefix(ip: ByteArray, vararg bytes: Int) = bytes.indices.all { u(ip[it]) == bytes[it] }

        /** True when [ip] (4 or 16 bytes) is not a public unicast address. */
        fun blocked(ip: ByteArray): Boolean {
            if (ip.size == 4) return blockedV4(ip)
            if (ip.size != 16) return true
            val first = u(ip[0])
            val second = u(ip[1])
            val embedded = ip.copyOfRange(12, 16)
            return when {
                // ::/96 covers ::, ::1 and deprecated IPv4-compatible addresses.
                (0 until 12).all { ip[it].toInt() == 0 } -> true
                // ::ffff:a.b.c.d (IPv4-mapped) and ::ffff:0:a.b.c.d (IPv4-translated): judge the IPv4 part.
                (0 until 10).all { ip[it].toInt() == 0 } && u(ip[10]) == 0xFF && u(ip[11]) == 0xFF -> blockedV4(embedded)
                (0 until 8).all { ip[it].toInt() == 0 } && u(ip[8]) == 0xFF && u(ip[9]) == 0xFF && ip[10].toInt() == 0 && ip[11].toInt() == 0 -> blockedV4(embedded)
                // NAT64 well-known prefix 64:ff9b::/96: on IPv6-only mobile networks every IPv4 site lives here.
                prefix(ip, 0x00, 0x64, 0xff, 0x9b) && (4 until 12).all { ip[it].toInt() == 0 } -> blockedV4(embedded)
                // Local-use NAT64 64:ff9b:1::/48 and discard-only 100::/64.
                prefix(ip, 0x00, 0x64, 0xff, 0x9b, 0x00, 0x01) -> true
                prefix(ip, 0x01, 0x00) && (2 until 8).all { ip[it].toInt() == 0 } -> true
                // 6to4 2002:AABB:CCDD::/48 embeds a.b.c.d in bytes 2–5.
                prefix(ip, 0x20, 0x02) -> blockedV4(ip.copyOfRange(2, 6))
                // Teredo 2001::/32 tunnels to obfuscated addresses; documentation 2001:db8::/32.
                prefix(ip, 0x20, 0x01, 0x00, 0x00) -> true
                prefix(ip, 0x20, 0x01, 0x0d, 0xb8) -> true
                (first and 0xFE) == 0xFC -> true // unique local fc00::/7
                first == 0xFE && (second and 0xC0) == 0x80 -> true // link-local fe80::/10
                first == 0xFE && (second and 0xC0) == 0xC0 -> true // site-local fec0::/10
                first == 0xFF -> true // multicast
                else -> false
            }
        }
    }
}
