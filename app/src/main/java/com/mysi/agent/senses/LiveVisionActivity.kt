package com.mysi.agent.senses

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.mysi.agent.Storage
import com.mysi.agent.UiActivity
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Live vision: the camera preview with the on-device object detector's boxes drawn over it in real
 * time, and a shutter that saves the current view to the workspace.
 *
 * Launch it for a result:
 * ```
 * startActivityForResult(LiveVisionActivity.intent(context), requestCode)
 * ```
 * - RESULT_OK: [EXTRA_PATH] is the absolute path of the saved JPEG in the workspace
 *   (Photos/live_<time>.jpg), ready for the vision tools or as an [com.mysi.agent.Attachment.Image];
 *   [EXTRA_OBJECTS] is what the detector saw at that moment, e.g. "2 person, 1 cup" (may be empty).
 * - RESULT_CANCELED: the user closed the screen, or camera access was refused.
 *
 * It asks for CAMERA itself when it opens and explains what to do if access is refused. Frames
 * never leave the phone and are not stored, except the one the user saves.
 */
class LiveVisionActivity : UiActivity(), LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private lateinit var stage: FrameLayout
    private lateinit var preview: PreviewView
    private lateinit var overlay: DetectionOverlay
    private lateinit var seen: TextView
    private lateinit var shutter: TextView

    private var provider: ProcessCameraProvider? = null
    private var capture: ImageCapture? = null
    private var analysis: ImageAnalysis? = null
    private val analyzer: ExecutorService = Executors.newSingleThreadExecutor()

    /** Used only on [analyzer]'s thread. */
    private var finder: ObjectFinder? = null
    private var finderFailed = false
    private var lastTimestamp = 0L

    /**
     * The latest upright frame and what was found in it; read on the main thread when saving.
     * Back leaves with RESULT_CANCELED, the default result.
     */
    @Volatile private var lastFrame: Bitmap? = null
    @Volatile private var lastSummary = ""
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        buildScreen()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
    }

    override fun onStart() {
        super.onStart()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onResume() {
        super.onResume()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onPause() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        super.onPause()
    }

    override fun onStop() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onStop()
    }

    override fun onDestroy() {
        // Destroying the lifecycle makes CameraX unbind and release the camera.
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        analysis?.clearAnalyzer()
        // The detector is only touched on the analysis thread, so it is closed there, after any frame in progress.
        analyzer.execute {
            finder?.close()
            finder = null
        }
        analyzer.shutdown()
        super.onDestroy()
    }

    // ---------------- screen ----------------

    private fun buildScreen() {
        root = column().apply { setBackgroundColor(Color.BLACK) }
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT < 35) window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, i ->
            val bars = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            i
        }
        setContentView(root)
        root.requestApplyInsets()
        val bar = row(root).apply { setPadding(dp(18), dp(10), dp(18), dp(6)) }
        text(bar, "Live vision", 26f).apply {
            typeface = serif
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        stage = FrameLayout(this)
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        stage.addView(preview, FrameLayout.LayoutParams(-1, -1))
        overlay = DetectionOverlay(this)
        stage.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        val bottom = column(root).apply { setPadding(dp(18), dp(10), dp(18), dp(8)) }
        seen = text(bottom, "Starting the camera…", 14f, muted).apply {
            setTextIsSelectable(false)
            contentDescription = "Objects in view"
        }
        val actions = row(bottom)
        button(actions, "Close") { finishCancelled() }.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        shutter = button(actions, "Save this view", true) { save() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) }
            isEnabled = false
        }
    }

    /** Shown instead of the camera when access was refused or the camera cannot start. */
    private fun showProblem(message: String, retry: Boolean) {
        stage.removeAllViews()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        stage.addView(box, FrameLayout.LayoutParams(-1, -1))
        text(box, message, 15f).setTextIsSelectable(false)
        if (retry) button(box, "Allow camera") { askAgain() }
        shutter.isEnabled = false
        seen.text = ""
    }

    private fun askAgain() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        else runCatching {
            // Android stops showing the prompt after repeated refusals; the app's settings page is the way back.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != REQ_CAMERA) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (preview.parent == null) recreate() else startCamera()
        } else showProblem(NO_CAMERA, retry = true)
    }

    override fun onRestart() {
        super.onRestart()
        // Back from the app settings page with access granted.
        if (provider == null && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) recreate()
    }

    // ---------------- camera ----------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isDestroyed) return@addListener
            val p = runCatching { future.get() }.getOrElse {
                showProblem("The camera could not start: ${it.message}", retry = false)
                return@addListener
            }
            provider = p
            bind(p)
        }, mainExecutor)
    }

    private fun bind(p: ProcessCameraProvider) {
        val back = runCatching { p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
        val front = runCatching { p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
        val selector = when {
            back -> CameraSelector.DEFAULT_BACK_CAMERA
            front -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> return showProblem("This phone has no camera MySI can use.", retry = false)
        }
        // The front camera's preview is mirrored; its analysis frames are not.
        overlay.mirror = !back
        val ratio = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
        val pv = Preview.Builder().setResolutionSelector(ratio).build().also { it.surfaceProvider = preview.surfaceProvider }
        val an = ImageAnalysis.Builder()
            .setResolutionSelector(ratio)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(analyzer, ::analyze) }
        val cap = ImageCapture.Builder().setResolutionSelector(ratio).setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        try {
            p.unbindAll()
            try {
                p.bindToLifecycle(this, selector, pv, an, cap)
                capture = cap
            } catch (e: IllegalArgumentException) {
                // Some cameras cannot stream three outputs at once; saving then uses the analysed frame.
                p.bindToLifecycle(this, selector, pv, an)
                capture = null
            }
            analysis = an
            shutter.isEnabled = true
            seen.text = "Looking…"
        } catch (e: Exception) {
            showProblem("The camera could not start: ${e.message}", retry = false)
        }
    }

    /** On [analyzer]'s thread: one frame, upright, through the detector. */
    private fun analyze(image: ImageProxy) {
        try {
            val upright = image.use {
                val raw = it.toBitmap()
                val deg = it.imageInfo.rotationDegrees
                if (deg == 0) raw
                else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(deg.toFloat()) }, true).also { r -> if (r !== raw) raw.recycle() }
            }
            val f = finder ?: if (finderFailed) null else runCatching { ObjectFinder.video(this) }.onFailure { e ->
                finderFailed = true
                Log.e("MySI", "Object detector failed to start", e)
                runOnUiThread { seen.text = "Object detection is not available on this phone: ${e.message}" }
            }.getOrNull()?.also { finder = it }
            // VIDEO mode needs strictly increasing timestamps.
            val now = maxOf(SystemClock.uptimeMillis(), lastTimestamp + 1).also { lastTimestamp = it }
            val dets = f?.let { DetectionReport.kept(it.detectFrame(upright, now)) }.orEmpty()
            // Older frames are left to the garbage collector: the main thread may be saving one.
            lastFrame = upright
            val summary = DetectionReport.summary(dets)
            lastSummary = summary
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                overlay.show(dets, Size(upright.width, upright.height))
                if (f != null) seen.text = summary.ifEmpty { "No common objects in view." }
            }
        } catch (e: Exception) {
            Log.e("MySI", "Live vision frame failed", e)
        }
    }

    // ---------------- saving ----------------

    private fun photoFile(): File =
        File(File(Storage.workspace(this), "Photos").apply { mkdirs() }, "live_${System.currentTimeMillis()}.jpg")

    private fun save() {
        if (saving) return
        saving = true
        shutter.isEnabled = false
        seen.text = "Saving…"
        val summary = lastSummary
        val out = photoFile()
        val cap = capture
        if (cap == null) return saveFrame(out, summary)
        cap.takePicture(
            ImageCapture.OutputFileOptions.Builder(out).build(),
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) = finishWith(out, summary)

                override fun onError(e: ImageCaptureException) {
                    Log.w("MySI", "Capture failed, saving the analysed frame instead", e)
                    out.delete()
                    saveFrame(out, summary)
                }
            },
        )
    }

    /** Saves the latest analysed frame (already upright) when a full-resolution capture is not possible. */
    private fun saveFrame(out: File, summary: String) {
        val frame = lastFrame
        if (frame == null || frame.isRecycled) {
            saving = false
            shutter.isEnabled = true
            seen.text = "Nothing to save yet. Try again in a moment."
            return
        }
        val ok = runCatching { out.writeBytes(Media.jpeg(frame)) }.isSuccess
        if (ok) finishWith(out, summary)
        else {
            saving = false
            shutter.isEnabled = true
            seen.text = "Could not save the picture. Check free space."
        }
    }

    private fun finishWith(out: File, summary: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_PATH, out.absolutePath).putExtra(EXTRA_OBJECTS, summary))
        finish()
    }

    private fun finishCancelled() {
        setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        /** Absolute path of the saved JPEG (RESULT_OK only). */
        const val EXTRA_PATH = "com.mysi.agent.extra.PHOTO_PATH"

        /** What the detector saw when the picture was saved, e.g. "2 person, 1 cup"; may be empty. */
        const val EXTRA_OBJECTS = "com.mysi.agent.extra.OBJECTS"

        private const val REQ_CAMERA = 41

        const val NO_CAMERA =
            "MySI needs camera access to show the live view. Nothing is recorded or uploaded: frames are checked on the phone and only a picture you save is kept. " +
                "Tap Allow camera, or allow it in Settings › Apps › MySI Agent › Permissions."

        fun intent(ctx: Context) = Intent(ctx, LiveVisionActivity::class.java)
    }
}

