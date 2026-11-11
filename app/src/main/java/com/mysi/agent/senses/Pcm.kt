package com.mysi.agent.senses

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Mono 16-bit audio. Pure Kotlin, so it can be tested on the JVM. */
class Pcm(val samples: ShortArray, val sampleRate: Int) {
    init {
        require(sampleRate > 0) { "Sample rate must be positive." }
    }

    val seconds: Double get() = samples.size.toDouble() / sampleRate

    fun slice(range: IntRange) = Pcm(samples.copyOfRange(range.first, range.last + 1), sampleRate)

    /** A complete WAV file (RIFF header plus little-endian samples). */
    fun toWav(): ByteArray {
        val out = ByteArrayOutputStream(Wav.HEADER_BYTES + samples.size * 2)
        out.write(Wav.header(sampleRate, 1, 16, samples.size.toLong() * 2))
        Wav.writeSamples(out, samples, 0, samples.size)
        return out.toByteArray()
    }
}

class WavException(message: String) : IOException(message)

/** The format of a WAV file's sample data. [encoding] is 1 for integer PCM, 3 for IEEE float. */
data class WavFormat(val encoding: Int, val channels: Int, val sampleRate: Int, val bitsPerSample: Int) {
    val bytesPerFrame get() = channels * (bitsPerSample / 8)
}

/**
 * Reading and writing WAV. The reader walks the RIFF chunks instead of assuming a 44-byte header,
 * so files with LIST/fact chunks, WAVE_FORMAT_EXTENSIBLE headers or unknown chunk sizes (written
 * by streaming recorders) still work. It accepts 8/16/24/32-bit integer and 32-bit float PCM, any
 * channel count, and mixes the channels down to mono.
 */
object Wav {
    const val HEADER_BYTES = 44
    private const val PCM = 1
    private const val FLOAT = 3
    private const val EXTENSIBLE = 0xFFFE

    /** A canonical 44-byte header for [dataBytes] of integer PCM. */
    fun header(sampleRate: Int, channels: Int, bitsPerSample: Int, dataBytes: Long): ByteArray {
        require(dataBytes in 0..0xFFFF_FFDBL) { "WAV data too large." }
        val b = ByteArray(HEADER_BYTES)
        fun ascii(at: Int, s: String) = s.forEachIndexed { i, c -> b[at + i] = c.code.toByte() }
        fun int32(at: Int, v: Long) = (0 until 4).forEach { b[at + it] = (v shr (8 * it)).toByte() }
        fun int16(at: Int, v: Int) = (0 until 2).forEach { b[at + it] = (v shr (8 * it)).toByte() }
        ascii(0, "RIFF")
        int32(4, 36 + dataBytes)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        int32(16, 16)
        int16(20, PCM)
        int16(22, channels)
        int32(24, sampleRate.toLong())
        int32(28, sampleRate.toLong() * channels * bitsPerSample / 8)
        int16(32, channels * bitsPerSample / 8)
        int16(34, bitsPerSample)
        ascii(36, "data")
        int32(40, dataBytes)
        return b
    }

