package com.mysi.agent.senses

import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** A box in pixels of the upright image: left, top, right, bottom. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

data class Detection(val label: String, val score: Float, val box: Box)

/** Turns detector output into exact counts and a list of boxes the model can quote. */
object DetectionReport {
    /** Detections below this confidence are not counted. */
    const val MIN_SCORE = 0.5f

    /** At most this many boxes are listed; counts always include every detection. */
    const val MAX_BOXES = 40

    /** Detections worth counting: confident enough and with a real label. */
    fun kept(all: List<Detection>, minScore: Float = MIN_SCORE): List<Detection> =
        all.filter { it.score >= minScore && it.label.isNotBlank() && it.label != "???" }

    /** How many of each label, most first; equal counts in alphabetical order. */
    fun counts(dets: List<Detection>): List<Pair<String, Int>> =
        dets.groupingBy { it.label }.eachCount().toList().sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

    /** One line of counts, e.g. "3 person, 1 cup"; empty when nothing was found. */
    fun summary(dets: List<Detection>): String = counts(dets).joinToString(", ") { (label, n) -> "$n $label" }

    /**
     * The text detect_objects returns for an image of [width] x [height]. Boxes are listed in
     * reading order (top to bottom, then left to right), in whole pixels.
     */
    fun format(all: List<Detection>, width: Int, height: Int, name: String, annotated: String?, minScore: Float = MIN_SCORE): String {
        val dets = kept(all, minScore)
        val pct = (minScore * 100).roundToInt()
        return buildString {
            append("Objects detected in $name ($width x $height pixels), exact counts at $pct% confidence or more:\n")
            if (dets.isEmpty()) append("none of the 80 common object types the detector knows.\n")
            else {
                append(summary(dets)).append(".\n")
                val ordered = dets.sortedWith(compareBy<Detection> { it.box.top.roundToInt() / max(1, height / 20) }.thenBy { it.box.left }.thenBy { it.label })
                append("Boxes (left, top, right, bottom):\n")
                ordered.take(MAX_BOXES).forEach { d ->
                    val b = d.box
                    append("- ${d.label} ${(d.score * 100).roundToInt()}%: ${b.left.roundToInt()}, ${b.top.roundToInt()}, ${b.right.roundToInt()}, ${b.bottom.roundToInt()}\n")
                }
                if (ordered.size > MAX_BOXES) append("(${ordered.size - MAX_BOXES} more boxes not listed)\n")
            }
            annotated?.let { append("Copy with the boxes drawn: $it\n") }
            append("The detector only knows 80 common object types (people, vehicles, animals, furniture, food, everyday items); for anything else, or to describe the scene, use look_at_image.")
        }
    }
}

/** One stretch of audio classified by YAMNet: its start time and labels with scores. */
data class SoundWindow(val startMs: Long, val labels: List<Pair<String, Float>>)

/** Turns per-window sound labels into a short timeline. */
object SoundReport {
    const val MIN_SCORE = 0.3f
    const val MAX_LABELS = 8

    /** Length of one YAMNet window (15,600 samples at 16 kHz). */
    const val WINDOW_MS = 975L

    data class Heard(val label: String, val windows: Int, val peak: Float, val spans: List<LongRange>)

    /**
     * Each label with how many windows it was heard in, its peak score and the time ranges it
     * covers (neighbouring windows merged), most-heard first, ties by peak score.
     */
    fun summarize(windows: List<SoundWindow>, minScore: Float = MIN_SCORE, windowMs: Long = WINDOW_MS): List<Heard> {
        val hits = LinkedHashMap<String, MutableList<Pair<Long, Float>>>()
        for (w in windows.sortedBy { it.startMs }) for ((label, score) in w.labels) {
            if (score >= minScore && label.isNotBlank()) hits.getOrPut(label) { mutableListOf() } += w.startMs to score
        }
        return hits.map { (label, list) ->
            val spans = mutableListOf<LongRange>()
            for ((start, _) in list) {
                val end = start + windowMs
                val last = spans.lastOrNull()
                if (last != null && start <= last.last) spans[spans.lastIndex] = last.first..max(last.last, end) else spans += start..end
            }
            Heard(label, list.size, list.maxOf { it.second }, spans)
        }.sortedWith(compareByDescending<Heard> { it.windows }.thenByDescending { it.peak }.thenBy { it.label })
    }

    fun format(windows: List<SoundWindow>, name: String, seconds: Double, truncated: Boolean, minScore: Float = MIN_SCORE): String {
        val heard = summarize(windows, minScore)
        return buildString {
            append("Sounds in $name (${clock((seconds * 1000).toLong())} long")
            if (truncated) append(", only the start was analysed")
            append("):\n")
            if (heard.isEmpty()) append("nothing recognised with confidence; the audio may be silent or too quiet.\n")
            heard.take(MAX_LABELS).forEach { h ->
                val times = h.spans.take(4).joinToString(", ") { "${clock(it.first)}-${clock(it.last)}" } + if (h.spans.size > 4) ", …" else ""
                append("- ${h.label}: $times (peak ${(h.peak * 100).roundToInt()}%)\n")
            }
            if (heard.size > MAX_LABELS) append("(${heard.size - MAX_LABELS} less frequent sounds not listed)\n")
            append("These are kinds of sound from a fixed list of 521 (speech, music, animals, vehicles, alarms…); it does not recognise words, people or song titles. For what is said, use transcribe_audio.")
        }
    }

    fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }
}
