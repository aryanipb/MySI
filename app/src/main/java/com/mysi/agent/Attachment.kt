package com.mysi.agent

import java.io.File

/**
 * A picture or recording the user gives MySI with a message. [file] must be inside the [Storage]
 * sandbox (the workspace or picked files); [Agent.submit] checks that before the request runs.
 * The model gets the media itself only in the turn it was sent; later turns replay [stub], which
 * keeps the path so tools can still use the file.
 */
sealed class Attachment {
    abstract val file: File

    /** A photo or other picture. Sent to the model as a JPEG with its longest side at most 1,024 pixels. */
    data class Image(override val file: File) : Attachment()

    /**
     * A recording or audio file. [seconds] is its length when known; the model hears at most
     * [Attachments.AUDIO_CLIP_SECONDS] of it in the chat (tools take longer audio).
     */
    data class Audio(override val file: File, val seconds: Double? = null) : Attachment()

    val kind get() = if (this is Image) "image" else "audio"

    val name: String get() = file.name
}

/** Model-free rules for attachments, so they can be unit-tested. */
object Attachments {
    /**
     * Images the model takes in one request. LiteRT-LM's EngineConfig.maxNumImages is set to this,
     * and each image costs about [IMAGE_TOKENS] of the 16k-token window.
     */
    const val MAX_IMAGES = 4

    /**
     * Longest audio clip given to the model in one piece. Google's AI Edge Gallery (the reference
     * app for these LiteRT-LM APIs) caps clips at 30 seconds (MAX_AUDIO_CLIP_DURATION_SEC), and
     * the runtime has a "Cropping input audio" path for longer input, so longer audio is split.
     */
    const val AUDIO_CLIP_SECONDS = 30.0

    /** Window budget per image: Gemma's default vision budget is 256-280 soft tokens, plus markers. */
    const val IMAGE_TOKENS = 300

    /**
     * Window budget per second of audio. Gemma 3n encodes 6.25 tokens per second; Gemma 4's audio
     * encoder is budgeted at 25 per second (one per 40 ms), the larger, safer figure.
     */
    const val AUDIO_TOKENS_PER_SECOND = 25

    fun tokens(a: Attachment): Int =
        when (a) {
            is Attachment.Image -> IMAGE_TOKENS
            is Attachment.Audio -> ((a.seconds ?: AUDIO_CLIP_SECONDS).coerceIn(0.0, AUDIO_CLIP_SECONDS) * AUDIO_TOKENS_PER_SECOND).toInt() + 8
        }

    /** What replaces an attachment in later turns: a reference the tools can still use. */
    fun stub(a: Attachment): String = "[${a.kind}: ${a.name} at ${a.file.absolutePath}]"

    /**
     * The text added to a request that carries [given], telling the model what it receives and
     * what it does not: media the loaded model cannot take, and images past [MAX_IMAGES], stay as
     * paths for the tools.
     */
    fun note(given: List<Attachment>, sent: List<Attachment>, canSee: Boolean, canHear: Boolean): String {
        if (given.isEmpty()) return ""
        val lines = given.map { a ->
            val why = when {
                a in sent -> if (a is Attachment.Audio && (a.seconds ?: 0.0) > AUDIO_CLIP_SECONDS) " (you hear the first ${AUDIO_CLIP_SECONDS.toInt()} seconds; transcribe_audio or listen_to_audio take all of it)" else " (attached)"
                a is Attachment.Image && !canSee -> " (not shown to you: the loaded model cannot see images)"
                a is Attachment.Audio && !canHear -> " (not played to you: the loaded model cannot hear audio)"
                else -> " (not shown to you: at most $MAX_IMAGES images per message; use look_at_image)"
            }
            "- ${stub(a)}$why"
        }
        return "\n\nThe user attached:\n" + lines.joinToString("\n")
    }

    /** The attachments the model takes directly: what it can see or hear, images up to [MAX_IMAGES], one audio clip. */
    fun sendable(given: List<Attachment>, canSee: Boolean, canHear: Boolean): List<Attachment> {
        val images = if (canSee) given.filterIsInstance<Attachment.Image>().take(MAX_IMAGES) else emptyList()
        val audio = if (canHear) given.filterIsInstance<Attachment.Audio>().take(1) else emptyList()
        return given.filter { it in images || it in audio }
    }
}
