package com.mysi.agent.senses

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WavTest {
    private fun le32(b: ByteArray, at: Int) = (0 until 4).sumOf { (b[at + it].toInt() and 0xFF) shl (8 * it) }

    private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun ascii(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)

    /** A RIFF file built chunk by chunk, for headers the writer never produces. */
    private fun riff(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WAVE".toByteArray())
        for ((id, data) in chunks) {
            body.write(id.toByteArray())
            body.write(int32(data.size.toLong()))
            body.write(data)
            if (data.size % 2 == 1) body.write(0)
        }
        return "RIFF".toByteArray() + int32(body.size().toLong()) + body.toByteArray()
    }

    private fun int32(v: Long) = ByteArray(4) { (v shr (8 * it)).toByte() }

    private fun int16(v: Int) = ByteArray(2) { (v shr (8 * it)).toByte() }

    private fun fmt(encoding: Int, channels: Int, rate: Int, bits: Int) =
        int16(encoding) + int16(channels) + int32(rate.toLong()) + int32(rate.toLong() * channels * bits / 8) + int16(channels * bits / 8) + int16(bits)

    private fun read(bytes: ByteArray, max: Double = Double.MAX_VALUE) = Wav.read(ByteArrayInputStream(bytes), max)

    @Test
    fun headerHasEveryFieldOfA16BitMonoFile() {
        val h = Wav.header(16_000, 1, 16, 32_000)
        assertEquals(44, h.size)
        assertEquals("RIFF", ascii(h, 0))
        assertEquals(36 + 32_000, le32(h, 4))
        assertEquals("WAVE", ascii(h, 8))
        assertEquals("fmt ", ascii(h, 12))
        assertEquals(16, le32(h, 16))
        assertEquals(1, le16(h, 20))
        assertEquals(1, le16(h, 22))
        assertEquals(16_000, le32(h, 24))
        assertEquals(32_000, le32(h, 28))
        assertEquals(2, le16(h, 32))
        assertEquals(16, le16(h, 34))
        assertEquals("data", ascii(h, 36))
        assertEquals(32_000, le32(h, 40))
    }

    @Test
    fun writeThenReadGivesTheSameSamples() {
        val s = ShortArray(1000) { ((it * 37) % 65536 - 32768).toShort() }
        val wav = Pcm(s, 16_000).toWav()
        assertEquals(44 + 2000, wav.size)
        val d = read(wav)
        assertArrayEquals(s, d.pcm.samples)
        assertEquals(16_000, d.pcm.sampleRate)
        assertFalse(d.truncated)
        assertEquals(1000 / 16_000.0, Wav.seconds(ByteArrayInputStream(wav))!!, 1e-9)
    }

    @Test
    fun stereoIsMixedToMono() {
        // Left 1000, right 3000 -> 2000; left -4000, right 0 -> -2000.
        val data = int16(1000) + int16(3000) + int16(-4000) + int16(0)
        val d = read(riff("fmt " to fmt(1, 2, 44_100, 16), "data" to data))
        assertArrayEquals(shortArrayOf(2000, -2000), d.pcm.samples)
        assertEquals(44_100, d.pcm.sampleRate)
    }

    @Test
    fun eightTwentyFourAndFloatSamplesAreScaledTo16Bit() {
        val eight = read(riff("fmt " to fmt(1, 1, 8000, 8), "data" to byteArrayOf(128.toByte(), 255.toByte(), 0)))
        assertArrayEquals(shortArrayOf(0, (127 * 256).toShort(), (-128 * 256).toShort()), eight.pcm.samples)
        // 24-bit 0x123456 -> 0x1234, and -1 (0xFFFFFF) -> -1/256 rounds to 0.
        val twentyFour = read(riff("fmt " to fmt(1, 1, 8000, 24), "data" to byteArrayOf(0x56, 0x34, 0x12, -1, -1, -1)))
        assertEquals(0x1234, twentyFour.pcm.samples[0].toInt())
        assertEquals(0, twentyFour.pcm.samples[1].toInt())
        val floats = int32(0.5f.toBits().toLong()) + int32((-1f).toBits().toLong())
        val f = read(riff("fmt " to fmt(3, 1, 8000, 32), "data" to floats))
        assertEquals(16384.0, f.pcm.samples[0].toDouble(), 1.0)
        assertEquals(-32767, f.pcm.samples[1].toInt())
    }

    @Test
    fun extraChunksOddSizesAndExtensibleHeadersAreSkipped() {
        val ext = fmt(0xFFFE, 1, 16_000, 16) + int16(22) + int16(16) + int32(4) + int16(1) + ByteArray(14)
        val bytes = riff("LIST" to "INFOabc".toByteArray(), "fmt " to ext, "fact" to int32(2), "data" to int16(7) + int16(-7))
        val d = read(bytes)
        assertArrayEquals(shortArrayOf(7, -7), d.pcm.samples)
    }

    @Test
    fun streamingWriterSizesReadToTheEnd() {
        val good = Pcm(ShortArray(300) { it.toShort() }, 16_000).toWav()
        // Writers that never patch their header leave the data size as 0xFFFFFFFF.
        for (i in 40 until 44) good[i] = -1
        val d = read(good)
        assertEquals(300, d.pcm.samples.size)
        assertNull(Wav.seconds(ByteArrayInputStream(good)))
    }

    @Test
    fun maxSecondsStopsEarlyAndSaysSo() {
        val wav = Pcm(ShortArray(16_000 * 3), 16_000).toWav()
        val d = read(wav, 1.0)
        assertEquals(16_000, d.pcm.samples.size)
        assertTrue(d.truncated)
        assertFalse(read(wav, 3.0).truncated)
        assertFalse(read(wav, 10.0).truncated)
    }

    @Test
    fun badFilesFailWithPlainMessages() {
        fun message(b: ByteArray) = try {
            read(b)
            fail("expected an error")
            ""
        } catch (e: WavException) {
            e.message!!
        }
        assertEquals("Not a WAV file.", message("ID3 not a wave file".toByteArray()))
        assertTrue(message(riff("fmt " to fmt(2, 1, 8000, 4), "data" to ByteArray(4))).startsWith("Unsupported WAV encoding"))
        assertEquals("The WAV file has no audio data.", message(riff("fmt " to fmt(1, 1, 8000, 16))))
        assertTrue(message(riff("data" to ByteArray(4))).contains("no format chunk"))
    }
}

