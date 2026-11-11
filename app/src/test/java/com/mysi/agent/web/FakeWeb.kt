package com.mysi.agent.web

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.util.zip.GZIPOutputStream

/**
 * An in-memory web for tests: a resolver with fixed answers and a transport that hands each
 * request to [handler] and replays the raw HTTP bytes it returns. Nothing touches the network.
 */
class FakeWeb {
    class Request(val raw: String, val url: WebUrl, val address: InetAddress) {
        val method get() = raw.substringBefore(' ')
        val body get() = raw.substringAfter("\r\n\r\n", "")
        val headerLines get() = raw.substringBefore("\r\n\r\n").split("\r\n").drop(1)

        fun header(name: String) = headerLines.firstOrNull { it.startsWith("$name:", ignoreCase = true) }?.substringAfter(':')?.trim()
    }

    val requests = mutableListOf<Request>()
    val dns = HashMap<String, List<InetAddress>>()
    var handler: (Request) -> ByteArray = { notFound() }

    /** Public address used for any host without an explicit DNS entry. */
    private val public = InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))

    val resolver = Resolver { host -> dns[host] ?: listOf(InetAddress.getByAddress(host, public.address)) }

    fun ip(vararg b: Int): InetAddress = InetAddress.getByAddress(ByteArray(b.size) { b[it].toByte() })

    val transport = Transport { address, url, _, _ ->
        val out = ByteArrayOutputStream()
        object : Connection {
            override val output: OutputStream = out
            override val input: InputStream by lazy {
                val req = Request(out.toString(Charsets.ISO_8859_1.name()), url, address)
                requests += req
                ByteArrayInputStream(handler(req))
            }

            override fun close() = Unit
        }
    }

    fun client(
        config: HttpClient.Config = HttpClient.Config(politenessMs = 0),
        cleartext: (String) -> Boolean = { false },
        nanoTime: () -> Long = System::nanoTime,
        sleeper: (Long) -> Unit = {},
    ) = HttpClient(UrlSafety(resolver), transport, config, cleartext, nanoTime, sleeper)

    companion object {
        fun response(status: Int, headers: List<Pair<String, String>>, body: ByteArray): ByteArray {
            val head = StringBuilder("HTTP/1.1 $status X\r\n")
            headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
            head.append("\r\n")
            return head.toString().toByteArray(Charsets.ISO_8859_1) + body
        }

        fun ok(body: String, type: String = "text/html; charset=utf-8") = ok(body.toByteArray(), type)

        fun ok(body: ByteArray, type: String) = response(200, listOf("Content-Type" to type, "Content-Length" to body.size.toString()), body)

        fun redirect(status: Int, location: String) = response(status, listOf("Location" to location, "Content-Length" to "0"), ByteArray(0))

        fun notFound() = response(404, listOf("Content-Length" to "9"), "not found".toByteArray())

        fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

        fun chunked(b: ByteArray, size: Int): ByteArray {
            val o = ByteArrayOutputStream()
            var i = 0
            while (i < b.size) {
                val n = minOf(size, b.size - i)
                o.write("${Integer.toHexString(n)};ext=1\r\n".toByteArray())
                o.write(b, i, n)
                o.write("\r\n".toByteArray())
                i += n
            }
            o.write("0\r\nX-Trailer: y\r\n\r\n".toByteArray())
            return o.toByteArray()
        }
    }
}
