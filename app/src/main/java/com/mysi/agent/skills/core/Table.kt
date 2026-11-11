package com.mysi.agent.skills.core

import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeParseException

class QueryException(message: String) : Exception(message)

/** A loaded table: [columns] from the first row, every row padded to the same width. */
class Table(val name: String, val columns: List<String>, val rows: List<List<String>>, val warnings: List<String> = emptyList(), val sheets: List<String> = emptyList()) {
    /** Index of a column by name, ignoring case and surrounding spaces; null when unknown. */
    fun column(name: String): Int? {
        val n = name.trim()
        return columns.indexOfFirst { it.equals(n, true) }.takeIf { it >= 0 }
            ?: columns.indexOfFirst { it.replace(" ", "").equals(n.replace(" ", ""), true) }.takeIf { it >= 0 }
    }

    fun requireColumn(name: String): Int =
        column(name) ?: throw QueryException("Unknown column '${name.trim()}'. Columns: ${columns.joinToString(", ")}.")

    companion object {
        /** First non-empty row is the header; blank names become "column N", repeats get "(2)". */
        fun fromRows(name: String, raw: List<List<String>>, warnings: List<String> = emptyList(), sheets: List<String> = emptyList()): Table {
            val nonEmpty = raw.dropWhile { r -> r.all { it.isBlank() } }
            if (nonEmpty.isEmpty()) return Table(name, emptyList(), emptyList(), warnings, sheets)
            val width = nonEmpty.maxOf { it.size }
            val seen = HashMap<String, Int>()
            val header = (0 until width).map { i ->
                val base = nonEmpty[0].getOrElse(i) { "" }.trim().replace(Regex("\\s+"), " ").ifEmpty { "column ${i + 1}" }
                val n = (seen[base.lowercase()] ?: 0) + 1
                seen[base.lowercase()] = n
                if (n == 1) base else "$base ($n)"
            }
            val rows = nonEmpty.drop(1).filter { r -> r.any { it.isNotBlank() } }.map { r -> List(width) { i -> r.getOrElse(i) { "" }.trim() } }
            return Table(name, header, rows, warnings, sheets)
        }
    }
}

enum class ColType(val label: String) { NUMBER("number"), DATE("date"), BOOLEAN("true/false"), TEXT("text"), EMPTY("empty") }

/** Reading cell text as numbers, dates and booleans the way people type them. */
object Values {
    private val grouped = Regex("^\\d{1,3}(,\\d{3})+(\\.\\d+)?$|^\\d{1,2}(,\\d{2})+,\\d{3}(\\.\\d+)?$")
    private val plain = Regex("^(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d{1,3})?$")
    private val currency = setOf('$', Char(0x20AC), Char(0x00A3), Char(0x20B9), Char(0x00A5))

