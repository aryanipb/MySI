package com.mysi.agent.models

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Thin wrapper around Android's [DownloadManager]: it needs no extra permission, keeps going when
 * MySI is closed, resumes broken connections with range requests, honours "Wi-Fi only", and
 * shows its own progress notification. All decisions about its answers live in [DownloadRules].
 *
 * The request goes to the pinned huggingface.co address only ([CatalogParser] guarantees it);
 * Hugging Face redirects it to its own file servers. The SHA-256 check afterwards ([HashCheck])
 * makes sure whatever arrived is exactly the published file.
 */
class ModelDownloader(context: Context) {
    private val app = context.applicationContext
    private val dm = app.getSystemService(DownloadManager::class.java)

    /** Starts downloading [entry] to [target], which must be inside the app's external files folder. Returns the download id. */
    fun enqueue(entry: ModelEntry, target: File, wifiOnly: Boolean): Long {
        target.parentFile?.mkdirs()
        val request = DownloadManager.Request(Uri.parse(entry.url))
            .setDestinationUri(Uri.fromFile(target))
            .setTitle("MySI model: ${entry.name}")
            .setDescription("${Format.size(entry.sizeBytes)} from Hugging Face")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(false)
            .setRequiresCharging(false)
            .setRequiresDeviceIdle(false)
            // The system's default User-Agent names the phone model and build; send a generic one.
            .addRequestHeader("User-Agent", USER_AGENT)
        return dm.enqueue(request)
    }

    fun query(id: Long): DmSnapshot {
        val cursor = runCatching { dm.query(DownloadManager.Query().setFilterById(id)) }.getOrNull()
            ?: return DmSnapshot(DmStatus.MISSING, 0, 0, -1)
        cursor.use { c ->
            if (!c.moveToFirst()) return DmSnapshot(DmStatus.MISSING, 0, 0, -1)
            fun int(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let(c::getInt) ?: 0
            fun long(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let(c::getLong) ?: -1L
            val status = when (int(DownloadManager.COLUMN_STATUS)) {
                DownloadManager.STATUS_PENDING -> DmStatus.PENDING
                DownloadManager.STATUS_RUNNING -> DmStatus.RUNNING
                DownloadManager.STATUS_PAUSED -> DmStatus.PAUSED
                DownloadManager.STATUS_SUCCESSFUL -> DmStatus.SUCCESSFUL
                DownloadManager.STATUS_FAILED -> DmStatus.FAILED
                else -> DmStatus.PENDING
            }
            return DmSnapshot(status, int(DownloadManager.COLUMN_REASON), long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR).coerceAtLeast(0), long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        }
    }

    /**
     * Cancels [id] and forgets it. For a download still in progress, the download manager also
     * deletes the partial file; once a finished file has been moved away, nothing is deleted.
     */
    fun remove(id: Long) {
        runCatching { dm.remove(id) }
    }

    companion object {
        /** Says only that this is MySI; nothing about the phone or the user. */
        const val USER_AGENT = "MySI"
    }
}
