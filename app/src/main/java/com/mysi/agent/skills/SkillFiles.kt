package com.mysi.agent.skills

import android.content.Context
import com.mysi.agent.Storage
import com.mysi.agent.skills.core.CalcException
import com.mysi.agent.skills.core.DateException
import com.mysi.agent.skills.core.DocSource
import com.mysi.agent.skills.core.Documents
import com.mysi.agent.skills.core.QueryException
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** File access shared by the skill packs: only the workspace and picked files, never anything else. */
internal object SkillFiles {
    const val NOT_ALLOWED =
        "MySI can only open files the user picked (pick_files) or files MySI made (list_files)."

    /** A readable file the model named, or null when it is outside the sandbox or missing. */
    fun file(ctx: Context, path: String): File? = Storage.resolve(ctx, path)?.takeIf { it.isFile }

    fun missing(path: String) = "No file at '${path.trim()}'. $NOT_ALLOWED"

    /** Paths separated by commas or new lines; a single path that contains a comma still works. */
    fun paths(ctx: Context, text: String): List<String> {
        val whole = text.trim()
        if (whole.isNotEmpty() && Storage.resolve(ctx, whole)?.exists() == true) return listOf(whole)
        return text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Opens a document, initialising PdfBox's font data on first use. */
    fun open(ctx: Context, file: File): DocSource {
        if (!PDFBoxResourceLoader.isReady()) PDFBoxResourceLoader.init(ctx.applicationContext)
        return Documents.open(file, ctx.cacheDir)
    }

    fun documentsDir(ctx: Context) = File(Storage.workspace(ctx), "Documents").apply { mkdirs() }

    /** Where a created file with this safe [name] goes; the model never chooses the folder. */
    fun output(ctx: Context, name: String) = File(documentsDir(ctx), name)

    /** Writes [bytes], publishes a copy to Download/MySI and says where both are. */
    fun save(ctx: Context, name: String, bytes: ByteArray, what: String): String {
        val f = output(ctx, name)
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) {
            tmp.delete()
            throw IOException("Could not save ${f.name}.")
        }
        val shown = runCatching { Storage.publish(ctx, f) }.getOrNull()
        return "$what saved: ${f.absolutePath}" + (shown?.let { "; the user can find it in $it" } ?: "")
    }

    fun overwriteQuestion(ctx: Context, name: String): String? =
        output(ctx, name).takeIf { it.isFile }?.let { "Replace the existing ${it.name}?" }

    /** Runs blocking work off the main thread and turns expected failures into plain answers. */
    suspend fun io(block: () -> String): String =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: IOException) {
                e.message ?: "Could not read the file."
            } catch (e: IllegalArgumentException) {
                e.message ?: "Invalid input."
            } catch (e: QueryException) {
                e.message ?: "Could not run the query."
            } catch (e: CalcException) {
                "Could not calculate: ${e.message}"
            } catch (e: DateException) {
                e.message ?: "Could not understand the date."
            } catch (e: OutOfMemoryError) {
                "The file is too large for this phone's memory."
            }
        }
}