    /** "1,234.5", "(1,200)", "$12", "12,50,000", "-3.5e2", "12%" (as 12); null for text. */
    fun number(raw: String): BigDecimal? {
        var s = raw.trim()
        if (s.isEmpty() || s.length > 60) return null
        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) { negative = true; s = s.substring(1, s.length - 1).trim() }
        if (s.startsWith("-") || s.startsWith("+")) { negative = negative xor (s[0] == '-'); s = s.substring(1).trim() }
        if (s.isNotEmpty() && s[0] in currency) s = s.substring(1).trim()
        if (s.isNotEmpty() && s.last() in currency) s = s.dropLast(1).trim()
        if (s.endsWith("%")) s = s.dropLast(1).trim()
        if (s.startsWith("-")) { negative = !negative; s = s.substring(1) }
        val digits = when {
            plain.matches(s) -> s
            grouped.matches(s) -> s.replace(",", "")
            else -> return null
        }
        val v = runCatching { BigDecimal(digits) }.getOrNull() ?: return null
        return if (negative) v.negate() else v
    }

    /** ISO dates and date-times (also with "/" separators); day-first or month-first text is not guessed. */
    fun date(raw: String): LocalDateTime? {
        val s = raw.trim().replace('/', '-')
        if (s.length < 8 || s.length > 25 || !s[0].isDigit()) return null
        val sep = s.indexOfAny(charArrayOf(' ', 'T'))
        val day = if (sep < 0) s else s.substring(0, sep)
        val time = if (sep < 0) "" else s.substring(sep + 1).trim()
        return try {
            val d = LocalDate.parse(normalizeDate(day))
            if (time.isEmpty()) d.atStartOfDay() else d.atTime(LocalTime.parse(time))
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** 2024-1-5 -> 2024-01-05 so java.time accepts it. */
    private fun normalizeDate(s: String): String {
        val p = s.split('-')
        if (p.size != 3 || p[0].length != 4) return s
        return p[0] + "-" + p[1].padStart(2, '0') + "-" + p[2].padStart(2, '0')
    }

    fun bool(raw: String): Boolean? =
        when (raw.trim().lowercase()) {
            "true", "yes", "y" -> true
            "false", "no", "n" -> false
            else -> null
        }

    /** The type that at least 95% of non-empty cells have; text otherwise. */
    fun infer(values: List<String>): ColType {
        val filled = values.filter { it.isNotBlank() }
        if (filled.isEmpty()) return ColType.EMPTY
        val need = (filled.size * 0.95).let { kotlin.math.ceil(it).toInt() }.coerceAtLeast(1)
        if (filled.count { number(it) != null } >= need) return ColType.NUMBER
        if (filled.count { date(it) != null } >= need) return ColType.DATE
        if (filled.all { bool(it) != null }) return ColType.BOOLEAN
        return ColType.TEXT
    }

    /** Plain decimal text without trailing zeros, at most 10 decimal places. */
    fun show(v: BigDecimal): String {
        var r = v
        if (r.scale() > 10) r = r.setScale(10, RoundingMode.HALF_EVEN)
        r = r.stripTrailingZeros()
        return if (r.signum() == 0) "0" else r.toPlainString()
    }

    fun showDate(d: LocalDateTime): String =
        if (d.toLocalTime().toSecondOfDay() == 0) d.toLocalDate().toString() else d.toString().replace('T', ' ')
}

object TableLoader {
    /** Loads CSV, TSV or one sheet of an XLSX ([sheet] by name or 1-based number; first non-empty by default). */
    fun load(file: File, sheet: String?, limits: ReadLimits = ReadLimits()): Table {
        if (!file.isFile) throw IOException("No such file: ${file.name}")
        return when (Documents.detect(file)) {
            DocType.XLSX -> xlsx(file, sheet, limits)
            DocType.CSV, DocType.TSV, DocType.TEXT -> {
                val (bytes, cut) = TextFiles.readCapped(file, limits.maxTextBytes)
                val delimiter = if (file.extension.equals("tsv", true) || file.extension.equals("tab", true)) '\t' else null
                val p = Csv.parse(TextFiles.decode(bytes), delimiter, limits.maxRows, limits.maxCells, limits.maxColumns)
                val warnings = buildList {
                    if (cut) add("Only the first ${limits.maxTextBytes / (1024 * 1024)} MB of the file was read.")
                    if (p.truncated) add("Only the first ${p.rows.size} rows were loaded.")
                }
                Table.fromRows(file.name, p.rows, warnings)
            }
            else -> throw IOException("${file.name} is not a table. Use a CSV, TSV or Excel (.xlsx) file.")
        }
    }

    private fun xlsx(file: File, sheet: String?, limits: ReadLimits): Table {
        val (zip, reader) = XlsxReader.open(file, limits)
        zip.use {
            val all = reader.sheets
            if (all.isEmpty()) throw IOException("${file.name} has no worksheets.")
            val names = all.map { it.name }
            val wanted = sheet?.trim().orEmpty()
            val chosen = when {
                wanted.isEmpty() -> null
                else -> all.firstOrNull { it.name.equals(wanted, true) }
                    ?: wanted.toIntOrNull()?.let { all.getOrNull(it - 1) }
                    ?: throw IOException("No sheet named '$wanted'. Sheets: ${names.joinToString(", ")}.")
            }
            val candidates = if (chosen != null) listOf(chosen) else all
            for (s in candidates) {
                val g = reader.grid(s)
                if (g.rows.isEmpty() && chosen == null) continue
                val warnings = if (g.truncated) listOf("Sheet '${s.name}' is larger than MySI reads; only part of it was loaded.") else emptyList()
                return Table.fromRows(s.name, g.rows, warnings, names)
            }
            return Table(all.first().name, emptyList(), emptyList(), emptyList(), names)
        }
    }
}

/**
 * The query language for table_query. Every parameter is parsed into a small syntax tree and
 * run in Kotlin; nothing is ever passed to SQL or evaluated as code.
 *
 * where:     Region = North and (Units > 5 or Paid = true)
 *            operators = != > >= < <=, contains, starts with, ends with, in (a, b), is empty,
 *            is not empty; and / or / not with brackets. Quote names or values with spaces
 *            or keywords: "Unit Price" > 5, City = 'New Delhi'.
 * aggregate: sum(Units), avg(Price), count(*), count(Region), min, max, median, count_distinct;
 *            "as name" renames a result column.
 * group_by:  Region, Year
 * select:    Region, Units (default: every column)
 * sort:      sum(Units) desc, Region
 */
object TableQuery {
    data class Result(val header: List<String>, val rows: List<List<String>>, val matched: Int, val total: Int)

    private val mc = MathContext(28, RoundingMode.HALF_EVEN)

    fun run(table: Table, select: String, where: String, groupBy: String, aggregate: String, sort: String, limit: Int): Result {
        if (table.columns.isEmpty()) throw QueryException("The table is empty.")
        val filter = if (where.isBlank()) null else WhereParser(table, where).parse()
        val matched = table.rows.filter { r -> filter?.invoke(r) ?: true }
        val groups = splitList(groupBy).map { table.requireColumn(it) }
        val aggs = splitList(aggregate).map { parseAggregate(table, it) }.ifEmpty { if (groups.isNotEmpty()) listOf(Agg("count", null, "count")) else emptyList() }
        val (header, rows) = if (aggs.isNotEmpty()) {
            val keyed = LinkedHashMap<List<String>, MutableList<List<String>>>()
            for (r in matched) keyed.getOrPut(groups.map { r[it] }) { mutableListOf() }.add(r)
            if (groups.isEmpty() && keyed.isEmpty()) keyed[emptyList()] = mutableListOf()
            val h = groups.map { table.columns[it] } + aggs.map { it.label }
            h to keyed.map { (key, members) -> key + aggs.map { compute(it, members) } }
        } else {
            val cols = splitList(select).let { s -> if (s.isEmpty() || s == listOf("*")) table.columns.indices.toList() else s.map { table.requireColumn(it) } }
            cols.map { table.columns[it] } to matched.map { r -> cols.map { r[it] } }
        }
        val sorted = sortRows(header, rows, sort)
        val n = limit.coerceIn(1, MAX_LIMIT)
        return Result(header, sorted.take(n), sorted.size.takeIf { aggs.isNotEmpty() } ?: matched.size, table.rows.size)
    }

    const val MAX_LIMIT = 200

    /** Compact text for the model; stops before [maxChars]. */
    fun format(r: Result, grouped: Boolean, maxChars: Int = Budget.MAX - 500): String {
        val sb = StringBuilder()
        if (grouped) sb.append("${r.matched} result row${if (r.matched == 1) "" else "s"}")
        else sb.append("${r.matched} of ${r.total} rows match")
        // A very wide table's header alone could fill the budget; keep it to a third and say how to narrow it.
        val fullHeader = r.header.joinToString(" | ")
        val header = if (fullHeader.length <= maxChars / 3) fullHeader
        else fullHeader.take(maxChars / 3).substringBeforeLast(" | ") + " | … (${r.header.size} columns; use select to show fewer)"
        val body = StringBuilder(header).append('\n')
        var shown = 0
        for (row in r.rows) {
            val line = row.joinToString(" | ") { it.replace('\n', ' ').take(120) } + "\n"
            if (body.length + line.length > maxChars) break
            body.append(line)
            shown++
        }
        sb.append(if (shown < r.matched) "; showing $shown:\n" else ":\n")
        return (sb.toString() + body).trimEnd()
    }

    private class Agg(val fn: String, val column: Int?, val label: String)

    private val aggPattern = Regex("^([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(\\s*(.*?)\\s*\\)\\s*(?:as\\s+(.+))?$", RegexOption.IGNORE_CASE)
    private val aggNames = mapOf(
        "count" to "count", "sum" to "sum", "total" to "sum", "avg" to "avg", "mean" to "avg", "average" to "avg",
        "min" to "min", "max" to "max", "median" to "median", "count_distinct" to "count_distinct", "distinct" to "count_distinct",
    )

    private fun parseAggregate(table: Table, text: String): Agg {
        val m = aggPattern.find(text.trim()) ?: throw QueryException("Aggregate '$text' should look like sum(Column) or count(*).")
        val fn = aggNames[m.groupValues[1].lowercase()] ?: throw QueryException("Unknown aggregate '${m.groupValues[1]}'. Use count, sum, avg, min, max, median or count_distinct.")
        val arg = unquote(m.groupValues[2])
        val col = if (arg == "*" || arg.isEmpty()) {
            if (fn != "count") throw QueryException("$fn needs a column, e.g. $fn(Price).")
            null
        } else table.requireColumn(arg)
        val label = m.groupValues[3].trim().let(::unquote).ifEmpty { "$fn(${col?.let { table.columns[it] } ?: "*"})" }
        return Agg(fn, col, label)
    }

    private fun compute(a: Agg, rows: List<List<String>>): String {
        if (a.column == null) return rows.size.toString()
        val cells = rows.map { it[a.column] }.filter { it.isNotBlank() }
        return when (a.fn) {
            "count" -> cells.size.toString()
            "count_distinct" -> cells.map { it.lowercase() }.toSet().size.toString()
            else -> {
                val nums = cells.mapNotNull { Values.number(it) }
                when (a.fn) {
                    "sum" -> Values.show(nums.fold(BigDecimal.ZERO, BigDecimal::add))
                    "avg" -> if (nums.isEmpty()) "" else Values.show(nums.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(nums.size), mc))
                    "median" -> if (nums.isEmpty()) "" else Values.show(Stats.median(nums))
                    "min", "max" -> extreme(cells, nums, a.fn == "max")
                    else -> ""
                }
            }
        }
    }

    /** Min or max as numbers, else as dates, else alphabetically. */
    private fun extreme(cells: List<String>, nums: List<BigDecimal>, max: Boolean): String {
        if (cells.isEmpty()) return ""
        if (nums.size == cells.size) return Values.show(if (max) nums.max() else nums.min())
        val dates = cells.mapNotNull { Values.date(it) }
        if (dates.size == cells.size) return Values.showDate(if (max) dates.max() else dates.min())
        return if (max) cells.maxWith(String.CASE_INSENSITIVE_ORDER) else cells.minWith(String.CASE_INSENSITIVE_ORDER)
    }

    private fun sortRows(header: List<String>, rows: List<List<String>>, sort: String): List<List<String>> {
        val keys = splitList(sort).map { s ->
            val m = Regex("^(.*?)(?:\\s+(asc|desc|ascending|descending))?$", RegexOption.IGNORE_CASE).find(s.trim())!!
            val name = unquote(m.groupValues[1])
            val idx = header.indexOfFirst { it.equals(name, true) }.takeIf { it >= 0 }
                ?: header.indexOfFirst { it.replace(" ", "").equals(name.replace(" ", ""), true) }.takeIf { it >= 0 }
                ?: throw QueryException("Cannot sort by '$name'. Result columns: ${header.joinToString(", ")}.")
            idx to m.groupValues[2].lowercase().startsWith("desc")
        }
        if (keys.isEmpty()) return rows
        val cmp = Comparator<List<String>> { a, b ->
            for ((i, desc) in keys) {
                val c = compareCells(a[i], b[i])
                if (c != 0) return@Comparator if (desc) -c else c
            }
            0
        }
        return rows.sortedWith(cmp)
    }

    /** Numbers by value, dates by time, empty cells last, text ignoring case. */
    fun compareCells(a: String, b: String): Int {
        if (a.isBlank() || b.isBlank()) return if (a.isBlank() && b.isBlank()) 0 else if (a.isBlank()) 1 else -1
        val na = Values.number(a)
        val nb = Values.number(b)
        if (na != null && nb != null) return na.compareTo(nb)
        val da = Values.date(a)
        val db = Values.date(b)
        if (da != null && db != null) return da.compareTo(db)
        return a.compareTo(b, ignoreCase = true)
    }

    /** Splits on commas outside quotes and brackets. */
    fun splitList(s: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var depth = 0
        var quote = 0.toChar()
        for (c in s) {
            when {
                quote != 0.toChar() -> { sb.append(c); if (c == quote) quote = 0.toChar() }
                c == '\'' || c == '"' || c == '`' -> { quote = c; sb.append(c) }
                c == '[' -> { quote = ']'; sb.append(c) }
                c == '(' -> { depth++; sb.append(c) }
                c == ')' -> { depth--; sb.append(c) }
                c == ',' && depth == 0 -> { out += sb.toString().trim(); sb.setLength(0) }
                else -> sb.append(c)
            }
        }
        out += sb.toString().trim()
        return out.filter { it.isNotEmpty() }
    }

    fun unquote(s: String): String {
        val t = s.trim()
        if (t.length >= 2 && ((t[0] == '"' && t.last() == '"') || (t[0] == '\'' && t.last() == '\'') || (t[0] == '`' && t.last() == '`') || (t[0] == '[' && t.last() == ']')))
            return t.substring(1, t.length - 1)
        return t
    }
}

