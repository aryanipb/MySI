package com.mysi.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.TelephonyManager
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App-level skills: hand files and text to WhatsApp, email and social apps through Android's
 * share system, open a page or a search in Chrome or the Google app, and open shopping, video,
 * map and ride apps. Background web search and reading live in the web skill (web/WebSkill.kt).
 *
 * MySI never operates another app's screen. Every hand-off opens the other app with the content
 * filled in, and the user makes the final tap (Send, Post, Book, Pay).
 */
object AppTools {
    private const val GMAIL = "com.google.android.gm"

    private val packages =
        mapOf(
            "whatsapp" to "com.whatsapp",
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

    private val rideApps =
        mapOf(
            "uber" to listOf("com.ubercab"),
            "ola" to listOf("com.olacabs.customer"),
            "rapido" to listOf("com.rapido.passenger"),
        )

    private fun installed(ctx: Context, pkg: String) =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** Splits "a.jpg, b.pdf" into files MySI may share; missing or out-of-bounds ones are reported. */
    private fun files(ctx: Context, paths: String): Pair<List<File>, List<String>> {
        val wanted = paths.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val (ok, missing) = wanted.map { it to Storage.resolve(ctx, it) }.partition { it.second?.isFile == true }
        return ok.mapNotNull { it.second } to missing.map { it.first }
    }

    private fun uri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    private fun mime(files: List<File>): String {
        val types = files.map(Storage::mime).distinct()
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

    private fun names(list: List<File>) = list.joinToString(", ") { it.name }

    private fun missingNote(missing: List<String>) =
        if (missing.isEmpty()) "" else " Not found or not shareable, so skipped: ${missing.joinToString(", ")}. Use pick_files to get shareable paths."

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
            // ---------- WhatsApp, email, sharing (the user taps Send / Post) ----------
            ToolDef(
                "whatsapp_files",
                "Open a WhatsApp chat with photos, videos or documents attached and an optional caption. The user taps Send. Get paths from pick_files, take_photo or create_pdf first.",
                listOf(
                    Param("to", "string", "Contact name or phone number"),
                    Param("paths", "string", "File paths separated by commas"),
                    Param("caption", "string", "Optional message with the files", false),
                ),
                privateData = true,
            ) { a ->
                val pkg = Tools.whatsappPackage(ctx) ?: return@ToolDef "WhatsApp is not installed."
                val (name, number) = Tools.whatsappTarget(ctx, a.str("to")) ?: return@ToolDef Tools.noTarget(ctx, a.str("to"))
                val (list, missing) = files(ctx, a.str("paths"))
                if (list.isEmpty()) return@ToolDef "None of the files can be shared.${missingNote(missing)}"
                // The jid extra opens the chat directly instead of WhatsApp's contact picker.
                val intent = shareIntent(ctx, list, a.str("caption")).setPackage(pkg).putExtra("jid", "$number@s.whatsapp.net")
                val opened = Tools.launch(ctx, intent)
                if (!Tools.opened(opened)) opened
                else "WhatsApp is open with ${list.size} file(s) (${names(list)}) for $name (+$number). The user must tap Send.${missingNote(missing)}"
            },
            ToolDef(
                "compose_email",
                "Open an email draft (Gmail if installed, else the user's email app), optionally with attachments. The user taps Send.",
                listOf(
                    Param("to", "string", "Email address, or several separated by commas"),
                    Param("subject", "string", "Subject line"),
                    Param("body", "string", "Email text"),
                    Param("attachments", "string", "File paths separated by commas", false),
                ),
            ) { a ->
                val (list, missing) = files(ctx, a.str("attachments"))
                val intent = shareIntent(ctx, list, a.str("body"))
                    .putExtra(Intent.EXTRA_EMAIL, a.str("to").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray())
                    .putExtra(Intent.EXTRA_SUBJECT, a.str("subject"))
                val opened =
                    if (installed(ctx, GMAIL)) Tools.launch(ctx, intent.setPackage(GMAIL))
                    // The mailto selector limits the chooser to email apps while SEND keeps the attachments.
                    else Tools.launch(ctx, Intent.createChooser(intent.apply { selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")) }, "Send email"))
                if (!Tools.opened(opened)) opened
                else "Email draft to ${a.str("to")} is open" + (if (list.isNotEmpty()) " with ${names(list)}" else "") +
                    ". The user must tap Send.${missingNote(missing)}"
            },
            ToolDef(
                "share_to_app",
                "Open an app's share screen with files and/or text, e.g. send to Instagram or Telegram chats, save to Drive or Google Files. The user picks the recipient and confirms.",
                listOf(
                    Param("app", "string", "Target app", true, packages.keys.toList() + "any"),
                    Param("paths", "string", "File paths separated by commas", false),
                    Param("text", "string", "Text to include", false),
                ),
            ) { a ->
                val (list, missing) = files(ctx, a.str("paths"))
                if (list.isEmpty() && a.str("text").isBlank()) return@ToolDef "Nothing to share.${missingNote(missing)}"
                val intent = shareIntent(ctx, list, a.str("text"))
                val pkg = packages[a.str("app")]
                val result =
                    if (pkg == null) Tools.launch(ctx, Intent.createChooser(intent, "Share"))
                    else if (!installed(ctx, pkg)) return@ToolDef "${a.str("app")} is not installed."
                    else Tools.launch(ctx, intent.setPackage(pkg))
                if (!Tools.opened(result)) result
                else "The share screen is open. The user picks where it goes and confirms.${missingNote(missing)}"
            },
            ToolDef(
                "post_to_social",
                "Start a post on Instagram, Facebook or X with photos/videos and a caption. Opens the post editor with the media attached and the caption copied; the user makes the final Post tap.",
                listOf(
                    Param("platform", "string", "Where to post", true, listOf("instagram", "facebook", "x")),
                    Param("paths", "string", "Photo or video paths separated by commas", false),
                    Param("caption", "string", "Post caption", false),
                ),
            ) { a ->
                val pkg = packages[a.str("platform")] ?: return@ToolDef "Unknown platform."
                if (!installed(ctx, pkg)) return@ToolDef "${a.str("platform")} is not installed."
                val (list, missing) = files(ctx, a.str("paths"))
                if (list.isEmpty() && a.str("caption").isBlank()) return@ToolDef "Nothing to post.${missingNote(missing)}"
                // Instagram and Facebook drop EXTRA_TEXT, so the caption also goes on the clipboard to paste.
                if (a.str("caption").isNotBlank())
                    withContext(Dispatchers.Main) {
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("caption", a.str("caption")))
                    }
                val opened = Tools.launch(ctx, shareIntent(ctx, list, a.str("caption")).setPackage(pkg))
                if (!Tools.opened(opened)) opened
                else "Opened the ${a.str("platform")} post editor with ${list.size} file(s); caption copied to clipboard.${missingNote(missing)} " +
                    "The user reviews it and taps Post."
            },
            ToolDef(
                "open_profile",
                "Open a profile or page in Instagram, X or Facebook.",
                listOf(
                    Param("platform", "string", "Which app", true, listOf("instagram", "x", "facebook")),
                    Param("username", "string", "Username or page name, without @"),
                ),
                outbound = true,
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
            // ---------- browser on screen (the web skill reads pages in the background) ----------
            ToolDef(
                "open_in_chrome",
                "Open a web address, or a Google search for any text, in Chrome on screen.",
                listOf(Param("url_or_query", "string", "A URL, or words to search on Google")),
                outbound = true,
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
                outbound = true,
            ) { a ->
                Tools.launch(
                    ctx,
                    Intent(Intent.ACTION_WEB_SEARCH).setPackage("com.google.android.googlequicksearchbox").putExtra(android.app.SearchManager.QUERY, a.str("query")),
                ).let { r ->
                    if (!Tools.opened(r)) Tools.launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(a.str("query"))))) else r
                }
            },
            // ---------- shopping, video, maps, store, rides ----------
            ToolDef(
                "amazon_search",
                "Open Amazon search results for a product (in the Amazon app if installed, else the browser). The user browses, adds to cart and pays there.",
                listOf(Param("query", "string", "Product to search for")),
                outbound = true,
            ) { a ->
                val url = "https://www.${countryHost(ctx)}/s?k=" + Uri.encode(a.str("query"))
                val app = listOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping").firstOrNull { installed(ctx, it) }
                val r = Tools.launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { app?.let { setPackage(it) } })
                if (Tools.opened(r)) "Amazon results for '${a.str("query")}' are open. The user picks a product and checks out." else r
            },
            ToolDef(
                "youtube_search",
                "Search YouTube.",
                listOf(Param("query", "string", "What to search for")),
                outbound = true,
            ) { a ->
                Tools.launch(
                    ctx,
                    Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", a.str("query"))
                ).let { r ->
                    if (!Tools.opened(r))
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
                outbound = true,
            ) { a ->
                val q = Uri.encode(a.str("place"))
                val uri = if (a.str("action") == "navigate") "google.navigation:q=$q" else "geo:0,0?q=$q"
                Tools.launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
            },
            ToolDef(
                "play_store_search",
                "Search for apps on the Play Store.",
                listOf(Param("query", "string", "App to search for")),
                outbound = true,
            ) { a -> Tools.launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(a.str("query"))))) },
            ToolDef(
                "ride_to",
                "Open Uber with the destination filled in, or open Ola or Rapido. The user compares rides and books in the app.",
                listOf(
                    Param("app", "string", "Ride app", true, rideApps.keys.toList()),
                    Param("destination", "string", "Where to go"),
                ),
                outbound = true,
            ) { a -> rideTo(ctx, a.str("app"), a.str("destination")) },
        )

    private fun rideTo(ctx: Context, app: String, dest: String): String {
        val pkg = rideApps[app]?.firstOrNull { installed(ctx, it) } ?: return "$app is not installed."
        if (app == "uber" && dest.isNotBlank()) {
            // Uber's documented universal link pre-fills pickup (current location) and drop-off.
            val link = "https://m.uber.com/ul/?action=setPickup&pickup=my_location&dropoff[formatted_address]=" + Uri.encode(dest)
            val r = Tools.launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg))
            if (Tools.opened(r)) return "Uber is open with the drop-off set to $dest. The user picks a ride and books it."
        }
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return "$app has no launch screen."
        val r = Tools.launch(ctx, launch)
        return if (Tools.opened(r)) "$app is open. The user enters $dest as the destination, picks a ride and books it." else r
    }
}
