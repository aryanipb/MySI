package com.mysi.agent.models

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Checks a downloaded model against its catalogue entry, streaming so a 4 GB file never sits in memory. */
object HashCheck {
    sealed class Result {
        object Ok : Result()
        object Missing : Result()
        object Cancelled : Result()
        data class WrongSize(val actual: Long) : Result()
        data class WrongHash(val actual: String) : Result()
        data class Unreadable(val message: String) : Result()
    }

    private const val BUFFER = 1 shl 20

    /**
     * Compares [file] with [expectedSize] and [expectedSha256] (lower-case hex). The size is
     * checked first, so a truncated download fails at once. [progress] receives bytes read so far,
     * at most every [progressEvery] bytes; [cancelled] is polled between reads. Blocking: call it
     * off the main thread.
     */
    fun verify(
        file: File,
        expectedSize: Long,
        expectedSha256: String,
        progress: (Long) -> Unit = {},
        cancelled: () -> Boolean = { false },
        progressEvery: Long = 32L shl 20,
    ): Result {
        if (!file.isFile) return Result.Missing
        val length = file.length()
        if (length != expectedSize) return Result.WrongSize(length)
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        var reported = 0L
        try {
            file.inputStream().use { input ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    if (cancelled()) return Result.Cancelled
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    done += n
                    if (done - reported >= progressEvery) {
                        reported = done
                        progress(done)
                    }
                }
            }
        } catch (e: IOException) {
            return Result.Unreadable(e.message ?: "read failed")
        }
        progress(done)
        val actual = hex(digest.digest())
        return if (actual.equals(expectedSha256, ignoreCase = true)) Result.Ok else Result.WrongHash(actual)
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** Plain English for a failed check. */
    fun message(r: Result): String =
        when (r) {
            Result.Ok -> "The model checked out."
            Result.Missing -> "The downloaded model is missing. Try again."
            Result.Cancelled -> "Checking was stopped."
            is Result.WrongSize -> "The download was incomplete or damaged, so it was deleted. Try again."
            is Result.WrongHash -> "The download didn't match the published file, so it was deleted. Try again."
            is Result.Unreadable -> "The downloaded model couldn't be read, so it was deleted. Try again."
        }
}
