package com.mysi.agent

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputFilter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.mysi.agent.skills.MemorySkill
import com.mysi.agent.skills.core.MemoryBook
import com.mysi.agent.skills.core.MemoryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What MySI remembers, from the memory skill's store on this phone: the user can read, edit,
 * delete or add each memory, or delete them all. Nothing here leaves the phone.
 */
class MemoryActivity : UiActivity() {
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var count: android.widget.TextView
    private lateinit var list: LinearLayout
    private var items: List<MemoryItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shell("Memory")
        link(titleBar, "Done", "Close memory") { finish() }
        text(body, "What MySI remembers about you. It stays on this phone, and MySI only saves a memory after you approve it.", 13f, muted)
        val actions = row(body)
        listOf<Pair<String, () -> Unit>>("Add a memory" to { edit(null) }, "Delete all" to ::deleteAll).forEachIndexed { i, (label, act) ->
            button(actions, label, i == 0, act).layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(8) }
        }
        count = text(body, "", 12f, muted).apply { setTextIsSelectable(false) }
        list = column(body)
        load()
    }

    override fun onDestroy() {
        ui.cancel()
        super.onDestroy()
    }

    private fun load() {
        val ctx = applicationContext
        ui.launch {
            items = withContext(Dispatchers.IO) { runCatching { MemorySkill.allMemories(ctx) }.getOrElse { emptyList() } }
            show()
        }
    }

    private fun show() {
        list.removeAllViews()
        count.text = when (items.size) {
            0 -> "Nothing remembered yet. Ask MySI to remember something, or add it here."
            1 -> "1 memory"
            else -> "${items.size} memories"
        }
        for (m in items) {
            val c = card(list)
            text(c, m.text, 15f)
            text(c, "Topic: ${m.topic}", 12f, muted).setTextIsSelectable(false)
            val actions = row(c).apply { gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL }
            link(actions, "Edit", "Edit memory: ${m.text}") { edit(m) }
            link(actions, "Delete", "Delete memory: ${m.text}") { delete(m) }
        }
    }

    /** Adds a memory when [m] is null, otherwise edits it. The dialog stays open on a problem. */
    private fun edit(m: MemoryItem?) {
        val form = column().apply { setPadding(dp(22), dp(8), dp(22), 0) }
        val fact = EditText(this).apply {
            hint = "One short fact, e.g. I am vegetarian"
            setText(m?.text.orEmpty())
            filters = arrayOf(InputFilter.LengthFilter(MemoryBook.MAX_CHARS))
            minLines = 2
            maxLines = 6
        }
        val topic = EditText(this).apply {
            hint = "Topic (optional), e.g. food"
            setText(m?.topic?.takeIf { it != "general" }.orEmpty())
            filters = arrayOf(InputFilter.LengthFilter(40))
            maxLines = 1
        }
        form.addView(fact)
        form.addView(topic)
        val d = AlertDialog.Builder(this)
            .setTitle(if (m == null) "Add a memory" else "Edit memory")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val ctx = applicationContext
            val text = fact.text.toString()
            val t = topic.text.toString()
            ui.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        if (m == null) {
                            if (MemorySkill.addMemory(ctx, text, t).duplicate) "MySI already remembered that. Its topic is updated." else null
                        } else if (!MemorySkill.updateMemory(ctx, m.id, text, t)) "That memory was already deleted." else null
                    }
                }
                outcome.exceptionOrNull()?.let { e ->
                    // Empty, too long or a duplicate: say so and keep the dialog open.
                    return@launch toast(if (e is IllegalArgumentException) e.message ?: "Could not save." else "Could not save the memory.")
                }
                outcome.getOrNull()?.let(::toast)
                d.dismiss()
                load()
            }
        }
    }

    private fun delete(m: MemoryItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete this memory?")
            .setMessage(m.text)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val ctx = applicationContext
                ui.launch {
                    withContext(Dispatchers.IO) { runCatching { MemorySkill.deleteMemory(ctx, m.id) } }
                    load()
                }
            }
            .show()
    }

    private fun deleteAll() {
        if (items.isEmpty()) return toast("There are no memories to delete.")
        val n = items.size
        AlertDialog.Builder(this)
            .setTitle("Delete all memories?")
            .setMessage((if (n == 1) "MySI will forget its one memory." else "MySI will forget all $n memories.") + " This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete all") { _, _ ->
                val ctx = applicationContext
                ui.launch {
                    val deleted = withContext(Dispatchers.IO) { runCatching { MemorySkill.deleteAllMemories(ctx) }.getOrNull() }
                    toast(if (deleted == null) "Could not delete the memories." else "Deleted ${if (deleted == 1) "1 memory" else "$deleted memories"}.")
                    load()
                }
            }
            .show()
    }

    private fun toast(s: String) = Toast.makeText(applicationContext, s, Toast.LENGTH_LONG).show()
}
