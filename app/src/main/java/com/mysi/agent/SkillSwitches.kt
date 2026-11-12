package com.mysi.agent

import android.content.Context

/**
 * Which skills the user turned off on the Skills screen. A skill that is off is never routed to,
 * never loaded by use_skill or by a tool call naming one of its tools, and its tools are never
 * offered to the model. The core skill is always on. Names match case-insensitively, as
 * [Skills.named] does. Immutable: [with] returns the changed copy.
 */
class SkillSwitches(disabled: Set<String> = emptySet()) {
    /** Lower-case names of the skills that are off; never contains core. */
    val disabled: Set<String> = disabled.map { it.trim().lowercase() }.filter { it.isNotEmpty() && it != CORE }.toSet()

    fun allows(name: String): Boolean = name.trim().lowercase().let { it == CORE || it !in disabled }

    /** A copy with [name] switched on or off; core stays on whatever is asked. */
    fun with(name: String, on: Boolean): SkillSwitches =
        SkillSwitches(if (on) disabled - name.trim().lowercase() else disabled + name.trim().lowercase())

    override fun equals(other: Any?) = other is SkillSwitches && other.disabled == disabled

    override fun hashCode() = disabled.hashCode()

    companion object {
        const val CORE = "core"

        /** A skill's name as the Skills screen shows it, e.g. "alarms_media" as "Alarms and media". */
        fun title(name: String): String =
            when (name) {
                "alarms_media" -> "Alarms and media"
                "shopping_travel" -> "Shopping and travel"
                else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
            }

        /** The first sentence of a tool's description, for a one-line summary; long ones are cut at a word. */
        fun oneLine(description: String, max: Int = 140): String {
            // A sentence ends at . ! or ? before a capital, but not after "e.g." or "i.e.".
            val first = description.trim().split(Regex("(?<!\\b(?:e\\.g|i\\.e)\\.)(?<=[.!?])\\s+(?=[A-Z])"), limit = 2).first()
            if (first.length <= max) return first
            val cut = first.take(max - 1)
            return cut.substring(0, cut.lastIndexOf(' ').takeIf { it > max / 2 } ?: cut.length).trimEnd(',', ';', ':', ' ') + "…"
        }
        private const val PREFS = "skills"
        private const val KEY = "disabled"

        /** The saved choice, or every skill on. */
        fun load(ctx: Context): SkillSwitches =
            SkillSwitches(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, null).orEmpty())

        fun save(ctx: Context, switches: SkillSwitches) {
            // A fresh set: SharedPreferences must not be handed back the instance it returned.
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, HashSet(switches.disabled)).apply()
        }
    }
}
