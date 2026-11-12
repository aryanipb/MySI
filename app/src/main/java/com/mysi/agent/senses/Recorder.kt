package com.mysi.agent.senses

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Records the microphone as 16 kHz mono 16-bit WAV, the format the model and YAMNet take.
 *
 * - [record] suspends until [stop] is called, [maxSeconds] pass, or the coroutine is cancelled.
 * - [stop] ends the recording early and keeps it; cancelling the coroutine deletes the file.
 * - The microphone is released on every path, including errors and cancellation.
 * - [onLevel] gets the loudness (0 to 1, RMS) about ten times a second, on the recording thread,
 *   for a level meter.
 *
 * The caller must hold RECORD_AUDIO (ask through Agent.askFor) and, from Android 14, should only
 * record while MySI is on screen: without a microphone foreground service Android delivers
 * silence once the app goes to the background.
 */
class Recorder(private val maxSeconds: Int = MAX_SECONDS) {
    @Volatile private var stopRequested = false

    data class Result(val file: File, val seconds: Double, val stoppedEarly: Boolean)

    /** Ends the recording in progress; [record] returns what was recorded so far. */
    fun stop() {
        stopRequested = true
    }

    @SuppressLint("MissingPermission")
    suspend fun record(ctx: Context, file: File, onLevel: (Float) -> Unit = {}): Result =
        withContext(Dispatchers.IO) {
            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw SecurityException("MySI does not have microphone access.")
            stopRequested = false
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) throw IOException("This phone cannot record 16 kHz audio.")
            val chunk = RATE / 10 // 100 ms
            val rec = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minBuf, chunk * 2 * 4))
                .build()
            file.parentFile?.mkdirs()
            var kept = false
            try {
                if (rec.state != AudioRecord.STATE_INITIALIZED) throw IOException("The microphone is not available. Another app may be using it.")
                RandomAccessFile(file, "rw").use { out ->
                    out.setLength(0)
                    out.write(Wav.header(RATE, 1, 16, 0))
                    rec.startRecording()
                    if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("The microphone is not available. Another app may be using it.")
                    val buf = ShortArray(chunk)
                    val bytes = java.io.ByteArrayOutputStream(chunk * 2)
                    var total = 0L
                    val limit = maxSeconds.toLong() * RATE
                    while (total < limit && !stopRequested) {
                        ensureActive()
                        val n = rec.read(buf, 0, minOf(chunk.toLong(), limit - total).toInt())
                        if (n < 0) throw IOException("The microphone stopped (error $n).")
                        if (n == 0) continue
                        bytes.reset()
                        Wav.writeSamples(bytes, buf, 0, n)
                        out.write(bytes.toByteArray())
                        total += n
                        onLevel(level(buf, n))
                    }
                    out.seek(0)
                    out.write(Wav.header(RATE, 1, 16, total * 2))
                    kept = true
                    Result(file, total.toDouble() / RATE, stopRequested)
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
                if (!kept) file.delete()
            }
        }

    companion object {
        const val RATE = 16_000

        /** The longest recording MySI makes in one go. */
        const val MAX_SECONDS = 10 * 60

        /** Root mean square of [n] samples, 0 to 1. */
        fun level(s: ShortArray, n: Int): Float {
            if (n <= 0) return 0f
            var sum = 0.0
            for (i in 0 until n) sum += s[i].toDouble() * s[i]
            return (sqrt(sum / n) / 32768.0).toFloat().coerceIn(0f, 1f)
        }
    }
}
