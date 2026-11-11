package com.mysi.agent.skills.core

import com.mysi.agent.skills.core.TestFiles.fixture
import java.math.BigDecimal
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TableTest {
    private val dir = TestFiles.tempDir()

    private val sales = TestFiles.write(
        dir, "sales.csv",
        """
        Region,Unit Price,Units,Date,Status,Rep
        North,"1,200.50",10,2024-01-15,Paid,Asha
        South,950,4,2024-02-29,Open,Ravi
        North,100,6,2024-03-01,Paid,
        East,(50),2,2024-03-10,In Progress,Meena
        South,₹2000,1,2024-03-11,Paid,Ravi
        """.trimIndent(),
    )

    private fun q(where: String = "", select: String = "", groupBy: String = "", aggregate: String = "", sort: String = "", limit: Int = 50) =
        TableQuery.run(TableLoader.load(sales, null), select, where, groupBy, aggregate, sort, limit)

    private fun fails(contains: String, block: () -> Unit) {
        try {
            block()
            fail("no error")
        } catch (e: QueryException) {
            assertTrue(e.message!!, e.message!!.contains(contains))
        }
    }

    @Test
    fun valuesParseTheWayPeopleTypeThem() {
        assertEquals(BigDecimal("1200.50"), Values.number("1,200.50"))
        assertEquals(BigDecimal("1250000"), Values.number("12,50,000"))
        assertEquals(BigDecimal("-50"), Values.number("(50)"))
        assertEquals(BigDecimal("-12"), Values.number("-$12"))
        assertEquals(BigDecimal("2000"), Values.number("₹2000"))
        assertEquals(BigDecimal("12"), Values.number("12%"))
        assertEquals(BigDecimal("1.5E+3"), Values.number("1.5e3"))
        assertNull(Values.number("12a"))
        assertNull(Values.number("1,20"))
        assertNull(Values.number("NaN"))
        assertNull(Values.number("2024-01-15"))
        assertEquals(LocalDateTime.of(2024, 1, 5, 0, 0), Values.date("2024/1/5"))
        assertEquals(LocalDateTime.of(2024, 1, 5, 9, 30), Values.date("2024-01-05 09:30"))
        assertNull(Values.date("05/01/2024"))
        assertNull(Values.date("2024-13-01"))
        assertEquals(ColType.NUMBER, Values.infer(listOf("1", "2", "", "3.5")))
        assertEquals(ColType.TEXT, Values.infer(listOf("1", "two", "3")))
        assertEquals(ColType.DATE, Values.infer(listOf("2024-01-01", "2024-02-01")))
        assertEquals(ColType.BOOLEAN, Values.infer(listOf("yes", "no")))
        assertEquals(ColType.EMPTY, Values.infer(listOf("", " ")))
    }

    @Test
    fun infoDescribesColumnsAndSamples() {
        val info = Stats.info(TableLoader.load(sales, null))
        assertTrue(info, info.startsWith("Table 'sales.csv': 5 data rows, 6 columns"))
        assertTrue(info, info.contains("- Unit Price: number, min -50, max 2000"))
        assertTrue(info, info.contains("- Date: date, 2024-01-15 to 2024-03-11"))
        assertTrue(info, info.contains("- Rep: text, 3 distinct, e.g. Asha, 1 empty"))
        assertTrue(info, info.contains("First rows:\nRegion | Unit Price | Units | Date | Status | Rep\nNorth | 1,200.50"))
    }

    @Test
    fun filtersWithAndOrNotAndBrackets() {
        assertEquals(2, q("Region = North").matched)
        assertEquals(2, q("region = NORTH and Units > 5").matched)
        assertEquals(4, q("Region = North or Status = Paid or Units <= 2").matched)
        assertEquals(1, q("Region = South and (Units > 3 or Rep = Meena)").matched)
        assertEquals(3, q("not Region = North").matched)
        assertEquals(3, q("Unit Price >= 950").matched)
        assertEquals(1, q("\"Unit Price\" < 0").matched)
        assertEquals(4, q("Date > 2024-02-28").matched)
        assertEquals(1, q("Status = 'In Progress'").matched)
        assertEquals(1, q("Status = In Progress").matched)
        assertEquals(2, q("Rep in (Ravi, Nobody)").matched)
        assertEquals(1, q("Status contains progress").matched)
        assertEquals(3, q("Status starts with pa").matched)
        assertEquals(1, q("Rep is empty").matched)
        assertEquals(4, q("Rep is not empty").matched)
        assertEquals(1, q("Status != Paid and Status != Open").matched)
    }

    @Test
    fun selectsSortsAndLimits() {
        val r = q(select = "Rep, Units", sort = "Units desc", limit = 2)
        assertEquals(listOf("Rep", "Units"), r.header)
        assertEquals(listOf(listOf("Asha", "10"), listOf("", "6")), r.rows)
        assertEquals(5, r.matched)
        val byPrice = q(select = "Unit Price", sort = "Unit Price")
        assertEquals(listOf("(50)", "100", "950", "1,200.50", "₹2000"), byPrice.rows.map { it[0] })
    }

    @Test
    fun groupsAndAggregatesExactly() {
        val r = q(groupBy = "Region", aggregate = "sum(Units) as units, avg(Unit Price), count(*), max(Date), count_distinct(Rep), median(Units)", sort = "units desc")
        assertEquals(listOf("Region", "units", "avg(Unit Price)", "count(*)", "max(Date)", "count_distinct(Rep)", "median(Units)"), r.header)
        assertEquals(listOf("North", "16", "650.25", "2", "2024-03-01", "1", "8"), r.rows[0])
        assertEquals(listOf("South", "5", "1475", "2", "2024-03-11", "1", "2.5"), r.rows[1])
        assertEquals(listOf("East", "2", "-50", "1", "2024-03-10", "1", "2"), r.rows[2])
        assertEquals(3, r.matched)
        val total = q(where = "Status = Paid", aggregate = "sum(Unit Price), min(Region), count(Rep)")
        assertEquals(listOf(listOf("3300.5", "North", "2")), total.rows)
        // Grouping alone counts.
        assertEquals(listOf("Status", "count"), q(groupBy = "Status").header)
        // An aggregate over nothing is still one row.
        assertEquals(listOf(listOf("0", "0")), q(where = "Units > 100", aggregate = "count(*), sum(Units)").rows)
    }

    @Test
    fun formatIsCompactAndHonestAboutTruncation() {
        val r = q(select = "Region")
        val text = TableQuery.format(r, grouped = false, maxChars = 30)
        assertTrue(text, text.startsWith("5 of 5 rows match; showing"))
        assertTrue(TableQuery.format(q(where = "Units > 5"), false).startsWith("2 of 5 rows match:\nRegion | Unit Price"))
    }

    @Test
    fun mistakesGetUsefulMessages() {
        fails("Unknown column 'Price'. Columns: Region, Unit Price") { q("Price > 5") }
        fails("Unknown aggregate 'average2'") { q(aggregate = "average2(Units)") }
        fails("sum needs a column") { q(aggregate = "sum(*)") }
        fails("Cannot sort by 'Nope'") { q(sort = "Nope") }
        fails("Expected an operator") { q("Region") }
        fails("bracket is not closed") { q("(Region = North") }
        fails("quote is not closed") { q("Region = 'North") }
        fails("Use 'is empty'") { q("Rep is Ravi") }
        fails("Unknown column") { q(groupBy = "Nope") }
    }

    @Test
    fun injectionShapedInputIsJustAValue() {
        // There is no SQL or code behind the filter: this simply matches no rows.
        assertEquals(0, q("Region = \"North'; DROP TABLE x; --\"").matched)
        fails("quote is not closed") { q("Rep = Robert'); DROP TABLE Students;--") }
    }

    @Test
    fun statsAreExact() {
        val s = Stats.describe(TableLoader.load(sales, null), "units")
        assertTrue(s, s.contains("Column 'Units' in sales.csv: number"))
        assertTrue(s, s.contains("Sum: 23\nMean: 4.6\nMedian: 4\nMin: 1\nMax: 10"))
        assertTrue(s, s.contains("25th percentile: 2\n75th percentile: 6"))
        // Sample standard deviation of 10, 4, 6, 2, 1 is sqrt(12.8) = 3.5777087639997, shown to 10 places.
        assertTrue(s, s.contains("Standard deviation (sample): 3.577708764"))
        assertTrue(s, !s.contains("Most common"))
        val text = Stats.describe(TableLoader.load(sales, null), "Status")
        assertTrue(text, text.contains("Most common: Paid (3), In Progress (1), Open (1)"))
        val dates = Stats.describe(TableLoader.load(sales, null), "Date")
        assertTrue(dates, dates.contains("Earliest: 2024-01-15\nLatest: 2024-03-11"))
    }

    @Test
    fun loadsExcelSheetsByNameOrNumber() {
        val t = TableLoader.load(fixture("book.xlsx"), null)
        assertEquals("Sales", t.name)
        assertEquals(listOf("Region", "Date", "Units", "Price", "Paid"), t.columns)
        assertEquals(listOf("Sales", "Notes & Misc"), t.sheets)
        val sum = TableQuery.run(t, "", "Region != Total", "Region", "sum(Units), sum(Price)", "Region", 10)
        assertEquals(listOf(listOf("North", "16", "2.7"), listOf("South", "4", "0.1")), sum.rows)
        assertEquals("Notes & Misc", TableLoader.load(fixture("book.xlsx"), "notes & misc").name)
        assertEquals("Notes & Misc", TableLoader.load(fixture("book.xlsx"), "2").name)
        try {
            TableLoader.load(fixture("book.xlsx"), "Nope")
            fail()
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("Sheets: Sales, Notes & Misc"))
        }
    }

    @Test
    fun headersAreCleanedAndRowsPadded() {
        val f = TestFiles.write(dir, "messy.csv", "\n,Name,Name,\n1,a\n\n2,b,c,d,e\n")
        val t = TableLoader.load(f, null)
        assertEquals(listOf("column 1", "Name", "Name (2)", "column 4", "column 5"), t.columns)
        assertEquals(listOf(listOf("1", "a", "", "", ""), listOf("2", "b", "c", "d", "e")), t.rows)
    }

    @Test
    fun hugeTablesAreCapped() {
        val f = java.io.File(dir, "huge.csv")
        f.bufferedWriter().use { w -> w.write("a,b\n"); repeat(50_000) { w.write("$it,${it * 2}\n") } }
        val t = TableLoader.load(f, null, ReadLimits(maxRows = 1_000))
        assertEquals(999, t.rows.size)
        assertTrue(t.warnings.first().contains("Only the first 1000 rows"))
    }
}
