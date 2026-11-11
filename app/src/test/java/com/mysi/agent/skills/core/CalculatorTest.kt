package com.mysi.agent.skills.core

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CalculatorTest {
    private fun calc(e: String) = Calculator.evaluate(e).text()

    private fun fails(e: String, contains: String) {
        try {
            val r = Calculator.evaluate(e)
            fail("'$e' gave ${r.text()}")
        } catch (x: CalcException) {
            assertTrue("'$e': ${x.message}", x.message!!.contains(contains, ignoreCase = true))
        }
    }

    @Test
    fun precedenceAndAssociativity() {
        assertEquals("14", calc("2 + 3 * 4"))
        assertEquals("20", calc("(2 + 3) * 4"))
        assertEquals("512", calc("2 ^ 3 ^ 2"))
        assertEquals("-4", calc("-2^2"))
        assertEquals("4", calc("(-2)^2"))
        assertEquals("0.5", calc("2^-1"))
        assertEquals("1", calc("10 - 4 - 5"))
        assertEquals("2", calc("16 / 4 / 2"))
        assertEquals("64", calc("2**6"))
        assertEquals("6", calc("--6"))
    }

    @Test
    fun decimalsAreExact() {
        assertEquals("0.3", calc("0.1 + 0.2"))
        assertEquals("1.21", calc("1.1 * 1.1"))
        assertEquals("3.5", calc("7 / 2"))
        assertEquals("0.000001", calc("1 / 1000000"))
        assertEquals("100000000000000000001", calc("100000000000000000000 + 1"))
    }

    @Test
    fun nonTerminatingDivisionIsRoundedAndSaysSo() {
        assertEquals("0.33333333333333333333 (rounded to 20 significant digits)", calc("1/3"))
        // 1/3 keeps 34 digits, so times 3 is 0.999...9; the display says it rounded.
        assertEquals("1 (rounded to 20 significant digits)", calc("1/3*3"))
        assertEquals("142857.14", calc("round(1000000/7, 2)"))
    }

    @Test
    fun percentFollowsCalculatorConventions() {
        assertEquals("230", calc("200 + 15%"))
        assertEquals("170", calc("200 - 15%"))
        assertEquals("30", calc("15% of 200"))
        assertEquals("30", calc("200 * 15%"))
        assertEquals("0.15", calc("15%"))
        assertEquals("2700", calc("(1250 * 12) * 18%"))
        assertEquals("20", calc("percent(50, 250)"))
        assertEquals("-25", calc("percent_change(200, 150)"))
        assertEquals("200.15", calc("200 + (15%)"))
    }

    @Test
    fun functionsAndConstants() {
        assertEquals("12", calc("sqrt(144)"))
        assertEquals("1.5", calc("sqrt(2.25)"))
        assertEquals("1.4142135623730950488 (rounded to 20 significant digits)", calc("sqrt(2)"))
        assertEquals("3", calc("abs(-3)"))
        assertEquals("2", calc("floor(2.7)"))
        assertEquals("-2", calc("ceil(-2.7)"))
        assertEquals("3", calc("round(2.5)"))
        assertEquals("2.68", calc("round(2.675, 2)"))
        assertEquals("1", calc("min(3, 1, 2)"))
        assertEquals("6", calc("sum(1, 2, 3)"))
        assertEquals("2", calc("avg(1, 2, 3)"))
        assertEquals("1", calc("mod(10, 3)"))
        assertEquals("1024", calc("pow(2, 10)"))
        assertEquals("3.1415926535897932385 (rounded to 20 significant digits)", calc("pi"))
        assertTrue(calc("ln(e)"), calc("ln(e)").startsWith("1 (approximate"))
        assertEquals("3 (approximate: uses a 15-digit function)", calc("log(1000)"))
        assertEquals("1.4142135623731 (approximate: uses a 15-digit function)", calc("2^0.5"))
    }

    @Test
    fun thousandsSeparatorsOutsideFunctions() {
        assertEquals("1250001", calc("1,250,000 + 1"))
        assertEquals("1250000", calc("12,50,000"))
        assertEquals("2", calc("max(1,2)"))
        fails("1,2", "Unexpected")
    }

    @Test
    fun unicodeOperatorsWork() {
        assertEquals("12", calc("3 ${Char(0x00D7)} 4"))
        assertEquals("2", calc("8 ${Char(0x00F7)} 4"))
        assertEquals("-1", calc("${Char(0x2212)}1"))
        assertEquals("5", calc("= 2 + 3"))
    }

    @Test
    fun errorsAreClearAndNothingIsExecuted() {
        fails("1 / 0", "Division by zero")
        fails("mod(1, 0)", "Division by zero")
        fails("0 ^ -1", "Division by zero")
        fails("", "empty")
        fails("2 +", "ends too early")
        fails("(2 + 3", "closing bracket")
        fails("2 + 3)", "Unexpected")
        fails("foo(2)", "Unknown function")
        fails("x + 1", "Unknown name")
        fails("sqrt(-1)", "negative")
        fails("(-8) ^ 0.5", "negative")
        fails("java.lang.Runtime.getRuntime().exec(\"rm\")", "not allowed")
        fails("System.exit(0)", "not allowed")
        fails("exec(1)", "Unknown function")
        fails("1; 2", "Unexpected")
        fails("2 & 3", "not allowed")
        fails("1".repeat(Calculator.MAX_LENGTH + 1), "longer than")
        fails("(".repeat(70) + "1" + ")".repeat(70), "nested too deeply")
    }

    @Test
    fun hugeResultsAreRefusedInsteadOfHanging() {
        fails("9^9^9", "exponent is too large")
        fails("10^100000", "exponent is too large")
        fails("10^5000", "too large")
        fails("1e9999 * 1e9999", "too large")
        assertEquals("1e100", calc("10^100"))
        assertEquals("1e-12", calc("10^-12"))
    }

    @Test
    fun displayUsesPlainNotationForOrdinarySizes() {
        assertEquals("123456789012345678901", calc("123456789012345678901"))
        assertEquals("1500000000000000000000", calc("1500000000000000000000"))
        assertEquals("1.234567890123456789e21 (rounded to 20 significant digits)", calc("1234567890123456789012.5"))
        assertEquals("1e40", calc("10^40"))
        assertEquals("0.000000001", calc("1/1000000000"))
        assertEquals("0" to false, Calculator.display(BigDecimal.ZERO))
        assertFalse(Calculator.display(BigDecimal("12.5")).second)
    }
}
