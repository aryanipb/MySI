package com.mysi.agent

import android.Manifest
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
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
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
 * One device capability exposed to the model.
 *
 * [confirm] returns the question shown to the user before the tool runs; a null lambda or a null
 * question means it runs without asking. [privateData] marks tools whose results carry personal
 * data (contacts, location, files, clipboard). [outbound] marks tools that send model-written text
 * to a third party (a web request, or an app opened on a URL). Once a request has touched private
 * data, every outbound call in that request needs the user's approval, so a prompt injection
 * cannot quietly carry personal data off the phone.
 */
class ToolDef(
    val name: String,
    val description: String,
    val params: List<Param> = emptyList(),
    val confirm: ((Args) -> String?)? = null,
    val privateData: Boolean = false,
    val outbound: Boolean = false,
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
    /** True while the MySI screen is in front, so an in-app dialog can be seen. */
    val inFront: Boolean

    suspend fun confirm(title: String, message: String): Boolean

    suspend fun takePhoto(file: File): Boolean

    /** Runs a system picker (photo picker or documents) and returns what the user chose. */
    suspend fun pick(intent: Intent): List<Uri>

    /** Shows Android's permission prompt; returns when the user has answered. */
    suspend fun requestPermissions(permissions: List<String>)
}

@SuppressLint("MissingPermission")
object Tools {
    private var tts: TextToSpeech? = null

