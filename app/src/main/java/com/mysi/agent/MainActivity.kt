package com.mysi.agent

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : UiActivity(), Host {
    private lateinit var status: TextView
    private lateinit var log: LinearLayout
    private lateinit var input: EditText
    private var dialog: Pair<AlertDialog, CancellableContinuation<Boolean>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shell("MySI")
        status = text(body, "", 13f, muted)
        val actions = row(body)
        listOf<Pair<String, () -> Unit>>(
                "Load model" to ::pickModel,
                "New chat" to { Agent.newChat() },
            )
            .forEachIndexed { i, (label, act) ->
                button(actions, label, i == 0, act).layoutParams =
                    LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(8) }
            }
        // Google Play's User Data policy wants the privacy policy reachable inside the app.
        text(body, "Privacy policy", 12f, muted).apply {
            setTextIsSelectable(false)
            minHeight = dp(40)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_URL))) }
                    .onFailure { toast("No browser to open ${BuildConfig.PRIVACY_URL}") }
            }
        }
        log = column(body)
        composer()

        Agent.attach(this)
        Agent.history.forEach(::render)
        Agent.listener = ::render
        if (!Agent.ready) {
            val saved = Agent.savedModel
            if (saved != null && File(saved).canRead()) Agent.scope.launch { Agent.load(saved) }
            else status.text = "No model loaded. Download a Gemma 4 .litertlm model (huggingface.co/litert-community), then tap Load model."
        } else status.text = "Ready · ${Agent.backend} · ${Agent.toolCount} tools"
    }

    override var inFront = false

    override fun onPause() {
        inFront = false
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        inFront = true
    }

    override fun onDestroy() {
        if (Agent.listener == this::render) Agent.listener = null
        // A dialog dies with its activity; answer "no" so the agent is never left waiting.
        dialog?.let { (d, cont) ->
            d.dismiss()
            if (cont.isActive) cont.resume(false)
        }
        dialog = null
        super.onDestroy()
    }

    // ---------------- chat ----------------

    private fun composer() {
        val bar = row(root).apply { setPadding(dp(14), dp(8), dp(14), dp(10)) }
        input =
            EditText(this).apply {
                hint = "Ask MySI for help on this phone"
                setTextColor(ink)
                setHintTextColor(muted)
                typeface = sans
                textSize = 15f
                background = shape(surface, 14)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                maxLines = 5
                imeOptions = EditorInfo.IME_ACTION_SEND
                setRawInputType(EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES)
                setOnEditorActionListener { _, id, _ -> (id == EditorInfo.IME_ACTION_SEND).also { if (it) send() } }
            }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        button(bar, "🎙") { voice() }.apply {
            contentDescription = "Speak"
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(48)).apply { marginStart = dp(8) }
        }
        button(bar, "Send", true) { send() }.layoutParams =
            LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(8) }
    }

    private fun send() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        input.setText("")
        if (!Agent.ready) return toast("Load a model first.")
        // Approvals arrive as notifications when another app is in front. Ask once, before the
        // first request runs, so the system prompt never pauses MySI in the middle of a task.
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("asked_notifications", false)
        ) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            queued = q
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            return
        }
        start(q)
    }

    private fun start(q: String) {
        status.text = "Thinking…"
        Agent.scope.launch { Agent.ask(q) }
    }

    private fun render(e: Event) {
        when (e) {
            is Event.Status -> status.text = e.text
            is Event.User ->
                card(log, Color.rgb(28, 29, 35)).also {
                    text(it, "You", 11f, muted, bold = true)
                    text(it, e.text, 15f)
                }
            is Event.ToolCall -> text(log, "⚙  ${e.name}(${e.args})", 12f, muted).typeface = Typeface.MONOSPACE
            is Event.ToolResult -> text(log, "↳ ${e.text.take(700)}", 12f, muted).apply { setPadding(dp(14), 0, 0, 0) }
            is Event.Reply -> {
                card(log).also {
                    text(it, "MySI", 11f, muted, bold = true).typeface = serif
                    text(it, e.text, 15f)
                    text(it, "Report this reply", 12f, muted).apply {
                        setTextIsSelectable(false)
                        gravity = Gravity.CENTER_VERTICAL or Gravity.END
                        minHeight = dp(40)
                        isClickable = true
                        isFocusable = true
                        contentDescription = "Report this reply as offensive or harmful"
                        setOnClickListener { report(e) }
                    }
                }
                status.text = "Ready · ${Agent.backend} · ${Agent.toolCount} tools"
            }
            is Event.Error -> {
                text(log, "⚠ ${e.text}", 13f, Color.rgb(255, 160, 150))
                status.text = "Error"
            }
        }
        scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    /** Google Play's AI-Generated Content policy: report offensive output without leaving the app. */
    private fun report(reply: Event.Reply) {
        val form = column().apply { setPadding(dp(22), dp(8), dp(22), 0) }
        val note = TextView(this).apply {
            text = "Tell us what is wrong with this AI reply. Only what you send here is shared with the MySI team, to improve safety."
            textSize = 13f
        }
        form.addView(note)
        val group = RadioGroup(this)
        val choices = Reports.categories.map { c ->
            RadioButton(this).apply {
                id = android.view.View.generateViewId()
                text = c
            }.also(group::addView)
        }
        choices.first().isChecked = true
        form.addView(group)
        val details = EditText(this).apply {
            hint = "Details (optional)"
            maxLines = 4
        }
        form.addView(details)
        val includePrompt = CheckBox(this).apply {
            text = "Include my message that led to this reply"
            isChecked = true
        }
        form.addView(includePrompt)
        AlertDialog.Builder(this)
            .setTitle("Report reply")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send") { _, _ ->
                val category = Reports.categories[choices.indexOfFirst { it.isChecked }.coerceAtLeast(0)]
                Agent.scope.launch {
                    val error = Reports.send(
                        category,
                        details.text.toString().trim(),
                        reply.text,
                        reply.prompt.takeIf { includePrompt.isChecked },
                        Agent.savedModel?.let { File(it).name },
                    )
                    toast(error ?: "Report sent. Thank you.")
                }
            }
            .show()
    }

    private fun voice() =
        try {
            startActivityForResult(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true),
                REQ_VOICE,
            )
        } catch (e: Exception) {
            toast("No speech recogniser on this phone.")
        }

    // ---------------- Host ----------------

    override suspend fun confirm(title: String, message: String): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val d = AlertDialog.Builder(this@MainActivity)
                    .setTitle(title)
                    .setMessage(message)
                    .setCancelable(false)
                    .setNegativeButton("Deny") { _, _ -> if (cont.isActive) cont.resume(false) }
                    .setPositiveButton("Allow") { _, _ -> if (cont.isActive) cont.resume(true) }
                    .setOnDismissListener { dialog = null }
                    .show()
                dialog = d to cont
                cont.invokeOnCancellation { d.dismiss() }
            }
        }

    /**
     * Starts a system screen and waits for its result. The wait lives in the companion object, so
     * a result still arrives if the activity is recreated (e.g. rotated) in the meantime.
     */
    private suspend fun forResult(code: Int, intent: Intent): Pair<Int, Intent?>? {
        val done = CompletableDeferred<Pair<Int, Intent?>>()
        results[code] = done
        try {
            val started = withContext(Dispatchers.Main) {
                runCatching { startActivityForResult(intent, code) }.isSuccess
            }
            if (!started) return null
            return withTimeoutOrNull(RESULT_TIMEOUT_MS) { done.await() }
        } finally {
            results.remove(code, done)
        }
    }

    override suspend fun takePhoto(file: File): Boolean {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val r = forResult(
            REQ_PHOTO,
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        return r?.first == RESULT_OK
    }

    override suspend fun pick(intent: Intent): List<Uri> {
        val (code, data) = forResult(REQ_PICK, intent) ?: return emptyList()
        if (code != RESULT_OK || data == null) return emptyList()
        val clip = data.clipData
        return if (clip != null) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } else listOfNotNull(data.data)
    }

    override suspend fun requestPermissions(permissions: List<String>) {
        val done = CompletableDeferred<Unit>()
        permissionWait = done
        withContext(Dispatchers.Main) { requestPermissions(permissions.toTypedArray(), REQ_PERMS) }
        withTimeoutOrNull(RESULT_TIMEOUT_MS) { done.await() }
        if (permissionWait === done) permissionWait = null
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_PERMS) permissionWait?.complete(Unit)
        if (code == REQ_NOTIFICATIONS) queued?.let {
            queued = null
            start(it)
        }
    }

    @Deprecated("Plain Activity result API; this app has no AndroidX activity dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_VOICE ->
                data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                    input.setText(it)
                    send()
                }
            REQ_MODEL -> data?.data?.takeIf { resultCode == RESULT_OK }?.let(::importModel)
            else -> results.remove(requestCode)?.complete(resultCode to data)
        }
    }

    // ---------------- model ----------------

    private fun pickModel() =
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                REQ_MODEL,
            )
        } catch (e: Exception) {
            toast("No file picker on this phone.")
        }

    /**
     * Copies a picked model into app storage, since the engine needs a real file path. Each import
     * gets its own folder so the model in use is never overwritten; older copies are deleted once
     * the new one loads, so only one multi-gigabyte model is kept.
     */
    private fun importModel(uri: Uri) {
        var name = "model.litertlm"
        var size = -1L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                it.getString(0)?.let { n -> name = n.replace('/', '_') }
                if (!it.isNull(1)) size = it.getLong(1)
            }
        }
        if (!name.endsWith(".litertlm", true)) return toast("Pick a .litertlm model file.")
        val models = File(filesDir, "models").apply { mkdirs() }
        if (size > 0 && models.usableSpace < size + 200L * 1024 * 1024)
            return toast("Not enough free space: the model needs ${"%.1f".format(size / 1e9)} GB.")
        val dir = File(models, System.currentTimeMillis().toString())
        val dest = File(dir.apply { mkdirs() }, name)
        status.text = "Copying $name into the app…"
        Agent.scope.launch {
            val ok =
                withContext(Dispatchers.IO) {
                    runCatching {
                            contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { i.copyTo(it, 1 shl 20) } }
                        }
                        .isSuccess
                }
            if (!ok) {
                withContext(Dispatchers.IO) { dir.deleteRecursively() }
                status.text = "Copy failed. Check free space and try again."
                return@launch
            }
            if (Agent.load(dest.absolutePath))
                withContext(Dispatchers.IO) { models.listFiles()?.filter { it != dir }?.forEach { it.deleteRecursively() } }
            else withContext(Dispatchers.IO) { dir.deleteRecursively() }
        }
    }

    // Application context: reports and imports finish on the agent scope, possibly after this activity is gone.
    private fun toast(s: String) = android.widget.Toast.makeText(applicationContext, s, android.widget.Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_PHOTO = 2
        private const val REQ_VOICE = 3
        private const val REQ_MODEL = 4
        private const val REQ_PICK = 5
        private const val REQ_NOTIFICATIONS = 6
        private const val RESULT_TIMEOUT_MS = 10 * 60_000L
        private val results = ConcurrentHashMap<Int, CompletableDeferred<Pair<Int, Intent?>>>()
        @Volatile private var permissionWait: CompletableDeferred<Unit>? = null
        /** The first request, held while the notification prompt is up; survives recreation. */
        private var queued: String? = null
    }
}
