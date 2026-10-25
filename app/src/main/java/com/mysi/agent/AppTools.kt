package com.mysi.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.telephony.TelephonyManager
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * App-level skills: find media and documents, hand them to WhatsApp, Gmail and social apps
 * through Android's share system, and open shopping, video and map searches.
 *
 * Anything that drives another app's screen needs that app in the foreground; Android has no way
 * to operate another app's UI invisibly. Searching storage runs fully in the background.
 */
object AppTools {
    private const val WHATSAPP = "com.whatsapp"
    private const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    private const val GMAIL = "com.google.android.gm"

    private val packages =
        mapOf(
            "whatsapp" to WHATSAPP,
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "messenger" to "com.facebook.orca",
            "x" to "com.twitter.android",
            "telegram" to "org.telegram.messenger",
            "gmail" to GMAIL,
            "drive" to "com.google.android.apps.docs",
            "google_photos" to "com.google.android.apps.photos",
            "google_files" to "com.google.android.apps.nbu.files",
        )

    private fun installed(ctx: Context, pkg: String) =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun whatsappPackage(ctx: Context) =
        listOf(WHATSAPP, WHATSAPP_BUSINESS).firstOrNull { installed(ctx, it) }

    /** Splits "a.jpg, Download/b.pdf" into existing files; missing ones are reported, not dropped. */
    private fun files(paths: String): Pair<List<File>, List<String>> {
        val wanted = paths.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val (ok, missing) = wanted.map { it to Tools.resolve(it) }.partition { it.second.isFile }
        return ok.map { it.second } to missing.map { it.first }
    }

