package com.mysi.agent.skills

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.mysi.agent.Param
import com.mysi.agent.Skill
import com.mysi.agent.ToolDef
import com.mysi.agent.Tools
import com.mysi.agent.skills.core.Planner

/** Local tasks and notes, and calendar events the user saves in their own calendar app. */
object PlannerSkill {
    val skill = Skill(
        name = "planner",
        description = "Keep a local to-do list and notes on the phone, and add events to the user's calendar.",
        keywords = listOf(
            "task", "tasks", "todo", "to-do", "to do", "deadline", "due", "note", "notes", "jot", "calendar", "event",
            "meeting", "schedule", "appointment", "plan", "agenda",
        ),
        instructions = """
            Planner: tasks and notes stay on this phone. For dates prefer YYYY-MM-DD HH:MM when you know the exact day; words like "tomorrow 9am", "next friday" or "in 3 days" also work.
            add_calendar_event opens the calendar app with the event filled in; tell the user to tap Save there. Use list_tasks to find task numbers before complete_task.
        """.trimIndent(),
        tools = ::tools,
    )

    private fun planner(ctx: Context) = Planner(SqlPlannerStore(SkillDb.get(ctx)))

    private fun tools(ctx: Context): List<ToolDef> = listOf(
        ToolDef(
            "add_task",
            "Add a task to the user's local to-do list, optionally with a due date. Example: add_task(title='Pay electricity bill', due='2026-11-20').",
            listOf(
                Param("title", "string", "What needs doing"),
                Param("due", "string", "When, e.g. 2026-11-20 or 2026-11-20 17:00 or tomorrow 9am", false),
            ),
        ) { a -> SkillFiles.io { planner(ctx).addTask(a.str("title"), a.str("due")) } },
        ToolDef(
            "list_tasks",
            "List the user's local tasks with their numbers and due dates, soonest first; overdue tasks are marked.",
            listOf(Param("status", "string", "Which tasks. Default open", false, listOf("open", "done", "all"))),
            privateData = true,
        ) { a -> SkillFiles.io { planner(ctx).listTasks(a.str("status", "open")) } },
        ToolDef(
            "complete_task",
            "Mark a task as done by its number from list_tasks. Example: complete_task(id=4).",
            listOf(Param("id", "integer", "Task number")),
        ) { a -> SkillFiles.io { planner(ctx).complete(a.int("id", -1).toLong()) } },
        ToolDef(
            "add_note",
            "Save a note on the phone, e.g. an idea, a recipe or meeting points. Example: add_note(title='Gift ideas', text='Book for Ravi; plant for Meena').",
            listOf(
                Param("title", "string", "Short title"),
                Param("text", "string", "The note"),
            ),
        ) { a -> SkillFiles.io { planner(ctx).addNote(a.str("title"), a.str("text")) } },
        ToolDef(
            "search_notes",
            "Find the user's saved notes by words in their title or text; an empty query lists the latest notes. Example: search_notes(query='gift').",
            listOf(Param("query", "string", "Words to look for; empty for the latest notes", false)),
            privateData = true,
        ) { a -> SkillFiles.io { planner(ctx).searchNotes(a.str("query")) } },
        ToolDef(
            "add_calendar_event",
            "Open the calendar app with a new event filled in; the user checks it and taps Save. Example: add_calendar_event(title='Dentist', start='2026-11-14 10:30', end='11:15', location='Apollo Clinic').",
            listOf(
                Param("title", "string", "Event title"),
                Param("start", "string", "Start, e.g. 2026-11-14 10:30; a date alone makes an all-day event"),
                Param("end", "string", "End time or date. Default: one hour after the start", false),
                Param("location", "string", "Place", false),
            ),
        ) { a ->
            SkillFiles.io {
                val title = a.str("title").trim()
                if (title.isEmpty()) return@io "The event needs a title."
                val t = planner(ctx).eventTimes(a.str("start"), a.str("end"))
                val intent = Intent(Intent.ACTION_INSERT)
                    .setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, title)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, t.start)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, t.end)
                    .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, t.allDay)
                a.str("location").trim().takeIf { it.isNotEmpty() }?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                val r = Tools.launch(ctx, intent)
                if (Tools.opened(r)) "The calendar app is open with \"$title\" on ${t.description}. The user must tap Save to keep it." else r
            }
        },
    )
}