/** Parses a where clause into a row predicate. */
private class WhereParser(private val table: Table, src: String) {
    private sealed class Tok {
        data class Word(val text: String) : Tok()
        data class Quoted(val text: String, val column: Boolean) : Tok()
        data class Op(val text: String) : Tok()
        object LParen : Tok()
        object RParen : Tok()
        object Comma : Tok()
    }

    private val toks: List<Tok> = lex(src)
    private var pos = 0
    private var depth = 0

    private fun pred(f: (List<String>) -> Boolean) = f

    fun parse(): (List<String>) -> Boolean {
        val e = or()
        if (pos < toks.size) throw QueryException("Could not understand the filter near '${describe(toks[pos])}'.")
        return e
    }

    private fun lex(s: String): List<Tok> {
        if (s.length > 1_000) throw QueryException("The filter is too long.")
        val out = mutableListOf<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '(' -> { out += Tok.LParen; i++ }
                c == ')' -> { out += Tok.RParen; i++ }
                c == ',' -> { out += Tok.Comma; i++ }
                c == '\'' || c == '"' || c == '`' || c == '[' -> {
                    val close = if (c == '[') ']' else c
                    val end = s.indexOf(close, i + 1)
                    if (end < 0) throw QueryException("A quote is not closed in the filter.")
                    out += Tok.Quoted(s.substring(i + 1, end), column = c == '`' || c == '[')
                    i = end + 1
                }
                c == '!' && s.startsWith("!=", i) -> { out += Tok.Op("!="); i += 2 }
                c == '<' && s.startsWith("<>", i) -> { out += Tok.Op("!="); i += 2 }
                c == '<' || c == '>' -> {
                    if (i + 1 < s.length && s[i + 1] == '=') { out += Tok.Op("$c="); i += 2 } else { out += Tok.Op(c.toString()); i++ }
                }
                c == '=' -> { out += Tok.Op("="); i += if (s.startsWith("==", i)) 2 else 1 }
                else -> {
                    var j = i
                    while (j < s.length && !s[j].isWhitespace() && s[j] !in "()<>=!,'\"`[") j++
                    out += Tok.Word(s.substring(i, j))
                    i = j
                }
            }
            if (out.size > 300) throw QueryException("The filter is too long.")
        }
        return out
    }

    private fun describe(t: Tok) = when (t) {
        is Tok.Word -> t.text
        is Tok.Quoted -> t.text
        is Tok.Op -> t.text
        Tok.LParen -> "("
        Tok.RParen -> ")"
        Tok.Comma -> ","
    }

    private fun isWord(vararg w: String) = (toks.getOrNull(pos) as? Tok.Word)?.text?.lowercase() in w

    private fun or(): (List<String>) -> Boolean {
        if (++depth > 40) throw QueryException("The filter is nested too deeply.")
        val parts = mutableListOf(and())
        while (isWord("or")) { pos++; parts += and() }
        depth--
        return if (parts.size == 1) parts[0] else pred { r -> parts.any { it(r) } }
    }

    private fun and(): (List<String>) -> Boolean {
        val parts = mutableListOf(not())
        while (isWord("and")) { pos++; parts += not() }
        return if (parts.size == 1) parts[0] else pred { r -> parts.all { it(r) } }
    }

    private fun not(): (List<String>) -> Boolean {
        if (isWord("not")) {
            pos++
            val inner = not()
            return { r -> !inner(r) }
        }
        if (toks.getOrNull(pos) == Tok.LParen) {
            pos++
            val inner = or()
            if (toks.getOrNull(pos) != Tok.RParen) throw QueryException("A bracket is not closed in the filter.")
            pos++
            return inner
        }
        return comparison()
    }

    private val operatorWords = setOf("contains", "starts", "startswith", "ends", "endswith", "in", "is", "like")

    private fun comparison(): (List<String>) -> Boolean {
        // Column: quoted, or the words up to the operator (so Unit Price > 5 works unquoted).
        val col: Int
        val first = toks.getOrNull(pos) ?: throw QueryException("The filter ends too early.")
        if (first is Tok.Quoted) {
            pos++
            col = table.requireColumn(first.text)
        } else {
            val words = mutableListOf<String>()
            while (true) {
                val t = toks.getOrNull(pos)
                if (t is Tok.Word && (words.isEmpty() || t.text.lowercase() !in operatorWords)) { words += t.text; pos++ } else break
            }
            if (words.isEmpty()) throw QueryException("Expected a column name near '${describe(first)}'.")
            col = table.requireColumn(words.joinToString(" "))
        }
        val op = when (val t = toks.getOrNull(pos)) {
            is Tok.Op -> { pos++; t.text }
            is Tok.Word -> {
                pos++
                when (t.text.lowercase()) {
                    "contains", "like" -> "contains"
                    "startswith" -> "starts"
                    "endswith" -> "ends"
                    "starts", "ends" -> { if (isWord("with")) pos++; t.text.lowercase() }
                    "in" -> "in"
                    "is" -> if (isWord("not")) { pos++; if (!isWord("empty", "null", "blank")) throw QueryException("Use 'is empty' or 'is not empty'."); pos++; "notempty" } else { if (!isWord("empty", "null", "blank")) throw QueryException("Use 'is empty' or 'is not empty'."); pos++; "empty" }
                    else -> throw QueryException("Unknown operator '${t.text}'. Use = != > >= < <= contains, starts with, ends with, in (...), is empty.")
                }
            }
            else -> throw QueryException("Expected an operator after column '${table.columns[col]}'.")
        }
        return when (op) {
            "empty" -> pred { r -> r[col].isBlank() }
            "notempty" -> pred { r -> r[col].isNotBlank() }
            "in" -> {
                if (toks.getOrNull(pos) != Tok.LParen) throw QueryException("Write in like this: Region in (North, South).")
                pos++
                val values = mutableListOf<String>()
                while (true) {
                    values += value(stopAtComma = true)
                    when (toks.getOrNull(pos)) {
                        Tok.Comma -> pos++
                        Tok.RParen -> { pos++; break }
                        else -> throw QueryException("The list after 'in' is not closed with ')'.")
                    }
                }
                pred { r -> values.any { v -> equalsCell(r[col], v) } }
            }
            else -> {
                val v = value(stopAtComma = false)
                when (op) {
                    "=" -> pred { r -> equalsCell(r[col], v) }
                    "!=" -> pred { r -> !equalsCell(r[col], v) }
                    "contains" -> pred { r -> r[col].contains(v, ignoreCase = true) }
                    "starts" -> pred { r -> r[col].startsWith(v, ignoreCase = true) }
                    "ends" -> pred { r -> r[col].endsWith(v, ignoreCase = true) }
                    ">", ">=", "<", "<=" -> pred { r ->
                        val cell = r[col]
                        if (cell.isBlank()) false
                        else {
                            val c = TableQuery.compareCells(cell, v)
                            when (op) {
                                ">" -> c > 0
                                ">=" -> c >= 0
                                "<" -> c < 0
                                else -> c <= 0
                            }
                        }
                    }
                    else -> throw QueryException("Unknown operator '$op'.")
                }
            }
        }
    }

    /** A quoted value, or bare words up to and/or/a bracket. */
    private fun value(stopAtComma: Boolean): String {
        val t = toks.getOrNull(pos) ?: throw QueryException("A value is missing at the end of the filter.")
        if (t is Tok.Quoted) { pos++; return t.text }
        val words = mutableListOf<String>()
        while (true) {
            val w = toks.getOrNull(pos)
            if (w is Tok.Word && !(words.isNotEmpty() && w.text.lowercase() in setOf("and", "or"))) { words += w.text; pos++ } else break
            if (stopAtComma && toks.getOrNull(pos) == Tok.Comma) break
        }
        if (words.isEmpty()) throw QueryException("A value is missing near '${describe(t)}'.")
        return words.joinToString(" ")
    }

    private fun equalsCell(cell: String, v: String): Boolean {
        val a = Values.number(cell)
        val b = Values.number(v)
        if (a != null && b != null) return a.compareTo(b) == 0
        val da = Values.date(cell)
        val db = Values.date(v)
        if (da != null && db != null) return da == db
        val ba = Values.bool(cell)
        val bb = Values.bool(v)
        if (ba != null && bb != null && (cell.length > 1 || v.length > 1)) return ba == bb
        return cell.trim().equals(v.trim(), ignoreCase = true)
    }
}