/** Draws the detector's boxes over the preview, mapped the way PreviewView fills the screen. */
internal class DetectionOverlay(ctx: Context) : View(ctx) {
    private var dets: List<Detection> = emptyList()
    private var frame = Size(1, 1)
    var mirror = false

    private val density = ctx.resources.displayMetrics.density
    private val box = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.rgb(255, 214, 10)
        isAntiAlias = true
    }
    private val tag = Paint().apply { color = Color.rgb(255, 214, 10) }
    private val label = Paint().apply {
        color = Color.BLACK
        textSize = 13f * density
        isAntiAlias = true
        isFakeBoldText = true
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Reused on every draw. */
    private val rect = RectF()

    /** The label drawn on each box, made once per frame rather than on every draw. */
    private var tags: List<String> = emptyList()

    fun show(dets: List<Detection>, frame: Size) {
        this.dets = dets
        tags = dets.map { "${it.label} ${(it.score * 100).toInt()}%" }
        if (frame != this.frame) {
            this.frame = frame
            remap()
        }
        invalidate()
    }

    /** Frame-to-view mapping, recomputed only when the frame or view size changes. */
    private var mapped = ImageMath.Transform(1f, 0f, 0f)

    private fun remap() {
        if (width > 0 && height > 0) mapped = ImageMath.fillCenter(frame, Size(width, height))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        remap()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val t = mapped
        for ((i, d) in dets.withIndex()) {
            var l = t.x(d.box.left)
            var r = t.x(d.box.right)
            if (mirror) {
                val ml = width - r
                r = width - l
                l = ml
            }
            rect.set(l, t.y(d.box.top), r, t.y(d.box.bottom))
            canvas.drawRect(rect, box)
            val text = tags[i]
            val pad = 4 * density
            val h = label.textSize + pad * 2
            val top = if (rect.top - h >= 0) rect.top - h else rect.top
            canvas.drawRect(rect.left, top, rect.left + label.measureText(text) + pad * 2, top + h, tag)
            canvas.drawText(text, rect.left + pad, top + h - pad - label.descent(), label)
        }
    }
}