    /** Writes [count] samples from [offset] as little-endian 16-bit. */
    fun writeSamples(out: OutputStream, samples: ShortArray, offset: Int, count: Int) {
        val buf = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = samples[offset + i].toInt()
            buf[2 * i] = s.toByte()
            buf[2 * i + 1] = (s shr 8).toByte()
        }
        out.write(buf)
    }

    /**
     * Reads a WAV stream into mono 16-bit samples at its own sample rate, keeping at most
     * [maxSeconds] (the rest is not read). [truncated] in the result says whether audio was left.
     */
    fun read(input: InputStream, maxSeconds: Double = Double.MAX_VALUE): Decoded {
        val d = DataInputStream(input.buffered())
        val (f, size) = toData(d)
        return readFrames(d, f, size, maxSeconds)
    }

    /** Length in seconds from the header alone; null when the header does not give the data size. */
    fun seconds(input: InputStream): Double? {
        val (f, size) = toData(DataInputStream(input.buffered()))
        return if (size == Long.MAX_VALUE) null else (size / f.bytesPerFrame).toDouble() / f.sampleRate
    }

    /** Reads up to the start of the samples: their format and byte count (Long.MAX_VALUE when unknown). */
    private fun toData(d: DataInputStream): Pair<WavFormat, Long> {
        val riff = d.ascii(4)
        if (riff != "RIFF" && riff != "RF64") throw WavException("Not a WAV file.")
        d.int32le()
        if (d.ascii(4) != "WAVE") throw WavException("Not a WAV file.")
        var format: WavFormat? = null
        while (true) {
            val id = try {
                d.ascii(4)
            } catch (_: EOFException) {
                throw WavException("The WAV file has no audio data.")
            }
            val size = d.int32le().toLong() and 0xFFFF_FFFFL
            when (id) {
                "fmt " -> format = readFormat(d, size)
                "data" -> {
                    val f = format ?: throw WavException("The WAV file has no format chunk before its audio.")
                    // 0 and 0xFFFFFFFF mean "until the end" in files from streaming writers.
                    val known = size != 0L && size != 0xFFFF_FFFFL
                    return f to if (known) size else Long.MAX_VALUE
                }
                else -> d.skipFully(size + (size and 1))
            }
        }
    }

    class Decoded(val pcm: Pcm, val truncated: Boolean, val format: WavFormat)

    private fun readFormat(d: DataInputStream, size: Long): WavFormat {
        if (size < 16) throw WavException("Broken WAV format chunk.")
        var encoding = d.int16le()
        val channels = d.int16le()
        val rate = d.int32le()
        d.int32le() // byte rate
        d.int16le() // block align
        val bits = d.int16le()
        var read = 16L
        if (encoding == EXTENSIBLE && size >= 40) {
            d.int16le() // extension size
            d.int16le() // valid bits
            d.int32le() // channel mask
            encoding = d.int16le() // first two bytes of the sub-format GUID are the format code
            read += 10
        }
        d.skipFully(size - read + (size and 1))
        if (channels < 1 || rate <= 0) throw WavException("Broken WAV header.")
        val ok = (encoding == PCM && bits in listOf(8, 16, 24, 32)) || (encoding == FLOAT && bits == 32)
        if (!ok) throw WavException("Unsupported WAV encoding ($encoding, $bits-bit). Use 16-bit PCM WAV, MP3, M4A or OGG.")
        return WavFormat(encoding, channels, rate, bits)
    }

    private fun readFrames(d: DataInputStream, f: WavFormat, dataBytes: Long, maxSeconds: Double): Decoded {
        val frameBytes = f.bytesPerFrame
        val maxFrames = if (maxSeconds == Double.MAX_VALUE) Long.MAX_VALUE else (maxSeconds * f.sampleRate).toLong()
        val wanted = min(dataBytes / frameBytes, maxFrames)
        val out = ShortGrowable(min(wanted, f.sampleRate * 60L).toInt())
        val frame = ByteArray(frameBytes * 1024)
        var frames = 0L
        var eof = false
        while (frames < wanted) {
            val want = min(1024L, wanted - frames).toInt() * frameBytes
            val n = d.readUpTo(frame, want)
            val whole = n / frameBytes
            for (i in 0 until whole) out.add(mix(frame, i * frameBytes, f))
            frames += whole
            if (n < want) {
                eof = true
                break
            }
        }
        val more = !eof && frames >= maxFrames && frames < dataBytes / frameBytes && d.read() >= 0
        return Decoded(Pcm(out.toArray(), f.sampleRate), more, f)
    }

    /** One frame mixed to mono 16-bit. */
    private fun mix(b: ByteArray, at: Int, f: WavFormat): Short {
        val width = f.bitsPerSample / 8
        var sum = 0.0
        for (c in 0 until f.channels) {
            val p = at + c * width
            sum += when {
                f.encoding == FLOAT -> Float.fromBits(le(b, p, 4)).toDouble().coerceIn(-1.0, 1.0) * 32767.0
                width == 1 -> ((b[p].toInt() and 0xFF) - 128) * 256.0
                width == 2 -> (le(b, p, 2).toShort()).toDouble()
                width == 3 -> ((le(b, p, 3) shl 8) shr 8) / 256.0
                else -> le(b, p, 4) / 65536.0
            }
        }
        return (sum / f.channels).roundToInt().coerceIn(-32768, 32767).toShort()
    }

    private fun le(b: ByteArray, at: Int, n: Int): Int {
        var v = 0
        for (i in 0 until n) v = v or ((b[at + i].toInt() and 0xFF) shl (8 * i))
        return v
    }

    private fun DataInputStream.ascii(n: Int): String = String(ByteArray(n).also { readFully(it) }, Charsets.US_ASCII)

    private fun DataInputStream.int32le(): Int = ByteArray(4).also { readFully(it) }.let { le(it, 0, 4) }

    private fun DataInputStream.int16le(): Int = ByteArray(2).also { readFully(it) }.let { le(it, 0, 2) }

    private fun DataInputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val s = skip(left)
            if (s <= 0) {
                if (read() < 0) throw WavException("The WAV file is cut short.")
                left--
            } else left -= s
        }
    }

    private fun DataInputStream.readUpTo(buf: ByteArray, n: Int): Int {
        var got = 0
        while (got < n) {
            val r = read(buf, got, n - got)
            if (r < 0) break
            got += r
        }
        return got
    }
}

