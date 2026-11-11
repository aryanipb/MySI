package com.mysi.agent.web

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** One open connection to a server. */
interface Connection : Closeable {
    val input: InputStream
    val output: OutputStream
}

/** Opens a connection to an already-vetted [address] for [url]. Injected so tests never touch the network. */
fun interface Transport {
    fun open(address: InetAddress, url: WebUrl, connectTimeoutMs: Int, readTimeoutMs: Int): Connection
}

/**
 * Plain sockets plus the platform TLS stack. The socket connects to the exact address the safety
 * check approved; TLS still verifies the certificate against the host name (SNI and
 * endpoint identification "HTTPS"), and [hostnameVerifier], when given, checks it once more.
 */
class SocketTransport(private val hostnameVerifier: HostnameVerifier? = null) : Transport {
    override fun open(address: InetAddress, url: WebUrl, connectTimeoutMs: Int, readTimeoutMs: Int): Connection {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(address, url.port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            if (!url.https) return SocketConnection(socket)
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(socket, url.bareHost, url.port, true) as SSLSocket
            val params = ssl.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            if (UrlSafety.literal(url.bareHost) == null) params.serverNames = listOf(SNIHostName(url.bareHost))
            ssl.sslParameters = params
            ssl.soTimeout = readTimeoutMs
            ssl.startHandshake()
            if (hostnameVerifier != null && !hostnameVerifier.verify(url.bareHost, ssl.session)) {
                ssl.close()
                throw SSLPeerUnverifiedException("The certificate does not match ${url.host}.")
            }
            return SocketConnection(ssl)
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
    }

    private class SocketConnection(private val socket: Socket) : Connection {
        override val input: InputStream = socket.getInputStream()
        override val output: OutputStream = socket.getOutputStream()

        override fun close() = socket.close()
    }
}

class HttpRequest(
    val url: WebUrl,
    val method: String = "GET",
    val body: ByteArray? = null,
    val contentType: String? = null,
    val accept: String = ACCEPT_HTML,
    val desktop: Boolean = false,
) {
    companion object {
        const val ACCEPT_HTML = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        const val ACCEPT_JSON = "application/json"
        const val ACCEPT_FEED = "application/rss+xml,application/atom+xml,application/xml;q=0.9,text/xml;q=0.8"
        const val ACCEPT_ANY = "*/*"
    }
}

/** A response whose body has not been read yet. Close it when done. */
class HttpResponse(
    val status: Int,
    /** Header names in lower case. */
    val headers: Map<String, List<String>>,
    /** Where the response finally came from, after redirects. */
    val url: WebUrl,
    /** De-chunked and decompressed body. */
    val body: InputStream,
    private val connection: Closeable,
) : Closeable {
    fun header(name: String): String? = headers[name.lowercase()]?.lastOrNull()

    val contentType get() = header("content-type")

    val mime get() = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()

    val contentLength get() = header("content-length")?.trim()?.toLongOrNull() ?: -1L

    override fun close() {
        runCatching { body.close() }
        runCatching { connection.close() }
    }
}

/** A fully read response. */
class FetchedPage(
    val url: WebUrl,
    val status: Int,
    val contentType: String?,
    val bytes: ByteArray,
    /** True when the body was longer than the byte cap and was cut. */
    val truncated: Boolean,
) {
    val mime get() = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()

    val text: String by lazy { TextDecoding.decode(bytes, contentType, truncated) }

    val ok get() = status in 200..299
}

/** Parses an HTTP/1.1 response head and frames its body. Bounded, so a hostile server cannot exhaust memory. */
object ResponseReader {
    const val MAX_LINE = 8 * 1024
    const val MAX_HEAD = 64 * 1024
    const val MAX_HEADERS = 200

    class Head(val status: Int, val headers: Map<String, List<String>>)

