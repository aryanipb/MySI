package com.mysi.agent

import java.util.Locale
import kotlin.math.sqrt

/**
 * The chat composer's rules, free of Android so they can be unit-tested: what is attached before
 * sending, what each chip says, which senses are available, and what text a request carries.
 */
object Composer {
    /** Attachments one message can carry, of any kind. */
    const val MAX_ITEMS = 10

    /** Sent for a spoken request with nothing typed: the model hears the recording. */
    const val VOICE_PROMPT = "My request is in the attached recording. Listen to it and help with it."

    /** Sent when only attachments are sent, with nothing typed. */
    const val ATTACH_PROMPT = "Take a look at what I attached."

    /** A sound shorter than this is a slip of the finger, not a request. */
    const val MIN_RECORDING_SECONDS = 0.6

    /**
     * An attachment waiting in the composer, and an optional extra line for its chip. [spoken] marks
     * a recording the user made with the microphone button: only that is the user's own request; a
     * picked audio file is content, never instructions.
     */
    data class Chip(val attachment: Attachment, val hint: String = "", val spoken: Boolean = false)

    /** What the files picker's result becomes, by MIME type. */
    fun attachmentFor(file: java.io.File, mime: String): Attachment =
        when {
            mime.startsWith("image/") -> Attachment.Image(file)
            mime.startsWith("audio/") -> Attachment.Audio(file)
            else -> Attachment.Document(file)
        }

    /**
     * Why [a] cannot be attached now, or null when it can. A picture needs a model that can see,
     * whichever picker it came from.
     */
    fun refusal(a: Attachment, ready: Boolean, canSee: Boolean): String? =
        if (a is Attachment.Image) Gate.photos(ready, canSee) else null

    /**
     * The text to send with [attachments] when the user typed [typed], or null when there is
     * nothing to send. With nothing typed, a [spoken] recording is the request itself; other
     * attachments only get a neutral stand-in.
     */
    fun requestText(typed: String, attachments: List<Attachment>, spoken: Boolean): String? {
        val t = typed.trim()
        return when {
            t.isNotEmpty() -> t
            attachments.isEmpty() -> null
            spoken && attachments.any { it is Attachment.Audio } -> VOICE_PROMPT
            else -> ATTACH_PROMPT
        }
    }

    /** The user's message as the chat shows it: the stand-in prompts above are not what the user wrote. */
    fun shownText(text: String): String = if (text == VOICE_PROMPT || text == ATTACH_PROMPT) "" else text

    /** m:ss, e.g. 0:07 or 12:40. */
    fun clock(seconds: Double): String {
        val s = seconds.coerceAtLeast(0.0).toLong()
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    /** A chip's text: the file's name, and a recording's length. */
    fun label(chip: Chip): String {
        val a = chip.attachment
        return if (a is Attachment.Audio && a.seconds != null) "${a.name} · ${clock(a.seconds)}" else a.name
    }

    /** What TalkBack reads for a chip. */
    fun describe(chip: Chip): String {
        val kind = when (chip.attachment) {
            is Attachment.Image -> "Photo"
            is Attachment.Audio -> "Recording"
            is Attachment.Document -> "File"
        }
        return "$kind ${label(chip)}" + if (chip.hint.isNotEmpty()) ". ${chip.hint}" else ""
    }

    /** The camera's detector summary ("2 person, 1 cup") as a chip's hint line. */
    fun seenHint(objects: String?): String = objects?.trim()?.takeIf { it.isNotEmpty() }?.let { "Seen: $it" } ?: ""

    /**
     * Microphone loudness (RMS, 0 to 1) as a meter fill (0 to 1). Speech sits around 0.02-0.2
     * RMS, so a square root spreads it over the meter, as the hearing skill's text meter does.
     */
    fun meter(level: Float): Float = (sqrt(level.toDouble().coerceIn(0.0, 1.0)) / 0.6).toFloat().coerceIn(0f, 1f)

    /** Why a sense's button does nothing right now, or null when it works. */
    object Gate {
        fun photos(ready: Boolean, canSee: Boolean): String? = when {
            !ready -> "Load a model first, then you can attach photos."
            !canSee -> "This model cannot see pictures. Load a Gemma model with vision to attach photos."
            else -> null
        }

        fun camera(ready: Boolean, canSee: Boolean): String? = when {
            !ready -> "Load a model first, then you can use the camera."
            !canSee -> "This model cannot see pictures. Load a Gemma model with vision to use the camera."
            else -> null
        }

        fun mic(ready: Boolean, canHear: Boolean): String? = when {
            !ready -> "Load a model first, then you can talk to MySI."
            !canHear -> "This model cannot hear. Load a Gemma model with audio to talk to MySI."
            else -> null
        }
    }
}

/** The attachments waiting in the composer, in the order they were added. */
class Draft {
    private val items = mutableListOf<Composer.Chip>()

