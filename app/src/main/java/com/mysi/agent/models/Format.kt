package com.mysi.agent.models

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Plain-English numbers for the model setup screen.
 *
 * Units: download and storage sizes use decimal units (1 GB = 1,000,000,000 bytes), as Android's
 * Settings and Files apps do, so "2.6 GB" here matches what the phone shows. Phone memory uses
 * binary units (1 GB = 2^30 bytes), as phone makers advertise it ("8 GB of RAM").
 */
object Format {
    private const val KB = 1_000L
    private const val MB = 1_000_000L
    private const val GB = 1_000_000_000L
    const val GIB = 1L shl 30
    const val MIB = 1L shl 20

    /** A download or storage size: "164 MB", "2.6 GB", "12 GB". */
    fun size(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        return when {
            b >= 10 * GB -> "${(b.toDouble() / GB).roundToLong()} GB"
            b >= GB -> "${oneDecimal(b.toDouble() / GB)} GB"
            b >= MB -> "${(b.toDouble() / MB).roundToLong().coerceAtMost(999)} MB"
            b >= KB -> "${(b.toDouble() / KB).roundToLong().coerceAtMost(999)} KB"
            else -> "$b bytes"
        }
    }

    /** Memory as an amount: "3.7 GB", "900 MB" (binary units). */
    fun memory(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        return if (b >= GIB) "${oneDecimal(b.toDouble() / GIB)} GB" else "${(b.toDouble() / MIB).roundToLong()} MB"
    }

    /** "1.2 GB of 2.6 GB". */
    fun progress(done: Long, total: Long): String = "${size(done)} of ${size(total)}"

    /** Whole percent, never 100 before the last byte. */
    fun percent(done: Long, total: Long): Int {
        if (total <= 0) return 0
        if (done >= total) return 100
        return (done.coerceAtLeast(0) * 100 / total).toInt().coerceIn(0, 99)
    }

    /** "4.2 MB/s", "650 KB/s". */
    fun speed(bytesPerSecond: Double): String {
        if (bytesPerSecond.isNaN() || bytesPerSecond <= 0) return "0 KB/s"
        return when {
            bytesPerSecond >= 10 * MB -> "${bytesPerSecond.div(MB).roundToLong()} MB/s"
            bytesPerSecond >= MB -> "${oneDecimal(bytesPerSecond / MB)} MB/s"
            else -> "${bytesPerSecond.div(KB).roundToLong().coerceAtLeast(1)} KB/s"
        }
    }

    /** Time left, rounded the way people say it: "Less than a minute left", "About 4 min left", "About 1 h 5 min left". */
    fun timeLeft(seconds: Long?): String {
        if (seconds == null || seconds < 0) return "Working out time left…"
        if (seconds < 60) return "Less than a minute left"
        val minutes = (seconds + 30) / 60
        if (minutes < 60) return "About $minutes min left"
        val h = minutes / 60
        val m = minutes % 60
        if (h >= 48) return "More than 2 days left"
        return if (m == 0L) "About $h h left" else "About $h h $m min left"
    }

    /** Whole GB of phone memory: "8 GB". */
    fun ramGb(gb: Int) = "$gb GB"

    /** Seconds with one decimal: "1.4 s". */
    fun seconds(ms: Long): String = "${oneDecimal(ms / 1000.0)} s"

    private fun oneDecimal(v: Double): String {
        val s = String.format(Locale.US, "%.1f", v)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }
}
