package com.mysi.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneNumberTest {
    private fun digits(raw: String, code: String = "91") = Tools.internationalDigits(raw, code)

    @Test
    fun plusPrefixKeepsTheGivenCountryCode() {
        assertEquals("442071234567", digits("+44 20 7123 4567"))
        assertEquals("919876543210", digits("+91-98765-43210"))
    }

    @Test
    fun doubleZeroIsAnInternationalPrefix() {
        assertEquals("442071234567", digits("0044 20 7123 4567"))
    }

    @Test
    fun trunkZeroIsReplacedByTheSimCountryCode() {
        assertEquals("919876543210", digits("09876543210"))
        assertEquals("442071234567", digits("020 7123 4567", "44"))
    }

    @Test
    fun localNumbersGetTheSimCountryCode() {
        assertEquals("919876543210", digits("98765 43210"))
        assertEquals("15551234567", digits("(555) 123-4567", "1"))
    }

    @Test
    fun longNumbersWithoutPrefixAreLeftAsIs() {
        assertEquals("919876543210", digits("919876543210"))
    }
}
