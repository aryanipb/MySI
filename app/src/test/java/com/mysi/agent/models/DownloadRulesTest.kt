package com.mysi.agent.models

import android.app.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRulesTest {
    private val size = 2_588_147_712L
    private val pending = PendingDownload(7, "gemma-4-e2b", "Gemma 4 E2B", size, "a".repeat(64), "/x/model-downloads/1/m.litertlm", "/x/models/1/m.litertlm", wifiOnly = true)

    private fun snap(status: DmStatus, reason: Int = 0, done: Long = 0, total: Long = size) = DmSnapshot(status, reason, done, total)

    private fun resume(p: PendingDownload? = pending, dm: DmSnapshot, download: Boolean = false, final: Boolean = false) =
        DownloadRules.resume(p, dm, download, final)

    // ---------------- every point the app can be killed at ----------------

    @Test
    fun nothingSaved() = assertEquals(ResumeAction.None, resume(null, snap(DmStatus.MISSING)))

    @Test
    fun stillDownloadingIsWatched() {
        for (s in listOf(DmStatus.PENDING, DmStatus.RUNNING, DmStatus.PAUSED))
            assertEquals(ResumeAction.Watch, resume(dm = snap(s, done = 1_000)))
        // Total unknown until the server answers.
        assertEquals(ResumeAction.Watch, resume(dm = snap(DmStatus.PENDING, total = -1)))
    }

    @Test
    fun finishedButNotMovedIsMoved() = assertEquals(ResumeAction.Move, resume(dm = snap(DmStatus.SUCCESSFUL, done = size), download = true))

    @Test
    fun killedAfterMoveBeforeRecordingIsVerified() =
        assertEquals(ResumeAction.Verify, resume(dm = snap(DmStatus.SUCCESSFUL, done = size), download = false, final = true))

    @Test
    fun movedAndRecordedIsVerifiedWhateverTheDownloadManagerSays() {
        val moved = pending.copy(moved = true)
        assertEquals(ResumeAction.Verify, resume(moved, snap(DmStatus.MISSING), final = true))
        assertEquals(ResumeAction.Verify, resume(moved, snap(DmStatus.SUCCESSFUL, done = size), final = true))
        assertTrue(resume(moved, snap(DmStatus.MISSING), final = false) is ResumeAction.Discard)
    }

    @Test
    fun aLoadThatNeverFinishedIsNotRetried() {
        // Killed (or crashed) while loading: delete instead of crashing again on every launch.
        val loading = pending.copy(moved = true, loadStarted = true)
        assertEquals(
            ResumeAction.Discard("MySI closed while starting Gemma 4 E2B, so the file was deleted to be safe. Try again, or choose another model."),
            resume(loading, snap(DmStatus.MISSING), final = true),
        )
    }

    @Test
    fun aLoadThatFinishedIsOnlyTidied() {
        val loading = pending.copy(moved = true, loadStarted = true)
        assertEquals(ResumeAction.Finished, DownloadRules.resume(loading, snap(DmStatus.MISSING), false, true, loadedModel = pending.finalPath))
        // The same path but the file is gone: not finished.
        assertTrue(DownloadRules.resume(loading, snap(DmStatus.MISSING), false, false, loadedModel = pending.finalPath) is ResumeAction.Discard)
        // Another model loaded: this one never finished.
        assertTrue(DownloadRules.resume(loading, snap(DmStatus.MISSING), false, true, loadedModel = "/other.litertlm") is ResumeAction.Discard)
    }

    @Test
    fun finishedFileGoneIsDiscarded() {
        val a = resume(dm = snap(DmStatus.SUCCESSFUL, done = size))
        assertEquals(ResumeAction.Discard("The downloaded model is missing. Try again."), a)
    }

    @Test
    fun recordRemovedElsewhere() {
        assertEquals(ResumeAction.Discard("The download was cancelled."), resume(dm = snap(DmStatus.MISSING, total = -1)))
        assertEquals(ResumeAction.Verify, resume(dm = snap(DmStatus.MISSING, total = -1), final = true))
    }

    @Test
    fun failuresAreDiscardedWithPlainEnglish() {
        val a = resume(dm = snap(DmStatus.FAILED, DownloadManager.ERROR_INSUFFICIENT_SPACE))
        assertEquals(ResumeAction.Discard("The phone ran out of storage. Free up some space and try again."), a)
    }

    @Test
    fun aDifferentFileIsStoppedEarly() {
        assertEquals(ResumeAction.Discard(DownloadRules.WRONG_SIZE), resume(dm = snap(DmStatus.RUNNING, total = size + 1)))
        assertEquals(ResumeAction.Discard(DownloadRules.WRONG_SIZE), resume(dm = snap(DmStatus.RUNNING, done = size + 1, total = -1)))
        assertEquals(ResumeAction.Watch, resume(dm = snap(DmStatus.RUNNING, done = size, total = size)))
    }

    // ---------------- messages ----------------

    @Test
    fun everyFailureReasonHasAMessage() {
        val codes = listOf(
            DownloadManager.ERROR_INSUFFICIENT_SPACE, DownloadManager.ERROR_DEVICE_NOT_FOUND, DownloadManager.ERROR_FILE_ERROR,
            DownloadManager.ERROR_FILE_ALREADY_EXISTS, DownloadManager.ERROR_CANNOT_RESUME, DownloadManager.ERROR_HTTP_DATA_ERROR,
            DownloadManager.ERROR_TOO_MANY_REDIRECTS, DownloadManager.ERROR_UNHANDLED_HTTP_CODE, DownloadManager.ERROR_UNKNOWN,
            401, 403, 404, 410, 416, 429, 500, 503, 0, -3,
        )
        for (c in codes) {
            val m = DownloadRules.failure(c)
            assertTrue(m, m.endsWith(".") && m.length in 10..140 && m[0].isUpperCase())
            assertTrue(m, listOf("HTTP", "error", "LFS", "backend").none { m.contains(it) })
        }
        assertEquals("This model is no longer at its address on Hugging Face. Check for a MySI update.", DownloadRules.failure(404))
        assertEquals("Hugging Face is having trouble right now. Try again later.", DownloadRules.failure(503))
        assertEquals("The download failed. Try again.", DownloadRules.failure(DownloadManager.ERROR_UNKNOWN))
    }

    @Test
    fun pausedReasonsDependOnWifiOnly() {
        val wifi = snap(DmStatus.PAUSED, DownloadManager.PAUSED_QUEUED_FOR_WIFI)
        assertEquals("Waiting for Wi-Fi. Turn off \"Wi-Fi only\" to use mobile data instead.", DownloadRules.paused(wifi, wifiOnly = true))
        assertEquals("Waiting for Wi-Fi: this download is too big for mobile data on this phone.", DownloadRules.paused(wifi, wifiOnly = false))
        assertEquals("Waiting for an internet connection.", DownloadRules.paused(snap(DmStatus.PAUSED, DownloadManager.PAUSED_WAITING_FOR_NETWORK), true))
        assertEquals("The connection dropped. Trying again shortly.", DownloadRules.paused(snap(DmStatus.PAUSED, DownloadManager.PAUSED_WAITING_TO_RETRY), true))
        assertEquals("Paused. It will continue on its own.", DownloadRules.paused(snap(DmStatus.PAUSED, DownloadManager.PAUSED_UNKNOWN), true))
        assertNull(DownloadRules.paused(snap(DmStatus.RUNNING), true))
    }

    // ---------------- speed and time left ----------------

    @Test
    fun speedIsSmoothedAndTimeLeftFollows() {
        val m = SpeedMeter(smoothing = 0.5)
        assertNull(m.secondsLeft(0, 100))
        m.add(0, 0)
        m.add(1_000_000, 1_000)
        assertEquals(1_000_000.0, m.bytesPerSecond, 0.001)
        m.add(4_000_000, 2_000) // 3 MB/s this second
        assertEquals(2_000_000.0, m.bytesPerSecond, 0.001)
        assertEquals(3L, m.secondsLeft(4_000_000, 10_000_000))
        // A reading with no time passed is ignored.
        m.add(5_000_000, 2_000)
        assertEquals(2_000_000.0, m.bytesPerSecond, 0.001)
    }

    @Test
    fun restartResetsTheMeter() {
        val m = SpeedMeter()
        m.add(0, 0)
        m.add(5_000_000, 1_000)
        m.add(1_000, 2_000) // bytes went backwards: the download restarted
        assertEquals(0.0, m.bytesPerSecond, 0.0)
        assertNull(m.secondsLeft(1_000, 10_000))
        m.reset()
        m.add(0, 0)
        m.add(500, 1_000)
        assertEquals(500.0, m.bytesPerSecond, 0.001)
        assertNull("under 1 byte/s is unknown", SpeedMeter().apply { add(0, 0); add(0, 1_000) }.secondsLeft(0, 10))
        assertNull(m.secondsLeft(0, -1))
    }
}