class ResamplerTest {
    private fun sine(rate: Int, hz: Double, seconds: Double) = Pcm(ShortArray((rate * seconds).toInt()) { (10_000 * sin(2 * PI * hz * it / rate)).roundToInt().toShort() }, rate)

    private fun crossings(s: ShortArray) = (1 until s.size).count { (s[it - 1] < 0) != (s[it] < 0) }

    @Test
    fun sameRateIsUnchanged() {
        val p = sine(16_000, 440.0, 0.1)
        assertArrayEquals(p.samples, Resampler.to(p, 16_000).samples)
    }

    @Test
    fun downsamplingKeepsDurationLevelAndPitch() {
        val p = sine(48_000, 300.0, 1.0)
        val r = Resampler.to(p, 16_000)
        assertEquals(16_000, r.sampleRate)
        assertEquals(16_000, r.samples.size)
        // 300 Hz has 600 zero crossings a second at any rate.
        assertEquals(600.0, crossings(r.samples).toDouble(), 4.0)
        assertTrue(r.samples.maxOf { it.toInt() } in 9_500..10_000)
        val dc = Resampler.to(Pcm(ShortArray(44_100) { 1234 }, 44_100), 16_000)
        assertTrue(dc.samples.all { it.toInt() == 1234 })
        assertEquals(16_000, dc.samples.size)
    }

    @Test
    fun upsamplingInterpolates() {
        val r = Resampler.to(Pcm(shortArrayOf(0, 100, 200, 300), 8_000), 16_000)
        assertArrayEquals(shortArrayOf(0, 50, 100, 150, 200, 250, 300, 300), r.samples)
    }
}

class AudioChunksTest {
    @Test
    fun shortAudioIsOnePieceAndEmptyIsNone() {
        assertEquals(listOf(0 until 16_000), AudioChunks.split(Pcm(ShortArray(16_000), 16_000), 30.0))
        assertEquals(emptyList<IntRange>(), AudioChunks.split(Pcm(ShortArray(0), 16_000), 30.0))
    }

    @Test
    fun piecesCoverEverySampleOnceInOrderAndFitTheLimit() {
        val rate = 16_000
        val pcm = Pcm(ShortArray(rate * 95) { ((it % 200) - 100).toShort() }, rate)
        val pieces = AudioChunks.split(pcm, 30.0)
        assertEquals(4, pieces.size)
        assertEquals(0, pieces.first().first)
        assertEquals(pcm.samples.size - 1, pieces.last().last)
        pieces.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        assertTrue(pieces.all { it.count() <= 30 * rate })
    }

    @Test
    fun cutsLandInAPause() {
        val rate = 16_000
        // Loud audio for 40 s with silence from 28.0 s to 28.4 s.
        val s = ShortArray(rate * 40) { i -> if (i in (28.0 * rate).toInt() until (28.4 * rate).toInt()) 0 else (if (i % 2 == 0) 8000 else -8000).toShort() }
        val pieces = AudioChunks.split(Pcm(s, rate), 30.0)
        assertEquals(2, pieces.size)
        val cut = pieces[1].first / rate.toDouble()
        assertTrue("cut at $cut s", cut in 28.0..28.4)
    }
}
