package com.mysi.agent.models

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import com.mysi.agent.Agent
import com.mysi.agent.UiActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-tap model setup: two short questions, the model that suits this phone, then download,
 * check, load and a short speed test.
 *
 * Result contract (start with [intent] and `startActivityForResult`):
 * - [android.app.Activity.RESULT_OK]: a model was downloaded, checked and loaded; [Agent.ready]
 *   is true and [Agent.savedModel] is its path. Extras: [EXTRA_MODEL_PATH] (absolute path) and
 *   [EXTRA_MODEL_ID] (catalogue id, e.g. "gemma-4-e2b").
 * - [RESULT_USE_OWN_FILE]: the user tapped "Use a file I already have"; open the existing file
 *   picker ("Load model"). No extras.
 * - [android.app.Activity.RESULT_CANCELED]: the user left without a new model. When a download
 *   continues in the background, the result carries [EXTRA_SETUP_PENDING] = true.
 *
 * Helpers for the chat screen:
 * - [hasPendingSetup]: a download or its check is unfinished (for example after the app was
 *   killed). Open this screen again to finish it; it resumes on its own.
 * - [pruneUnusedModels]: call after the chat screen loads a model of its own (its "Load model"
 *   import), so models downloaded here that are no longer used are deleted.
 *
 * Uses from [UiActivity]: shell, body, text, button, card, row, column, space, dp, shape, ink, muted,
 * surface, sans and serif.
 */
