package com.mysi.agent.skills

import android.content.Context
import com.mysi.agent.Param
import com.mysi.agent.Skill
import com.mysi.agent.ToolDef
import com.mysi.agent.skills.core.Csv
import com.mysi.agent.skills.core.Documents
import com.mysi.agent.skills.core.DocxWriter
import com.mysi.agent.skills.core.FileNames
import com.mysi.agent.skills.core.SheetText
import com.mysi.agent.skills.core.XlsxWriter

/** Reads PDF, Word, Excel, PowerPoint, CSV, Markdown, text and HTML; writes Word, Excel, CSV and Markdown. */
object DocumentsSkill {
    private const val MAX_INPUT = 200_000

    val skill = Skill(
        name = "documents",
        description = "Read, search and create documents: PDF, Word, Excel, PowerPoint, CSV, Markdown, text and web page files.",
        keywords = listOf(
            "document", "pdf", "word", "docx", "excel", "xlsx", "spreadsheet", "powerpoint", "pptx", "slides", "csv",
            "markdown", "file", "page", "read", "summarise", "summarize", "report", "letter", "contract", "resume", "invoice",
            "save", "create",
        ),
        instructions = """
            Documents: get a path from pick_files or list_files first. For a file you have not seen, call document_info, then read_document one page at a time; never describe content you have not read.
            In long files use search_document to find the right page, then read it. Mention page or slide numbers in answers.
            For totals, averages or filters over Excel or CSV data, use the data analyst tools instead of reading rows.
            To write a file: create_docx for Word, create_xlsx for Excel, create_csv, create_markdown (create_pdf makes a PDF). Copies appear in Download/MySI.
        """.trimIndent(),
        tools = ::tools,
    )

