package com.mysi.agent.models

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import java.io.File

/** A tiny key-value store, so [ModelStore] runs on the JVM in tests with a map. */
interface KeyValue {
    fun get(key: String): String?

    fun put(key: String, value: String?)
}

class PrefsKeyValue(private val prefs: SharedPreferences) : KeyValue {
    override fun get(key: String): String? = prefs.getString(key, null)

    // commit, not apply: the state must be on disk before the next step, in case the app is killed.
    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()
    }
}

/**
 * Where downloaded models live, and keeping only one.
 *
 * Android's download manager can only write to shared or app-specific external storage, never to
 * the app's internal files. Models are therefore downloaded into the app-specific external files
 * folder ([Context.getExternalFilesDir], which needs no permission and which other apps cannot
 * read on Android 11 and later) and the engine loads them from there:
 * - Moving a 2 to 7 GB file into internal storage (`filesDir/models/`, where the chat screen's
 *   "Load model" copies picked files) would mean copying it, because external and internal storage
 *   are different mounts and `File.renameTo` fails across them. That needs twice the free space and
 *   minutes of writing, for no gain: the external folder is private to MySI too, and is removed
 *   when MySI is uninstalled.
 * - The file is still moved once, within the external folder, from [downloadsDir] to [modelsDir]
 *   (the same mount, so the rename is instant). That takes it out of the download manager's hands:
 *   its idle clean-up deletes finished downloads that apps leave registered for a week, and
 *   [android.app.DownloadManager.remove] deletes the file it points to. After the move the record
 *   is removed and the file stays.
 *
 * Every model sits in its own timestamped folder, so the model in use is never overwritten.
 */
class ModelStore(val filesDir: File, val externalDir: File?, private val kv: KeyValue) {
    val downloadsDir: File? get() = externalDir?.let { File(it, "model-downloads") }
    val modelsDir: File? get() = externalDir?.let { File(it, "models") }

    /** Where the chat screen's "Load model" (MainActivity.importModel) keeps its copies. */
    val importDir: File get() = File(filesDir, "models")

    /** Fresh download and final paths for [entry], or null when external storage is unavailable. */
    fun plan(entry: ModelEntry, stamp: Long): Pair<File, File>? {
        val d = downloadsDir ?: return null
        val m = modelsDir ?: return null
        return File(File(d, stamp.toString()), entry.file) to File(File(m, stamp.toString()), entry.file)
    }

    fun savePending(p: PendingDownload) = kv.put(KEY_PENDING, Gson().toJson(p))

    /** The saved download, or null when there is none or it is unreadable. */
    fun loadPending(): PendingDownload? {
        val json = kv.get(KEY_PENDING) ?: return null
        val p = runCatching { Gson().fromJson(json, PendingDownload::class.java) }.getOrNull()
        // Gson can leave Kotlin non-null fields null when the stored text is damaged.
        val sane = p != null && present(p.modelId, p.modelName, p.sha256, p.downloadPath, p.finalPath) &&
            inside(File(p.downloadPath), downloadsDir) && inside(File(p.finalPath), modelsDir)
        if (!sane) kv.put(KEY_PENDING, null)
        return if (sane) p else null
    }

    fun clearPending() = kv.put(KEY_PENDING, null)

    private fun present(vararg values: Any?) = values.all { it != null }

    /** Moves a finished download to its final place on the same storage. True on success. */
    fun move(p: PendingDownload): Boolean {
        val from = File(p.downloadPath)
        val to = File(p.finalPath)
        if (!inside(from, downloadsDir) || !inside(to, modelsDir)) return false
        to.parentFile?.mkdirs()
        return from.renameTo(to)
    }

    /** Deletes every file of [p]: the partial or finished download and the moved model. */
    fun discard(p: PendingDownload) {
        deleteFolder(File(p.downloadPath).parentFile)
        deleteFolder(File(p.finalPath).parentFile)
    }

    /**
     * After [model] has loaded: deletes every other model copy, in [modelsDir], in [downloadsDir]
     * and in [importDir], so only one multi-gigabyte model is kept (as "Load model" does).
     */
    fun keepOnly(model: File) {
        val keep = model.parentFile
        for (dir in listOfNotNull(modelsDir, downloadsDir, importDir)) dir.listFiles()?.filter { it != keep }?.forEach(::deleteFolder)
    }

    /**
     * Deletes model folders nothing uses: not the model the chat loads ([savedModel]) and not the
     * download in progress. Leaves [importDir] alone, since "Load model" may be copying into it.
     */
    fun pruneUnused(savedModel: String?, pending: PendingDownload?) {
        val keep = setOfNotNull(
            savedModel?.let { File(it).parentFile },
            pending?.let { File(it.downloadPath).parentFile },
            pending?.let { File(it.finalPath).parentFile },
        )
        for (dir in listOfNotNull(modelsDir, downloadsDir)) dir.listFiles()?.filter { it !in keep }?.forEach(::deleteFolder)
    }

    /** True when [file] is directly inside a folder of [root] (root/<stamp>/<file>). */
    private fun inside(file: File, root: File?): Boolean {
        root ?: return false
        val parent = file.parentFile ?: return false
        return parent.parentFile?.canonicalPath == root.canonicalPath && file.name.isNotEmpty() && !file.name.startsWith(".")
    }

    /** Deletes [folder] only when it is one of the model folders this class manages. */
    private fun deleteFolder(folder: File?) {
        folder ?: return
        val parent = folder.parentFile?.canonicalPath ?: return
        val roots = listOfNotNull(modelsDir, downloadsDir, importDir).map { it.canonicalPath }
        if (parent in roots) folder.deleteRecursively()
    }

    companion object {
        private const val KEY_PENDING = "pending_download"

        fun of(context: Context): ModelStore {
            val app = context.applicationContext
            return ModelStore(app.filesDir, app.getExternalFilesDir(null), PrefsKeyValue(app.getSharedPreferences("model_setup", Context.MODE_PRIVATE)))
        }
    }
}
