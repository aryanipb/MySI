@file:Suppress("ExifInterface") // Platform ExifInterface: see uprightBitmap.

package com.mysi.agent.senses

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Turns the user's pictures and recordings into what the model and the detectors take. Every
 * picture is re-encoded (the photo picker hands over HEIC, WebP and PNG; the runtime's own
 * decoder reads JPEG and PNG), turned upright from its EXIF orientation and shrunk. Audio is
 * decoded (WAV directly; MP3, M4A/AAC, OGG/Opus, FLAC and AMR through Android's codecs), mixed to
 * mono and resampled to 16 kHz.
 */
object Media {
    /** Speech models and YAMNet both take 16 kHz mono. */
    const val SAMPLE_RATE = 16_000

    /** The longest audio MySI decodes at once (about 38 MB of samples). */
    const val MAX_AUDIO_SECONDS = 20 * 60.0

    private const val JPEG_QUALITY = 90

    // ---------------- pictures ----------------

    /**
     * [file] decoded upright with its longest side at most [maxSide]. Throws IOException with a
     * plain message. The platform ExifInterface is used: the bugs AndroidX's copy works around
     * were fixed long before MySI's minimum, Android 14.
     */
    @android.annotation.SuppressLint("ExifInterface")
    fun uprightBitmap(file: File, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("${file.name} is not a picture Android can open.")
        val orientation = Orientation.fromExif(
            runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        )
        val opts = BitmapFactory.Options().apply {
            inSampleSize = ImageMath.sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeFile(file.path, opts) ?: throw IOException("Could not decode ${file.name}.")
        val target = ImageMath.fit(raw.width, raw.height, maxSide)
        val m = Matrix().apply {
            postScale(target.width.toFloat() / raw.width, target.height.toFloat() / raw.height)
            if (orientation.mirror) postScale(-1f, 1f)
            if (orientation.degrees != 0) postRotate(orientation.degrees.toFloat())
        }
        if (m.isIdentity) return raw
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (out !== raw) raw.recycle()
        return out
    }

    fun jpeg(bitmap: Bitmap, quality: Int = JPEG_QUALITY): ByteArray =
        ByteArrayOutputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw IOException("Could not encode the picture.")
            out.toByteArray()
        }

    /** A picture as the model takes it: upright JPEG, longest side at most [ImageMath.MODEL_MAX_SIDE]. */
    fun modelJpeg(file: File): ByteArray =
        cached("img", file) {
            val b = uprightBitmap(file, ImageMath.MODEL_MAX_SIDE)
            try {
                jpeg(b)
            } finally {
                b.recycle()
            }
        }

    // ---------------- audio ----------------

    class Decoded(val pcm: Pcm, val truncated: Boolean)

    /** [file] as 16 kHz mono, at most [maxSeconds] of it. Throws IOException with a plain message. */
    fun audio(file: File, maxSeconds: Double = MAX_AUDIO_SECONDS): Decoded {
        if (!file.isFile) throw IOException("No audio file at ${file.name}.")
        val raw = if (isWav(file)) file.inputStream().use { Wav.read(it, maxSeconds) }.let { Decoded(it.pcm, it.truncated) } else decodeWithCodec(file, maxSeconds)
        return Decoded(Resampler.to(raw.pcm, SAMPLE_RATE), raw.truncated)
    }

    /** The first [seconds] of [file] as a 16 kHz mono 16-bit WAV, the form the model takes. */
    fun modelWav(file: File, seconds: Double): ByteArray = cached("wav$seconds", file) { audio(file, seconds).pcm.toWav() }

    /** Length in seconds, or null when Android cannot tell. */
    fun audioSeconds(file: File): Double? =
        runCatching {
            if (isWav(file)) return@runCatching file.inputStream().use { Wav.seconds(it) }
            MediaExtractor().run {
                try {
                    setDataSource(file.path)
                    (0 until trackCount).map { getTrackFormat(it) }.firstOrNull { it.mime().startsWith("audio/") }
                        ?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }?.getLong(MediaFormat.KEY_DURATION)?.div(1e6)
                } finally {
                    release()
                }
            }
        }.getOrNull()

    private fun isWav(file: File): Boolean =
        file.inputStream().use { i ->
            val h = ByteArray(12)
            i.read(h) == 12 && String(h, 0, 4, Charsets.US_ASCII).let { it == "RIFF" || it == "RF64" } && String(h, 8, 4, Charsets.US_ASCII) == "WAVE"
        }

    private fun MediaFormat.mime() = getString(MediaFormat.KEY_MIME).orEmpty()

    /** Decodes any audio Android has a codec for into mono 16-bit samples at the file's own rate. */
    private fun decodeWithCodec(file: File, maxSeconds: Double): Decoded {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                ex.setDataSource(file.path)
            } catch (e: IOException) {
                throw IOException("${file.name} is not an audio file Android can open.")
            }
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).mime().startsWith("audio/") }
                ?: throw IOException("${file.name} has no audio track.")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = android.media.AudioFormat.ENCODING_PCM_16BIT
            val c = try {
                MediaCodec.createDecoderByType(format.mime())
            } catch (e: Exception) {
                throw IOException("This phone cannot decode ${format.mime()} audio. Use WAV, MP3 or M4A.")
            }
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            val out = ShortGrowable(rate * 60)
            var maxFrames = (maxSeconds * rate).toLong()
            var frames = 0L
            var inputDone = false
            var truncated = false
            while (true) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        maxFrames = (maxSeconds * rate).toLong()
                    }
                    o >= 0 -> {
                        val buf = c.getOutputBuffer(o)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        while (buf.remaining() >= channels * bytesPer(encoding)) {
                            if (frames >= maxFrames) {
                                truncated = true
                                break
                            }
                            var sum = 0.0
                            repeat(channels) {
                                sum += when (encoding) {
                                    android.media.AudioFormat.ENCODING_PCM_FLOAT -> buf.float.toDouble().coerceIn(-1.0, 1.0) * 32767.0
                                    android.media.AudioFormat.ENCODING_PCM_8BIT -> ((buf.get().toInt() and 0xFF) - 128) * 256.0
                                    else -> buf.short.toDouble()
                                }
                            }
                            out.add((sum / channels).toInt().coerceIn(-32768, 32767).toShort())
                            frames++
                        }
                        c.releaseOutputBuffer(o, false)
                        if (truncated || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            return Decoded(Pcm(out.toArray(), rate), truncated)
        } catch (e: IllegalStateException) {
            throw IOException("Could not decode ${file.name}: ${e.message ?: "codec error"}.")
        } catch (e: IllegalArgumentException) {
            throw IOException("Could not decode ${file.name}: ${e.message ?: "unsupported format"}.")
        } finally {
            codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
            ex.release()
        }
    }

    private fun bytesPer(encoding: Int) =
        when (encoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> 4
            android.media.AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> 2
        }

    // ---------------- cache ----------------

    /** Rebuilding a conversation replays the open turn's media, so prepared bytes are kept for a few files. */
    private val cache = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 6
    }

    private fun cached(kind: String, file: File, make: () -> ByteArray): ByteArray {
        val key = "$kind|${file.absolutePath}|${file.lastModified()}|${file.length()}"
        synchronized(cache) { cache[key]?.let { return it } }
        val bytes = make()
        synchronized(cache) { cache[key] = bytes }
        return bytes
    }

    fun clearCache() = synchronized(cache) { cache.clear() }
}
