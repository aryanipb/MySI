package com.mysi.agent.web

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UrlSafetyTest {
    private val fake = FakeWeb()
    private val safety = UrlSafety(fake.resolver)

    private fun blocked(url: String): Boolean {
        val u = WebUrl.parse(url) ?: return true
        return try {
            safety.check(u)
            false
        } catch (_: BlockedUrlException) {
            true
        }
    }

    private fun v4(s: String) = UrlSafety.parseIpv4(s)?.joinToString(".") { (it.toInt() and 0xFF).toString() }

    @Test
    fun everyPrivateAndSpecialIpv4RangeIsBlocked() {
        for (ip in listOf(
            "0.0.0.0", "0.1.2.3", "10.0.0.1", "10.255.255.255", "100.64.0.1", "100.127.255.254", "127.0.0.1", "127.255.0.9",
            "169.254.169.254", "172.16.0.1", "172.31.255.255", "192.0.0.8", "192.0.2.1", "192.88.99.1", "192.168.1.1",
            "198.18.0.1", "198.19.255.255", "198.51.100.7", "203.0.113.9", "224.0.0.1", "239.255.255.250", "240.0.0.1", "255.255.255.255",
        )) assertTrue("$ip should be blocked", blocked("http://$ip/"))
        for (ip in listOf("8.8.8.8", "1.1.1.1", "172.15.255.255", "172.32.0.1", "100.63.255.255", "100.128.0.1", "93.184.216.34", "11.0.0.1"))
            assertFalse("$ip should be allowed", blocked("http://$ip/"))
    }

    @Test
    fun ipv6LoopbackPrivateLinkLocalMulticastAndMappedAreBlocked() {
        for (ip in listOf(
            "[::1]", "[::]", "[0:0:0:0:0:0:0:1]", "[fc00::1]", "[fd12:3456::1]", "[fe80::1]", "[febf::1]", "[fec0::1]", "[ff02::1]",
            "[::ffff:127.0.0.1]", "[::ffff:7f00:1]", "[::ffff:192.168.0.1]", "[::ffff:0:10.0.0.1]", "[::127.0.0.1]",
            "[64:ff9b::7f00:1]", "[64:ff9b::192.168.1.1]", "[64:ff9b:1::1]", "[2002:7f00:1::1]", "[2002:c0a8:101::]",
            "[2001::1]", "[2001:db8::1]", "[100::1]",
        )) assertTrue("$ip should be blocked", blocked("http://$ip/"))
    }

    @Test
    fun nat64And6to4AreJudgedByTheEmbeddedIpv4() {
        // On IPv6-only mobile networks, DNS64 maps every IPv4-only site into 64:ff9b::/96.
        assertFalse(blocked("http://[64:ff9b::808:808]/"))
        assertFalse(blocked("http://[64:ff9b::8.8.8.8]/"))
        assertFalse(blocked("http://[2002:808:808::1]/"))
        assertFalse(blocked("http://[2606:4700:4700::1111]/"))
        assertFalse(blocked("http://[::ffff:8.8.8.8]/"))
    }

    @Test
    fun decimalHexOctalAndShortIpv4FormsAreDecodedLikeInetAton() {
        assertEquals("127.0.0.1", v4("2130706433"))
        assertEquals("127.0.0.1", v4("0x7f000001"))
        assertEquals("127.0.0.1", v4("0x7f.0.0.1"))
        assertEquals("127.0.0.1", v4("0177.0.0.1"))
        assertEquals("127.0.0.1", v4("0177.0.0.01"))
        assertEquals("127.0.0.1", v4("127.1"))
        assertEquals("127.0.0.1", v4("127.0.1"))
        assertEquals("10.0.0.1", v4("012.0.0.1"))
        assertEquals("192.168.0.1", v4("0xc0.0xa8.0.1"))
        assertEquals("192.168.0.1", v4("3232235521"))
        assertNull(v4("256.0.0.1"))
        assertNull(v4("1.2.3.4.5"))
        assertNull(v4("example.com"))
        for (h in listOf("2130706433", "0x7f000001", "0x7f.1", "0177.0.0.1", "127.1", "017700000001", "0xA9.0xFE.0xA9.0xFE", "3232235521", "167772161"))
            assertTrue("$h should be blocked", blocked("http://$h/"))
        // Numeric-looking but invalid hosts are refused, never sent to DNS.
        assertTrue(blocked("http://1.2.3.999/"))
        assertTrue(blocked("http://08.0.0.1/"))
        assertTrue(blocked("http://example.0x1/"))
    }

    @Test
    fun userInfoAndOtherParserTricksAreRejected() {
        for (u in listOf(
            "http://google.com@127.0.0.1/", "http://user:pass@example.com/", "http://127.0.0.1#@example.com/", "http://example.com\\@127.0.0.1/",
            "http://127.0.0.1%2f@example.com/", "http://[::1%25eth0]/", "ftp://example.com/", "file:///etc/passwd", "javascript:alert(1)",
            "data:text/html,hi", "gopher://example.com/", "http:///x", "http://exa mple.com/", "http://example.com:99999/", "http://example.com:/x",
        )) assertTrue("$u should be refused", blocked(u))
        // '#@' puts "@example.com" in the fragment, so the host really is 127.0.0.1: blocked, not misread.
        assertEquals("127.0.0.1", WebUrl.parse("http://127.0.0.1#@example.com/")?.host)
    }

    @Test
    fun localNamesAndSingleLabelHostsAreBlockedBeforeDns() {
        for (u in listOf(
            "http://localhost/", "http://LOCALHOST./", "http://foo.localhost/", "http://printer.local/", "http://nas.lan/", "http://router/",
            "http://intranet/", "http://db.internal/", "http://box.home.arpa/", "http://ip6-localhost/",
        )) assertTrue("$u should be blocked", blocked(u))
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun badPortsAreRefused() {
        for (p in listOf(22, 25, 110, 143, 6667)) assertTrue("port $p", blocked("http://example.com:$p/"))
        assertFalse(blocked("http://example.com:8080/"))
        assertFalse(blocked("https://example.com:8443/"))
    }

    @Test
    fun hostsThatResolveToPrivateAddressesAreBlocked() {
        fake.dns["rebind.example"] = listOf(fake.ip(10, 0, 0, 7))
        fake.dns["mixed.example"] = listOf(fake.ip(8, 8, 8, 8), fake.ip(192, 168, 1, 1))
        fake.dns["v6.example"] = listOf(InetAddress.getByAddress(UrlSafety.parseIpv6("fd00::5")!!))
        fake.dns["ok.example"] = listOf(fake.ip(8, 8, 4, 4))
        assertTrue(blocked("https://rebind.example/"))
        assertTrue(blocked("https://mixed.example/"))
        assertTrue(blocked("https://v6.example/"))
        assertFalse(blocked("https://ok.example/"))
        assertEquals(listOf(fake.ip(8, 8, 4, 4)), safety.check(WebUrl.parse("https://ok.example/")!!))
    }

    @Test
    fun parsingNormalisesWithoutRepairingTheHost() {
        assertEquals("https://example.com/", WebUrl.parse("example.com")?.toString())
        assertEquals("https://example.com/a/b?q=1", WebUrl.parse("  example.com/a/b?q=1 ")?.toString())
        assertEquals("https://xn--bcher-kva.de/", WebUrl.parse("https://BÜCHER.de")?.toString())
        assertEquals("https://example.com/a%20b?x=%5B1%5D%7C2", WebUrl.parse("https://example.com/a b?x=[1]|2")?.toString())
        assertEquals("https://ru.example/%D0%9C%D0%BE%D1%81%D0%BA%D0%B2%D0%B0", WebUrl.parse("https://ru.example/Москва")?.toString())
        assertEquals("https://example.com/100%25", WebUrl.parse("https://example.com/100%")?.toString())
        assertNull(WebUrl.parse("/relative/path"))
        assertNull(WebUrl.parse("not a url"))
        assertNull(WebUrl.parse("https://exa_mple.com/"))
        assertNull(WebUrl.parse("https://example.com\u0000/"))
        assertNotNull(WebUrl.parse("http://[2606:4700::1111]:8080/x"))
        assertEquals(8080, WebUrl.parse("http://[2606:4700::1111]:8080/x")!!.port)
    }

    @Test
    fun relativeReferencesResolveLikeBrowsers() {
        val base = WebUrl.parse("https://example.com/docs/guide/page.html?x=1")!!
        assertEquals("https://example.com/docs/guide/next.html", base.resolve("next.html").toString())
        assertEquals("https://example.com/docs/up.html", base.resolve("../up.html").toString())
        assertEquals("https://example.com/root", base.resolve("/root").toString())
        assertEquals("https://example.com/docs/guide/page.html?y=2", base.resolve("?y=2").toString())
        assertEquals("https://cdn.example.org/a.js", base.resolve("//cdn.example.org/a.js").toString())
        assertEquals("http://other.example/", base.resolve("http://other.example").toString())
        assertNull(base.resolve("mailto:a@example.com"))
        assertNull(base.resolve("javascript:alert(1)"))
        assertNull(base.resolve("http://127.0.0.1@evil.example/"))
    }

    @Test
    fun canonicalFormIgnoresTrackingAndCosmeticDifferences() {
        val a = WebUrl.parse("https://www.example.com/story/?utm_source=x&b=2&a=1&fbclid=abc#top")!!
        val b = WebUrl.parse("http://example.com/story?a=1&b=2")!!
        assertEquals(a.canonical, b.canonical)
        try {
            UrlSafety.checkStatic(WebUrl.parse("https://example.com/")!!)
        } catch (e: BlockedUrlException) {
            fail(e.message)
        }
    }
}
