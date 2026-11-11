package com.mysi.agent.skills.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class DateException(message: String) : Exception(message)

/**
 * Dates and times as people and models write them: ISO ("2026-11-12 15:30"), "today",
 * "tomorrow 9am", "next friday", "in 3 days", "12 Nov", "November 12 2026 at 6:30 pm",
 * "noon". Day-first or month-first numbers like 03/04 are refused rather than guessed.
 */
object DateText {
    data class When(val date: LocalDate, val time: LocalTime?) {
        fun at(zone: ZoneId): Long = (time?.let { date.atTime(it) } ?: date.atStartOfDay()).atZone(zone).toInstant().toEpochMilli()

        override fun toString() = date.toString() + (time?.let { " %02d:%02d".format(it.hour, it.minute) } ?: "")
    }

    private val months = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "sept" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )
    fun parse(input: String, now: LocalDateTime): When {
        if (input.isBlank()) throw DateException("No date given.")
        var s = " " + input.trim().lowercase().replace(',', ' ').replace(Regex("\\s+"), " ") + " "
        val tonight = s.contains(" tonight ")
        if (tonight) s = s.replace(" tonight ", " today ")
        var time: LocalTime? = null
        fun take(r: Regex, f: (MatchResult) -> LocalTime?) {
            if (time != null) return
            val m = r.find(s) ?: return
            time = f(m) ?: return
            s = s.removeRange(m.range).let { " $it " }
        }
        // ISO date-time with T or a space: handled by the date part below once the time is cut.
        take(Regex("[ t](\\d{1,2}):(\\d{2})(?::\\d{2})?\\s*(am|pm|a\\.m\\.|p\\.m\\.)? ")) { m -> hm(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3]) }
        take(Regex(" (?:at )?(\\d{1,2})\\s*(am|pm|a\\.m\\.|p\\.m\\.) ")) { m -> hm(m.groupValues[1].toInt(), 0, m.groupValues[2]) }
        take(Regex(" at (\\d{1,2}) ")) { m -> hm(m.groupValues[1].toInt(), 0, "") }
        take(Regex(" (noon|midday) ")) { LocalTime.NOON }
        take(Regex(" midnight ")) { LocalTime.MIDNIGHT }
        take(Regex(" (morning) ")) { LocalTime.of(9, 0) }
        take(Regex(" (afternoon) ")) { LocalTime.of(15, 0) }
        take(Regex(" (evening) ")) { LocalTime.of(18, 0) }
        if (tonight && time == null) time = LocalTime.of(20, 0)
        val rest = s.replace(Regex("\\b(at|on|this|the|of)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        val today = now.toLocalDate()
        val date: LocalDate = when {
            rest.isEmpty() -> today
            rest == "today" || rest == "now" -> today
            rest == "tomorrow" -> today.plusDays(1)
            rest == "day after tomorrow" -> today.plusDays(2)
            Regex("^in (\\d{1,3}) (minute|min|hour|hr)s?$").matches(rest) -> {
                val m = Regex("^in (\\d{1,3}) (minute|min|hour|hr)s?$").find(rest)!!
                val n = m.groupValues[1].toLong()
                val t = if (m.groupValues[2].startsWith("h")) now.plusHours(n) else now.plusMinutes(n)
                if (time == null) time = t.toLocalTime().withSecond(0).withNano(0)
                t.toLocalDate()
            }
            Regex("^in (\\d{1,3}) (day|week|month|year)s?$").matches(rest) -> {
                val m = Regex("^in (\\d{1,3}) (day|week|month|year)s?$").find(rest)!!
                val n = m.groupValues[1].toLong()
                when (m.groupValues[2]) {
                    "day" -> today.plusDays(n)
                    "week" -> today.plusWeeks(n)
                    "month" -> today.plusMonths(n)
                    else -> today.plusYears(n)
                }
            }
            weekday(rest) != null -> {
                val d = weekday(rest)!!
                var x = today.plusDays(1)
                while (x.dayOfWeek != d) x = x.plusDays(1)
                x
            }
            else -> absolute(rest, today) ?: throw DateException("Could not understand the date '${input.trim()}'. Use YYYY-MM-DD HH:MM, e.g. ${today.plusDays(1)} 15:30.")
        }
        return When(date, time)
    }

    private fun hm(h: Int, m: Int, ampm: String): LocalTime? {
        if (m !in 0..59) return null
        val pm = ampm.startsWith("p")
        val am = ampm.startsWith("a")
        val hour = when {
            pm && h in 1..11 -> h + 12
            am && h == 12 -> 0
            (am || pm) && h !in 1..12 -> return null
            else -> h
        }
        return if (hour in 0..23) LocalTime.of(hour, m) else null
    }

    /** "friday", "fri", "next thurs": the next such day after today. */
    private fun weekday(s: String): DayOfWeek? {
        val w = s.removePrefix("next ").trim()
        if (w.length < 3) return null
        return DayOfWeek.values().firstOrNull { d -> d.name.lowercase().startsWith(w) }
    }

    /** ISO dates and month-name dates; a date without a year is the next one to come. */
    private fun absolute(s: String, today: LocalDate): LocalDate? {
        Regex("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})(t)?$").find(s)?.let { m ->
            return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull()
        }
        if (Regex("^\\d{1,2}[-/.]\\d{1,2}([-/.]\\d{2,4})?$").matches(s))
            throw DateException("'$s' could be day-first or month-first. Use YYYY-MM-DD, e.g. ${today.plusDays(1)}.")
        val dm = Regex("^(\\d{1,2})(?:st|nd|rd|th)? ([a-z]+)\\.?(?: (\\d{4}))?$").find(s)
        val md = Regex("^([a-z]+)\\.? (\\d{1,2})(?:st|nd|rd|th)?(?: (\\d{4}))?$").find(s)
        val (day, monthName, year) = when {
            dm != null -> Triple(dm.groupValues[1].toInt(), dm.groupValues[2], dm.groupValues[3])
            md != null -> Triple(md.groupValues[2].toInt(), md.groupValues[1], md.groupValues[3])
            else -> return null
        }
        val month = months[monthName.take(3)]?.takeIf { monthName.length <= 9 } ?: return null
        return try {
            if (year.isNotEmpty()) LocalDate.of(year.toInt(), month, day)
            else LocalDate.of(today.year, month, day).let { if (it.isBefore(today)) LocalDate.of(today.year + 1, month, day) else it }
        } catch (e: java.time.DateTimeException) {
            null
        }
    }
}

