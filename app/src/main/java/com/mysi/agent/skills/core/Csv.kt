package com.mysi.agent.skills.core

/** RFC 4180 CSV with delimiter detection: comma, tab, semicolon or pipe. */
object Csv {
    /** The byte-order mark, written as a code so no invisible character sits in the source. */
    val BOM = Char(0xFEFF)

    data class Parsed(val rows: List<List<String>>, val delimiter: Char, val truncated: Boolean)

    /** Picks the delimiter that splits the first lines most consistently. */
    fun detect(text: String): Char {
        val sample = text.lineSequence().filter { it.isNotBlank() }.take(20).toList()
        if (sample.isEmpty()) return ','
        return listOf(',', '\t', ';', '|').maxByOrNull { d ->
            val counts = sample.map { line -> countOutsideQuotes(line, d) }
            val first = counts.first()
            if (first == 0) -1.0 else counts.count { it == first } * 1000.0 + first
        } ?: ','
    }

    private fun countOutsideQuotes(line: String, d: Char): Int {
        var n = 0
        var q = false
        for (c in line) {
            if (c == '"') q = !q else if (c == d && !q) n++
        }
        return n
    }

    fun parse(text: String, delimiter: Char? = null, maxRows: Int = Int.MAX_VALUE, maxCells: Int = Int.MAX_VALUE, maxColumns: Int = Int.MAX_VALUE): Parsed {
        val t = if (text.isNotEmpty() && text[0] == BOM) text.substring(1) else text
        val d = delimiter ?: detect(t)
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var cells = 0
        var truncated = false
        var fieldStarted = false
        var i = 0
        fun endField() {
            if (row.size < maxColumns) row.add(field.toString()) else truncated = true
            field.setLength(0)
            fieldStarted = false
        }
        fun endRow(): Boolean {
            endField()
            if (!(row.size == 1 && row[0].isEmpty())) {
                cells += row.size
                rows.add(row)
            }
            row = ArrayList()
            if (rows.size >= maxRows || cells >= maxCells) return false
            return true
        }
        while (i < t.length) {
            val c = t[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < t.length && t[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else inQuotes = false
                } else field.append(c)
            } else when {
                c == '"' && !fieldStarted && field.isEmpty() -> {
                    inQuotes = true
                    fieldStarted = true
                }
                c == d -> endField()
                c == '\r' -> {
                    if (i + 1 < t.length && t[i + 1] == '\n') i++
                    if (!endRow()) { truncated = i + 1 < t.length; return Parsed(rows, d, truncated) }
                }
                c == '\n' -> if (!endRow()) { truncated = i + 1 < t.length; return Parsed(rows, d, truncated) }
                else -> {
                    field.append(c)
                    fieldStarted = true
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty() || fieldStarted) endRow()
        return Parsed(rows, d, truncated)
    }

    /** One CSV line, quoting fields that need it. */
    fun line(fields: List<String>, delimiter: Char = ','): String =
        fields.joinToString(delimiter.toString()) { f ->
            if (f.any { it == delimiter || it == '"' || it == '\n' || it == '\r' } || f != f.trim()) "\"" + f.replace("\"", "\"\"") + "\"" else f
        }

    /** Whole CSV text with CRLF line ends, as RFC 4180 and Excel expect. */
    fun write(rows: List<List<String>>, delimiter: Char = ','): String =
        rows.joinToString("\r\n", postfix = if (rows.isEmpty()) "" else "\r\n") { line(it, delimiter) }
}