    val chips: List<Composer.Chip> get() = items.toList()

    val isEmpty get() = items.isEmpty()

    /** A recording made with the microphone button is waiting (e.g. one that could not be sent yet). */
    val hasSpoken get() = items.any { it.spoken }

    /** How many more pictures fit in this message. */
    val imageSlots get() = Attachments.MAX_IMAGES - items.count { it.attachment is Attachment.Image }

    /** Adds [chip], or says plainly why it cannot be added. */
    fun add(chip: Composer.Chip): String? {
        val a = chip.attachment
        return when {
            items.any { it.attachment.file.absolutePath == a.file.absolutePath } -> "${a.name} is already attached."
            items.size >= Composer.MAX_ITEMS -> "A message can carry at most ${Composer.MAX_ITEMS} attachments."
            a is Attachment.Image && imageSlots <= 0 -> "A message can carry at most ${Attachments.MAX_IMAGES} photos."
            else -> {
                items += chip
                null
            }
        }
    }

    /** Replaces the chip for the same file, e.g. once a recording's length is known. */
    fun update(chip: Composer.Chip) {
        val i = items.indexOfFirst { it.attachment.file.absolutePath == chip.attachment.file.absolutePath }
        if (i >= 0) items[i] = chip
    }

    fun remove(chip: Composer.Chip): Boolean = items.remove(chip)

    fun clear() = items.clear()

    fun attachments(): List<Attachment> = items.map { it.attachment }
}

/**
 * Hold to talk, with tap to start and tap to stop for TalkBack, keyboards and quick taps.
 *
 * - Press: recording starts ([Action.START]).
 * - Release after a hold: the recording is sent ([Action.FINISH]).
 * - Release after a quick tap: recording goes on, hands free, until the next tap finishes it.
 * - Sliding away while holding, or Cancel: the recording is thrown away ([Action.DISCARD]).
 * - A click (TalkBack's double tap, a keyboard) starts, and the next click finishes.
 */
class PushToTalk(private val tapMillis: Long = TAP_MILLIS) {
    enum class Mode { IDLE, HOLD, TAP }

    enum class Action { NONE, START, FINISH, DISCARD }

    var mode = Mode.IDLE
        private set

    private var downAt = 0L
    private var secondTap = false

    val recording get() = mode != Mode.IDLE

    fun down(now: Long): Action =
        when (mode) {
            Mode.IDLE -> {
                mode = Mode.HOLD
                downAt = now
                Action.START
            }
            Mode.TAP -> {
                secondTap = true
                Action.NONE
            }
            Mode.HOLD -> Action.NONE
        }

    fun up(now: Long): Action =
        when (mode) {
            Mode.HOLD ->
                if (now - downAt < tapMillis) {
                    mode = Mode.TAP
                    Action.NONE
                } else finish()
            Mode.TAP -> if (secondTap) finish() else Action.NONE
            Mode.IDLE -> Action.NONE
        }

    /** The finger slid off the button while holding. */
    fun slideAway(): Action = if (mode == Mode.HOLD) discard() else Action.NONE

    /** A click that is not a touch: TalkBack's double tap or a keyboard. */
    fun click(): Action =
        if (mode == Mode.IDLE) {
            mode = Mode.TAP
            secondTap = false
            Action.START
        } else finish()

    /** The Cancel button, or the screen going away. */
    fun cancel(): Action = if (mode != Mode.IDLE) discard() else Action.NONE

    /** The recording ended by itself (the length cap, or an error): back to idle. */
    fun ended() = reset()

    private fun finish(): Action = reset().let { Action.FINISH }

    private fun discard(): Action = reset().let { Action.DISCARD }

    private fun reset() {
        mode = Mode.IDLE
        secondTap = false
    }

    companion object {
        const val TAP_MILLIS = 400L
    }
}

/**
 * Stops a recording reliably. [com.mysi.agent.senses.Recorder.stop] only works once recording has
 * started on its own thread, so a finish asked for earlier (a very quick release) is held here and
 * applied on the first chunk. [chunk] runs on the recording thread, about ten times a second.
 */
class StopLatch(private val stop: () -> Unit) {
    @Volatile var finishing = false
        private set

    @Volatile var chunks = 0
        private set

    /** Seconds recorded so far, from the 100 ms chunks. */
    val seconds get() = chunks / 10.0

    fun finish() {
        finishing = true
        stop()
    }

    fun chunk() {
        chunks++
        if (finishing) stop()
    }
}