    /** Opens another app. Android blocks this while MySI is in the background, so say so instead. */
    internal fun launch(ctx: Context, intent: Intent): String =
        if (!Agent.inFront) "Could not open: MySI is in the background, and Android only lets it open apps while it is on screen. Ask the user to come back to MySI and try again."
        else try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opened."
        } catch (e: Exception) {
            "Could not open: ${e.message}"
        }

    internal fun opened(result: String) = !result.startsWith("Could not")

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

    internal const val NO_CONTACTS =
        "MySI does not have contacts access. Ask the user to allow it when MySI asks, or in Settings › Apps › MySI Agent › Permissions."

    fun all(ctx: Context, host: () -> Host?): List<ToolDef> =
        listOf(
            // ---------- apps ----------
            ToolDef(
                "open_app",
                "Open an installed app by its name, e.g. WhatsApp, Camera, Zomato, Uber.",
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
            ToolDef("list_apps", "List names of all installed apps.", privateData = true) { apps(ctx).map { it.first } },
            // ---------- messages, calls, contacts (the user makes the final tap) ----------
            ToolDef(
                "whatsapp_message",
                "Open a WhatsApp chat with a contact or phone number with the message typed in. The user taps Send. Use this whenever the user says WhatsApp.",
                listOf(
                    Param("to", "string", "Contact name or phone number"),
                    Param("message", "string", "Message text"),
                ),
                privateData = true,
            ) { a -> whatsappMessage(ctx, a.str("to"), a.str("message")) },
            ToolDef(
                "compose_sms",
                "Open the SMS app with a text message ready to send. The user taps Send.",
                listOf(
                    Param("to", "string", "Phone number"),
                    Param("message", "string", "Message text"),
                ),
            ) { a ->
                val r = launch(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(a.str("to")))).putExtra("sms_body", a.str("message")))
                if (opened(r)) "SMS to ${a.str("to")} is ready in the messages app. The user must tap Send." else r
            },
            ToolDef(
                "dial_number",
                "Open the phone dialer with a number filled in. The user taps Call.",
                listOf(Param("number", "string", "Phone number")),
            ) { a ->
                val r = launch(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(a.str("number")))))
                if (opened(r)) "Dialer is open with ${a.str("number")}. The user must tap Call." else r
            },
            ToolDef(
                "search_contacts",
                "Find contacts and their phone numbers by name.",
                listOf(Param("name", "string", "Full or partial name")),
                privateData = true,
            ) { a ->
                if (!Agent.askFor(Manifest.permission.READ_CONTACTS)) return@ToolDef NO_CONTACTS
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
                    ?.use { c -> while (c.moveToNext() && out.size < 15) out += mapOf("name" to c.getString(0).orEmpty(), "number" to c.getString(1).orEmpty()) }
                out.ifEmpty { "No contact matches '${a.str("name")}'." }
            },
            // ---------- sensors & device ----------
            ToolDef("read_sensors", "Read motion and environment sensors: accelerometer, gyroscope, magnetometer, light, proximity, pressure, temperature, humidity.") {
                readSensors(ctx)
            },
            ToolDef("get_location", "Get the current location.", privateData = true) {
                if (!Agent.askFor(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    return@ToolDef "MySI does not have location access. Ask the user to allow it when MySI asks, or in Settings › Apps › MySI Agent › Permissions."
                location(ctx)
            },
            ToolDef("device_status", "Battery, charging, network, storage, memory, phone model and Android version.") {
                status(ctx)
            },
            ToolDef("get_datetime", "Current date, time and weekday.") {
                SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date())
            },
            // ---------- camera, voice ----------
            ToolDef("take_photo", "Open the camera so the user can take a photo; returns the photo's path for sharing tools.") {
                val h = host()?.takeIf { it.inFront } ?: return@ToolDef "Open MySI on screen to use the camera."
                val f = File(File(Storage.workspace(ctx), "Photos").apply { mkdirs() }, "photo_${System.currentTimeMillis()}.jpg")
                if (!h.takePhoto(f) || f.length() == 0L) {
                    f.delete()
                    return@ToolDef "No photo was taken."
                }
                val shown = runCatching { Storage.publish(ctx, f, photo = true) }.getOrNull()
                "Photo saved: ${f.absolutePath}" + (shown?.let { " (also in the gallery under $it)" } ?: "")
            },
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
                "open_settings",
                "Open a settings screen, e.g. to change Wi-Fi, Bluetooth, brightness or mobile data. Apps cannot flip these switches themselves, so tell the user what to tap.",
                listOf(
                    Param(
                        "page",
                        "string",
                        "Which settings page",
                        true,
                        listOf("wifi", "hotspot", "bluetooth", "mobile_data", "airplane", "nfc", "location", "display", "sound", "battery", "apps", "storage", "accessibility", "notifications", "date", "main"),
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
                    else -> Intent(Settings.ACTION_SETTINGS)
                }
                val r = launch(ctx, intent)
                if (!opened(r) && a.str("page") == "hotspot") launch(ctx, Intent(Settings.ACTION_WIRELESS_SETTINGS)) else r
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
                        .putExtra(AlarmClock.EXTRA_HOUR, a.int("hour", 7).coerceIn(0, 23))
                        .putExtra(AlarmClock.EXTRA_MINUTES, a.int("minute", 0).coerceIn(0, 59))
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
                        .putExtra(AlarmClock.EXTRA_LENGTH, a.int("seconds", 60).coerceIn(1, 86_400))
                        .putExtra(AlarmClock.EXTRA_MESSAGE, a.str("label", "MySI"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true),
                )
            },
            ToolDef("clipboard_read", "Read the text on the clipboard.", privateData = true) {
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
            // ---------- files (picked by the user, or made by MySI) ----------
            ToolDef(
                "pick_files",
                "Ask the user to choose photos, videos or documents in Android's picker. Returns paths to use with sharing and file tools. This is the only way to reach the user's own files.",
                listOf(
                    Param("kind", "string", "What to pick", true, listOf("photo", "video", "photo_or_video", "pdf", "document", "audio", "any")),
                    Param("multiple", "boolean", "Allow choosing several, default true", false),
                ),
                privateData = true,
            ) { a -> pickFiles(ctx, host, a.str("kind"), a.bool("multiple", true)) },
            ToolDef(
                "list_files",
                "List the files MySI made (notes, PDFs, photos) in its workspace.",
                listOf(Param("folder", "string", "Sub-folder, e.g. Documents or Photos. Default: the whole workspace", false)),
                privateData = true,
            ) { a ->
                val dir = Storage.resolve(ctx, a.str("folder").ifBlank { "." })
                    ?: return@ToolDef "MySI can only list its own workspace."
                val files = dir.listFiles() ?: return@ToolDef "No folder named '${a.str("folder")}' in the workspace."
                files.sortedByDescending { it.lastModified() }.take(100).map {
                    if (it.isDirectory) "${it.name}/" else "${it.absolutePath} (${it.length() / 1024} KB)"
                }.ifEmpty { "The workspace is empty." }
            },
            ToolDef(
                "read_text_file",
                "Read a text file (txt, md, csv, json, etc.) that the user picked or MySI made.",
                listOf(Param("path", "string", "File path from pick_files or list_files")),
                privateData = true,
            ) { a ->
                val f = Storage.resolve(ctx, a.str("path")) ?: return@ToolDef "MySI can only read files the user picked (pick_files) or files it made."
                if (!f.isFile) "No such file: ${a.str("path")}" else f.readText().take(4000)
            },
            ToolDef(
                "write_text_file",
                "Create or overwrite a text file in MySI's workspace; a copy appears in Download/MySI.",
                listOf(Param("path", "string", "File name, e.g. Documents/notes.txt"), Param("content", "string", "Text to write")),
                confirm = { a ->
                    Storage.resolve(ctx, a.str("path"))?.takeIf { it.isFile }?.let { "Overwrite ${it.name} with ${a.str("content").length} characters?" }
                },
            ) { a ->
                val f = Storage.resolve(ctx, a.str("path"))?.takeIf { !it.isDirectory }
                    ?: return@ToolDef "Give a file name inside the workspace, e.g. Documents/notes.txt."
                f.parentFile?.mkdirs()
                f.writeText(a.str("content"))
                val shown = runCatching { Storage.publish(ctx, f) }.getOrNull()
                "Saved ${f.absolutePath}" + (shown?.let { "; the user can find it in $it" } ?: "")
            },
            ToolDef(
                "create_pdf",
                "Create a PDF document or report from text; a copy appears in Download/MySI.",
                listOf(
                    Param("title", "string", "Document title"),
                    Param("body", "string", "Full document text; use blank lines between paragraphs"),
                    Param("filename", "string", "File name without extension", false),
                ),
            ) { a -> pdf(ctx, a.str("title"), a.str("body"), a.str("filename")) },
        )

    internal val callingCodes =
        mapOf("in" to "91", "us" to "1", "ca" to "1", "gb" to "44", "ae" to "971", "sa" to "966", "sg" to "65", "au" to "61", "de" to "49", "fr" to "33", "np" to "977", "bd" to "880", "lk" to "94", "pk" to "92")

    /**
     * Turns a contact name or a local/international number into WhatsApp's digits-only form.
     * Contacts access is only asked for when [to] is a name. Null means no match or no access.
     */
    internal suspend fun whatsappTarget(ctx: Context, to: String): Pair<String, String>? {
        val (name, raw) =
            if (to.count { it.isDigit() } >= 7) Pair(to, to)
            else {
                if (!Agent.askFor(Manifest.permission.READ_CONTACTS)) return null
                val hits = mutableListOf<Pair<String, String>>()
                ctx.contentResolver
                    .query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                        arrayOf("%$to%"),
                        null,
                    )
                    ?.use { c -> while (c.moveToNext()) hits += c.getString(0).orEmpty() to c.getString(1).orEmpty() }
                (hits.firstOrNull { it.first.equals(to, true) } ?: hits.firstOrNull()) ?: return null
            }
        val code = callingCodes[ctx.getSystemService(TelephonyManager::class.java).simCountryIso.lowercase()] ?: "91"
        return name to internationalDigits(raw, code)
    }

    /** "+44 20…" → "4420…", "0098…" → "98…", "098…" or "98…" (≤10 digits) → [countryCode] + "98…". */
    internal fun internationalDigits(raw: String, countryCode: String): String {
        val digits = raw.filter { it.isDigit() }
        return when {
            raw.trim().startsWith("+") -> digits
            digits.startsWith("00") -> digits.drop(2)
            digits.startsWith("0") -> countryCode + digits.drop(1)
            digits.length <= 10 -> countryCode + digits
            else -> digits
        }
    }

    /** Explains a null [whatsappTarget]: missing contacts access, or no match. */
    internal fun noTarget(ctx: Context, to: String) =
        if (to.count { it.isDigit() } < 7 && ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) NO_CONTACTS
        else "No contact or number matches '$to'. Use search_contacts, or ask the user for the number."

    internal fun whatsappPackage(ctx: Context) =
        listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { runCatching { ctx.packageManager.getPackageInfo(it, 0) }.isSuccess }

    private suspend fun whatsappMessage(ctx: Context, to: String, message: String): String {
        val (name, number) = whatsappTarget(ctx, to) ?: return noTarget(ctx, to)
        val pkg = whatsappPackage(ctx) ?: return "WhatsApp is not installed."
        val r = launch(
            ctx,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$number&text=" + Uri.encode(message))).setPackage(pkg),
        )
        return if (opened(r)) "WhatsApp chat with $name (+$number) is open with the message typed. The user must tap Send." else r
    }

    private val PICK_DOCS = mapOf(
        "pdf" to arrayOf("application/pdf"),
        "document" to arrayOf(
            "application/pdf", "text/*", "application/msword", "application/rtf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        ),
        "audio" to arrayOf("audio/*"),
        "any" to arrayOf("*/*"),
    )

    private suspend fun pickFiles(ctx: Context, host: () -> Host?, kind: String, multiple: Boolean): Any {
        val h = host()?.takeIf { it.inFront } ?: return "Open MySI on screen so the user can pick files."
        val intent =
            if (kind in listOf("photo", "video", "photo_or_video"))
                // The system photo picker: no media permission, the user shares only what they choose.
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    when (kind) {
                        "photo" -> type = "image/*"
                        "video" -> type = "video/*"
                    }
                    if (multiple) putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, minOf(20, MediaStore.getPickImagesMaxLimit()))
                }
            else
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, PICK_DOCS[kind] ?: PICK_DOCS.getValue("any"))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
                }
        val uris = h.pick(intent)
        if (uris.isEmpty()) return "The user did not pick anything."
        val (files, skipped) = withContext(Dispatchers.IO) { Storage.importPicked(ctx, uris) }
        if (files.isEmpty()) return "Could not read the picked files." + if (skipped.isEmpty()) "" else " Skipped: ${skipped.joinToString(", ")}."
        return mapOf("paths" to files.map { it.absolutePath }, "skipped" to skipped)
    }

    private suspend fun readSensors(ctx: Context): Map<String, Any> {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val wanted = mapOf(
            Sensor.TYPE_ACCELEROMETER to "accelerometer_m_s2",
            Sensor.TYPE_GYROSCOPE to "gyroscope_rad_s",
            Sensor.TYPE_MAGNETIC_FIELD to "magnetometer_uT",
            Sensor.TYPE_LIGHT to "light_lux",
            Sensor.TYPE_PROXIMITY to "proximity_cm",
            Sensor.TYPE_PRESSURE to "pressure_hPa",
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
        try {
            delay(1500)
        } finally {
            sm.unregisterListener(listener)
        }
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
        val f = File(File(Storage.workspace(ctx), "Documents").apply { mkdirs() }, "$safe.pdf")
        try {
            f.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        val shown = runCatching { Storage.publish(ctx, f) }.getOrNull()
        return "PDF saved (${page - 1} pages): ${f.absolutePath}" + (shown?.let { "; the user can find it in $it" } ?: "")
    }
}