    private fun tools(ctx: Context): List<ToolDef> = listOf(
        ToolDef(
            "read_document",
            "Read a PDF, Word, Excel, PowerPoint, CSV, Markdown, text or HTML file one page at a time. Example: read_document(path, page=2) reads page or slide 2; use the offset it gives to continue a long page.",
            listOf(
                Param("path", "string", "File path from pick_files or list_files"),
                Param("page", "integer", "Page, slide or part number, starting at 1. Default 1", false),
                Param("offset", "integer", "Character offset inside the page, from the previous result. Default 0", false),
            ),
            privateData = true,
        ) { a ->
            SkillFiles.io {
                val f = SkillFiles.file(ctx, a.str("path")) ?: return@io SkillFiles.missing(a.str("path"))
                SkillFiles.open(ctx, f).use { d -> Documents.read(f.name, d, a.int("page", 1), a.int("offset", 0)) }
            }
        },
        ToolDef(
            "document_info",
            "Describe a document before reading it: its type, number of pages, slides or sheets, size and title. Example: document_info(path) on a PDF the user just picked.",
            listOf(Param("path", "string", "File path from pick_files or list_files")),
            privateData = true,
        ) { a ->
            SkillFiles.io {
                val f = SkillFiles.file(ctx, a.str("path")) ?: return@io SkillFiles.missing(a.str("path"))
                SkillFiles.open(ctx, f).use { d -> Documents.info(f, d) }
            }
        },
        ToolDef(
            "search_document",
            "Find where a word or phrase appears in one document and return the matching passages with their page numbers. Example: search_document(path, query='termination notice').",
            listOf(
                Param("path", "string", "File path from pick_files or list_files"),
                Param("query", "string", "Words or phrase to find"),
            ),
            privateData = true,
        ) { a ->
            SkillFiles.io {
                val f = SkillFiles.file(ctx, a.str("path")) ?: return@io SkillFiles.missing(a.str("path"))
                val q = a.str("query").trim()
                if (q.isEmpty()) return@io "Give the words to search for."
                SkillFiles.open(ctx, f).use { d ->
                    val (hits, ranked) = Documents.search(d, q)
                    val searched = minOf(d.partCount, 400)
                    val note = if (d.partCount > searched) " (searched the first $searched of ${d.partCount} ${d.type.partKind}s)" else ""
                    when {
                        hits.isEmpty() -> "No match for '$q' in ${f.name}$note."
                        else -> (if (ranked) "No exact match for '$q'; closest passages in ${f.name}$note:\n" else "Matches for '$q' in ${f.name}$note:\n") +
                            hits.joinToString("\n") { "- ${it.location} (read_document page=${it.page}): ${it.snippet}" }
                    }
                }
            }
        },
        ToolDef(
            "create_docx",
            "Create a Word document (.docx) with headings, lists and tables from text. Example: create_docx(title='Leave letter', body='Dear Sir,\n\nI request leave from 12 to 14 November.').",
            listOf(
                Param("title", "string", "Document title, shown at the top"),
                Param("body", "string", "Full text with blank lines between paragraphs; lines starting # are headings, - bullets, 1. numbered steps and | table rows; **bold** works"),
                Param("filename", "string", "File name without extension. Default: the title", false),
            ),
            confirm = { a -> SkillFiles.overwriteQuestion(ctx, FileNames.safe(a.str("filename"), a.str("title"), "docx")) },
        ) { a ->
            SkillFiles.io {
                val body = a.str("body")
                if (body.isBlank() && a.str("title").isBlank()) return@io "Give the document a title or some text."
                if (body.length > MAX_INPUT) return@io "The text is too long for one document; split it into parts."
                val name = FileNames.safe(a.str("filename"), a.str("title"), "docx")
                SkillFiles.save(ctx, name, DocxWriter.write(a.str("title"), body), "Word document")
            }
        },
        ToolDef(
            "create_xlsx",
            "Create an Excel workbook (.xlsx) with one or more sheets of rows. Example: create_xlsx(filename='budget', sheets='## Sales\nMonth,Amount\nJan,1200\nFeb,950').",
            listOf(
                Param("filename", "string", "File name without extension"),
                Param("sheets", "string", "Each sheet is a line '## Sheet name' then comma-separated rows, header first; numbers, YYYY-MM-DD dates and =SUM(B2:B9) formulas become real cells"),
            ),
            confirm = { a -> SkillFiles.overwriteQuestion(ctx, FileNames.safe(a.str("filename"), "workbook", "xlsx")) },
        ) { a ->
            SkillFiles.io {
                val text = a.str("sheets")
                if (text.length > MAX_INPUT) return@io "The data is too long for one call; write fewer rows."
                val sheets = SheetText.parse(text).filter { it.rows.isNotEmpty() }
                if (sheets.isEmpty()) return@io "No rows found. Write '## Sheet name' and then comma-separated rows."
                val name = FileNames.safe(a.str("filename"), "workbook", "xlsx")
                val summary = sheets.joinToString(", ") { "${it.name} (${it.rows.size} rows)" }
                SkillFiles.save(ctx, name, XlsxWriter.write(sheets, name.removeSuffix(".xlsx")), "Excel workbook with $summary")
            }
        },
        ToolDef(
            "create_csv",
            "Save rows as a .csv file that Excel and Google Sheets open. Example: create_csv(filename='contacts', content='Name,Phone\nAsha,98450 12345').",
            listOf(
                Param("filename", "string", "File name without extension"),
                Param("content", "string", "Rows, one per line, values separated by commas (tabs also work)"),
            ),
            confirm = { a -> SkillFiles.overwriteQuestion(ctx, FileNames.safe(a.str("filename"), "table", "csv")) },
        ) { a ->
            SkillFiles.io {
                val text = a.str("content")
                if (text.length > MAX_INPUT) return@io "The data is too long for one call; write fewer rows."
                val rows = Csv.parse(text).rows
                if (rows.isEmpty()) return@io "No rows to save."
                val width = rows.maxOf { it.size }
                val ragged = rows.count { it.size != width }
                val name = FileNames.safe(a.str("filename"), "table", "csv")
                // A byte-order mark lets Excel read non-English text in UTF-8 correctly.
                val bytes = (Csv.BOM.toString() + Csv.write(rows)).toByteArray(Charsets.UTF_8)
                SkillFiles.save(ctx, name, bytes, "CSV with ${rows.size} rows and $width columns") +
                    if (ragged > 0) ". Note: $ragged rows have a different number of values than the widest row." else ""
            }
        },
        ToolDef(
            "create_markdown",
            "Save Markdown text as a .md file, e.g. meeting notes, a checklist or a README. Example: create_markdown(filename='trip plan', content='# Goa trip\n- Book train').",
            listOf(
                Param("filename", "string", "File name without extension"),
                Param("content", "string", "Markdown text"),
            ),
            confirm = { a -> SkillFiles.overwriteQuestion(ctx, FileNames.safe(a.str("filename"), "notes", "md")) },
        ) { a ->
            SkillFiles.io {
                val text = a.str("content")
                if (text.isBlank()) return@io "There is no text to save."
                if (text.length > MAX_INPUT) return@io "The text is too long for one file."
                val name = FileNames.safe(a.str("filename"), "notes", "md")
                val body = text.replace("\r\n", "\n").trimEnd() + "\n"
                SkillFiles.save(ctx, name, body.toByteArray(Charsets.UTF_8), "Markdown file")
            }
        },
    )
}