/** A growable ShortArray, so decoding never boxes samples. */
internal class ShortGrowable(initial: Int = 16_000) {
    private var a = ShortArray(max(16, initial))
    var size = 0
        private set

    fun add(s: Short) {
        if (size == a.size) a = a.copyOf(if (a.size < (1 shl 29)) a.size * 2 else Int.MAX_VALUE - 8)
        a[size++] = s
    }

    fun addAll(src: ShortArray, count: Int = src.size) {
        for (i in 0 until count) add(src[i])
    }

    fun toArray(): ShortArray = a.copyOf(size)
}

/** Sample-rate conversion for speech input. */
object Resampler {
    /**
     * Converts [pcm] to [rate]. Downsampling averages the source samples each output sample
     * covers (a box filter, which removes most of what would otherwise alias into the speech
     * band); upsampling interpolates linearly.
     */
    fun to(pcm: Pcm, rate: Int): Pcm {
        require(rate > 0)
        val src = pcm.samples
        if (pcm.sampleRate == rate || src.isEmpty()) return Pcm(src, rate)
        val ratio = pcm.sampleRate.toDouble() / rate
        val n = max(1, floor(src.size / ratio).toInt())
        val out = ShortArray(n)
        if (ratio > 1) {
            for (i in 0 until n) {
                val start = i * ratio
                val end = min(src.size.toDouble(), start + ratio)
                var sum = 0.0
                var weight = 0.0
                var k = floor(start).toInt()
                while (k < end && k < src.size) {
                    val w = min(k + 1.0, end) - max(k.toDouble(), start)
                    sum += src[k] * w
                    weight += w
                    k++
                }
                out[i] = if (weight > 0) (sum / weight).roundToInt().coerceIn(-32768, 32767).toShort() else 0
            }
        } else {
            for (i in 0 until n) {
                val x = i * ratio
                val k = floor(x).toInt()
                val t = x - k
                val a = src[min(k, src.size - 1)].toDouble()
                val b = src[min(k + 1, src.size - 1)].toDouble()
                out[i] = (a + (b - a) * t).roundToInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return Pcm(out, rate)
    }
}

/** Splits long audio into pieces the model can take one at a time. */
object AudioChunks {
    /**
     * Ranges of at most [maxSeconds] each, covering every sample once, in order. Each cut (except
     * the end) is placed at the quietest [windowMs] stretch within the last [searchSeconds] of the
     * piece, so it falls in a pause rather than mid-word whenever the audio has one.
     */
    fun split(pcm: Pcm, maxSeconds: Double, searchSeconds: Double = 3.0, windowMs: Int = 50): List<IntRange> {
        require(maxSeconds > 0)
        val total = pcm.samples.size
        if (total == 0) return emptyList()
        val maxLen = max(1, (maxSeconds * pcm.sampleRate).toInt())
        val window = max(1, pcm.sampleRate * windowMs / 1000)
        val search = min(maxLen - 1, (searchSeconds * pcm.sampleRate).toInt())
        val out = mutableListOf<IntRange>()
        var start = 0
        while (start < total) {
            if (total - start <= maxLen) {
                out += start until total
                break
            }
            val hardEnd = start + maxLen
            var cut = hardEnd
            if (search >= window) {
                var best = Long.MAX_VALUE
                var w = hardEnd - window
                val lowest = hardEnd - search
                // Step by half a window: fine enough to land in a pause, cheap on long audio.
                while (w >= lowest) {
                    val e = energy(pcm.samples, w, window)
                    if (e < best) {
                        best = e
                        cut = w + window / 2
                    }
                    w -= max(1, window / 2)
                }
            }
            out += start until cut
            start = cut
        }
        return out
    }

    private fun energy(s: ShortArray, from: Int, n: Int): Long {
        var e = 0L
        for (i in from until from + n) e += abs(s[i].toInt())
        return e
    }
}
