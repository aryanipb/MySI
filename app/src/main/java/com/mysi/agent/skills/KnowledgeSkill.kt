package com.mysi.agent.skills

import android.content.Context
import com.mysi.agent.Param
import com.mysi.agent.Skill
import com.mysi.agent.Storage
import com.mysi.agent.ToolDef
import com.mysi.agent.skills.core.Documents
import com.mysi.agent.skills.core.KnowledgeBase
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date

/** A local, searchable index of the user's documents, stored in SQLite on the phone. */
object KnowledgeSkill {
    private const val MAX_FILES_PER_CALL = 50

    val skill = Skill(
        name = "knowledge_base",
        description = "Search the user's own documents that were added to MySI's local knowledge base, and answer with quoted sources.",
        keywords = listOf(
            "knowledge base", "my documents", "my files", "my notes", "index", "search my", "sources", "cite", "according to",
            "handbook", "manual", "policy", "notes", "library",
        ),
        instructions = """
            Knowledge base: kb_add indexes files once (paths from pick_files or list_files); after that kb_search finds passages across all of them, even after the picked copies expire.
            Answer only from passages kb_search returns and cite each fact as [document, location]. If nothing relevant comes back, say you could not find it instead of guessing.
            Use kb_list to see what is indexed.
        """.trimIndent(),
        tools = ::tools,
    )

    private fun kb(ctx: Context) = KnowledgeBase(SqlKbStore(SkillDb.get(ctx)))

    private fun tools(ctx: Context): List<ToolDef> = listOf(
        ToolDef(
            "kb_add",
            "Add documents to the local knowledge base so they can be searched later; adding a file again updates it. Example: kb_add(paths='/path/a.pdf, /path/b.docx') with paths from pick_files.",
            listOf(Param("paths", "string", "File or workspace folder paths, separated by commas or new lines")),
            privateData = true,
        ) { a -> SkillFiles.io { add(ctx, a.str("paths")) } },
        ToolDef(
            "kb_list",
            "List the documents in the local knowledge base with their size and when they were added.",
            privateData = true,
        ) {
            SkillFiles.io {
                val docs = kb(ctx).list()
                if (docs.isEmpty()) "The knowledge base is empty. Add files with kb_add."
                else {
                    val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM)
                    "${docs.size} document${if (docs.size == 1) "" else "s"}:\n" +
                        docs.take(100).joinToString("\n") { "- ${it.name} (${it.type}, ${it.chunks} passages, added ${fmt.format(Date(it.added))})" }
                }
            }
        },
        ToolDef(
            "kb_remove",
            "Remove a document from the local knowledge base by its name as kb_list shows it. The original file is not touched.",
            listOf(Param("name", "string", "Document name from kb_list")),
            confirm = { a -> "Remove ${a.str("name").trim()} from the knowledge base?" },
        ) { a ->
            SkillFiles.io {
                val removed = kb(ctx).remove(a.str("name").trim())
                if (removed != null) "Removed $removed from the knowledge base." else "No single document matches '${a.str("name")}'. Use kb_list for exact names."
            }
        },
        ToolDef(
            "kb_search",
            "Search every document in the local knowledge base and return the best passages with document name and page. Example: kb_search(query='notice period for resignation').",
            listOf(
                Param("query", "string", "What to look for, in a few words"),
                Param("count", "integer", "How many passages, 1-10. Default 5", false),
            ),
            privateData = true,
        ) { a ->
            SkillFiles.io {
                val q = a.str("query").trim()
                if (q.isEmpty()) return@io "Give some words to search for."
                KnowledgeBase.format(kb(ctx).search(q, a.int("count", 5)))
            }
        },
    )

    private fun add(ctx: Context, paths: String): String {
        val wanted = SkillFiles.paths(ctx, paths)
        if (wanted.isEmpty()) return "Give at least one file path from pick_files or list_files."
        val files = mutableListOf<File>()
        val problems = mutableListOf<String>()
        for (p in wanted) {
            val f = Storage.resolve(ctx, p)
            when {
                f == null || !f.exists() -> problems += SkillFiles.missing(p)
                f.isDirectory -> files += f.walkTopDown().maxDepth(4).filter { it.isFile && it.extension.lowercase() in Documents.supportedExtensions }.take(MAX_FILES_PER_CALL)
                else -> files += f
            }
        }
        val kb = kb(ctx)
        val lines = mutableListOf<String>()
        for (f in files.distinct().take(MAX_FILES_PER_CALL)) {
            lines += try {
                SkillFiles.open(ctx, f).use { d ->
                    val parts = (0 until d.partCount).map { d.part(it) }
                    val r = kb.add(f.name, f.absolutePath, d.type, parts)
                    "${if (r.replaced) "Updated" else "Added"} ${r.name}: ${r.chunks} passages" +
                        (d.warnings.firstOrNull()?.let { " ($it)" } ?: "")
                }
            } catch (e: IOException) {
                "Skipped ${f.name}: ${e.message}"
            } catch (e: IllegalArgumentException) {
                "Skipped ${f.name}: ${e.message}"
            }
        }
        if (files.size > MAX_FILES_PER_CALL) lines += "Only the first $MAX_FILES_PER_CALL files were added; call kb_add again for the rest."
        return (lines + problems).joinToString("\n").ifEmpty { "No supported documents found." }
    }
}