/** Column statistics for table_stats and the median aggregate. */
object Stats {
    private val mc = MathContext(28, RoundingMode.HALF_EVEN)

    fun median(nums: List<BigDecimal>): BigDecimal {
        val s = nums.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else s[m - 1].add(s[m]).divide(BigDecimal(2), mc)
    }

    /** Linear-interpolated percentile (the "inclusive" method Excel's PERCENTILE uses). */
    fun percentile(sorted: List<BigDecimal>, p: Double): BigDecimal {
        if (sorted.size == 1) return sorted[0]
        val rank = BigDecimal(p).multiply(BigDecimal(sorted.size - 1), mc)
        val lo = rank.setScale(0, RoundingMode.FLOOR).toInt()
        val frac = rank.subtract(BigDecimal(lo))
        val hi = (lo + 1).coerceAtMost(sorted.size - 1)
        return sorted[lo].add(sorted[hi].subtract(sorted[lo]).multiply(frac, mc), mc)
    }

    fun describe(table: Table, column: String): String {
        val c = table.requireColumn(column)
        val cells = table.rows.map { it[c] }
        val filled = cells.filter { it.isNotBlank() }
        val type = Values.infer(cells)
        val sb = StringBuilder()
        sb.append("Column '${table.columns[c]}' in ${table.name}: ${type.label}\n")
        sb.append("Rows: ${cells.size}, filled: ${filled.size}, empty: ${cells.size - filled.size}, distinct: ${filled.map { it.lowercase() }.toSet().size}\n")
        when (type) {
            ColType.NUMBER -> {
                val nums = filled.mapNotNull { Values.number(it) }
                val skipped = filled.size - nums.size
                val sorted = nums.sorted()
                val sum = nums.fold(BigDecimal.ZERO, BigDecimal::add)
                val mean = sum.divide(BigDecimal(nums.size), mc)
                sb.append("Count: ${nums.size}${if (skipped > 0) " ($skipped non-numeric cells ignored)" else ""}\n")
                sb.append("Sum: ${Values.show(sum)}\nMean: ${Values.show(mean)}\nMedian: ${Values.show(median(nums))}\n")
                sb.append("Min: ${Values.show(sorted.first())}\nMax: ${Values.show(sorted.last())}\n")
                sb.append("25th percentile: ${Values.show(percentile(sorted, 0.25))}\n75th percentile: ${Values.show(percentile(sorted, 0.75))}\n")
                if (nums.size > 1) {
                    val variance = nums.fold(BigDecimal.ZERO) { acc, x -> acc.add(x.subtract(mean).pow(2, mc), mc) }.divide(BigDecimal(nums.size - 1), mc)
                    sb.append("Standard deviation (sample): ${Values.show(Calculator.sqrt(variance).round(MathContext(15)))}\n")
                }
            }
            ColType.DATE -> {
                val dates = filled.mapNotNull { Values.date(it) }
                sb.append("Earliest: ${Values.showDate(dates.min())}\nLatest: ${Values.showDate(dates.max())}\n")
            }
            else -> {}
        }
        val top = filled.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(5)
        // Only worth listing when some value repeats.
        if (top.isNotEmpty() && top.first().value > 1)
            sb.append("Most common: ").append(top.joinToString(", ") { "${it.key.take(60)} (${it.value})" })
        return sb.toString().trim()
    }

