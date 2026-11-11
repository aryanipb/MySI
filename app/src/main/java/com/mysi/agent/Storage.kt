package com.mysi.agent

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File

/**
 * Scoped storage only, so no storage permission is needed. The agent reads and writes inside
 * its own workspace, reads copies of files the user picked in the system picker, and publishes
 * finished documents and photos to shared Download/MySI and Pictures/MySI through MediaStore.
 */
object Storage {
    private const val PICKED_MAX_BYTES = 200L * 1024 * 1024
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun workspace(ctx: Context) = File(ctx.filesDir, "workspace").apply { mkdirs() }

    private fun pickedRoot(ctx: Context) = File(ctx.cacheDir, "picked").apply { mkdirs() }

    /** Maps a path from the model to a file inside the workspace or the picked files; null otherwise. */
    fun resolve(ctx: Context, path: String): File? = resolveIn(workspace(ctx), pickedRoot(ctx), path)

    /**
     * Relative paths are inside [workspace]; absolute ones must already be inside [workspace] or
     * [picked]. Symlinks and ".." are resolved first, so nothing can escape either folder.
     */
    internal fun resolveIn(workspace: File, picked: File, path: String): File? {
        val p = path.trim()
        if (p.isEmpty()) return null
        val ws = workspace.canonicalFile
        val pk = picked.canonicalFile
        val f = (if (p.startsWith("/")) File(p) else File(ws, p)).canonicalFile
        return f.takeIf { it.startsWith(ws) || it.startsWith(pk) }
    }

    fun mime(f: File) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"

    /** Copies content URIs from the system pickers into the app, since grants on them are temporary. */
    fun importPicked(ctx: Context, uris: List<Uri>): Pair<List<File>, List<String>> {
        val root = pickedRoot(ctx)
        root.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > DAY_MS }?.forEach { it.deleteRecursively() }
        val dir = File(root, System.currentTimeMillis().toString()).apply { mkdirs() }
        val ok = mutableListOf<File>()
        val skipped = mutableListOf<String>()
        uris.forEachIndexed { i, uri ->
            var name = "file_$i"
            var size = -1L
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            name = name.replace(Regex("[/\\\\\\u0000]"), "_").ifBlank { "file_$i" }
            if (size > PICKED_MAX_BYTES) {
                skipped += "$name (over 200 MB)"
                return@forEachIndexed
            }
            var out = File(dir, name)
            var n = 1
            while (out.exists()) out = File(dir, "${name.substringBeforeLast('.')} ($n)" + name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }).also { n++ }
            val copied = runCatching {
                ctx.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } }
            }.isSuccess
            if (copied) ok += out else { out.delete(); skipped += name }
        }
        return ok to skipped
    }

    /**
     * Copies [file] to shared storage so the user can find it in Files or Photos. Replaces this
     * app's earlier copy of the same name instead of piling up "name (1)" duplicates.
     */
    fun publish(ctx: Context, file: File, photo: Boolean = false): String {
        val collection = if (photo) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val dir = (if (photo) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS) + "/MySI/"
        val cr = ctx.contentResolver
        val existing = cr.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(dir, file.name),
            null,
        )?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(collection, c.getLong(0)) else null }
        val uri = existing ?: cr.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime(file))
                put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ) ?: throw java.io.IOException("Android refused to create ${file.name} in $dir")
        cr.openOutputStream(uri, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        if (existing == null) cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return dir + file.name
    }
}
