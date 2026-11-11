package com.mysi.agent.skills

import android.content.Context
import com.mysi.agent.Param
import com.mysi.agent.Skill
import com.mysi.agent.ToolDef
import com.mysi.agent.skills.core.Calculator
import com.mysi.agent.skills.core.Stats
import com.mysi.agent.skills.core.TableLoader
import com.mysi.agent.skills.core.TableQuery

/** Exact table analysis over CSV and Excel files, and an exact calculator. */
object DataSkill {
    val skill = Skill(
        name = "data_analyst",
        description = "Analyse tables in CSV and Excel files (filter, group, totals, averages, statistics) and do exact arithmetic with a calculator.",
        keywords = listOf(
            "calculate", "calculator", "sum", "total", "average", "mean", "median", "percent", "percentage", "table", "csv",
            "excel", "spreadsheet", "column", "rows", "statistics", "group", "filter", "count", "how many", "how much",
            "math", "interest", "emi", "budget", "expenses", "sales", "profit",
        ),
        instructions = """
            Data analyst: never do arithmetic yourself; use calculate for every number you work out, and table_query or table_stats for numbers from a file.
            Call table_info first to learn the exact column names and the sheet names.
            table_query: where = Region = North and Units > 5 (also != >= <= contains, starts with, ends with, in (a, b), is empty, or, not, brackets; quote names or values that have spaces: "Unit Price" > 5, City = 'New Delhi').
            aggregate = sum(Units), avg(Price), count(*), min, max, median, count_distinct, with optional "as name"; group_by = Region; sort = sum(Units) desc; limit = 20.
        """.trimIndent(),
        tools = ::tools,
    )

    private fun tools(ctx: Context): List<ToolDef> = listOf(
        ToolDef(
            "table_info",
            "Show a CSV or Excel table's columns with their types and ranges, the row count, the sheet names and the first rows. Example: table_info(path, sheet='Sales').",
            listOf(
                Param("path", "string", "CSV, TSV or .xlsx path from pick_files or list_files"),
                Param("sheet", "string", "Excel sheet name or number. Default: the first sheet with data", false),
            ),
            privateData = true,
        ) { a ->
            SkillFiles.io {
                val f = SkillFiles.file(ctx, a.str("path")) ?: return@io SkillFiles.missing(a.str("path"))
                Stats.info(TableLoader.load(f, a.str("sheet").ifBlank { null }))
            }
        },
        ToolDef(
            "table_query",
            "Filter, group, total and sort a CSV or Excel table exactly. Example: table_query(path, where='Region = North', group_by='Month', aggregate='sum(Amount)', sort='sum(Amount) desc').",
            listOf(
                Param("path", "string", "CSV, TSV or .xlsx path"),
                Param("sheet", "string", "Excel sheet name or number", false),
                Param("select", "string", "Columns to show, separated by commas. Default: all", false),
                Param("where", "string", "Filter, e.g. Units > 5 and Region = North", false),
                Param("group_by", "string", "Columns to group by, separated by commas", false),
                Param("aggregate", "string", "e.g. sum(Units), avg(Price), count(*)", false),
                Param("sort", "string", "e.g. Units desc", false),
                Param("limit", "integer", "Rows to return, 1-200. Default 20", false),
            ),
            privateData = true,
        ) { a ->
            SkillFiles.io {
                val f = SkillFiles.file(ctx, a.str("path")) ?: return@io SkillFiles.missing(a.str("path"))
                val table = TableLoader.load(f, a.str("sheet").ifBlank { null })
                val grouped = a.str("aggregate").isNotBlank() || a.str("group_by").isNotBlank()
                val r = TableQuery.run(table, a.str("select"), a.str("where"), a.str("group_by"), a.str("aggregate"), a.str("sort"), a.int("limit", 20))
                TableQuery.format(r, grouped)
            }
        },
        ToolDef(
            "table_stats",
            "Exact statistics for one column of a CSV or Excel table: count, sum, mean, median, min, max, standard deviation and most common values. Example: table_stats(path, column='Amount').",
            listOf(
                Param("path", "string", "CSV, TSV or .xlsx path"),
                Param("column", "string", "Column name from table_info"),
                Param("sheet", "string", "Excel sheet name or number", false),
            ),
            privateData = true,
        ) { a ->
            SkillFiles.io {
                val f = SkillFiles.file(ctx, a.str("path")) ?: return@io SkillFiles.missing(a.str("path"))
                Stats.describe(TableLoader.load(f, a.str("sheet").ifBlank { null }), a.str("column"))
            }
        },
        ToolDef(
            "calculate",
            "Exact calculator; use it for every sum instead of doing arithmetic yourself. Supports + - * / ^ brackets, %, sqrt, round(x, 2), min, max, avg; '200 + 15%' adds 15% of 200 (= 230) and '15% of 200' = 30.",
            listOf(Param("expression", "string", "e.g. (1250 * 12) * 18% or round(100000 / 7, 2)")),
        ) { a ->
            SkillFiles.io {
                val e = a.str("expression")
                "${e.trim()} = ${Calculator.evaluate(e).text()}"
            }
        },
    )
}
