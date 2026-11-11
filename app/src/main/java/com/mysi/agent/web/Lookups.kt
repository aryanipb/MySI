package com.mysi.agent.web

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Weather from Open-Meteo (no key): geocoding, then current conditions and a daily forecast. */
object Weather {
    class Place(val name: String, val admin: String?, val country: String?, val countryCode: String?, val latitude: Double, val longitude: Double) {
        val label get() = listOfNotNull(name, admin?.takeIf { it != name }, country).joinToString(", ")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Open-Meteo's geocoder matches one place name, so "Pune, India" searches "Pune" and the rest picks among matches. */
    fun geocodeUrl(place: String) =
        "https://geocoding-api.open-meteo.com/v1/search?name=${enc(place.substringBefore(',').trim())}&count=10&language=en&format=json"

    fun forecastUrl(p: Place, days: Int) =
        "https://api.open-meteo.com/v1/forecast?latitude=${p.latitude}&longitude=${p.longitude}" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum" +
            "&timezone=auto&forecast_days=${days.coerceIn(1, 7)}"

    /** Best match for [query]: qualifiers after the first comma must match the region or country. */
    fun pickPlace(json: String, query: String): Place? {
        val results = runCatching { JsonParser.parseString(json).asJsonObject.getAsJsonArray("results") }.getOrNull() ?: return null
        val places = results.mapNotNull { e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            Place(
                o.str("name") ?: return@mapNotNull null,
                o.str("admin1"),
                o.str("country"),
                o.str("country_code"),
                o.get("latitude")?.asDouble ?: return@mapNotNull null,
                o.get("longitude")?.asDouble ?: return@mapNotNull null,
            )
        }
        val qualifiers = query.split(',').drop(1).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (qualifiers.isEmpty()) return places.firstOrNull()
        return places.firstOrNull { p ->
            qualifiers.all { q -> listOfNotNull(p.admin, p.country, p.countryCode).any { it.lowercase() == q || it.lowercase().startsWith(q) } }
        } ?: places.firstOrNull { p -> qualifiers.any { q -> listOfNotNull(p.admin, p.country, p.countryCode).any { it.lowercase().contains(q) } } }
    }

    private fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString

    /** WMO weather interpretation codes, as Open-Meteo documents them. */
    fun describe(code: Int): String =
        when (code) {
            0 -> "clear sky"
            1 -> "mainly clear"
            2 -> "partly cloudy"
            3 -> "overcast"
            45, 48 -> "fog"
            51, 53, 55 -> "drizzle"
            56, 57 -> "freezing drizzle"
            61 -> "light rain"
            63 -> "rain"
            65 -> "heavy rain"
            66, 67 -> "freezing rain"
            71 -> "light snow"
            73 -> "snow"
            75 -> "heavy snow"
            77 -> "snow grains"
            80 -> "light showers"
            81 -> "showers"
            82 -> "violent showers"
            85, 86 -> "snow showers"
            95 -> "thunderstorm"
            96, 99 -> "thunderstorm with hail"
            else -> "weather code $code"
        }