    private fun uri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    private fun mime(files: List<File>): String {
        val types = files.map {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.extension.lowercase()) ?: "application/octet-stream"
        }.distinct()
        return when {
            types.size == 1 -> types[0]
            types.all { it.startsWith("image/") } -> "image/*"
            types.all { it.startsWith("video/") } -> "video/*"
            else -> "*/*"
        }
    }

    /** ACTION_SEND for one file, ACTION_SEND_MULTIPLE for several, with read access granted. */
    private fun shareIntent(ctx: Context, list: List<File>, text: String): Intent {
        val uris = ArrayList(list.map { uri(ctx, it) })
        val intent =
            if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else if (uris.isEmpty()) Intent(Intent.ACTION_SEND)
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        intent.type = if (list.isEmpty()) "text/plain" else mime(list)
        if (text.isNotBlank()) intent.putExtra(Intent.EXTRA_TEXT, text)
        if (uris.isNotEmpty())
            intent.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        return intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Waits for [pkg] to show its send button, then taps it. */
    private suspend fun tapSend(pkg: String, id: String): Boolean {
        val a11y = AgentAccessibilityService.instance ?: return false
        repeat(24) {
            delay(500)
            if (withContext(Dispatchers.Main) { a11y.clickById(id, "Send", pkg) }) {
                delay(900)
                return true
            }
        }
        return false
    }

    private fun names(list: List<File>) = list.joinToString(", ") { it.name }

    private fun missingNote(missing: List<String>) =
        if (missing.isEmpty()) "" else " Not found, so skipped: ${missing.joinToString(", ")}."

    private fun countryHost(ctx: Context) =
        when (ctx.getSystemService(TelephonyManager::class.java).simCountryIso.lowercase()) {
            "in" -> "amazon.in"
            "gb" -> "amazon.co.uk"
            "ae" -> "amazon.ae"
            "ca" -> "amazon.ca"
            "au" -> "amazon.com.au"
            "de" -> "amazon.de"
            else -> "amazon.com"
        }

    fun all(ctx: Context): List<ToolDef> =
        listOf(
            // ---------- finding things (runs in background) ----------
            ToolDef(
                "find_media",
                "Find photos, videos or audio on the phone, newest first. Returns file paths to use with sharing tools.",
                listOf(
                    Param("kind", "string", "What to find", true, listOf("photo", "video", "audio")),
                    Param("days", "integer", "Only from the last N days, default 30", false),
                    Param("count", "integer", "How many, default 10", false),
                    Param("contains", "string", "Only names or albums containing this, e.g. Screenshot, WhatsApp, Camera", false),
                ),
            ) { a -> findMedia(ctx, a) },
            ToolDef(
                "find_files",
                "Find documents and other files by name anywhere on the phone, newest first.",
                listOf(
                    Param("name", "string", "Part of the file name", false),
                    Param("kind", "string", "File type filter", false, listOf("pdf", "word", "excel", "powerpoint", "text", "zip", "apk", "any")),
                    Param("count", "integer", "How many, default 15", false),
                ),
            ) { a -> findFiles(ctx, a) },
            // ---------- WhatsApp ----------
            ToolDef(
                "whatsapp_send_files",
                "Send photos, videos or documents to a WhatsApp contact, with an optional caption.",
                listOf(
                    Param("to", "string", "Contact name or phone number"),
                    Param("paths", "string", "File paths separated by commas"),
                    Param("caption", "string", "Optional message with the files", false),
                ),
                confirm = { a ->
                    val who = Tools.whatsappTarget(ctx, a.str("to"))
                    val (list, missing) = files(a.str("paths"))
                    "Send ${list.size} file(s) on WhatsApp to ${who?.let { "${it.first} (+${it.second})" } ?: a.str("to")}?\n\n${names(list)}" +
                        missingNote(missing) + if (a.str("caption").isNotBlank()) "\n\nCaption: \"${a.str("caption")}\"" else ""
                },
            ) { a ->
                val pkg = whatsappPackage(ctx) ?: return@ToolDef "WhatsApp is not installed."
                val (name, number) = Tools.whatsappTarget(ctx, a.str("to")) ?: return@ToolDef "No contact or number matches '${a.str("to")}'."
                val (list, missing) = files(a.str("paths"))
                if (list.isEmpty()) return@ToolDef "None of the files exist.${missingNote(missing)} Use find_media or find_files first."
                // The jid extra opens the chat directly instead of WhatsApp's contact picker.
                val intent = shareIntent(ctx, list, a.str("caption")).setPackage(pkg).putExtra("jid", "$number@s.whatsapp.net")
                val opened = Tools.launch(ctx, intent)
                if (opened.startsWith("Could not")) return@ToolDef opened
                if (AgentAccessibilityService.instance == null)
                    return@ToolDef "Opened WhatsApp with the files for $name; screen control is off, so the user must tap Send."
                if (tapSend(pkg, "$pkg:id/send")) "Sent ${list.size} file(s) to $name on WhatsApp.${missingNote(missing)}"
                else "Opened WhatsApp with the files for $name but could not find Send. Use read_screen to check."
            },
            // ---------- email ----------
            ToolDef(
                "send_email",
                "Send an email through Gmail, optionally with attachments.",
                listOf(
                    Param("to", "string", "Email address, or several separated by commas"),
                    Param("subject", "string", "Subject line"),
                    Param("body", "string", "Email text"),
                    Param("attachments", "string", "File paths separated by commas", false),
                ),
                confirm = { a ->
                    val (list, missing) = files(a.str("attachments"))
                    "Email ${a.str("to")}?\n\nSubject: ${a.str("subject")}\n\n${a.str("body").take(400)}" +
                        (if (list.isNotEmpty()) "\n\nAttachments: ${names(list)}" else "") + missingNote(missing)
                },
            ) { a ->
                if (!installed(ctx, GMAIL)) return@ToolDef "Gmail is not installed."
                val (list, missing) = files(a.str("attachments"))
                val intent = shareIntent(ctx, list, a.str("body"))
                    .setPackage(GMAIL)
                    .putExtra(Intent.EXTRA_EMAIL, a.str("to").split(',').map { it.trim() }.toTypedArray())
                    .putExtra(Intent.EXTRA_SUBJECT, a.str("subject"))
                val opened = Tools.launch(ctx, intent)
                if (opened.startsWith("Could not")) return@ToolDef opened
                if (AgentAccessibilityService.instance == null) return@ToolDef "Gmail draft is open; screen control is off, so the user must tap Send."
                if (tapSend(GMAIL, "$GMAIL:id/send")) "Email sent to ${a.str("to")}.${missingNote(missing)}"
                else "The Gmail draft is open but Send was not found. Use read_screen to check."
            },
            // ---------- sharing & social ----------
            ToolDef(
                "share_to_app",
                "Open an app's share screen with files and/or text, e.g. send to Instagram or Messenger chats, save to Drive or Google Files. The user or later tap_text steps pick the recipient.",
                listOf(
                    Param("app", "string", "Target app", true, packages.keys.toList() + "any"),
                    Param("paths", "string", "File paths separated by commas", false),
                    Param("text", "string", "Text to include", false),
                ),
                confirm = { a ->
                    val (list, missing) = files(a.str("paths"))
                    "Share to ${a.str("app")}?\n\n${names(list).ifBlank { a.str("text").take(300) }}" + missingNote(missing)
                },
            ) { a ->
                val (list, missing) = files(a.str("paths"))
                if (list.isEmpty() && a.str("text").isBlank()) return@ToolDef "Nothing to share.${missingNote(missing)}"
                val intent = shareIntent(ctx, list, a.str("text"))
                val pkg = packages[a.str("app")]
                val result =
                    if (pkg == null) Tools.launch(ctx, Intent.createChooser(intent, "Share"))
                    else if (!installed(ctx, pkg)) return@ToolDef "${a.str("app")} is not installed."
                    else Tools.launch(ctx, intent.setPackage(pkg))
                "$result${missingNote(missing)} Use read_screen to see the next step."
            },
            ToolDef(
                "post_to_social",
                "Start a post on Instagram, Facebook or X with photos/videos and a caption. Opens the post editor with the media attached and the caption copied; the user makes the final Post tap.",
                listOf(
                    Param("platform", "string", "Where to post", true, listOf("instagram", "facebook", "x")),
                    Param("paths", "string", "Photo or video paths separated by commas", false),
                    Param("caption", "string", "Post caption", false),
                ),
                confirm = { a ->
                    val (list, missing) = files(a.str("paths"))
                    "Open a ${a.str("platform")} post with ${list.size} file(s)?\n\n${names(list)}" + missingNote(missing) +
                        if (a.str("caption").isNotBlank()) "\n\nCaption: \"${a.str("caption")}\"" else ""
                },
            ) { a ->
                val pkg = packages[a.str("platform")] ?: return@ToolDef "Unknown platform."
                if (!installed(ctx, pkg)) return@ToolDef "${a.str("platform")} is not installed."
                val (list, missing) = files(a.str("paths"))
                if (list.isEmpty() && a.str("caption").isBlank()) return@ToolDef "Nothing to post.${missingNote(missing)}"
                // Instagram and Facebook drop EXTRA_TEXT, so the caption also goes on the clipboard to paste.
                if (a.str("caption").isNotBlank())
                    withContext(Dispatchers.Main) {
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("caption", a.str("caption")))
                    }
                val opened = Tools.launch(ctx, shareIntent(ctx, list, a.str("caption")).setPackage(pkg))
                if (opened.startsWith("Could not")) opened
                else "Opened the ${a.str("platform")} post editor with ${list.size} file(s); caption copied to clipboard.${missingNote(missing)} " +
                    "Tell the user to review and tap Post. Do not tap Post or Share yourself."
            },
            ToolDef(
                "open_profile",
                "Open a profile or page in Instagram, X or Facebook.",
                listOf(
                    Param("platform", "string", "Which app", true, listOf("instagram", "x", "facebook")),
                    Param("username", "string", "Username or page name, without @"),
                ),
            ) { a ->
                val u = Uri.encode(a.str("username").removePrefix("@"))
                val url = when (a.str("platform")) {
                    "instagram" -> "https://www.instagram.com/$u/"
                    "x" -> "https://x.com/$u"
                    else -> "https://www.facebook.com/$u"
                }
                val pkg = packages[a.str("platform")]
                val viaApp = pkg != null && installed(ctx, pkg)
                Tools.launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { if (viaApp) setPackage(pkg) })
            },
            // ---------- shopping, video, maps, store ----------
            // ---------- internet (search_internet and read_web_page run fully in background) ----------
            ToolDef(
                "search_internet",
                "Search the internet in the background and get result titles, links and snippets without opening any app. Use read_web_page to read a result.",
                listOf(
                    Param("query", "string", "What to search for"),
                    Param("count", "integer", "How many results, default 6", false),
                ),
            ) { a -> searchInternet(a.str("query"), a.int("count", 6).coerceIn(1, 15)) },
            ToolDef(
                "read_web_page",
                "Download a web page in the background and return its readable text.",
                listOf(
                    Param("url", "string", "Page address"),
                    Param("max_chars", "integer", "How much text to return, default 3000", false),
                ),
            ) { a -> readPage(a.str("url"), a.int("max_chars", 3000).coerceIn(500, 3400)) },
            ToolDef(
                "open_in_chrome",
                "Open a web address, or a Google search for any text, in Chrome on screen.",
                listOf(Param("url_or_query", "string", "A URL, or words to search on Google")),
            ) { a ->
                val q = a.str("url_or_query").trim()
                val url = when {
                    q.startsWith("http://") || q.startsWith("https://") -> q
                    !q.contains(' ') && q.contains('.') -> "https://$q"
                    else -> "https://www.google.com/search?q=" + Uri.encode(q)
                }
                val chrome = "com.android.chrome"
                Tools.launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { if (installed(ctx, chrome)) setPackage(chrome) })
            },
            ToolDef(
                "google_app_search",
                "Search in the Google app (shows Google's answer cards, images, news).",
                listOf(Param("query", "string", "What to search for")),
            ) { a ->
                Tools.launch(
                    ctx,
                    Intent(Intent.ACTION_WEB_SEARCH).setPackage("com.google.android.googlequicksearchbox").putExtra(android.app.SearchManager.QUERY, a.str("query")),
                ).let { r ->
                    if (r.startsWith("Could not")) Tools.launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(a.str("query"))))) else r
                }
            },
            ToolDef(
                "amazon_search",
                "Search for a product on Amazon (app if installed, else browser).",
                listOf(Param("query", "string", "Product to search for")),
            ) { a ->
                val url = "https://www.${countryHost(ctx)}/s?k=" + Uri.encode(a.str("query"))
                val app = listOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping").firstOrNull { installed(ctx, it) }
                val r = Tools.launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { app?.let { setPackage(it) } })
                "$r Showing Amazon results for '${a.str("query")}'. Use read_screen to read prices and titles."
            },
            ToolDef(
                "youtube_search",
                "Search YouTube.",
                listOf(Param("query", "string", "What to search for")),
            ) { a ->
                Tools.launch(
                    ctx,
                    Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", a.str("query"))
                ).let { r ->
                    if (r.startsWith("Could not"))
                        Tools.launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(a.str("query")))))
                    else r
                }
            },
            ToolDef(
                "maps",
                "Search places or start navigation in Google Maps.",
                listOf(
                    Param("action", "string", "search or navigate", true, listOf("search", "navigate")),
                    Param("place", "string", "Place, address or kind of place, e.g. petrol pump near me"),
                ),
            ) { a ->
                val q = Uri.encode(a.str("place"))
                val uri = if (a.str("action") == "navigate") "google.navigation:q=$q" else "geo:0,0?q=$q"
                Tools.launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
            },
            ToolDef(
                "play_store_search",
                "Search for apps on the Play Store.",
                listOf(Param("query", "string", "App to search for")),
            ) { a -> Tools.launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(a.str("query"))))) },
        )

    private const val AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    /** GET with timeouts and a 2 MB cap so a huge page cannot exhaust memory. */
    private fun fetch(url: String, form: String? = null): String {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", AGENT)
        c.setRequestProperty("Accept-Language", "en")
        if (form != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(form.toByteArray()) }
        }
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            val bytes = c.inputStream.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16_384)
                while (buf.size() < 2_000_000) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray()
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    private fun plain(html: String) =
        android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()

    /** DuckDuckGo's no-JavaScript endpoint: plain HTML results, no API key, no tracking cookies. */
    private fun searchInternet(query: String, count: Int): Any {
        val html = fetch("https://html.duckduckgo.com/html/", "q=" + Uri.encode(query))
        val links = Regex("""<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        val snippets = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(html).map { plain(it.groupValues[1]) }.toList()
        val results = links.mapIndexedNotNull { i, m ->
            var href = android.text.Html.fromHtml(m.groupValues[1], android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            if (href.startsWith("//")) href = "https:$href"
            // Results link through a redirect; the real address is in the uddg parameter.
            Uri.parse(href).getQueryParameter("uddg")?.let { href = it }
            if (href.contains("duckduckgo.com/y.js")) null // sponsored
            else mapOf("title" to plain(m.groupValues[2]), "url" to href, "snippet" to snippets.getOrElse(i) { "" })
        }
        return results.take(count).ifEmpty { "No results, or the search page changed format. Try open_in_chrome instead." }
    }

    private fun readPage(url: String, max: Int): String {
        val full = if (url.startsWith("http")) url else "https://$url"
        val html = fetch(full)
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(html)?.groupValues?.get(1)?.let(::plain).orEmpty()
        val body = html
            .replace(Regex("<(script|style|noscript|svg|nav|footer|header)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
        val text = plain(body)
        return "Title: $title\n${text.take(max)}" + if (text.length > max) " …(truncated)" else ""
    }

    private fun findMedia(ctx: Context, a: Args): Any {
        val (uri, hasAlbum) = when (a.str("kind")) {
            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true
            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to false
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to true
        }
        val since = System.currentTimeMillis() / 1000 - a.int("days", 30).coerceIn(1, 3650) * 86_400L
        val contains = a.str("contains")
        val cols = buildList {
            add(MediaStore.MediaColumns.DATA)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.SIZE)
            if (hasAlbum) add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        }
        var where = "${MediaStore.MediaColumns.DATE_ADDED} >= ?"
        val args = mutableListOf(since.toString())
        if (contains.isNotBlank()) {
            where += " AND (${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" +
                (if (hasAlbum) " OR ${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} LIKE ?" else "") + ")"
            args += "%$contains%"
            if (hasAlbum) args += "%$contains%"
        }
        val out = mutableListOf<Map<String, String>>()
        ctx.contentResolver
            .query(uri, cols.toTypedArray(), where, args.toTypedArray(), "${MediaStore.MediaColumns.DATE_ADDED} DESC")
            ?.use { c ->
                while (c.moveToNext() && out.size < a.int("count", 10).coerceIn(1, 50))
                    out += buildMap {
                        put("path", c.getString(0).orEmpty())
                        put("added", Tools.time(c.getLong(1) * 1000))
                        put("kb", (c.getLong(2) / 1024).toString())
                        if (hasAlbum) put("album", c.getString(3).orEmpty())
                    }
            }
        return out.ifEmpty { "No ${a.str("kind", "photo")}s found for that filter." }
    }

    private fun findFiles(ctx: Context, a: Args): Any {
        val mimes = when (a.str("kind")) {
            "pdf" -> listOf("application/pdf")
            "word" -> listOf("application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
            "excel" -> listOf("application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv")
            "powerpoint" -> listOf("application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation")
            "text" -> listOf("text/plain", "text/markdown")
            "zip" -> listOf("application/zip", "application/x-7z-compressed", "application/x-rar-compressed")
            "apk" -> listOf("application/vnd.android.package-archive")
            else -> emptyList()
        }
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (a.str("name").isNotBlank()) {
            clauses += "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            args += "%${a.str("name")}%"
        }
        if (mimes.isNotEmpty()) {
            clauses += "${MediaStore.MediaColumns.MIME_TYPE} IN (${mimes.joinToString(",") { "?" }})"
            args += mimes
        }
        if (clauses.isEmpty()) return "Give a name or a kind of file to search for."
        val out = mutableListOf<Map<String, String>>()
        ctx.contentResolver
            .query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE),
                clauses.joinToString(" AND "),
                args.toTypedArray(),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            )
            ?.use { c ->
                while (c.moveToNext() && out.size < a.int("count", 15).coerceIn(1, 50)) {
                    val path = c.getString(0).orEmpty()
                    if (File(path).isDirectory) continue
                    out += mapOf("path" to path, "modified" to Tools.time(c.getLong(1) * 1000), "kb" to (c.getLong(2) / 1024).toString())
                }
            }
        return out.ifEmpty { "No matching files. All-files access may be missing." }
    }
}
