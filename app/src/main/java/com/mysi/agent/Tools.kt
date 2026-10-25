package com.mysi.agent

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.os.StatFs
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.KeyEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class Param(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
    val options: List<String>? = null,
)

class Args(private val m: Map<String, Any?>) {
    fun str(k: String, default: String = "") = m[k]?.toString() ?: default

    fun int(k: String, default: Int) =
        when (val v = m[k]) {
            is Number -> v.toInt()
            is String -> v.toDoubleOrNull()?.toInt() ?: default
            else -> default
        }

    fun bool(k: String, default: Boolean) =
        when (val v = m[k]) {
            is Boolean -> v
            is String -> v.equals("true", true)
            else -> default
        }

    override fun toString() = m.entries.joinToString(", ") { "${it.key}=${it.value}" }
}

/**
 * One device capability exposed to the model. [confirm] returns the question shown to the user
 * before anything leaves the phone or is overwritten; null means the tool runs without asking.
 */
class ToolDef(
    val name: String,
    val description: String,
    val params: List<Param> = emptyList(),
    val confirm: ((Args) -> String)? = null,
    val run: suspend (Args) -> Any?,
) {
    fun schemaJson(): String {
        val props = org.json.JSONObject()
        params.forEach { p ->
            props.put(
                p.name,
                org.json.JSONObject().apply {
                    put("type", p.type)
                    put("description", p.description)
                    p.options?.let { put("enum", org.json.JSONArray(it)) }
                },
            )
        }
        return org.json.JSONObject()
            .apply {
                put("name", name)
                put("description", description)
                put(
                    "parameters",
                    org.json.JSONObject().apply {
                        put("type", "object")
                        put("properties", props)
                        put("required", org.json.JSONArray(params.filter { it.required }.map { it.name }))
                    },
                )
            }
            .toString()
    }
}

/** UI-side abilities the agent borrows from the visible activity. */
interface Host {
    suspend fun confirm(title: String, message: String): Boolean

    suspend fun takePhoto(file: File): Boolean
}

@SuppressLint("MissingPermission")
object Tools {
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun time(ms: Long): String = stamp.format(Date(ms))

    private var tts: TextToSpeech? = null

    private fun outDir(ctx: Context, sub: String): File {
        val base =
            if (Environment.isExternalStorageManager())
                File(Environment.getExternalStorageDirectory(), "Documents/MySI/$sub")
            else File(ctx.getExternalFilesDir(null), sub)
        return base.apply { mkdirs() }
    }

    internal fun resolve(path: String): File =
        if (path.startsWith("/")) File(path) else File(Environment.getExternalStorageDirectory(), path)

