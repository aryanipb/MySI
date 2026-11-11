package com.mysi.agent.skills.core

import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Thrown when a file is larger, deeper or more repetitive than MySI is willing to unpack. */
class LimitException(message: String) : IOException(message)

/**
 * Caps for reading zip-based Office files. The defaults comfortably fit real documents and
 * spreadsheets while stopping zip bombs: bytes are counted as they are inflated, so a lying
 * size in the zip header does not help an attacker.
 */
data class ZipLimits(
    val maxFileBytes: Long = 150L * 1024 * 1024,
    val maxEntries: Int = 10_000,
    val maxEntryBytes: Long = 100L * 1024 * 1024,
    val maxTotalBytes: Long = 300L * 1024 * 1024,
    /** Inflated-to-compressed ratio allowed once an entry passes [ratioCheckAfter] bytes. */
    val maxRatio: Long = 250,
    val ratioCheckAfter: Long = 1L * 1024 * 1024,
)

/**
 * Read-only access to a zip file by part name. Part names are matched case-insensitively and
 * without a leading slash, as Office's packaging rules require.
 */
class SafeZip(file: File, private val limits: ZipLimits = ZipLimits()) : Closeable {
    private val zip: ZipFile
    private val entries: Map<String, ZipEntry>
    private var inflatedTotal = 0L

    init {
        if (file.length() > limits.maxFileBytes)
            throw LimitException("File is over ${limits.maxFileBytes / (1024 * 1024)} MB.")
        zip = try {
            ZipFile(file)
        } catch (e: ZipException) {
            throw IOException("Not a valid Office file (the zip container is damaged).")
        }
        try {
            if (zip.size() > limits.maxEntries)
                throw LimitException("The file has more than ${limits.maxEntries} parts, so MySI will not open it.")
            val map = HashMap<String, ZipEntry>()
            val e = zip.entries()
            var count = 0
            while (e.hasMoreElements()) {
                if (++count > limits.maxEntries)
                    throw LimitException("The file has more than ${limits.maxEntries} parts, so MySI will not open it.")
                val entry = e.nextElement()
                if (!entry.isDirectory) map.putIfAbsent(key(entry.name), entry)
            }
            entries = map
        } catch (t: Throwable) {
            zip.close()
            throw t
        }
    }

    val names: Collection<String> get() = entries.values.map { it.name }

    fun has(name: String) = entries.containsKey(key(name))

    /** Opens a part with its inflated size counted against the per-entry and total caps. */
    fun open(name: String): InputStream? {
        val entry = entries[key(name)] ?: return null
        return Bounded(zip.getInputStream(entry), entry.compressedSize.coerceAtLeast(1))
    }

    fun readText(name: String): String? = open(name)?.use { String(it.readBytes(), Charsets.UTF_8) }

    override fun close() = zip.close()

    private inner class Bounded(input: InputStream, private val compressed: Long) : FilterInputStream(input) {
        private var count = 0L

        private fun add(n: Long) {
            if (n <= 0) return
            count += n
            inflatedTotal += n
            if (count > limits.maxEntryBytes)
                throw LimitException("A part of this file inflates past ${limits.maxEntryBytes / (1024 * 1024)} MB, so MySI stopped reading it.")
            if (inflatedTotal > limits.maxTotalBytes)
                throw LimitException("This file inflates past ${limits.maxTotalBytes / (1024 * 1024)} MB, so MySI stopped reading it.")
            if (count > limits.ratioCheckAfter && count / compressed > limits.maxRatio)
                throw LimitException("This file looks like a zip bomb (it inflates far more than any real document), so MySI stopped reading it.")
        }

        override fun read(): Int {
            val c = super.read()
            if (c >= 0) add(1)
            return c
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            add(n.toLong())
            return n
        }

        override fun skip(n: Long): Long {
            val s = super.skip(n)
            add(s)
            return s
        }
    }

    companion object {
        internal fun key(name: String) = name.trimStart('/').replace('\\', '/').lowercase()

        /** True when [file] starts with the zip signature. */
        fun isZip(file: File): Boolean =
            runCatching {
                file.inputStream().use { val h = ByteArray(4); it.read(h) == 4 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() && h[2].toInt() == 3 && h[3].toInt() == 4 }
            }.getOrDefault(false)
    }
}
