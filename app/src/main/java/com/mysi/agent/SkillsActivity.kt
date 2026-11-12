package com.mysi.agent

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every skill MySI has, what it does and its tools, with a switch to turn each one off. A skill
 * that is off is never offered to the model (see [SkillSwitches]); core is always on. The choice
 * is saved on the phone and applies from the next request.
 */
class SkillsActivity : UiActivity() {
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shell("Skills")
        link(titleBar, "Done", "Close skills") { finish() }
        text(body, "Turn off a skill you do not want MySI to use. MySI never offers a skill that is off to the model. Changes apply from your next message.", 13f, muted)
        space(body, 4)
        list = column(body)
        val ctx = applicationContext
        ui.launch {
            // Building the tool lists reads no data; it is kept off the main thread all the same.
            val skills = listOf(Skills.core) + Skills.all
            val tools = withContext(Dispatchers.Default) { skills.map { s -> runCatching { s.tools(ctx) }.getOrDefault(emptyList()) } }
            skills.zip(tools).forEach { (s, t) -> skillCard(s, t) }
        }
    }

    override fun onDestroy() {
        ui.cancel()
        super.onDestroy()
    }

    private fun skillCard(s: Skill, tools: List<ToolDef>) {
        val core = s === Skills.core
        val title = SkillSwitches.title(s.name)
        val c = card(list)
        val head = row(c)
        text(head, title, 17f).apply {
            setTextIsSelectable(false)
            typeface = serif
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        if (core) text(head, "Always on", 12f, muted).apply {
            setTextIsSelectable(false)
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }
        else {
            Switch(this).apply {
                isChecked = Skills.switches.allows(s.name)
                contentDescription = "$title skill"
                minHeight = dp(48)
                setOnCheckedChangeListener { _, on ->
                    val next = Skills.switches.with(s.name, on)
                    Skills.switches = next
                    SkillSwitches.save(applicationContext, next)
                    c.alpha = if (on) 1f else OFF_ALPHA
                }
                head.addView(this, LinearLayout.LayoutParams(-2, -2))
            }
            if (!Skills.switches.allows(s.name)) c.alpha = OFF_ALPHA
        }
        text(c, s.description, 14f).setTextIsSelectable(false)
        if (core) text(c, "MySI's own tools. It cannot be turned off.", 12f, muted).setTextIsSelectable(false)
        if (tools.isEmpty()) return
        text(c, if (tools.size == 1) "1 tool" else "${tools.size} tools", 11f, muted, bold = true).apply {
            setTextIsSelectable(false)
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(4)
        }
        for (t in tools) {
            val line = column(c).apply {
                // One stop for TalkBack per tool: its name, then what it does.
                isScreenReaderFocusable = true
                contentDescription = "${t.name}. ${SkillSwitches.oneLine(t.description)}"
            }
            text(line, t.name, 12f).apply {
                setTextIsSelectable(false)
                typeface = android.graphics.Typeface.MONOSPACE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(1)
            }
            text(line, SkillSwitches.oneLine(t.description), 12f, muted).apply {
                setTextIsSelectable(false)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
    }

    private companion object {
        const val OFF_ALPHA = 0.55f
    }
}
