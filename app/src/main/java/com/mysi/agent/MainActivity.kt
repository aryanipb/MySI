package com.mysi.agent

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class MainActivity : UiActivity(), Host {
    private lateinit var status: TextView
    private lateinit var setup: LinearLayout
    private lateinit var log: LinearLayout
    private lateinit var input: EditText
    private var photo: CompletableDeferred<Boolean>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shell("MySI")
        status = text(body, "", 13f, muted)
        val actions = row(body)
        listOf<Pair<String, () -> Unit>>(
                "Grant access" to ::grantAll,
                "Load model" to ::pickModel,
                "New chat" to { Agent.newChat() },
            )
            .forEachIndexed { i, (label, act) ->
                button(actions, label, i == 1, act).layoutParams =
                    LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(8) }
            }
        space(body, 6)
        setup = column(body)
        log = column(body)
        composer()

        Agent.attach(this)
        Agent.history.forEach(::render)
        Agent.listener = ::render
        if (!Agent.ready) {
            val saved = Agent.savedModel
            if (saved != null && File(saved).canRead()) Agent.scope.launch { Agent.load(saved) }
            else status.text = "No model loaded. Download a .litertlm model, then tap Load model."
        } else status.text = "Ready · ${Agent.backend} · ${Agent.toolCount} tools"
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
    }

    override fun onDestroy() {
        if (Agent.listener == this::render) Agent.listener = null
        super.onDestroy()
    }

    // ---------------- chat ----------------

    private fun composer() {
        val bar = row(root).apply { setPadding(dp(14), dp(8), dp(14), dp(10)) }
        input =
            EditText(this).apply {
                hint = "Ask MySI to do anything on this phone"
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
        status.text = "Thinking…"
        Agent.scope.launch { Agent.ask(q) }
    }

    private fun render(e: Event) {
        when (e) {
            is Event.Status -> status.text = e.text
            is Event.User -> card(log, Color.rgb(28, 29, 35)).also { text(it, e.text, 15f) }
            is Event.ToolCall -> text(log, "⚙  ${e.name}(${e.args})", 12f, muted).typeface = Typeface.MONOSPACE
            is Event.ToolResult -> text(log, "↳ ${e.text.take(280)}", 12f, muted).apply { setPadding(dp(14), 0, 0, 0) }
            is Event.Reply -> {
                card(log).also { text(it, e.text, 15f) }
                status.text = "Ready · ${Agent.backend} · ${Agent.toolCount} tools"
            }
            is Event.Error -> {
                text(log, "⚠ ${e.text}", 13f, Color.rgb(255, 160, 150))
                status.text = "Error"
            }
        }
        scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
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
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(title)
                    .setMessage(message)
                    .setCancelable(false)
                    .setNegativeButton("Deny") { _, _ -> cont.resume(false) }
                    .setPositiveButton("Allow") { _, _ -> cont.resume(true) }
                    .show()
            }
        }

    override suspend fun takePhoto(file: File): Boolean {
        val done = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            photo = done
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
            startActivityForResult(
                Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
                REQ_PHOTO,
            )
        }
        return done.await()
    }

    @Deprecated("Plain Activity result API; this app has no AndroidX activity dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PHOTO -> photo?.complete(resultCode == RESULT_OK).also { photo = null }
            REQ_VOICE ->
                data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                    input.setText(it)
                    send()
                }
            REQ_MODEL -> data?.data?.takeIf { resultCode == RESULT_OK }?.let(::importModel)
        }
    }

    // ---------------- model ----------------

    private fun pickModel() {
        val found =
            if (Environment.isExternalStorageManager())
                listOf("Download", "Documents", "")
                    .flatMap { File(Environment.getExternalStorageDirectory(), it).listFiles()?.toList().orEmpty() }
                    .filter { it.isFile && it.name.endsWith(".litertlm") }
                    .distinct()
            else emptyList()
        val labels = found.map { "${it.name}  ·  ${"%.1f".format(it.length() / 1e9)} GB" } + "Browse for a file…"
        AlertDialog.Builder(this)
            .setTitle(if (found.isEmpty()) "No .litertlm in Download yet" else "Choose a model")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i < found.size) Agent.scope.launch { Agent.load(found[i].absolutePath) }
                else
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                        REQ_MODEL,
                    )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Copies a picked model into app storage, since the engine needs a real file path. */
    private fun importModel(uri: Uri) {
        val name =
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "model.litertlm"
        val dest = File(File(filesDir, "models").apply { mkdirs() }, name)
        status.text = "Copying $name into the app…"
        Agent.scope.launch {
            val ok =
                withContext(Dispatchers.IO) {
                    runCatching {
                            contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { i.copyTo(it, 1 shl 20) } }
                        }
                        .isSuccess
                }
            if (ok) Agent.load(dest.absolutePath) else status.text = "Copy failed."
        }
    }

    // ---------------- permissions ----------------

    private fun grantAll() {
        val missing = RUNTIME.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)
        else toast("All app permissions granted. Finish the special access items below.")
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        refreshSetup()
    }

    private fun refreshSetup() {
        setup.removeAllViews()
        val pm = getSystemService(PowerManager::class.java)
        val items =
            listOf(
                Triple("App permissions", RUNTIME.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) { grantAll() },
                Triple("All files access", Environment.isExternalStorageManager()) {
                    open(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                },
                Triple("Screen control (Accessibility)", AgentAccessibilityService.instance != null) {
                    open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                Triple("Notification access", notificationAccess()) {
                    open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
                Triple("Modify system settings", Settings.System.canWrite(this)) {
                    open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
                },
                Triple("Run in background", pm.isIgnoringBatteryOptimizations(packageName)) {
                    open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                },
            )
        if (items.all { it.second }) return
        val c = card(setup)
        text(c, "Setup", 18f).typeface = serif
        text(c, "Tap each item to give the agent full access.", 12f, muted)
        items.forEach { (label, ok, act) ->
            text(c, (if (ok) "✓  " else "○  ") + label, 15f, if (ok) muted else ink).apply {
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(44)
                setTextIsSelectable(false)
                if (!ok) {
                    isClickable = true
                    setOnClickListener { act() }
                    Design.press(this)
                }
            }
        }
        text(c, "If Android says “Restricted setting” for Accessibility: App info → ⋮ → Allow restricted settings.", 12f, muted)
            .apply {
                setTextIsSelectable(false)
                setOnClickListener { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            }
    }

    private fun notificationAccess() =
        getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(this, NotificationReader::class.java))

    private fun open(i: Intent) =
        try {
            startActivity(i)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_PHOTO = 2
        private const val REQ_VOICE = 3
        private const val REQ_MODEL = 4
        private val RUNTIME =
            listOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.BODY_SENSORS,
                Manifest.permission.ACTIVITY_RECOGNITION,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )
    }
}
