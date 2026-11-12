package com.mysi.agent

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.PowerManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** MyLife visual language: black canvas, pearl ink, quiet surfaces, Google Sans + Playfair. */
object Design {
    fun motion(context: Context) =
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f &&
            ValueAnimator.areAnimatorsEnabled() &&
            !context.getSystemService(PowerManager::class.java).isPowerSaveMode

    // Observe touches without consuming them; native View dispatch retains click/accessibility
    // handling.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun press(view: View) {
        view.setOnTouchListener { v, event ->
            if (motion(v.context))
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        v.animate().scaleX(.984f).scaleY(.984f).setDuration(110).start()
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL ->
                        v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(190)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                }
            false
        }
    }
}

open class UiActivity : Activity() {
    val ink = Color.rgb(249, 248, 245)
    val muted = Color.rgb(166, 167, 180)
    val surface = Color.rgb(17, 18, 22)
    val sans: Typeface by lazy { resources.getFont(R.font.google_sans) }
    val serif: Typeface by lazy { resources.getFont(R.font.playfair) }
    lateinit var root: LinearLayout
    lateinit var body: LinearLayout
    lateinit var scroll: ScrollView

    fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    fun shape(color: Int = surface, radius: Int = 16) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), Color.rgb(48, 49, 57))
        }

    fun column(parent: LinearLayout? = null) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            parent?.addView(this, LinearLayout.LayoutParams(-1, -2))
        }

    fun row(parent: LinearLayout) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }

    fun text(
        parent: LinearLayout,
        value: String,
        size: Float = 15f,
        color: Int = ink,
        bold: Boolean = false,
    ) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = if (bold) Typeface.create(sans, Typeface.BOLD) else sans
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }

    fun space(parent: LinearLayout = body, n: Int = 10) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(n)))
    }

    fun card(parent: LinearLayout = body, color: Int = surface) =
        column().apply {
            background = shape(color)
            setPadding(dp(16), dp(14), dp(16), dp(8))
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }

    fun button(parent: LinearLayout, label: String, primary: Boolean = false, action: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.create(sans, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.BLACK else ink)
            background = shape(if (primary) ink else surface, 12)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minHeight = dp(48)
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { action() }
            Design.press(this)
            parent.addView(
                this,
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(6)
                },
            )
        }

    /** A square icon button in the same quiet surface as [button]. */
    fun iconButton(parent: LinearLayout, icon: Int, description: String, action: () -> Unit) =
        ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(ink)
            background = shape(surface, 12)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(12), dp(11), dp(12))
            isClickable = true
            isFocusable = true
            contentDescription = description
            setOnClickListener { action() }
            Design.press(this)
            parent.addView(this, LinearLayout.LayoutParams(dp(44), dp(48)))
        }

    /** A small text action in muted ink, at least 48 dp tall for touch, like the privacy link. */
    fun link(parent: LinearLayout, label: String, description: String = label, action: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.create(sans, Typeface.BOLD)
            setTextColor(muted)
            gravity = Gravity.CENTER
            minHeight = dp(48)
            minWidth = dp(48)
            setPadding(dp(10), 0, dp(10), 0)
            isClickable = true
            isFocusable = true
            contentDescription = description
            setOnClickListener { action() }
            parent.addView(this, LinearLayout.LayoutParams(-2, -2))
        }

    lateinit var titleBar: LinearLayout

    /** Black edge-to-edge shell with a title bar and a scrolling body. */
    fun shell(title: String) {
        root = column().apply { setBackgroundColor(Color.BLACK) }
        // Edge-to-edge is enforced from API 35; older versions need to opt in.
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT < 35) window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, i ->
            val bars = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = i.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            i
        }
        setContentView(root)
        root.requestApplyInsets()
        val bar = row(root).apply { setPadding(dp(18), dp(10), dp(18), 0) }
        titleBar = bar
        text(bar, title, 26f).apply {
            // Not selectable: a selectable title takes focus back from closed dialogs and shows a highlight.
            setTextIsSelectable(false)
            typeface = serif
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        scroll =
            ScrollView(this).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
            }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body = column().apply { setPadding(dp(18), dp(12), dp(18), dp(20)) }
        scroll.addView(body)
    }
}

/** A thin rounded meter, e.g. the microphone level while recording. */
class LevelBar(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    var track = Color.rgb(48, 49, 57)
    var fill = Color.WHITE

    /** 0 to 1. */
    var level = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val r = h / 2
        paint.color = track
        canvas.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, paint)
        if (level > 0f) {
            paint.color = fill
            canvas.drawRoundRect(0f, 0f, maxOf(h, width * level), h, r, r, paint)
        }
    }
}
