package com.mysi.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Screen reading and UI control: the bridge for anything Android gives no direct API for. */
class AgentAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun global(action: String): String {
        val code =
            when (action) {
                "home" -> GLOBAL_ACTION_HOME
                "back" -> GLOBAL_ACTION_BACK
                "recents" -> GLOBAL_ACTION_RECENTS
                "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
                "lock_screen" -> GLOBAL_ACTION_LOCK_SCREEN
                "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
                "power_menu" -> GLOBAL_ACTION_POWER_DIALOG
                else -> return "Unknown action '$action'."
            }
        return if (performGlobalAction(code)) "Done: $action" else "Failed: $action"
    }

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "Screen content unavailable."
        val out = StringBuilder("App: ${root.packageName}\n")
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || out.length > 3500) return
            val label = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
            if (label.isNotEmpty()) {
                val tags = buildList {
                    if (n.isClickable) add("tap")
                    if (n.isEditable) add("input")
                    if (n.isCheckable) add(if (checked(n)) "on" else "off")
                }
                out.append("  ".repeat(minOf(depth, 6))).append(label.take(120))
                if (tags.isNotEmpty()) out.append(" [").append(tags.joinToString(",")).append("]")
                out.append('\n')
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out.toString()
    }

    fun tapText(text: String): String {
        val root = rootInActiveWindow ?: return "Screen content unavailable."
        val matches = root.findAccessibilityNodeInfosByText(text)
        val node =
            matches.firstOrNull { (it.text ?: it.contentDescription)?.toString().equals(text, true) }
                ?: matches.firstOrNull()
                ?: return "No element with text '$text' on screen. Use read_screen to see what is there."
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            return "Tapped '$text'."
        val r = Rect().also { node.getBoundsInScreen(it) }
        gesture(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), 60)
        return "Tapped '$text' by position."
    }

    fun typeText(text: String): String {
        val root = rootInActiveWindow ?: return "Screen content unavailable."
        val field =
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findEditable(root)
                ?: return "No text field on screen."
        val args =
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
        return if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "Typed." else "Typing failed."
    }

    /** Depth-first search of the whole window, including WebView content such as Amazon pages. */
    fun findNode(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(n: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (n == null || depth > 60) return null
            if (match(n)) return n
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)?.let { return it }
            return null
        }
        return walk(rootInActiveWindow, 0)
    }

    fun label(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()

    fun screenHas(pattern: Regex) = findNode { pattern.containsMatchIn(label(it)) } != null

    fun packageOnScreen() = rootInActiveWindow?.packageName?.toString()

    /** Clicks the node (or its nearest clickable parent); falls back to a tap on its centre. */
    fun click(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        val r = Rect().also { node.getBoundsInScreen(it) }
        val m = resources.displayMetrics
        if (r.isEmpty || r.centerY() !in 1 until m.heightPixels || r.centerX() !in 1 until m.widthPixels) return false
        gesture(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), 60)
        return true
    }

    /** Presses the keyboard's search/enter key on the focused field. */
    fun submit(): Boolean {
        val field = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    /** Taps the screen's search bar, for apps whose search box is a button until tapped. */
    fun focusSearch(): Boolean {
        val node = findNode { n ->
            val id = n.viewIdResourceName.orEmpty()
            val hint = n.hintText?.toString().orEmpty()
            (n.isClickable || n.isEditable) &&
                listOf(label(n), hint, id).any { it.contains("search", true) }
        } ?: return false
        return click(node)
    }

    /** Clicks a view by resource id, falling back to its accessibility label, only inside [pkg]. */
    fun clickById(id: String, label: String, pkg: String): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != pkg) return false
        val node =
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull()
                ?: root.findAccessibilityNodeInfosByText(label).firstOrNull {
                    it.contentDescription?.toString().equals(label, true)
                }
                ?: return false
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    fun scroll(direction: String): String {
        val m = resources.displayMetrics
        val x = m.widthPixels / 2f
        val (y1, y2) =
            if (direction == "up") m.heightPixels * .3f to m.heightPixels * .75f
            else m.heightPixels * .75f to m.heightPixels * .3f
        gesture(x, y1, x, y2, 350)
        return "Scrolled $direction."
    }

    private fun checked(n: AccessibilityNodeInfo) =
        if (android.os.Build.VERSION.SDK_INT >= 36) n.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE
        else @Suppress("DEPRECATION") n.isChecked

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) findEditable(n.getChild(i))?.let { return it }
        return null
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
                .build(),
            null,
            null,
        )
    }

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
    }
}