    /** Plain-text report from a forecast response. */
    fun format(json: String, place: Place): String {
        val o = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return "The weather service sent an unreadable answer."
        val cu = o.getAsJsonObject("current_units")
        val c = o.getAsJsonObject("current")
        val sb = StringBuilder("Weather for ${place.label}")
        if (c != null) {
            fun v(k: String) = c.get(k)?.takeIf { it.isJsonPrimitive }?.asString
            fun u(k: String) = cu?.get(k)?.asString.orEmpty()
            sb.append(" (now, ${v("time")?.replace('T', ' ') ?: "current"} local time): ")
            sb.append(listOfNotNull(
                v("weather_code")?.toDoubleOrNull()?.let { describe(it.toInt()) },
                v("temperature_2m")?.let { "${it}${u("temperature_2m")}" },
                v("apparent_temperature")?.let { "feels like ${it}${u("apparent_temperature")}" },
                v("relative_humidity_2m")?.let { "humidity ${it}${u("relative_humidity_2m")}" },
                v("wind_speed_10m")?.let { "wind ${it} ${u("wind_speed_10m")}" },
                v("precipitation")?.takeIf { (it.toDoubleOrNull() ?: 0.0) > 0 }?.let { "precipitation ${it} ${u("precipitation")}" },
            ).joinToString(", "))
            sb.append('.')
        }
        val d = o.getAsJsonObject("daily")
        val du = o.getAsJsonObject("daily_units")
        val days = d?.getAsJsonArray("time")
        if (d != null && days != null) {
            sb.append("\nForecast:")
            val fmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
            for (i in 0 until days.size()) {
                fun at(k: String) = d.getAsJsonArray(k)?.get(i)?.takeIf { !it.isJsonNull }?.asString
                val day = runCatching { LocalDate.parse(days[i].asString).format(fmt) }.getOrDefault(days[i].asString)
                val unit = du?.get("temperature_2m_max")?.asString.orEmpty()
                sb.append("\n- ").append(day).append(": ")
                sb.append(listOfNotNull(
                    at("weather_code")?.toDoubleOrNull()?.let { describe(it.toInt()) },
                    if (at("temperature_2m_min") != null && at("temperature_2m_max") != null) "${at("temperature_2m_min")}–${at("temperature_2m_max")}$unit" else null,
                    at("precipitation_probability_max")?.let { "rain chance $it%" },
                    at("precipitation_sum")?.takeIf { (it.toDoubleOrNull() ?: 0.0) > 0 }?.let { "rain ${it} ${du?.get("precipitation_sum")?.asString.orEmpty()}" },
                ).joinToString(", "))
            }
        }
        sb.append("\nSource: Open-Meteo.com")
        return sb.toString()
    }
}

/** Exchange rates without a key: Frankfurter (European Central Bank rates), then open.er-api.com for other currencies. */
object Currency {
    class Rate(val from: String, val to: String, val rate: Double, val date: String, val source: String)

    fun code(s: String): String? = s.trim().uppercase().takeIf { Regex("^[A-Z]{3}$").matches(it) }

    fun frankfurterUrl(from: String, to: String) = "https://api.frankfurter.dev/v1/latest?base=$from&symbols=$to"

    fun erApiUrl(from: String) = "https://open.er-api.com/v6/latest/$from"

    fun parseFrankfurter(json: String, from: String, to: String): Rate? {
        val o = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
        val rate = o.getAsJsonObject("rates")?.get(to)?.takeIf { it.isJsonPrimitive }?.asDouble ?: return null
        val per = o.get("amount")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 1.0
        if (per <= 0) return null
        return Rate(from, to, rate / per, o.get("date")?.asString.orEmpty(), "European Central Bank via frankfurter.dev")
    }

    fun parseErApi(json: String, from: String, to: String): Rate? {
        val o = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
        if (o.get("result")?.asString != "success") return null
        val rate = o.getAsJsonObject("rates")?.get(to)?.takeIf { it.isJsonPrimitive }?.asDouble ?: return null
        val date = o.get("time_last_update_utc")?.asString?.let { runCatching { it.split(' ').drop(1).take(3).joinToString(" ") }.getOrNull() }.orEmpty()
        return Rate(from, to, rate, date, "ExchangeRate-API (open.er-api.com)")
    }

    fun format(r: Rate, amount: Double): String {
        fun n(x: Double) =
            when {
                x == Math.floor(x) && x < 1e15 -> String.format(Locale.ENGLISH, "%,d", x.toLong())
                x >= 100 -> String.format(Locale.ENGLISH, "%,.2f", x)
                else -> String.format(Locale.ENGLISH, "%,.4f", x).trimEnd('0').trimEnd('.')
            }
        return "${n(amount)} ${r.from} = ${n(amount * r.rate)} ${r.to} (1 ${r.from} = ${n(r.rate)} ${r.to}; rate date ${r.date}; source: ${r.source}). " +
            "Rates are reference rates; banks and card networks charge a margin."
    }
}
