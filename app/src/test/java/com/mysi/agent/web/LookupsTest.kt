package com.mysi.agent.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupsTest {
    @Test
    fun placesArePickedByCountryOrRegion() {
        val json = Fixtures.text("geocode.json")
        assertEquals("India", Weather.pickPlace(json, "Pune")!!.country)
        assertEquals("India", Weather.pickPlace(json, "Pune, India")!!.country)
        assertEquals("Brazil", Weather.pickPlace(json, "Pune, Brazil")!!.country)
        assertEquals("Maharashtra", Weather.pickPlace(json, "Pune, Maharashtra")!!.admin)
        assertNull(Weather.pickPlace("""{"generationtime_ms":0.1}""", "Nowhere"))
        assertTrue(Weather.geocodeUrl("Pune, India").contains("name=Pune&"))
    }

    @Test
    fun forecastIsFormattedFromTheRealResponse() {
        val place = Weather.pickPlace(Fixtures.text("geocode.json"), "Pune")!!
        val text = Weather.format(Fixtures.text("forecast.json"), place)
        assertTrue(text, text.startsWith("Weather for Pune, Maharashtra, India (now, 2026-11-11 12:45 local time): clear sky, 29.5°C, feels like 28.8°C, humidity 27%, wind 12.7 km/h."))
        assertTrue(text.contains("- Wed 11 Nov: clear sky, 18.8–29.9°C, rain chance 0%"))
        assertTrue(text.contains("- Thu 12 Nov: overcast"))
        assertTrue(text.endsWith("Source: Open-Meteo.com"))
        assertEquals("thunderstorm with hail", Weather.describe(99))
    }

    @Test
    fun exchangeRatesFromBothProviders() {
        val f = Currency.parseFrankfurter(Fixtures.text("frankfurter.json"), "USD", "INR")!!
        assertEquals(93.93, f.rate, 1e-9)
        assertEquals("2026-11-10", f.date)
        assertTrue(Currency.format(f, 250.0).startsWith("250 USD = 23,482.50 INR (1 USD = 93.93 INR; rate date 2026-11-10"))
        assertNull(Currency.parseFrankfurter("""{"message":"not found"}""", "USD", "XYZ"))
        val e = Currency.parseErApi(Fixtures.text("erapi.json"), "USD", "KES")!!
        assertTrue(e.rate > 0)
        assertTrue(e.source.contains("open.er-api.com"))
        assertNull(Currency.parseErApi(Fixtures.text("erapi.json"), "USD", "ZZZ"))
        assertEquals("USD", Currency.code(" usd "))
        assertNull(Currency.code("dollars"))
        assertNull(Currency.code("US"))
    }
}