    internal fun launch(ctx: Context, intent: Intent): String =
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opened."
        } catch (e: Exception) {
            "Could not open: ${e.message}"
        }

    private fun apps(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                PackageManager.ResolveInfoFlags.of(0),
            )
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinct()
            .sortedBy { it.first.lowercase() }
    }

    fun all(ctx: Context, host: () -> Host?): List<ToolDef> =
        listOf(
            // ---------- apps & navigation ----------
            ToolDef(
                "open_app",
                "Open an installed app by its name, e.g. WhatsApp, Camera, Settings.",
                listOf(Param("name", "string", "App name as shown on the home screen")),
            ) { a ->
                val q = a.str("name")
                val list = apps(ctx)
                val hit =
                    list.firstOrNull { it.first.equals(q, true) }
                        ?: list.firstOrNull { it.first.contains(q, true) }
                        ?: return@ToolDef "No app named '$q'. Use list_apps to see installed apps."
                val intent = ctx.packageManager.getLaunchIntentForPackage(hit.second)
                    ?: return@ToolDef "App '${hit.first}' has no launch screen."
                launch(ctx, intent).let { "Opening ${hit.first}. $it" }
            },
            ToolDef("list_apps", "List names of all installed apps.") { apps(ctx).map { it.first } },
            // ---------- messages, calls, contacts ----------
            ToolDef(
                "read_sms",
                "Read recent SMS messages.",
                listOf(
                    Param("box", "string", "inbox or sent", false, listOf("inbox", "sent")),
                    Param("count", "integer", "How many, default 10", false),
                    Param("contains", "string", "Only messages containing this text or from this number", false),
                ),
            ) { a ->
                val uri = if (a.str("box") == "sent") Telephony.Sms.Sent.CONTENT_URI else Telephony.Sms.Inbox.CONTENT_URI
                val q = a.str("contains")
                val out = mutableListOf<Map<String, String>>()
                ctx.contentResolver
                    .query(uri, arrayOf("address", "body", "date"), null, null, "date DESC")
                    ?.use { c ->
                        while (c.moveToNext() && out.size < a.int("count", 10).coerceIn(1, 50)) {
                            val from = c.getString(0).orEmpty()
                            val body = c.getString(1).orEmpty()
                            if (q.isNotEmpty() && !body.contains(q, true) && !from.contains(q)) continue
                            out += mapOf("from" to from, "text" to body.take(400), "time" to time(c.getLong(2)))
                        }
                    }
                out
            },
            ToolDef(
                "send_whatsapp",
                "Send a WhatsApp message to a contact name or phone number. Use this whenever the user says WhatsApp.",
                listOf(
                    Param("to", "string", "Contact name or phone number"),
                    Param("message", "string", "Message text"),
                ),
                confirm = { a ->
                    val who = whatsappTarget(ctx, a.str("to"))
                    if (who == null) "No contact or number matches '${a.str("to")}'. Continue anyway?"
                    else "Send WhatsApp to ${who.first} (+${who.second})?\n\n\"${a.str("message")}\""
                },
            ) { a -> sendWhatsapp(ctx, a.str("to"), a.str("message")) },
            ToolDef(
                "send_sms",
                "Send an SMS text message.",
                listOf(
                    Param("to", "string", "Phone number"),
                    Param("message", "string", "Message text"),
                ),
                confirm = { a -> "Send SMS to ${a.str("to")}?\n\n\"${a.str("message")}\"" },
            ) { a ->
                val sms = ctx.getSystemService(SmsManager::class.java)
                sms.sendMultipartTextMessage(a.str("to"), null, sms.divideMessage(a.str("message")), null, null)
                "SMS sent to ${a.str("to")}."
            },
            ToolDef(
                "call_phone",
                "Place a phone call.",
                listOf(Param("number", "string", "Phone number")),
                confirm = { a -> "Call ${a.str("number")} now?" },
            ) { a -> launch(ctx, Intent(Intent.ACTION_CALL, Uri.parse("tel:" + a.str("number")))) },
            ToolDef(
                "search_contacts",
                "Find contacts and their phone numbers by name.",
                listOf(Param("name", "string", "Full or partial name")),
            ) { a ->
                val out = mutableListOf<Map<String, String>>()
                ctx.contentResolver
                    .query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ),
                        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                        arrayOf("%${a.str("name")}%"),
                        null,
                    )
                    ?.use { c -> while (c.moveToNext() && out.size < 15) out += mapOf("name" to c.getString(0), "number" to c.getString(1)) }
                out.ifEmpty { "No contact matches '${a.str("name")}'." }
            },
            ToolDef(
                "read_call_log",
                "Read recent calls.",
                listOf(Param("count", "integer", "How many, default 10", false)),
            ) { a ->
                val out = mutableListOf<Map<String, String>>()
                ctx.contentResolver
                    .query(
                        CallLog.Calls.CONTENT_URI,
                        arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                        null,
                        null,
                        "${CallLog.Calls.DATE} DESC",
                    )
                    ?.use { c ->
                        while (c.moveToNext() && out.size < a.int("count", 10).coerceIn(1, 50)) {
                            val type =
                                when (c.getInt(2)) {
                                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                                    CallLog.Calls.MISSED_TYPE -> "missed"
                                    CallLog.Calls.REJECTED_TYPE -> "rejected"
                                    else -> "other"
                                }
                            out += mapOf(
                                "number" to c.getString(0).orEmpty(),
                                "name" to (c.getString(1) ?: ""),
                                "type" to type,
                                "time" to time(c.getLong(3)),
                                "seconds" to c.getLong(4).toString(),
                            )
                        }
                    }
                out
            },
            ToolDef(
                "read_notifications",
                "Read the notifications currently shown on the phone.",
                listOf(Param("count", "integer", "How many, default 15", false)),
            ) { a -> NotificationReader.read(a.int("count", 15).coerceIn(1, 40)) },
            // ---------- sensors & device ----------
            ToolDef("read_sensors", "Read motion and environment sensors: accelerometer, gyroscope, magnetometer, light, proximity, pressure, steps, heart rate.") {
                readSensors(ctx)
            },
            ToolDef("get_location", "Get the current GPS location.") { location(ctx) },
            ToolDef("device_status", "Battery, charging, network, storage, memory, phone model and Android version.") {
                status(ctx)
            },
            ToolDef("get_datetime", "Current date, time and weekday.") {
                SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date())
            },
            // ---------- camera, audio, voice ----------
            ToolDef("take_photo", "Open the camera so the user can take a photo; returns the saved file path.") {
                val h = host() ?: return@ToolDef "The app must be open on screen to use the camera."
                val f = File(ctx.getExternalFilesDir("Pictures"), "photo_${System.currentTimeMillis()}.jpg")
                if (!h.takePhoto(f) || f.length() == 0L) return@ToolDef "No photo was taken."
                val final = File(outDir(ctx, "Photos"), f.name)
                if (final != f) f.copyTo(final, true).also { f.delete() }
                "Photo saved: ${final.absolutePath}"
            },
            ToolDef(
                "record_audio",
                "Record audio from the microphone.",
                listOf(Param("seconds", "integer", "Length in seconds, 1-120")),
            ) { a -> record(ctx, a.int("seconds", 10).coerceIn(1, 120)) },
            ToolDef(
                "speak",
                "Speak text aloud through the phone speaker.",
                listOf(Param("text", "string", "What to say")),
            ) { a -> speak(ctx, a.str("text")) },
            ToolDef(
                "flashlight",
                "Turn the flashlight on or off.",
                listOf(Param("on", "boolean", "true for on, false for off")),
            ) { a ->
                val cm = ctx.getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: return@ToolDef "No flashlight on this phone."
                cm.setTorchMode(id, a.bool("on", true))
                "Flashlight ${if (a.bool("on", true)) "on" else "off"}."
            },
            ToolDef(
                "vibrate",
                "Vibrate the phone.",
                listOf(Param("ms", "integer", "Duration in milliseconds, default 400", false)),
            ) { a ->
                ctx.getSystemService(VibratorManager::class.java).defaultVibrator
                    .vibrate(VibrationEffect.createOneShot(a.int("ms", 400).coerceIn(50, 5000).toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
                "Vibrated."
            },
            // ---------- system controls ----------
            ToolDef(
                "set_volume",
                "Set a volume level.",
                listOf(
                    Param("stream", "string", "Which volume", true, listOf("media", "ring", "alarm", "notification", "call")),
                    Param("percent", "integer", "0 to 100"),
                ),
            ) { a ->
                val am = ctx.getSystemService(AudioManager::class.java)
                val s = when (a.str("stream")) {
                    "ring" -> AudioManager.STREAM_RING
                    "alarm" -> AudioManager.STREAM_ALARM
                    "notification" -> AudioManager.STREAM_NOTIFICATION
                    "call" -> AudioManager.STREAM_VOICE_CALL
                    else -> AudioManager.STREAM_MUSIC
                }
                val max = am.getStreamMaxVolume(s)
                am.setStreamVolume(s, max * a.int("percent", 50).coerceIn(0, 100) / 100, AudioManager.FLAG_SHOW_UI)
                "Volume set."
            },
            ToolDef(
                "media_control",
                "Control music or video playing in any app.",
                listOf(Param("action", "string", "Command", true, listOf("play_pause", "next", "previous", "stop"))),
            ) { a ->
                val key = when (a.str("action")) {
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                    else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                }
                val am = ctx.getSystemService(AudioManager::class.java)
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                "Sent ${a.str("action")}."
            },
            ToolDef(
                "set_brightness",
                "Set screen brightness.",
                listOf(Param("percent", "integer", "0 to 100")),
            ) { a ->
                if (!Settings.System.canWrite(ctx)) return@ToolDef "Modify-system-settings permission is not granted."
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 255 * a.int("percent", 50).coerceIn(1, 100) / 100)
                "Brightness set."
            },
            ToolDef(
                "bluetooth",
                "Turn Bluetooth on or off. Android shows a one-tap system prompt.",
                listOf(Param("on", "boolean", "true for on, false for off")),
            ) { a ->
                launch(
                    ctx,
                    Intent(if (a.bool("on", true)) "android.bluetooth.adapter.action.REQUEST_ENABLE" else "android.bluetooth.adapter.action.REQUEST_DISABLE"),
                )
            },
            ToolDef(
                "open_settings",
                "Open a settings screen. Android blocks apps from switching Wi-Fi, hotspot, mobile data, NFC or airplane mode directly, so open the page and then use tap_text to flip the switch.",
                listOf(
                    Param(
                        "page",
                        "string",
                        "Which settings page",
                        true,
                        listOf("wifi", "hotspot", "bluetooth", "mobile_data", "airplane", "nfc", "location", "display", "sound", "battery", "apps", "storage", "accessibility", "notifications", "date", "developer", "main"),
                    )
                ),
            ) { a ->
                val intent = when (a.str("page")) {
                    "wifi" -> Intent(Settings.Panel.ACTION_WIFI)
                    "hotspot" -> Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.TetherSettings"))
                    "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    "mobile_data" -> Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                    "airplane" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                    "nfc" -> Intent(Settings.Panel.ACTION_NFC)
                    "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
                    "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
                    "battery" -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
                    "apps" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
                    "storage" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
                    "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    "notifications" -> Intent(Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS)
                    "date" -> Intent(Settings.ACTION_DATE_SETTINGS)
                    "developer" -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    else -> Intent(Settings.ACTION_SETTINGS)
                }
                val r = launch(ctx, intent)
                if (r.startsWith("Could not") && a.str("page") == "hotspot") launch(ctx, Intent(Settings.ACTION_WIRELESS_SETTINGS)) else r
            },
            ToolDef(
                "set_alarm",
                "Set an alarm.",
                listOf(
                    Param("hour", "integer", "0-23"),
                    Param("minute", "integer", "0-59"),
                    Param("label", "string", "Alarm label", false),
                ),
            ) { a ->
                launch(
                    ctx,
                    Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, a.int("hour", 7))
                        .putExtra(AlarmClock.EXTRA_MINUTES, a.int("minute", 0))
                        .putExtra(AlarmClock.EXTRA_MESSAGE, a.str("label", "MySI"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
                )
            },
            ToolDef(
                "set_timer",
                "Start a countdown timer.",
                listOf(Param("seconds", "integer", "Duration in seconds"), Param("label", "string", "Timer label", false)),
            ) { a ->
                launch(
                    ctx,
                    Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, a.int("seconds", 60))
                        .putExtra(AlarmClock.EXTRA_MESSAGE, a.str("label", "MySI"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
                )
            },
            ToolDef("clipboard_read", "Read the text on the clipboard.") {
                withContext(Dispatchers.Main) {
                    ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
                        ?: "Clipboard is empty."
                }
            },
            ToolDef(
                "clipboard_write",
                "Copy text to the clipboard.",
                listOf(Param("text", "string", "Text to copy")),
            ) { a ->
                withContext(Dispatchers.Main) {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("MySI", a.str("text")))
                }
                "Copied."
            },
            // ---------- screen control (accessibility) ----------
            ToolDef(
                "press_button",
                "Press a system button or open a system panel.",
                listOf(
                    Param("action", "string", "Which", true, listOf("home", "back", "recents", "notifications", "quick_settings", "lock_screen", "screenshot", "power_menu"))
                ),
            ) { a -> AgentAccessibilityService.instance?.global(a.str("action")) ?: NO_A11Y },
            ToolDef("read_screen", "Read all visible text and buttons on the current screen, in any app.") {
                AgentAccessibilityService.instance?.readScreen() ?: NO_A11Y
            },
            ToolDef(
                "tap_text",
                "Tap the button, switch or item on screen that shows this text.",
                listOf(Param("text", "string", "Visible text of the element")),
            ) { a ->
                val s = AgentAccessibilityService.instance ?: return@ToolDef NO_A11Y
                withContext(Dispatchers.Main) { s.tapText(a.str("text")) }.also { delay(700) }
            },
            ToolDef(
                "type_text",
                "Type text into the focused text field on screen.",
                listOf(Param("text", "string", "Text to type")),
            ) { a -> AgentAccessibilityService.instance?.typeText(a.str("text")) ?: NO_A11Y },
            ToolDef(
                "scroll",
                "Scroll the current screen.",
                listOf(Param("direction", "string", "Direction", true, listOf("up", "down"))),
            ) { a -> (AgentAccessibilityService.instance?.scroll(a.str("direction")) ?: NO_A11Y).also { delay(600) } },
            // ---------- files & documents ----------
            ToolDef(
                "list_files",
                "List files in a folder of phone storage.",
                listOf(Param("path", "string", "Folder, e.g. Download or Documents. Default: storage root", false)),
            ) { a ->
                val dir = resolve(a.str("path"))
                val files = dir.listFiles() ?: return@ToolDef "Cannot read ${dir.absolutePath}. All-files access may be missing."
                files.sortedByDescending { it.lastModified() }.take(100).map {
                    if (it.isDirectory) "${it.name}/" else "${it.name} (${it.length() / 1024} KB)"
                }
            },
            ToolDef(
                "read_text_file",
                "Read a text file (txt, md, csv, json, etc.).",
                listOf(Param("path", "string", "File path")),
            ) { a ->
                val f = resolve(a.str("path"))
                if (!f.isFile) "No such file: ${f.absolutePath}" else f.readText().take(4000)
            },
            ToolDef(
                "write_text_file",
                "Create or overwrite a text file.",
                listOf(Param("path", "string", "File path, e.g. Documents/notes.txt"), Param("content", "string", "Text to write")),
                confirm = { a -> "Write ${a.str("content").length} characters to ${resolve(a.str("path")).absolutePath}?" },
            ) { a ->
                val f = resolve(a.str("path"))
                f.parentFile?.mkdirs()
                f.writeText(a.str("content"))
                "Saved ${f.absolutePath}"
            },
            ToolDef(
                "create_pdf",
                "Create a PDF document or report from text.",
                listOf(
                    Param("title", "string", "Document title"),
                    Param("body", "string", "Full document text; use blank lines between paragraphs"),
                    Param("filename", "string", "File name without extension", false),
                ),
            ) { a -> pdf(ctx, a.str("title"), a.str("body"), a.str("filename")) },
        )

    internal val callingCodes =
        mapOf("in" to "91", "us" to "1", "ca" to "1", "gb" to "44", "ae" to "971", "sa" to "966", "sg" to "65", "au" to "61", "de" to "49", "fr" to "33", "np" to "977", "bd" to "880", "lk" to "94", "pk" to "92")

    /** Turns a contact name or a local/international number into WhatsApp's digits-only form. */
    internal fun whatsappTarget(ctx: Context, to: String): Pair<String, String>? {
        val (name, raw) =
            if (to.count { it.isDigit() } >= 7) Pair(to, to)
            else {
                val hits = mutableListOf<Pair<String, String>>()
                ctx.contentResolver
                    .query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                        arrayOf("%$to%"),
                        null,
                    )
                    ?.use { c -> while (c.moveToNext()) hits += c.getString(0) to c.getString(1) }
                (hits.firstOrNull { it.first.equals(to, true) } ?: hits.firstOrNull()) ?: return null
            }
        var digits = raw.filter { it.isDigit() }
        val code = callingCodes[ctx.getSystemService(TelephonyManager::class.java).simCountryIso.lowercase()] ?: "91"
        digits = when {
            raw.trim().startsWith("+") -> digits
            digits.startsWith("00") -> digits.drop(2)
            digits.startsWith("0") -> code + digits.drop(1)
            digits.length <= 10 -> code + digits
            else -> digits
        }
        return name to digits
    }

    private suspend fun sendWhatsapp(ctx: Context, to: String, message: String): String {
        val (name, number) = whatsappTarget(ctx, to) ?: return "No contact or number matches '$to'. Use search_contacts."
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull {
            runCatching { ctx.packageManager.getPackageInfo(it, 0) }.isSuccess
        } ?: return "WhatsApp is not installed."
        val opened = launch(
            ctx,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$number&text=" + Uri.encode(message))).setPackage(pkg),
        )
        if (opened.startsWith("Could not")) return opened
        val a11y = AgentAccessibilityService.instance
            ?: return "Opened the WhatsApp chat with $name with the message typed, but screen control is off, so the user must tap Send."
        repeat(20) {
            delay(500)
            if (withContext(Dispatchers.Main) { a11y.clickById("$pkg:id/send", "Send", pkg) }) {
                delay(800)
                return "WhatsApp message sent to $name (+$number)."
            }
        }
        return "Opened the chat with $name but could not find the Send button. Use read_screen to see what WhatsApp is showing."
    }

    private const val NO_A11Y = "Screen control is off. Ask the user to enable 'MySI Agent control' in Accessibility settings."

    private suspend fun readSensors(ctx: Context): Map<String, Any> {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val wanted = mapOf(
            Sensor.TYPE_ACCELEROMETER to "accelerometer_m_s2",
            Sensor.TYPE_GYROSCOPE to "gyroscope_rad_s",
            Sensor.TYPE_MAGNETIC_FIELD to "magnetometer_uT",
            Sensor.TYPE_LIGHT to "light_lux",
            Sensor.TYPE_PROXIMITY to "proximity_cm",
            Sensor.TYPE_PRESSURE to "pressure_hPa",
            Sensor.TYPE_STEP_COUNTER to "steps_since_boot",
            Sensor.TYPE_HEART_RATE to "heart_rate_bpm",
            Sensor.TYPE_AMBIENT_TEMPERATURE to "temperature_C",
            Sensor.TYPE_RELATIVE_HUMIDITY to "humidity_pct",
        )
        val out = sortedMapOf<String, Any>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val key = wanted[e.sensor.type] ?: return
                synchronized(out) {
                    out[key] = if (e.values.size >= 3 && e.sensor.type in setOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD))
                        e.values.take(3).map { "%.2f".format(it) }
                    else "%.1f".format(e.values[0])
                }
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        val missing = mutableListOf<String>()
        wanted.forEach { (type, key) ->
            val s = sm.getDefaultSensor(type)
            if (s == null) missing += key else sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL)
        }
        delay(1500)
        sm.unregisterListener(listener)
        return synchronized(out) { out.toMap() } + ("not_on_this_phone" to missing)
    }

    private suspend fun location(ctx: Context): Any {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { lm.isProviderEnabled(it) } ?: return "Location is turned off."
        val loc = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                lm.getCurrentLocation(provider, cancel, ctx.mainExecutor) { cont.resume(it) }
            }
        } ?: lm.getLastKnownLocation(provider) ?: return "Could not get a location fix."
        return mapOf(
            "latitude" to loc.latitude,
            "longitude" to loc.longitude,
            "accuracy_m" to loc.accuracy.toInt(),
            "map" to "https://maps.google.com/?q=${loc.latitude},${loc.longitude}",
        )
    }

    private fun status(ctx: Context): Map<String, Any?> {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val stat = StatFs(Environment.getDataDirectory().path)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            else -> "other"
        }
        return mapOf(
            "battery_pct" to bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            "charging" to bm.isCharging,
            "network" to net,
            "ram_free_gb" to "%.1f".format(mem.availMem / 1e9),
            "ram_total_gb" to "%.1f".format(mem.totalMem / 1e9),
            "storage_free_gb" to "%.1f".format(stat.availableBytes / 1e9),
            "phone" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to Build.VERSION.RELEASE,
        )
    }

    private suspend fun record(ctx: Context, seconds: Int): String {
        val f = File(outDir(ctx, "Recordings"), "rec_${System.currentTimeMillis()}.m4a")
        val r = MediaRecorder(ctx).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44_100)
            setAudioEncodingBitRate(128_000)
            setOutputFile(f.absolutePath)
            prepare()
            start()
        }
        try {
            delay(seconds * 1000L)
        } finally {
            runCatching { r.stop() }
            r.release()
        }
        return "Recorded $seconds s: ${f.absolutePath}"
    }

    private suspend fun speak(ctx: Context, text: String): String =
        withContext(Dispatchers.Main) {
            val engine = tts ?: suspendCancellableCoroutine { cont ->
                var t: TextToSpeech? = null
                t = TextToSpeech(ctx) { status -> cont.resume(if (status == TextToSpeech.SUCCESS) t else null) }
            }?.also { tts = it }
            if (engine == null) "Text-to-speech is unavailable." else {
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mysi")
                "Speaking."
            }
        }

    private fun pdf(ctx: Context, title: String, body: String, name: String): String {
        val doc = PdfDocument()
        val w = 595
        val h = 842
        val margin = 50
        val titlePaint = TextPaint().apply { textSize = 20f; isFakeBoldText = true; color = Color.BLACK; isAntiAlias = true }
        val paint = TextPaint().apply { textSize = 11.5f; color = Color.rgb(30, 30, 30); isAntiAlias = true }
        val layout = StaticLayout.Builder.obtain(body, 0, body.length, paint, w - 2 * margin)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(3f, 1f)
            .build()
        var line = 0
        var page = 1
        while (line < layout.lineCount || page == 1) {
            val p = doc.startPage(PdfDocument.PageInfo.Builder(w, h, page).create())
            val c = p.canvas
            var top = margin.toFloat()
            if (page == 1) {
                c.drawText(title, margin.toFloat(), top + 20, titlePaint)
                top += 44
            }
            val first = line
            val startY = layout.getLineTop(first).toFloat()
            while (line < layout.lineCount && layout.getLineBottom(line) - startY < h - margin - top) line++
            c.save()
            c.clipRect(margin.toFloat(), top, (w - margin).toFloat(), (h - margin).toFloat())
            c.translate(margin.toFloat(), top - startY)
            layout.draw(c)
            c.restore()
            doc.finishPage(p)
            page++
            if (layout.lineCount == 0) break
        }
        val safe = name.ifBlank { title }.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "report" }
        val f = File(outDir(ctx, "Documents"), "$safe.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return "PDF saved (${page - 1} pages): ${f.absolutePath}"
    }
}