    /**
     * Columns with types and ranges, plus sample rows, for table_info. A very wide table lists as
     * many columns as fit and says how many more there are.
     */
    fun info(table: Table, maxChars: Int = Budget.MAX - 500): String {
        val sb = StringBuilder()
        sb.append("Table '${table.name.take(120)}': ${table.rows.size} data rows, ${table.columns.size} columns")
        if (table.sheets.size > 1) sb.append(" (sheets: ${table.sheets.joinToString(", ").take(400)})")
        sb.append("\n")
        table.warnings.forEach { sb.append("Note: $it\n") }
        val lines = table.columns.mapIndexed { i, name ->
            val cells = table.rows.map { it[i] }
            val type = Values.infer(cells)
            val filled = cells.filter { it.isNotBlank() }
            val detail = when (type) {
                ColType.NUMBER -> filled.mapNotNull { Values.number(it) }.let { n -> "min ${Values.show(n.min())}, max ${Values.show(n.max())}" }
                ColType.DATE -> filled.mapNotNull { Values.date(it) }.let { d -> "${Values.showDate(d.min())} to ${Values.showDate(d.max())}" }
                ColType.EMPTY -> "no values"
                else -> "${filled.map { it.lowercase() }.toSet().size} distinct, e.g. ${filled.first().take(40)}"
            }
            val missing = cells.size - filled.size
            "- ${name.take(80)}: ${type.label}, $detail${if (missing > 0 && type != ColType.EMPTY) ", $missing empty" else ""}"
        }
        val n = Budget.fit(lines, maxChars - sb.length - 600)
        lines.take(n).forEach { sb.append(it).append('\n') }
        if (n < lines.size) {
            // Name the rest so table_query and table_stats can still use them.
            val rest = table.columns.drop(n).joinToString(", ") { it.take(40) }
            val room = (maxChars - sb.length - 60).coerceAtLeast(0)
            sb.append("Other columns (${lines.size - n}): ").append(if (rest.length > room) rest.take(room).substringBeforeLast(", ") + ", …" else rest).append('\n')
        }
        val header = table.columns.joinToString(" | ") { it.take(40) }
        // Sample rows only with their header, and only when both fit.
        if (table.rows.isNotEmpty() && sb.length + header.length + 20 < maxChars) {
            sb.append("First rows:\n").append(header).append('\n')
            for (r in table.rows.take(3)) {
                val line = r.joinToString(" | ") { it.take(40) } + "\n"
                if (sb.length + line.length > maxChars) break
                sb.append(line)
            }
        }
        return sb.toString().trim().take(maxChars)
    }
}