data class Task(val id: Long, val title: String, val due: Long?, val dueHasTime: Boolean, val done: Boolean, val created: Long, val completed: Long?)

data class Note(val id: Long, val title: String, val text: String, val created: Long, val updated: Long)

interface PlannerStore {
    fun addTask(title: String, due: Long?, dueHasTime: Boolean, now: Long): Long

    fun tasks(): List<Task>

    fun completeTask(id: Long, now: Long): Boolean

    fun addNote(title: String, text: String, now: Long): Long

    fun notes(): List<Note>
}

/** Local tasks and notes. Everything returns short text for the model. */
class Planner(private val store: PlannerStore, private val zone: () -> ZoneId = ZoneId::systemDefault, private val clock: () -> Long = System::currentTimeMillis) {
    private fun now(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(clock()), zone())

    fun addTask(title: String, due: String): String {
        val t = title.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) throw IllegalArgumentException("The task needs a title.")
        if (t.length > 200) throw IllegalArgumentException("Keep the task title under 200 characters; put details in a note.")
        if (store.tasks().count { !it.done } >= MAX_OPEN) throw IllegalArgumentException("There are already $MAX_OPEN open tasks. Complete some first.")
        val w = due.trim().takeIf { it.isNotEmpty() }?.let { DateText.parse(it, now()) }
        val id = store.addTask(t, w?.at(zone()), w?.time != null, clock())
        return "Task #$id added: $t" + (w?.let { " (due ${it})" } ?: "")
    }

    /** Tasks from [offset], within the result budget, with the offset of the next page. */
    fun listTasks(status: String, offset: Int = 0): String {
        val all = store.tasks()
        val wanted = status.trim().lowercase().ifEmpty { "open" }
        val chosen = when (wanted) {
            "done", "completed" -> all.filter { it.done }.sortedWith(compareByDescending<Task> { it.completed ?: 0 }.thenByDescending { it.id })
            "all" -> all.sortedWith(compareBy<Task> { it.done }.thenBy { it.due ?: Long.MAX_VALUE }.thenBy { it.id })
            else -> all.filter { !it.done }.sortedWith(compareBy<Task> { it.due ?: Long.MAX_VALUE }.thenBy { it.id })
        }
        if (chosen.isEmpty()) return if (all.isEmpty()) "No tasks yet." else "No tasks with status '$wanted'."
        val start = offset.coerceAtLeast(0)
        if (start >= chosen.size) return "There are only ${chosen.size} $wanted tasks."
        val nowMs = clock()
        val lines = chosen.drop(start).map { t ->
            val due = t.due?.let { ms ->
                val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone())
                val text = if (t.dueHasTime) "%s %02d:%02d".format(dt.toLocalDate(), dt.hour, dt.minute) else dt.toLocalDate().toString()
                val overdue = !t.done && (if (t.dueHasTime) ms < nowMs else dt.toLocalDate().isBefore(now().toLocalDate()))
                " (due $text${if (overdue) ", OVERDUE" else ""})"
            } ?: ""
            "#${t.id} ${if (t.done) "[done] " else ""}${t.title}$due"
        }
        val n = Budget.fit(lines, Budget.CHARS - 100)
        val text = lines.take(n).joinToString("\n")
        return if (start + n < chosen.size) "$text\n[More: list_tasks(status=$wanted, offset=${start + n})]" else text
    }

    fun complete(id: Long): String {
        val t = store.tasks().firstOrNull { it.id == id } ?: return "No task #$id. Use list_tasks to see task numbers."
        if (t.done) return "Task #$id was already done: ${t.title}"
        store.completeTask(id, clock())
        return "Done: #$id ${t.title}"
    }

    fun addNote(title: String, text: String): String {
        val body = text.trim()
        if (body.isEmpty() && title.isBlank()) throw IllegalArgumentException("The note is empty.")
        if (body.length > MAX_NOTE) throw IllegalArgumentException("Notes can be at most $MAX_NOTE characters; save long text as a document instead.")
        if (store.notes().size >= MAX_NOTES) throw IllegalArgumentException("There are already $MAX_NOTES notes.")
        val t = title.trim().replace(Regex("\\s+"), " ").take(120).ifEmpty { body.lineSequence().first().take(60) }
        val id = store.addNote(t, body, clock())
        return "Note #$id saved: $t"
    }

    fun searchNotes(query: String, count: Int = 5): String {
        val notes = store.notes()
        if (notes.isEmpty()) return "No notes yet."
        val found = if (query.isBlank()) notes.sortedByDescending { it.updated }.take(count)
        else {
            val index = MemoryIndex()
            notes.forEach { index.add(it.id, it.title + "\n" + it.text) }
            val byId = notes.associateBy { it.id }
            Bm25Ranker(index).rank(query, count).filter { it.score > 0 }.mapNotNull { byId[it.id] }
        }
        if (found.isEmpty()) return "No notes match '$query'."
        val per = (2_800 / found.size).coerceAtLeast(200)
        return found.joinToString("\n\n") { n ->
            val date = LocalDateTime.ofInstant(Instant.ofEpochMilli(n.updated), zone()).toLocalDate()
            val body = n.text.let { if (it.length > per) it.take(per - 1).trimEnd() + "…" else it }
            "#${n.id} ${n.title} ($date)\n$body"
        }
    }

    /** Start and end in epoch millis for a calendar event; all-day when no time is given. */
    data class EventTimes(val start: Long, val end: Long, val allDay: Boolean, val description: String)

    fun eventTimes(start: String, end: String): EventTimes {
        val s = DateText.parse(start.ifBlank { throw IllegalArgumentException("The event needs a start date.") }, now())
        val z = zone()
        if (s.time == null) {
            val e = end.trim().takeIf { it.isNotEmpty() }?.let { DateText.parse(it, now()) }
            val lastDay = e?.date ?: s.date
            if (lastDay.isBefore(s.date)) throw IllegalArgumentException("The event ends before it starts.")
            // All-day events use UTC midnight boundaries (CalendarContract.Events: eventTimezone UTC,
            // times on a midnight boundary), ending at the midnight after the last day.
            val startMs = s.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val endMs = lastDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            return EventTimes(startMs, endMs, true, if (lastDay == s.date) "${s.date} (all day)" else "${s.date} to $lastDay (all day)")
        }
        val startMs = s.at(z)
        val e = end.trim().takeIf { it.isNotEmpty() }?.let { DateText.parse(it, LocalDateTime.of(s.date, s.time)) }
        val endMs = when {
            e == null -> startMs + 60 * 60 * 1000
            e.time == null -> throw IllegalArgumentException("Give the end as a time, e.g. 16:30, or leave it out for one hour.")
            // The end is read relative to the start, so "16:30" means 16:30 on the start day.
            else -> e.at(z)
        }
        if (endMs <= startMs) throw IllegalArgumentException("The event ends before it starts.")
        val endText = LocalDateTime.ofInstant(Instant.ofEpochMilli(endMs), z)
        return EventTimes(startMs, endMs, false, "$s to ${if (endText.toLocalDate() == s.date) "%02d:%02d".format(endText.hour, endText.minute) else endText.toString().replace('T', ' ')}")
    }

    companion object {
        const val MAX_OPEN = 2_000
        const val MAX_NOTES = 5_000
        const val MAX_NOTE = 20_000
    }
}