    fun readLine(input: InputStream, budget: IntArray): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (sb.isEmpty()) throw IOException("The server closed the connection without answering.")
                break
            }
            if (--budget[0] < 0) throw IOException("The server sent oversized headers.")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > MAX_LINE) throw IOException("The server sent an oversized header line.")
        }
        return sb.toString()
    }

    fun readHead(input: InputStream): Head {
        val budget = intArrayOf(MAX_HEAD)
        while (true) {
            val statusLine = readLine(input, budget)
            val m = Regex("^HTTP/1\\.[01] (\\d{3})(?: .*)?$").find(statusLine) ?: throw IOException("The server did not answer in HTTP.")
            val status = m.groupValues[1].toInt()
            val headers = LinkedHashMap<String, MutableList<String>>()
            var count = 0
            while (true) {
                val line = readLine(input, budget)
                if (line.isEmpty()) break
                if (++count > MAX_HEADERS) throw IOException("The server sent too many headers.")
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                headers.getOrPut(line.substring(0, colon).trim().lowercase()) { mutableListOf() }.add(line.substring(colon + 1).trim())
            }
            // 1xx informational responses (100 Continue, 103 Early Hints) precede the real one.
            if (status in 100..199) continue
            return Head(status, headers)
        }
    }

    /** Body stream for [head]: framed by chunking, Content-Length or connection close, then decompressed. */
    fun body(head: Head, input: InputStream, method: String): InputStream {
        val noBody = method == "HEAD" || head.status == 204 || head.status == 304
        val framed: InputStream = when {
            noBody -> InputStream.nullInputStream()
            head.headers["transfer-encoding"]?.lastOrNull()?.lowercase()?.contains("chunked") == true -> ChunkedInputStream(input)
            head.headers["content-length"] != null -> {
                val len = head.headers.getValue("content-length").last().trim().toLongOrNull()
                    ?: throw IOException("The server sent an invalid Content-Length.")
                if (len < 0) throw IOException("The server sent an invalid Content-Length.")
                FixedLengthInputStream(input, len)
            }
            else -> input
        }
        return when (val enc = head.headers["content-encoding"]?.lastOrNull()?.trim()?.lowercase().orEmpty()) {
            "", "identity", "none" -> framed
            "gzip", "x-gzip" -> if (noBody) framed else GZIPInputStream(framed, 8192)
            "deflate" -> if (noBody) framed else InflaterInputStream(framed)
            else -> throw IOException("The server used an unsupported compression ($enc).")
        }
    }

    class ChunkedInputStream(private val raw: InputStream) : InputStream() {
        private var remaining = 0L
        private var started = false
        private var done = false

        private fun nextChunk() {
            val budget = intArrayOf(MAX_HEAD)
            // Each chunk after the first is preceded by the CRLF that ended the previous one.
            if (started) readLine(raw, budget)
            started = true
            val line = readLine(raw, budget).substringBefore(';').trim()
            val size = line.toLongOrNull(16) ?: throw IOException("The server sent a broken chunked response.")
            if (size < 0) throw IOException("The server sent a broken chunked response.")
            if (size == 0L) {
                // Trailers until an empty line; tolerate a server that just closes.
                while (runCatching { readLine(raw, budget) }.getOrDefault("").isNotEmpty()) Unit
                done = true
            }
            remaining = size
        }

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (done) return -1
            if (len == 0) return 0
            if (remaining == 0L) {
                nextChunk()
                if (done) return -1
            }
            val n = raw.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n < 0) throw IOException("The connection closed in the middle of the page.")
            remaining -= n
            return n
        }
    }

    class FixedLengthInputStream(private val raw: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = raw.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n < 0) throw IOException("The connection closed in the middle of the page.")
            remaining -= n
            return n
        }
    }
}

/** Fails a read once the request's overall time is up, so a server trickling bytes cannot hold a tool forever. */
class DeadlineInputStream(input: InputStream, private val deadlineNanos: Long, private val nanoTime: () -> Long) : FilterInputStream(input) {
    private fun check() {
        if (nanoTime() > deadlineNanos) throw SocketTimeoutException("The site took too long to respond.")
    }

