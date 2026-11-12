package com.mysi.agent.models

import android.app.DownloadManager

/** Where a [DownloadManager] download stands, read from its row (or [MISSING] when there is no row). */
enum class DmStatus { PENDING, RUNNING, PAUSED, SUCCESSFUL, FAILED, MISSING }

/** One reading of a download: status, [DownloadManager.COLUMN_REASON], and bytes. [total] is -1 until the server says. */
data class DmSnapshot(val status: DmStatus, val reason: Int, val done: Long, val total: Long)

/**
 * A model download in progress, saved so it survives the app being closed or killed. Both paths
 * are fixed when the download starts: [downloadPath] is where Android's download manager writes,
 * [finalPath] is where the file is moved once complete (see [ModelStore] for why it moves).
 */
data class PendingDownload(
    val downloadId: Long,
    val modelId: String,
    /** Display name, kept so the screen can name the model even if the catalogue changes meanwhile. */
    val modelName: String,
    val sizeBytes: Long,
    val sha256: String,
    val downloadPath: String,
    val finalPath: String,
    val wifiOnly: Boolean,
    /** True once the file has been moved to [finalPath] and the download manager has forgotten it. */
    val moved: Boolean = false,
    /**
     * True once loading has started. If the app is found with this still set, loading never
     * finished (the model may crash the engine), so the file is deleted instead of loaded again:
     * a model that crashes MySI must never be retried on every launch.
     */
    val loadStarted: Boolean = false,
)

/** What to do with a saved [PendingDownload] after the app (re)opens. Pure, so every kill point is tested. */
sealed class ResumeAction {
    /** Nothing saved. */
    object None : ResumeAction()

    /** Still downloading (or waiting for Wi-Fi): keep watching it. */
    object Watch : ResumeAction()

    /** Finished downloading: move it to its final place, then check it. */
    object Move : ResumeAction()

    /** The file is in its final place: check its size and SHA-256, then load it. */
    object Verify : ResumeAction()

    /** It had already loaded when the app was killed: only tidy up (forget the download, delete other copies). */
    object Finished : ResumeAction()

    /** Something went wrong: delete every trace and tell the user [message]. */
    data class Discard(val message: String) : ResumeAction()
}

object DownloadRules {
    const val WRONG_SIZE = "The file on Hugging Face isn't the size MySI expects, so the download was stopped and deleted. Check for a MySI update."

    /**
     * [loadedModel] is the model the chat loads ([com.mysi.agent.Agent.savedModel]), which is
     * only ever set after a successful load.
     */
    fun resume(pending: PendingDownload?, dm: DmSnapshot, downloadExists: Boolean, finalExists: Boolean, loadedModel: String? = null): ResumeAction {
        pending ?: return ResumeAction.None
        if (finalExists && loadedModel == pending.finalPath) return ResumeAction.Finished
        if (pending.loadStarted) return ResumeAction.Discard(closedWhileStarting(pending.modelName))
        if (pending.moved) return if (finalExists) ResumeAction.Verify else ResumeAction.Discard("The downloaded model is missing. Try again.")
        // Stop early, rather than fill the phone, when the server sends a different file.
        if ((dm.total > 0 && dm.total != pending.sizeBytes) || dm.done > pending.sizeBytes)
            return ResumeAction.Discard(WRONG_SIZE)
        return when (dm.status) {
            DmStatus.PENDING, DmStatus.RUNNING, DmStatus.PAUSED -> ResumeAction.Watch
            DmStatus.SUCCESSFUL -> when {
                downloadExists -> ResumeAction.Move
                // Killed after the move but before it was recorded.
                finalExists -> ResumeAction.Verify
                else -> ResumeAction.Discard("The downloaded model is missing. Try again.")
            }
            DmStatus.FAILED -> ResumeAction.Discard(failure(dm.reason))
            DmStatus.MISSING -> if (finalExists) ResumeAction.Verify else ResumeAction.Discard("The download was cancelled.")
        }
    }

