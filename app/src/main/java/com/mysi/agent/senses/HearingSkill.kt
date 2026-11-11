package com.mysi.agent.senses

import android.Manifest
import android.content.Context
import com.mysi.agent.Agent
import com.mysi.agent.Param
import com.mysi.agent.SenseUnavailable
import com.mysi.agent.Skill
import com.mysi.agent.Storage
import com.mysi.agent.ToolDef
import com.mysi.agent.skills.SkillFiles
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hearing, all on the phone: recording the microphone with the user's consent, understanding
 * speech with the on-device model, and naming sounds with the bundled YAMNet classifier.
 */
object HearingSkill {
    /** Longest recording the model may ask for in one go. */
    const val MAX_RECORD_SECONDS = 300

    /** Longest stretch identify_sounds analyses. */
    private const val SOUNDS_MAX_SECONDS = 10 * 60.0

    const val NO_MIC =
        "MySI does not have microphone access. Ask the user to allow it when MySI asks, or in Settings › Apps › MySI Agent › Permissions."

    val skill = Skill(
        name = "hearing",
        description = "Record a voice note from the microphone when the user agrees, transcribe speech, answer questions about recordings and audio files, and name the sounds in them.",
        keywords = listOf(
            "audio", "recording", "record", "voice note", "voice memo", "transcribe", "transcript", "transcription", "listen",
            "hear", "heard", "sound", "noise", "speech", "dictate", "dictation", "microphone", "mic", "what was said",
            "meeting recording", "lecture", "podcast", "wav", "mp3", "m4a",
        ),
        instructions = """
            Hearing: record_audio records the microphone only after the user agrees; then use the path it returns. For a recording the user has, call pick_files with kind audio first.
            transcribe_audio writes down what is said; listen_to_audio answers a question about a recording, such as summarise it or list the action points; identify_sounds names kinds of sound (music, a dog, a siren). Recordings are private: never send them anywhere unless the user asks.
        """.trimIndent(),
        tools = ::tools,
    )

    private fun tools(ctx: Context): List<ToolDef> = listOf(
        ToolDef(
            "record_audio",
            "Record sound from the phone's microphone for a number of seconds, after the user agrees, and save it as a WAV file. Returns its path.",
            listOf(Param("seconds", "integer", "How long to record, 1 to $MAX_RECORD_SECONDS. Default 30", false)),
            confirm = { a -> "Record ${seconds(a.int("seconds", 30))} seconds from the microphone? The recording stays on this phone." },
            privateData = true,
        ) { a -> record(ctx, seconds(a.int("seconds", 30))) },
        ToolDef(
            "transcribe_audio",
            "Write down what is said in a recording or audio file (WAV, MP3, M4A, OGG and more) with the on-device model. Long recordings are done in parts.",
            listOf(Param("path", "string", "Audio path from record_audio, pick_files or list_files")),
            privateData = true,
        ) { a -> withAudio(ctx, a.str("path")) { f -> transcribe(f) } },
        ToolDef(
            "listen_to_audio",
            "Listen to a recording or audio file with the on-device model and answer a question about it, e.g. summarise it, list the decisions, or what was said about a topic.",
            listOf(
                Param("path", "string", "Audio path from record_audio, pick_files or list_files"),
                Param("question", "string", "What to find out, e.g. Summarise this voice note"),
            ),
            privateData = true,
        ) { a -> withAudio(ctx, a.str("path")) { f -> Hearing.listen(f, a.str("question").ifBlank { "Summarise this recording." }) } },
        ToolDef(
            "identify_sounds",
            "Name the kinds of sound in a recording or audio file (speech, music, a dog barking, a siren, traffic…) with times, using a sound classifier on the phone.",
            listOf(Param("path", "string", "Audio path from record_audio, pick_files or list_files")),
            privateData = true,
        ) { a -> withAudio(ctx, a.str("path")) { f -> sounds(ctx, f) } },
    )

    private fun seconds(n: Int) = n.coerceIn(1, MAX_RECORD_SECONDS)

    private suspend fun record(ctx: Context, seconds: Int): String {
        if (!Agent.inFront) return "Open MySI on screen to record: Android only lets an app use the microphone while it is in front."
        if (!Agent.askFor(Manifest.permission.RECORD_AUDIO)) return NO_MIC
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val file = File(File(Storage.workspace(ctx), "Recordings").apply { mkdirs() }, "recording_$stamp.wav")
        var reads = 0
        val r = try {
            Recorder(seconds).record(ctx, file) { level ->
                // About ten level readings a second; the status line shows one a second.
                if (reads++ % 10 == 0) Agent.progress("Recording… ${meter(level)} ${reads / 10} of $seconds s")
            }
        } catch (e: SecurityException) {
            return NO_MIC
        } catch (e: IOException) {
            return e.message ?: "Could not record."
        }
        return "Recorded ${"%.1f".format(Locale.ROOT, r.seconds)} seconds: ${r.file.absolutePath} (16 kHz mono WAV). " +
            "Use transcribe_audio, listen_to_audio or identify_sounds with this path."
    }

    /** A five-step text meter for the status line. */
    internal fun meter(level: Float): String {
        // Speech RMS sits around 0.02-0.2; a square root spreads that over the meter.
        val steps = (Math.sqrt(level.toDouble().coerceIn(0.0, 1.0)) * 5 / 0.6).toInt().coerceIn(0, 5)
        return "▮".repeat(steps) + "▯".repeat(5 - steps)
    }

    private suspend fun transcribe(f: File): String {
        val t = Hearing.transcript(f)
        val head = "Transcript of ${f.name} (${SoundReport.clock((t.seconds * 1000).toLong())}" +
            (if (t.pieces > 1) ", in ${t.pieces} parts" else "") + (if (t.truncated) ", first ${(Media.MAX_AUDIO_SECONDS / 60).toInt()} minutes only" else "") + "):\n"
        return head + t.text.ifEmpty { "(no speech)" }
    }

    private suspend fun sounds(ctx: Context, f: File): String =
        withContext(Dispatchers.IO) {
            val d = Media.audio(f, SOUNDS_MAX_SECONDS)
            if (d.pcm.samples.isEmpty()) return@withContext "${f.name} is empty."
            val windows = SoundClassifier.create(ctx).use { it.classify(d.pcm) }
            SoundReport.format(windows, f.name, d.pcm.seconds, d.truncated)
        }

    private suspend fun withAudio(ctx: Context, path: String, block: suspend (File) -> String): String {
        val f = SkillFiles.file(ctx, path) ?: return SkillFiles.missing(path)
        return try {
            block(f)
        } catch (e: SenseUnavailable) {
            "${e.message} identify_sounds still names the kinds of sound without it."
        } catch (e: IOException) {
            e.message ?: "Could not read the audio."
        } catch (e: OutOfMemoryError) {
            "The recording is too long for this phone's memory."
        }
    }
}