    override fun read(): Int {
        check()
        return super.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        check()
        return super.read(b, off, len)
    }
}

/**
 * The only way web tools reach the network. Every hop (including each redirect) is parsed,
 * checked by [UrlSafety] and connected to the vetted address. Requests carry only a fixed,
 * honest User-Agent, Accept headers and Accept-Encoding: gzip; never cookies, referrers or anything
 * about the user.
 */
class HttpClient(
    private val safety: UrlSafety,
    private val transport: Transport,
    val config: Config = Config(),
    /** Android's network security policy; when it forbids plain http for a host, http is upgraded to https. */
    private val cleartextPermitted: (String) -> Boolean = { false },
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    class Config(
        val mobileAgent: String = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36 MySI",
        val desktopAgent: String = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 MySI",
        val connectTimeoutMs: Int = 10_000,
        val readTimeoutMs: Int = 15_000,
        val deadlineMs: Long = 30_000,
        val maxRedirects: Int = 5,
        /** Minimum gap between two requests to the same host. */
        val politenessMs: Long = 750,
        val maxTextBytes: Int = 3_000_000,
    )

    val cache = PageCache()

    private val nextSlot = HashMap<String, Long>()

    private fun politeness(host: String) {
        if (config.politenessMs <= 0) return
        val now = nanoTime() / 1_000_000
        val wait: Long
        synchronized(nextSlot) {
            val slot = maxOf(now, nextSlot[host] ?: 0L)
            wait = slot - now
            nextSlot[host] = slot + config.politenessMs
        }
        if (wait > 0) sleeper(wait)
    }

    /** Applies the platform's cleartext rule: http becomes https unless plain http is permitted. */
    private fun upgrade(url: WebUrl): WebUrl {
        if (url.https || cleartextPermitted(url.bareHost)) return url
        if (url.explicitPort > 0 && url.explicitPort != 80) throw BlockedUrlException("Plain http on port ${url.explicitPort} is not allowed; the site must use https.")
        return url.upgradedToHttps()
    }

    private fun writeRequest(out: OutputStream, url: WebUrl, method: String, req: HttpRequest, body: ByteArray?) {
        val sb = StringBuilder()
        sb.append(method).append(' ').append(url.target).append(" HTTP/1.1\r\n")
        sb.append("Host: ").append(url.authority).append("\r\n")
        sb.append("User-Agent: ").append(if (req.desktop) config.desktopAgent else config.mobileAgent).append("\r\n")
        sb.append("Accept: ").append(req.accept).append("\r\n")
        sb.append("Accept-Language: en\r\n")
        sb.append("Accept-Encoding: gzip\r\n")
        sb.append("Connection: close\r\n")
        if (body != null) {
            sb.append("Content-Type: ").append(req.contentType ?: "application/x-www-form-urlencoded").append("\r\n")
            sb.append("Content-Length: ").append(body.size).append("\r\n")
        }
        sb.append("\r\n")
        if (sb.any { it.code > 0x7E }) throw IOException("The address contains characters that cannot be sent.")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        body?.let { out.write(it) }
        out.flush()
    }

    private fun connect(addresses: List<InetAddress>, url: WebUrl, deadline: Long): Connection {
        var last: IOException? = null
        // In the resolver's order (IPv6/IPv4 as the network prefers), at most three addresses.
        for (a in addresses.take(3)) {
            if (nanoTime() > deadline) break
            try {
                return transport.open(a, url, config.connectTimeoutMs, config.readTimeoutMs)
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: SocketTimeoutException("The site took too long to respond.")
    }

    /**
     * Sends [request], following up to [Config.maxRedirects] redirects. 301/302/303 become a GET
     * without body (as browsers do); 307/308 keep the method and body. Returns the first
     * non-redirect response, whatever its status.
     */
    fun open(request: HttpRequest, deadlineMs: Long = config.deadlineMs): HttpResponse {
        val deadline = nanoTime() + deadlineMs * 1_000_000
        UrlSafety.checkStatic(request.url)
        var url = upgrade(request.url)
        var method = request.method
        var body = request.body
        for (hop in 0..config.maxRedirects) {
            val addresses = safety.check(url)
            politeness(url.bareHost)
            val conn = connect(addresses, url, deadline)
            try {
                writeRequest(conn.output, url, method, request, body)
                val input = DeadlineInputStream(BufferedInputStream(conn.input, 16_384), deadline, nanoTime)
                val head = ResponseReader.readHead(input)
                val location = head.headers["location"]?.lastOrNull()
                if (head.status in setOf(301, 302, 303, 307, 308) && location != null) {
                    conn.close()
                    if (hop == config.maxRedirects) throw IOException("The site redirected too many times.")
                    val next = url.resolve(location) ?: throw BlockedUrlException("The site redirected to an address web tools cannot open.")
                    if (head.status == 303 || ((head.status == 301 || head.status == 302) && method == "POST")) {
                        method = "GET"
                        body = null
                    }
                    url = upgrade(next)
                    continue
                }
                return HttpResponse(head.status, head.headers, url, ResponseReader.body(head, input, method), conn)
            } catch (t: Throwable) {
                runCatching { conn.close() }
                throw t
            }
        }
        throw IOException("The site redirected too many times.")
    }

    /** Reads up to [max] bytes of [response]'s body; the flag says whether more was left. */
    fun readBody(response: HttpResponse, max: Int): Pair<ByteArray, Boolean> {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16_384)
        var truncated = false
        while (true) {
            val room = max - out.size()
            if (room <= 0) {
                truncated = response.body.read() >= 0
                break
            }
            val n = response.body.read(buf, 0, minOf(buf.size, room))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray() to truncated
    }

    /**
     * GETs or POSTs [request] and reads the body (cut at [maxBytes]). Successful GETs are cached
     * for this session, so the same page is not downloaded twice during one task.
     */
    fun fetch(request: HttpRequest, maxBytes: Int = config.maxTextBytes): FetchedPage {
        val cacheable = request.method == "GET" && request.body == null
        val key = cacheKey(request)
        if (cacheable) cache.get(key)?.let { return it }
        open(request).use { r ->
            val (bytes, truncated) = readBody(r, maxBytes)
            val page = FetchedPage(r.url, r.status, r.contentType, bytes, truncated)
            if (cacheable && page.ok) cache.put(key, page)
            return page
        }
    }

    fun cacheKey(request: HttpRequest) = "${request.accept}|${request.desktop}|" + request.url.toString().substringBefore('#')
}

/** Small per-session cache of fetched pages: newest 48 pages, at most 24 MB, each kept 15 minutes. */
class PageCache(
    private val maxEntries: Int = 48,
    private val maxBytes: Long = 24L * 1024 * 1024,
    private val ttlMs: Long = 15 * 60 * 1000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val page: FetchedPage, val at: Long)

    private val map = LinkedHashMap<String, Entry>(64, 0.75f, true)
    private var bytes = 0L

    @Synchronized
    fun get(key: String): FetchedPage? {
        val e = map[key] ?: return null
        if (now() - e.at > ttlMs) {
            map.remove(key)
            bytes -= e.page.bytes.size
            return null
        }
        return e.page
    }

    @Synchronized
    fun put(key: String, page: FetchedPage) {
        if (page.bytes.size > maxBytes / 4) return
        map.remove(key)?.let { bytes -= it.page.bytes.size }
        map[key] = Entry(page, now())
        bytes += page.bytes.size
        val it = map.entries.iterator()
        while ((map.size > maxEntries || bytes > maxBytes) && it.hasNext()) {
            val e = it.next()
            bytes -= e.value.page.bytes.size
            it.remove()
        }
    }

    @Synchronized
    fun clear() {
        map.clear()
        bytes = 0
    }

    @get:Synchronized
    val size get() = map.size
}