    /**
     * Told on the next launch when a model never finished loading (it may crash the engine), for a
     * download here and for the chat screen's "Load model" import ([ModelStore.recoverInterruptedImport]).
     */
    fun closedWhileStarting(modelName: String) =
        "MySI closed while starting $modelName, so the file was deleted to be safe. Try again, or choose another model."

    const val WAITING_FOR_WIFI =
        "Waiting for Wi-Fi, as you chose. To use mobile data instead, cancel and start again with \"Wi-Fi only\" turned off."

    /**
     * A plain-English line for a download that is waiting, or null while it runs. Recent Android
     * versions leave a download that waits for its network as "pending" rather than "paused", so
     * the current connection ([unmetered], [online]) is used to explain a pending one.
     */
    fun waiting(dm: DmSnapshot, wifiOnly: Boolean, unmetered: Boolean, online: Boolean): String? {
        if (dm.status == DmStatus.PENDING) {
            return when {
                !online -> "Waiting for an internet connection."
                wifiOnly && !unmetered -> WAITING_FOR_WIFI
                else -> null
            }
        }
        if (dm.status != DmStatus.PAUSED) return null
        return when (dm.reason) {
            DownloadManager.PAUSED_QUEUED_FOR_WIFI ->
                if (wifiOnly) WAITING_FOR_WIFI
                else "Waiting for Wi-Fi: this download is too big for mobile data on this phone."
            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for an internet connection."
            DownloadManager.PAUSED_WAITING_TO_RETRY -> "The connection dropped. Trying again shortly."
            else -> "Paused. It will continue on its own."
        }
    }

    /** Plain English for a failed download's [DownloadManager.COLUMN_REASON] (an error code or an HTTP status). */
    fun failure(reason: Int): String =
        when (reason) {
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "The phone ran out of storage. Free up some space and try again."
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "The phone's storage isn't available right now. Try again later."
            DownloadManager.ERROR_FILE_ERROR, DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "The model couldn't be saved on this phone. Try again."
            DownloadManager.ERROR_CANNOT_RESUME -> "The download couldn't continue where it stopped. Try again to start over."
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "The connection broke during the download. Try again."
            DownloadManager.ERROR_TOO_MANY_REDIRECTS, DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Hugging Face sent an answer the download couldn't use. Try again later."
            401, 403 -> "Hugging Face refused the download. The file may have moved; check for a MySI update."
            404, 410 -> "This model is no longer at its address on Hugging Face. Check for a MySI update."
            416 -> "The download couldn't continue where it stopped. Try again to start over."
            429 -> "Hugging Face is busy. Try again in a few minutes."
            in 500..599 -> "Hugging Face is having trouble right now. Try again later."
            else -> "The download failed. Try again."
        }
}

/**
 * Download speed and time left from periodic readings. Speed is an exponential moving average
 * over readings, so one slow second does not swing the estimate; readings with no time between
 * them, or bytes going backwards (a restart), reset it.
 */
class SpeedMeter(private val smoothing: Double = 0.3) {
    private var lastBytes = -1L
    private var lastMs = 0L
    var bytesPerSecond: Double = 0.0
        private set

    fun add(bytes: Long, atMs: Long) {
        if (lastBytes < 0 || bytes < lastBytes || atMs < lastMs) {
            lastBytes = bytes
            lastMs = atMs
            bytesPerSecond = 0.0
            return
        }
        val dt = atMs - lastMs
        if (dt <= 0) return
        val instant = (bytes - lastBytes) * 1000.0 / dt
        bytesPerSecond = if (bytesPerSecond == 0.0) instant else smoothing * instant + (1 - smoothing) * bytesPerSecond
        lastBytes = bytes
        lastMs = atMs
    }

    /** Seconds left at the current speed, or null when it cannot be told yet. */
    fun secondsLeft(done: Long, total: Long): Long? {
        if (total <= 0 || bytesPerSecond < 1.0) return null
        return ((total - done).coerceAtLeast(0) / bytesPerSecond).toLong()
    }

    fun reset() {
        lastBytes = -1
        lastMs = 0
        bytesPerSecond = 0.0
    }
}