class ModelSetupActivity : UiActivity() {
    private enum class Page { QUESTIONS, MAIN, OPTIONS, UNTESTED }

    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var catalogs: CatalogRepository
    private lateinit var catalog: Catalog
    private var device: DeviceProfile? = null
    private var page = Page.QUESTIONS
    private var use = Use.CHAT
    private var priority = Priority.BALANCED
    private var wifiOnly = true
    private val prefs by lazy { getSharedPreferences("model_setup_ui", MODE_PRIVATE) }
    private val listener: (SetupState) -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shell("Get a model")
        catalogs = CatalogRepository(this)
        catalog = catalogs.current()
        use = savedInstanceState?.getString(KEY_USE)?.let(::useOf) ?: prefs.getString(KEY_USE, null)?.let(::useOf) ?: Use.CHAT
        priority = savedInstanceState?.getString(KEY_PRIORITY)?.let(::priorityOf) ?: prefs.getString(KEY_PRIORITY, null)?.let(::priorityOf) ?: Priority.BALANCED
        wifiOnly = savedInstanceState?.getBoolean(KEY_WIFI) ?: prefs.getBoolean(KEY_WIFI, true)
        page = savedInstanceState?.getString(KEY_PAGE)?.let { runCatching { Page.valueOf(it) }.getOrNull() }
            ?: if (prefs.contains(KEY_USE)) Page.MAIN else Page.QUESTIONS
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, onBackInvokedCallback)
        ModelSetup.resume(this, catalog)
        refreshCatalog()
    }

    override fun onStart() {
        super.onStart()
        ModelSetup.observe(listener)
    }

    override fun onResume() {
        super.onResume()
        readDevice()
    }

    override fun onStop() {
        ModelSetup.unobserve(listener)
        super.onStop()
    }

    override fun onDestroy() {
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(onBackInvokedCallback)
        ui.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PAGE, page.name)
        outState.putString(KEY_USE, use.name)
        outState.putString(KEY_PRIORITY, priority.name)
        outState.putBoolean(KEY_WIFI, wifiOnly)
    }

    /**
     * Back from a sub-page returns to the main page; otherwise the screen closes with its result.
     * Android 16 delivers back (including the gesture) only to [onBackInvokedCallback]; Android 14
     * and 15 call [onBackPressed] unless the app opts in to the new callback, so both lead here.
     */
    private fun back() {
        if (!ModelSetup.state.busy && (page == Page.OPTIONS || page == Page.UNTESTED)) return show(Page.MAIN)
        finishWithState()
        if (ModelSetup.state is SetupState.Done) ModelSetup.acknowledge()
        finish()
    }

    private val onBackInvokedCallback = android.window.OnBackInvokedCallback { back() }

    @android.annotation.SuppressLint("GestureBackNavigation") // Android 16+ uses onBackInvokedCallback, registered in onCreate.
    @Deprecated("Plain Activity back handling; this app has no AndroidX activity dependency.")
    override fun onBackPressed() = back()

    /** Sets the result the caller sees, whichever way the screen closes. */
    private fun finishWithState() {
        when (val s = ModelSetup.state) {
            is SetupState.Done -> setResult(RESULT_OK, Intent().putExtra(EXTRA_MODEL_PATH, s.path).putExtra(EXTRA_MODEL_ID, s.modelId))
            else -> setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_SETUP_PENDING, s.busy))
        }
    }

    // ---------------- data ----------------

    private fun readDevice() {
        ui.launch {
            device = withContext(Dispatchers.IO) {
                val store = ModelStore.of(this@ModelSetupActivity)
                // Leftovers from an earlier attempt or an old model take space; clear them first.
                if (!ModelSetup.running) store.pruneUnused(Agent.savedModel, store.loadPending())
                DeviceProfiles.read(this@ModelSetupActivity, store.externalDir)
            }
            render()
        }
    }

    private fun refreshCatalog() {
        ui.launch {
            val changed = withContext(Dispatchers.IO) { runCatching { catalogs.refresh() }.getOrDefault(false) }
            if (changed) {
                catalog = catalogs.current()
                render()
            }
        }
    }

    private fun advice(d: DeviceProfile) = ModelAdvisor.advise(d, catalog, use, priority)

    // ---------------- drawing ----------------

    private fun show(p: Page) {
        page = p
        render()
        scroll.post { scroll.scrollTo(0, 0) }
        // The connection or free space may have changed since the screen opened.
        readDevice()
    }

    /** The views of a download or check card, updated in place on every progress tick. */
    private class Live(val kind: Class<*>, val bar: ProgressBar, val amount: TextView, val detail: TextView)

    private var live: Live? = null

    private fun render() {
        if (isFinishing || isDestroyed) return
        val s = ModelSetup.state
        // Progress ticks every second: update the same views, so TalkBack keeps its place and
        // nothing flickers. Anything else redraws the page.
        live?.takeIf { it.kind == s.javaClass }?.let { v ->
            when (s) {
                is SetupState.Downloading -> return updateDownload(v, s)
                is SetupState.Verifying -> return updateCheck(v, s)
                else -> Unit
            }
        }
        live = null
        body.removeAllViews()
        val d = device
        when {
            s.busy || s is SetupState.Done -> progress(s)
            d == null -> text(body, "Looking at this phone…", 15f, muted)
            page == Page.QUESTIONS -> questions()
            else -> {
                if (s is SetupState.Failed) problem(s.message)
                when (page) {
                    Page.OPTIONS -> options(d)
                    Page.UNTESTED -> untested(d)
                    else -> main(d)
                }
            }
        }
    }

    private fun heading(parent: LinearLayout, value: String, size: Float = 20f) =
        text(parent, value, size).apply {
            typeface = serif
            isAccessibilityHeading = true
        }

    private fun questions() {
        text(body, "MySI runs an AI model on this phone, so your chats stay private. Two quick questions help pick the best model for you.", 15f, muted)
        space()
        heading(body, "What will you mainly use MySI for?", 18f)
        Use.entries.forEach { u -> choice(u.label, use == u) { use = u; render() } }
        space()
        heading(body, "What matters more?", 18f)
        listOf(Priority.FASTER, Priority.BETTER).forEach { p ->
            choice(p.label, priority == p) { priority = if (priority == p) Priority.BALANCED else p; render() }
        }
        text(body, "Tap again to clear. No answer means a balance of both.", 12f, muted)
        space()
        button(body, "Continue", primary = true) {
            prefs.edit().putString(KEY_USE, use.name).putString(KEY_PRIORITY, priority.name).apply()
            show(Page.MAIN)
        }
        button(body, "Skip") {
            use = Use.CHAT
            priority = Priority.BALANCED
            prefs.edit().putString(KEY_USE, use.name).putString(KEY_PRIORITY, priority.name).apply()
            show(Page.MAIN)
        }
    }

    /** A selectable answer. The tick and the spoken "Selected" carry the state, not colour alone. */
    private fun choice(label: String, selected: Boolean, action: () -> Unit) =
        button(body, if (selected) "✓  $label" else label, primary = selected, action = action).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isSelected = selected
            contentDescription = label
            stateDescription = if (selected) "Selected" else "Not selected"
        }

    private fun phoneLine(d: DeviceProfile) =
        text(body, "This phone: ${d.ramGb} GB memory · ${Format.size(d.freeStorageBytes)} free · ${d.chip}", 12f, muted).apply {
            contentDescription = "This phone has ${d.ramGb} GB of memory and ${Format.size(d.freeStorageBytes)} of free storage. Chip: ${d.chip}."
        }

    private fun main(d: DeviceProfile) {
        val a = advice(d)
        phoneLine(d)
        if (Agent.ready) Agent.savedModel?.let { text(body, "In use now: ${java.io.File(it).name}", 12f, muted) }
        val pick = a.recommended
        if (pick != null) {
            val c = card()
            text(c, "Recommended for your phone", 12f, muted, bold = true).isAccessibilityHeading = true
            modelDetails(c, pick)
            button(c, "Download and start", primary = true) { download(pick.entry) }
            source(c, pick.entry)
            wifiSwitch(c, d)
        } else {
            val c = card()
            heading(c, "No recommended model for this phone", 18f)
            text(c, a.noRecommendation ?: "", 14f, muted)
        }
        button(body, "See other options") { show(Page.OPTIONS) }
        button(body, "Other models (untested)") { show(Page.UNTESTED) }
        button(body, "Use a file I already have") {
            setResult(RESULT_USE_OWN_FILE)
            finish()
        }
        link("Change my answers (${use.label.lowercase()}; ${priority.label.lowercase()})") { show(Page.QUESTIONS) }
    }

    private fun modelDetails(c: LinearLayout, choice: Choice) {
        val e = choice.entry
        heading(c, e.name, 22f)
        text(c, "${Format.size(e.sizeBytes)} download · made by ${e.publisher}", 13f, muted)
        text(c, choice.reason, 15f)
        choice.warnings.forEach { w -> text(c, "Note: $w", 13f, WARN) }
        if (e.notes.isNotBlank()) text(c, e.notes, 13f, muted)
    }

    /** What the download is: size, where it comes from, and its licence. */
    private fun source(c: LinearLayout, e: ModelEntry) =
        text(c, "${Format.size(e.sizeBytes)}, downloaded from Google's page on Hugging Face (huggingface.co/${e.repo}). Licence: ${e.licenceText}. Checked against its published fingerprint before use.", 12f, muted)

    private fun wifiSwitch(c: LinearLayout, d: DeviceProfile) {
        val sw = Switch(this).apply {
            text = "Wi-Fi only"
            textSize = 14f
            typeface = sans
            setTextColor(ink)
            isChecked = wifiOnly
            minHeight = dp(48)
        }
        c.addView(sw, LinearLayout.LayoutParams(-1, -2))
        val note = text(c, wifiNote(d), 12f, muted)
        sw.setOnCheckedChangeListener { _, on ->
            wifiOnly = on
            prefs.edit().putBoolean(KEY_WIFI, on).apply()
            note.text = wifiNote(d)
        }
    }

    private fun wifiNote(d: DeviceProfile): String =
        when {
            !wifiOnly -> "Uses mobile data when there's no Wi-Fi."
            d.unmetered -> "You're on Wi-Fi now. If Wi-Fi drops, the download waits for it."
            d.online -> "You're on mobile data now, so the download waits for Wi-Fi."
            else -> "You're offline now. The download starts on Wi-Fi."
        }

    private fun options(d: DeviceProfile) {
        val a = advice(d)
        heading(body, "Other options")
        phoneLine(d)
        val list = listOfNotNull(a.recommended) + a.options
        if (list.isEmpty()) text(body, "No tested model fits this phone.", 15f, muted)
        list.forEach { ch ->
            val c = card()
            if (ch === a.recommended) text(c, "Recommended", 12f, muted, bold = true)
            modelDetails(c, ch)
            button(c, "Download ${ch.entry.name}", primary = ch === a.recommended) { download(ch.entry) }
            source(c, ch.entry)
        }
        val out = a.excluded.filter { it.entry.verified && !it.entry.testOnly }
        if (out.isNotEmpty()) {
            space()
            heading(body, "Not available on this phone", 18f)
            out.forEach { x -> excluded(x) }
        }
        if (list.isNotEmpty()) wifiCard(d)
        button(body, "Back") { show(Page.MAIN) }
    }

    private fun untested(d: DeviceProfile) {
        val a = advice(d)
        heading(body, "Other models (untested)")
        text(body, "These models have not been tested with MySI. MySI's tools, photos and audio may not work with them, and some may not load at all. MySI never picks them for you.", 14f, WARN)
        a.untested.forEach { ch ->
            val c = card()
            modelDetails(c, ch)
            button(c, "Download ${ch.entry.name} anyway") { download(ch.entry) }
            source(c, ch.entry)
        }
        val out = a.excluded.filter { !it.entry.verified || it.entry.testOnly }
        if (out.isNotEmpty()) {
            space()
            heading(body, "Not available on this phone", 18f)
            out.forEach { x -> excluded(x) }
        }
        if (a.untested.isNotEmpty()) wifiCard(d)
        button(body, "Back") { show(Page.MAIN) }
    }

    private fun wifiCard(d: DeviceProfile) = wifiSwitch(card(), d)

    private fun excluded(x: Exclusion) {
        val c = card()
        text(c, x.entry.label, 15f, bold = true)
        x.reasons.forEach { r -> text(c, "Not available: $r", 13f, muted) }
    }

    private fun problem(message: String) {
        val c = card()
        text(c, "Something went wrong", 13f, WARN, bold = true)
        text(c, message, 15f)
        link("Dismiss", c) { ModelSetup.acknowledge() }
    }

    private fun link(label: String, parent: LinearLayout = body, action: () -> Unit) =
        text(parent, label, 13f, muted).apply {
            setTextIsSelectable(false)
            minHeight = dp(44)
            gravity = Gravity.CENTER_VERTICAL
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { action() }
        }

    private fun bar(parent: LinearLayout, done: Long, total: Long, label: String): ProgressBar {
        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            setBar(this, done, total, label)
        }
        parent.addView(pb, LinearLayout.LayoutParams(-1, dp(12)).apply { bottomMargin = dp(10) })
        return pb
    }

    private fun setBar(pb: ProgressBar, done: Long, total: Long, label: String) {
        pb.isIndeterminate = total <= 0
        pb.progress = if (total > 0) (done.coerceAtMost(total) * 1000 / total).toInt() else 0
        pb.contentDescription = label
    }

    private fun updateDownload(v: Live, s: SetupState.Downloading) {
        val pct = Format.percent(s.done, s.total)
        setBar(v.bar, s.done, s.total, "Download $pct percent")
        v.amount.text = "${Format.progress(s.done, s.total)} ($pct%)"
        // Waiting is shown in words, with the amber only as a second cue.
        v.detail.text = s.paused ?: "${Format.speed(s.bytesPerSecond)} · ${Format.timeLeft(s.secondsLeft)}"
        v.detail.setTextColor(if (s.paused != null) WARN else muted)
    }

    private fun updateCheck(v: Live, s: SetupState.Verifying) {
        val pct = Format.percent(s.done, s.total)
        setBar(v.bar, s.done, s.total, "Checking $pct percent")
        v.amount.text = "Checked ${Format.progress(s.done, s.total)} ($pct%)"
    }

    private fun progress(s: SetupState) {
        val c = card()
        when (s) {
            is SetupState.Downloading -> {
                heading(c, "Downloading ${s.name}", 20f)
                val v = Live(s.javaClass, bar(c, 0, 0, ""), text(c, "", 15f), text(c, "", 13f, muted))
                text(c, "You can leave this screen or close MySI; the download carries on. Open this screen again to finish.", 12f, muted)
                button(c, "Cancel download") { ModelSetup.cancel(this) }
                live = v
                updateDownload(v, s)
            }
            is SetupState.Verifying -> {
                heading(c, "Checking the download", 20f)
                val v = Live(s.javaClass, bar(c, 0, 0, ""), text(c, "", 15f), text(c, "Making sure ${s.name} is exactly the published file.", 14f, muted))
                button(c, "Cancel") { ModelSetup.cancel(this) }
                live = v
                updateCheck(v, s)
            }
            is SetupState.Loading -> {
                heading(c, "Starting ${s.name}", 20f)
                bar(c, 0, -1, "Starting the model")
                text(c, "This can take a minute the first time.", 14f, muted)
            }
            is SetupState.Checking -> {
                heading(c, "${s.name} is loaded", 20f)
                bar(c, 0, -1, "Testing speed")
                text(c, "Testing how fast it replies on this phone…", 14f, muted)
            }
            is SetupState.Done -> {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_MODEL_PATH, s.path).putExtra(EXTRA_MODEL_ID, s.modelId))
                heading(c, "${s.name} is ready", 22f)
                text(c, s.verdict?.message ?: "It's loaded and ready to chat.", 15f)
                button(c, "Start chatting", primary = true) {
                    finishWithState()
                    ModelSetup.acknowledge()
                    finish()
                }
                val suggest = when (val v = s.verdict) {
                    is FitVerdict.TooSlow -> v.suggest
                    is FitVerdict.Tight -> v.suggest
                    is FitVerdict.Roomy -> v.suggest
                    else -> null
                }
                if (suggest != null) {
                    button(c, "Switch to ${suggest.name} (${Format.size(suggest.sizeBytes)})") {
                        ModelSetup.acknowledge()
                        download(suggest)
                    }
                    text(c, "Only if you want to: MySI never switches model on its own.", 12f, muted)
                }
            }
            else -> Unit
        }
    }

    private fun download(e: ModelEntry) {
        ModelSetup.acknowledge()
        ModelSetup.start(this, e, wifiOnly, catalog)
        scroll.post { scroll.scrollTo(0, 0) }
    }

    companion object {
        /** The user chose "Use a file I already have": open the file picker. */
        const val RESULT_USE_OWN_FILE = RESULT_FIRST_USER
        const val EXTRA_MODEL_PATH = "com.mysi.agent.models.MODEL_PATH"
        const val EXTRA_MODEL_ID = "com.mysi.agent.models.MODEL_ID"
        const val EXTRA_SETUP_PENDING = "com.mysi.agent.models.SETUP_PENDING"

        private const val KEY_PAGE = "page"
        private const val KEY_USE = "use"
        private const val KEY_PRIORITY = "priority"
        private const val KEY_WIFI = "wifi_only"

        /** Amber for notes and problems; always paired with words ("Note:", "Something went wrong"). */
        private val WARN = Color.rgb(255, 196, 140)

        fun intent(context: Context) = Intent(context, ModelSetupActivity::class.java)

        /** True while a model download or its check is unfinished; open [intent] to finish it. */
        fun hasPendingSetup(context: Context): Boolean = ModelSetup.state.busy || ModelStore.of(context).loadPending() != null

        /** Deletes downloaded models that are no longer the loaded one. Blocking file work: call off the main thread. */
        fun pruneUnusedModels(context: Context) {
            if (ModelSetup.running) return
            val store = ModelStore.of(context)
            store.pruneUnused(Agent.savedModel, store.loadPending())
        }

        private fun useOf(s: String) = runCatching { Use.valueOf(s) }.getOrNull()

        private fun priorityOf(s: String) = runCatching { Priority.valueOf(s) }.getOrNull()
    }
}
