package com.mysi.agent

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.content.FileProvider
import com.mysi.agent.models.LiteRtLmFile
import com.mysi.agent.models.ModelSetupActivity
import com.mysi.agent.models.ModelStore
import com.mysi.agent.senses.LiveVisionActivity
import com.mysi.agent.senses.Media
import com.mysi.agent.senses.Recorder
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : UiActivity(), Host {
    private lateinit var status: TextView
    private lateinit var modelButton: TextView
    private lateinit var log: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendButton: TextView
    private lateinit var cameraButton: ImageView
    private lateinit var micButton: ImageView
    private lateinit var chipsScroll: HorizontalScrollView
    private lateinit var chipsRow: LinearLayout
    private lateinit var recPanel: LinearLayout
    private lateinit var recClock: TextView
    private lateinit var recMeter: LevelBar
    private lateinit var recHint: TextView

    /** The reply card being streamed into, and its text. */
    private var live: Pair<LinearLayout, TextView>? = null

    /** The card showing the latest step of a long job, and its text. */
    private var working: Pair<LinearLayout, TextView>? = null
    private var dialog: Pair<AlertDialog, CancellableContinuation<Boolean>>? = null

    /** Lives as long as this screen: recordings and thumbnails stop with it. */
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val talk = PushToTalk()
    private var recording: Job? = null
    private var latch: StopLatch? = null
    private var downX = 0f
    private var downY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shell("MySI")
        status = text(body, "", 13f, muted)
        val actions = row(body)
        // The primary action opens model setup ("Get a model", "Change model" or "Show download");
        // its "Use a file I already have" leads to the file picker.
        modelButton = button(actions, ModelButton.GET, true, ::openSetup).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        button(actions, "New chat", false) { Agent.newChat() }.layoutParams =
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) }
        val screens = row(body)
        listOf<Pair<String, () -> Unit>>(
                "Skills" to { startActivity(Intent(this, SkillsActivity::class.java)) },
                "Memory" to { startActivity(Intent(this, MemoryActivity::class.java)) },
            )
            .forEachIndexed { i, (label, act) ->
                button(screens, label, false, act).layoutParams =
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

        shown = WeakReference(this)
        Agent.attach(this)
        Agent.history.forEach(::render)
        Agent.listener = ::render
        renderChips()
        syncBusy()
        if (!Agent.ready) {
            val saved = Agent.savedModel
            if (saved != null && File(saved).canRead()) Agent.scope.launch { Agent.load(saved) }
            else status.text = if (ModelSetupActivity.hasPendingSetup(this)) ModelButton.DOWNLOADING else ModelButton.NO_MODEL
        } else status.text = readyText()
        syncModelButton()
        recoverInterruptedImport()
    }

    private fun readyText() = "Ready · ${Agent.backend} · ${Agent.toolCount} tools"

    private fun syncModelButton() {
        val label = ModelButton.label(Agent.ready, ModelSetupActivity.hasPendingSetup(this))
        modelButton.text = label
        modelButton.contentDescription = label
    }

    private fun openSetup() {
        setupShown = true
        startActivityForResult(ModelSetupActivity.intent(this), REQ_SETUP)
    }

    /**
     * Once per process: a "Load model" import whose load never finished (the model may have
     * crashed the app) is deleted rather than retried, and the user is told why.
     */
    private fun recoverInterruptedImport() {
        if (importChecked) return
        importChecked = true
        val app = applicationContext
        ui.launch {
            val message = withContext(Dispatchers.IO) { ModelStore.of(app).recoverInterruptedImport(Agent.savedModel) } ?: return@launch
            text(log, "⚠ $message", 13f, Color.rgb(255, 160, 150))
            if (!Agent.ready && Agent.savedModel == null) status.text = message
        }
    }

    override var inFront = false

    override fun onPause() {
        inFront = false
        // Android hands a background app silence, so a recording never outlives the screen.
        if (talk.recording) act(talk.cancel(), quiet = true)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        inFront = true
        // The sense buttons and Send/Stop follow whatever happened while another screen was in front.
        syncBusy()
        syncModelButton()
        // An interrupted download needs the setup screen to finish; open it once, then only offer it.
        if (ModelButton.openSetupOnItsOwn(ModelSetupActivity.hasPendingSetup(this), setupShown)) openSetup()
    }

    override fun onDestroy() {
        if (Agent.listener == this::render) Agent.listener = null
        if (shown?.get() === this) shown = null
        ui.cancel()
        // A dialog dies with its activity; answer "no" so the agent is never left waiting.
        dialog?.let { (d, cont) ->
            d.dismiss()
            if (cont.isActive) cont.resume(false)
        }
        dialog = null
        super.onDestroy()
    }

    // ---------------- composer ----------------

    private fun composer() {
        val box = column(root).apply { setPadding(dp(14), dp(6), dp(14), dp(10)) }
        chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
        }
        chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipsScroll.addView(chipsRow)
        box.addView(chipsScroll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        recPanel = row(box).apply {
            visibility = View.GONE
            background = shape(surface, 14)
            setPadding(dp(14), dp(4), dp(4), dp(4))
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(8)
        }
        text(recPanel, "●", 14f, Color.rgb(255, 120, 110)).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
        }
        recClock = text(recPanel, "", 14f).apply {
            setTextIsSelectable(false)
            typeface = Typeface.create(sans, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) }
        }
        recMeter = LevelBar(this).apply {
            fill = ink
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        recPanel.addView(recMeter, LinearLayout.LayoutParams(0, dp(6), 1f).apply { marginEnd = dp(8) })
        link(recPanel, "Cancel", "Cancel and discard the recording") { act(talk.cancel()) }
        recHint = text(box, "", 12f, muted).apply {
            setTextIsSelectable(false)
            visibility = View.GONE
            // Read out when it changes, so TalkBack users hear that recording started.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }

        val bar = row(box)
        iconButton(bar, R.drawable.ic_attach, "Attach photos or files") { attach() }
        cameraButton = iconButton(bar, R.drawable.ic_camera, "Take a photo with the camera") { camera() }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(6)
        }
        input =
            EditText(this).apply {
                // Short enough for one line beside the attach, camera and microphone buttons.
                hint = "Ask MySI"
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
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        micButton = iconButton(bar, R.drawable.ic_mic, "Talk to MySI") { micClick() }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(6)
            holdToTalk(this)
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    // TalkBack says "double-tap to start recording" / "to stop and send".
                    val label = if (talk.recording) "stop and send" else "start recording"
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, label))
                }
            }
        }
        sendButton = button(bar, "Send", true) { if (Agent.busy) Agent.stop() else send() }
        sendButton.layoutParams = LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(6) }
    }

    /** Send turns into Stop while a request runs; the sense buttons follow the loaded model. */
    private fun syncBusy() {
        val label = if (Agent.busy) "Stop" else "Send"
        sendButton.text = label
        sendButton.contentDescription = if (Agent.busy) "Stop MySI" else "Send"
        if (!Agent.busy) resolveWorking()
        cameraButton.alpha = if (Composer.Gate.camera(Agent.ready, Agent.canSee) == null) 1f else DIM
        micButton.alpha = if (talk.recording || Composer.Gate.mic(Agent.ready, Agent.canHear) == null) 1f else DIM
        micButton.stateDescription = if (talk.recording) "Recording" else null
    }

    private fun send(voice: Attachment.Audio? = null) {
        val attachments = listOfNotNull(voice) + draft.attachments()
        val q = Composer.requestText(input.text.toString(), attachments, spoken = voice != null || draft.hasSpoken) ?: return
        val blocked = if (Agent.busy) "MySI is still working. Wait, or tap Stop." else if (!Agent.ready) "Load a model first." else null
        if (blocked != null) {
            // Nothing is lost: a recording waits with the other attachments, the text stays typed.
            if (voice != null) {
                draft.add(Composer.Chip(voice, spoken = true))
                renderChips()
            }
            return toast(blocked)
        }
        input.setText("")
        draft.clear()
        renderChips()
        // Approvals arrive as notifications when another app is in front. Ask once, before the
        // first request runs, so the system prompt never pauses MySI in the middle of a task.
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("asked_notifications", false)
        ) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            queued = q to attachments
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            return
        }
        start(q, attachments)
    }

    private fun start(q: String, attachments: List<Attachment>) {
        status.text = "Thinking…"
        Agent.submit(q, attachments)
        syncBusy()
    }

    // ---------------- attachments ----------------

    private fun attach() {
        AlertDialog.Builder(this)
            .setTitle("Attach")
            .setItems(arrayOf("Photos", "Files or recordings")) { _, which -> if (which == 0) pickPhotos() else pickFiles() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickPhotos() {
        Composer.Gate.photos(Agent.ready, Agent.canSee)?.let { return toast(it) }
        val slots = draft.imageSlots
        if (slots <= 0) return toast("A message can carry at most ${Attachments.MAX_IMAGES} photos.")
        // The system photo picker: no media permission, the user shares only what they choose.
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        // The limit must be above 1; a single slot is the picker's default.
        if (slots > 1) intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, minOf(slots, MediaStore.getPickImagesMaxLimit()))
        importFrom(intent, photos = true)
    }

    private fun pickFiles() {
        importFrom(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
            photos = false,
        )
    }

    /**
     * Runs a picker and copies what was chosen into the picked-files sandbox, as pick_files does.
     * Runs on the agent's scope with its own request code, so it outlives a rotation and never
     * takes the result meant for a tool's picker.
     */
    private fun importFrom(intent: Intent, photos: Boolean) {
        val ctx = applicationContext
        Agent.scope.launch {
            val (code, data) = forResult(REQ_ATTACH, intent) ?: return@launch toast("No picker on this phone.")
            if (code != RESULT_OK || data == null) return@launch
            val clip = data.clipData
            val uris = if (clip != null) (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } else listOfNotNull(data.data)
            if (uris.isEmpty()) return@launch
            val (files, skipped) = withContext(Dispatchers.IO) { Storage.importPicked(ctx, uris) }
            val picked = files.map { f -> Composer.Chip(if (photos) Attachment.Image(f) else Composer.attachmentFor(f, Storage.mime(f))) }
            // A picture from the files picker still needs a model that can see.
            val refused = picked.mapNotNull { c -> Composer.refusal(c.attachment, Agent.ready, Agent.canSee)?.let { c to it } }
            val chips = picked - refused.map { it.first }.toSet()
            val problems = refused.map { it.second } + chips.mapNotNull { draft.add(it) }
            shown?.get()?.renderChips()
            when {
                skipped.isNotEmpty() -> toast("Could not attach ${skipped.joinToString(", ")}.")
                problems.isNotEmpty() -> toast(problems.first())
            }
            // A recording's length, for its chip.
            for (c in chips) {
                val a = c.attachment as? Attachment.Audio ?: continue
                val seconds = withContext(Dispatchers.IO) { Media.audioSeconds(a.file) } ?: continue
                draft.update(c.copy(attachment = a.copy(seconds = seconds)))
                shown?.get()?.renderChips()
            }
        }
    }

    private fun camera() {
        Composer.Gate.camera(Agent.ready, Agent.canSee)?.let { return toast(it) }
        if (draft.imageSlots <= 0) return toast("A message can carry at most ${Attachments.MAX_IMAGES} photos.")
        val ctx = applicationContext
        Agent.scope.launch {
            val (code, data) = forResult(REQ_CAMERA, LiveVisionActivity.intent(ctx)) ?: return@launch toast("Could not open the camera.")
            if (code != RESULT_OK || data == null) return@launch
            val path = data.getStringExtra(LiveVisionActivity.EXTRA_PATH) ?: return@launch
            val f = Storage.resolve(ctx, path)?.takeIf { it.isFile } ?: return@launch toast("The photo was not saved.")
            draft.add(Composer.Chip(Attachment.Image(f), Composer.seenHint(data.getStringExtra(LiveVisionActivity.EXTRA_OBJECTS))))?.let(::toast)
            shown?.get()?.renderChips()
        }
    }

    /** The removable chips above the text box. */
    private fun renderChips() {
        val grew = draft.chips.size > chipsRow.childCount
        chipsRow.removeAllViews()
        val chips = draft.chips
        chipsScroll.visibility = if (chips.isEmpty()) View.GONE else View.VISIBLE
        // A new chip is added at the end: bring it into view.
        if (grew) chipsScroll.post { chipsScroll.fullScroll(View.FOCUS_RIGHT) }
        for (c in chips) {
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = shape(surface, 14)
                setPadding(dp(6), dp(4), 0, dp(4))
                contentDescription = Composer.describe(c)
                isScreenReaderFocusable = true
            }
            chipsRow.addView(pill, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            if (c.attachment is Attachment.Image) thumbnail(pill, c.attachment.file, 40)
            else text(pill, if (c.attachment is Attachment.Audio) "♪" else "▤", 16f, muted).apply {
                setTextIsSelectable(false)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(32), -2)
            }
            val words = column(pill).apply {
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            text(words, Composer.label(c), 13f).apply {
                setTextIsSelectable(false)
                maxWidth = dp(180)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
            }
            if (c.hint.isNotEmpty()) text(words, c.hint, 11f, muted).apply {
                setTextIsSelectable(false)
                maxWidth = dp(180)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
            }
            link(pill, "✕", "Remove ${Composer.describe(c)}") {
                draft.remove(c)
                renderChips()
            }
        }
    }

    /** A square picture decoded off the main thread; nothing is shown for a file that is gone. */
    private fun thumbnail(parent: LinearLayout, file: File, sizeDp: Int) {
        val view = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = shape(Color.rgb(28, 29, 35), 10)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        parent.addView(view, LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)))
        val key = file.absolutePath
        thumbs.get(key)?.let { return view.setImageBitmap(it) }
        val px = dp(sizeDp) * 2
        ui.launch {
            val bmp = withContext(Dispatchers.IO) { runCatching { Media.uprightBitmap(file, px) }.getOrNull() } ?: return@launch
            thumbs.put(key, bmp)
            view.setImageBitmap(bmp)
        }
    }

    // ---------------- hold to talk ----------------

    /** Hold to talk, slide away to discard; a quick tap records hands free until the next tap. */
    @SuppressLint("ClickableViewAccessibility") // Clicks (TalkBack, keyboards) go to micClick, the same toggle.
    private fun holdToTalk(view: View) {
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    v.animate().scaleX(.94f).scaleY(.94f).setDuration(110).start()
                    if (talk.recording || micReady()) act(talk.down(e.eventTime))
                }
                MotionEvent.ACTION_MOVE ->
                    if (talk.mode == PushToTalk.Mode.HOLD && Math.hypot((e.rawX - downX).toDouble(), (e.rawY - downY).toDouble()) > dp(SLIDE_DP))
                        act(talk.slideAway())
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(190).start()
                    act(talk.up(e.eventTime))
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(190).start()
                    // The system took the gesture (e.g. a scroll): only a hold is thrown away.
                    act(talk.slideAway())
                }
            }
            true
        }
    }

    /** A click that is not a touch: TalkBack's double tap, or a keyboard. */
    private fun micClick() {
        if (talk.recording || micReady()) act(talk.click())
    }

    /** Whether recording may start now; explains why not, and asks for the microphone the first time. */
    private fun micReady(): Boolean {
        Composer.Gate.mic(Agent.ready, Agent.canHear)?.let {
            toast(it)
            return false
        }
        if (Agent.busy) {
            toast("MySI is still working. Wait, or tap Stop.")
            return false
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // The permission prompt takes over the screen, so this press only asks; the next one records.
            Agent.scope.launch {
                if (Agent.askFor(Manifest.permission.RECORD_AUDIO)) toast("Microphone ready. Hold the microphone button and speak.")
                else toast("MySI needs the microphone to hear you. You can allow it in Settings › Apps › MySI Agent › Permissions.")
            }
            return false
        }
        return true
    }

    private fun act(a: PushToTalk.Action, quiet: Boolean = false) {
        when (a) {
            PushToTalk.Action.START -> startRecording()
            PushToTalk.Action.FINISH -> latch?.finish()
            PushToTalk.Action.DISCARD -> {
                recording?.cancel()
                if (!quiet) toast("Recording discarded.")
            }
            PushToTalk.Action.NONE -> {}
        }
        showRecording()
    }

    private fun startRecording() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val file = File(File(Storage.workspace(this), "Recordings").apply { mkdirs() }, "voice_$stamp.wav")
        val limit = Attachments.AUDIO_CLIP_SECONDS.toInt()
        val recorder = Recorder(limit)
        val l = StopLatch(recorder::stop)
        latch = l
        recMeter.level = 0f
        recClock.text = "0:00 / ${Composer.clock(limit.toDouble())}"
        val ctx = applicationContext
        recording = ui.launch {
            val result: Recorder.Result? = try {
                recorder.record(ctx, file) { level ->
                    l.chunk()
                    runOnUiThread {
                        if (latch === l) {
                            recMeter.level = Composer.meter(level)
                            recClock.text = "${Composer.clock(l.seconds)} / ${Composer.clock(limit.toDouble())}"
                        }
                    }
                }
            } catch (e: SecurityException) {
                toast("MySI needs the microphone to hear you. You can allow it in Settings › Apps › MySI Agent › Permissions.")
                null
            } catch (e: IOException) {
                toast(e.message ?: "Could not record.")
                null
            } finally {
                // Also on cancel (a discard), when the recorder has already deleted the file.
                if (latch === l) {
                    latch = null
                    recording = null
                    talk.ended()
                    showRecording()
                }
            }
            if (result == null) return@launch
            if (result.seconds < Composer.MIN_RECORDING_SECONDS) {
                withContext(Dispatchers.IO) { file.delete() }
                return@launch toast("Too short. Hold the microphone button while you speak.")
            }
            if (!result.stoppedEarly) toast("Recordings stop at $limit seconds. Sending it now.")
            send(Attachment.Audio(file, result.seconds))
        }
    }

    private fun showRecording() {
        val on = talk.recording
        recPanel.visibility = if (on) View.VISIBLE else View.GONE
        recHint.visibility = if (on) View.VISIBLE else View.GONE
        val hint = when (talk.mode) {
            PushToTalk.Mode.HOLD -> "Recording. Release to send, or slide away to cancel."
            PushToTalk.Mode.TAP -> "Recording. Tap the microphone again to send."
            PushToTalk.Mode.IDLE -> ""
        }
        if (recHint.text.toString() != hint) recHint.text = hint
        syncBusy()
    }

    // ---------------- log ----------------

    /** Removes the streaming card; only finished replies, which can be reported, stay in the log. */
    private fun dropLive() {
        live?.let { log.removeView(it.first) }
        live = null
    }

    /** Shows the latest step of a long job in a quiet card with a Stop action, kept at the end of the log. */
    private fun showWorking(step: String) {
        // Steps replayed from history after the job ended (a rotation) need no card.
        if (!Agent.busy) return
        val (card, line) = working ?: card(log).let { c ->
            text(c, "Working", 11f, muted, bold = true).typeface = serif
            // Not a live region: some jobs report a step every second, too often to read out.
            val t = text(c, "", 14f)
            link(c, "Stop", "Stop this job") { Agent.stop() }.apply {
                (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.END
            }
            (c to t).also { working = it }
        }
        line.text = step
        if (log.indexOfChild(card) != log.childCount - 1) {
            log.removeView(card)
            log.addView(card)
        }
    }

    /** The job ended (a reply, an error or a stop): its card goes, the result is what remains. */
    private fun resolveWorking() {
        working?.let { log.removeView(it.first) }
        working = null
    }

    private fun render(e: Event) {
        when (e) {
            is Event.Status -> status.text = e.text
            is Event.User ->
                card(log, Color.rgb(28, 29, 35)).also {
                    text(it, "You", 11f, muted, bold = true)
                    Composer.shownText(e.text).takeIf { t -> t.isNotEmpty() }?.let { t -> text(it, t, 15f) }
                    if (e.attachments.isNotEmpty()) sent(it, e.attachments)
                }
            is Event.Partial -> {
                val (_, body) = live ?: card(log).let { c ->
                    text(c, "MySI", 11f, muted, bold = true).typeface = serif
                    (c to text(c, "", 15f)).also { live = it }
                }
                body.text = e.text
            }
            is Event.Progress -> {
                status.text = e.text
                showWorking(e.text)
            }
            is Event.ToolCall -> {
                dropLive()
                text(log, "⚙  ${e.name}(${e.args})", 12f, muted).typeface = Typeface.MONOSPACE
            }
            is Event.ToolResult -> text(log, "↳ ${e.text.take(700)}", 12f, muted).apply { setPadding(dp(14), 0, 0, 0) }
            is Event.Reply -> {
                dropLive()
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
                dropLive()
                text(log, "⚠ ${e.text}", 13f, Color.rgb(255, 160, 150))
                status.text = "Error"
            }
        }
        if (e is Event.Reply || e is Event.Error || e is Event.Status) resolveWorking()
        // A model can finish loading while this screen is open (a download started in setup).
        if (e is Event.Error || e is Event.Status) syncModelButton()
        syncBusy()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** What the user sent with a message: pictures as thumbnails, recordings with their length, files by name. */
    private fun sent(parent: LinearLayout, attachments: List<Attachment>) {
        val pictures = attachments.filterIsInstance<Attachment.Image>()
        if (pictures.isNotEmpty()) {
            val strip = row(parent).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(8) }
            pictures.forEachIndexed { i, p ->
                thumbnail(strip, p.file, 72)
                strip.getChildAt(strip.childCount - 1).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    contentDescription = "Photo ${p.name}"
                    if (i > 0) (layoutParams as LinearLayout.LayoutParams).marginStart = dp(6)
                }
            }
        }
        for (a in attachments) {
            if (a is Attachment.Image) continue
            val chip = Composer.Chip(a)
            text(parent, (if (a is Attachment.Audio) "♪  " else "▤  ") + Composer.label(chip), 13f, muted).contentDescription = Composer.describe(chip)
        }
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
                id = View.generateViewId()
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
                runCatching { (shown?.get() ?: this@MainActivity).startActivityForResult(intent, code) }.isSuccess
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
        if (code == REQ_NOTIFICATIONS) queued?.let { (q, attachments) ->
            queued = null
            start(q, attachments)
        }
    }

    @Deprecated("Plain Activity result API; this app has no AndroidX activity dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_MODEL -> data?.data?.takeIf { resultCode == RESULT_OK }?.let(::importModel)
            REQ_SETUP -> setupClosed(resultCode, data)
            else -> results.remove(requestCode)?.complete(resultCode to data)
        }
    }

    /** The model setup screen closed (see its result contract in ModelSetupActivity). */
    private fun setupClosed(resultCode: Int, data: Intent?) {
        // A cancel without an Intent means Android removed the setup screen because the app died
        // (for example while a model was starting), so it may open once more.
        if (ModelButton.closedByUser(resultCode, data != null)) setupShown = true
        val pending = data?.getBooleanExtra(ModelSetupActivity.EXTRA_SETUP_PENDING, false) == true
        when (ModelButton.afterSetup(resultCode, pending)) {
            ModelButton.After.Loaded -> status.text = readyText()
            ModelButton.After.PickOwnFile -> pickModel()
            ModelButton.After.DownloadContinues -> status.text = ModelButton.DOWNLOADING
            ModelButton.After.Nothing -> Unit
        }
        syncModelButton()
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
        val app = applicationContext
        val store = ModelStore.of(app)
        Agent.scope.launch {
            // Turn a file that is not a chat model away before copying gigabytes of it.
            val header = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)!!.use(LiteRtLmFile::check) }.getOrDefault(LiteRtLmFile.Result.Damaged)
            }
            LiteRtLmFile.message(header, name)?.let { problem ->
                withContext(Dispatchers.IO) { dir.deleteRecursively() }
                render(Event.Error(problem))
                status.text = if (Agent.ready) readyText() else ModelButton.NO_MODEL
                return@launch
            }
            status.text = "Copying $name into the app…"
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
            // Saved before the native load: if this model crashes MySI, the next launch deletes it
            // and says why, instead of leaving it behind (see recoverInterruptedImport).
            withContext(Dispatchers.IO) { store.markImportLoading(dest) }
            val loaded = Agent.load(dest.absolutePath)
            withContext(NonCancellable + Dispatchers.IO) {
                if (loaded) {
                    models.listFiles()?.filter { it != dir }?.forEach { it.deleteRecursively() }
                    // Models downloaded by the setup screen are no longer used either.
                    ModelSetupActivity.pruneUnusedModels(app)
                } else dir.deleteRecursively()
                store.clearImportLoading()
            }
            // A rejected file leaves the previous model loaded; the reason is already in the log.
            if (!loaded) status.text = if (Agent.ready) readyText() else ModelButton.NO_MODEL
            syncModelButton()
        }
    }

    // Application context: reports and imports finish on the agent scope, possibly after this activity is gone.
    private fun toast(s: String) = android.widget.Toast.makeText(applicationContext, s, android.widget.Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_PHOTO = 2
        private const val REQ_MODEL = 4
        private const val REQ_PICK = 5
        private const val REQ_NOTIFICATIONS = 6
        private const val REQ_ATTACH = 7
        private const val REQ_CAMERA = 8
        private const val REQ_SETUP = 9
        private const val RESULT_TIMEOUT_MS = 10 * 60_000L
        private const val DIM = 0.4f

        /** How far the finger slides off the microphone to throw a recording away. */
        private const val SLIDE_DP = 72
        private val results = ConcurrentHashMap<Int, CompletableDeferred<Pair<Int, Intent?>>>()
        @Volatile private var permissionWait: CompletableDeferred<Unit>? = null
        /** The first request and its attachments, held while the notification prompt is up; survives recreation. */
        private var queued: Pair<String, List<Attachment>>? = null

        /** The attachments waiting to be sent; survives recreation (e.g. rotation). */
        private val draft = Draft()

        /** The setup screen was opened or returned in this process, so it is only offered from now on. */
        private var setupShown = false

        /** recoverInterruptedImport ran in this process (it must not race an import of this process). */
        private var importChecked = false

        /** The chat screen on show, for pickers that finish after a rotation. */
        @Volatile private var shown: WeakReference<MainActivity>? = null

        /** Small decoded pictures for chips and the log, so a re-render does not decode again. */
        private val thumbs = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
    }
}
