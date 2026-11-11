package com.mysi.agent

import android.content.Context

/**
 * Every skill MySI has. [core] is always loaded; the router picks from [all] for each request and
 * the model can load more with use_skill. Adding a skill pack is one line in [all].
 */
object Skills {
    /** The built-in tools of each skill, by tool name. A unit test checks every built-in tool is listed exactly once. */
    internal val builtinGroups: Map<String, List<String>> =
        linkedMapOf(
            "core" to listOf("get_datetime", "open_app", "list_apps", "clipboard_read", "clipboard_write"),
            "communication" to listOf("whatsapp_message", "whatsapp_files", "compose_sms", "dial_number", "search_contacts", "compose_email", "share_to_app"),
            "files" to listOf("pick_files", "list_files", "read_text_file", "write_text_file", "create_pdf", "take_photo"),
            "device" to listOf("device_status", "read_sensors", "get_location", "flashlight", "vibrate", "open_settings"),
            "alarms_media" to listOf("set_alarm", "set_timer", "set_volume", "media_control", "speak"),
            "web" to listOf("search_internet", "read_web_page", "open_in_chrome", "google_app_search"),
            "social" to listOf("post_to_social", "open_profile", "youtube_search"),
            "shopping_travel" to listOf("amazon_search", "maps", "ride_to", "play_store_search"),
        )

    @Volatile private var builtinCache: Map<String, ToolDef>? = null

    private fun builtin(ctx: Context): Map<String, ToolDef> =
        builtinCache ?: (Tools.all(ctx) { Agent.host } + AppTools.all(ctx)).associateBy { it.name }.also { builtinCache = it }

    private fun group(name: String): (Context) -> List<ToolDef> = { ctx ->
        val tools = builtin(ctx)
        builtinGroups.getValue(name).map { tools[it] ?: error("Skill '$name' lists unknown tool '$it'.") }
    }

    val core =
        Skill(
            name = "core",
            description = "Date and time, opening installed apps, the clipboard, loading other skills and reading long tool results.",
            tools = { ctx -> group("core")(ctx) + Agent.metaTools() },
        )

    val communication =
        Skill(
            name = "communication",
            description = "Message, call, text or email people on WhatsApp, SMS, phone or email, find contacts, and share files or text to apps.",
            keywords = listOf("whatsapp", "message", "msg", "text", "sms", "chat", "call", "phone", "dial", "ring", "contact", "number", "email", "mail", "gmail", "send", "share", "telegram", "reply", "invite", "wish", "inform"),
            instructions = "WhatsApp: whatsapp_message for text, whatsapp_files for photos and documents. Use compose_sms only when the user asks for SMS or a text message.",
            tools = group("communication"),
        )

    val files =
        Skill(
            name = "files",
            description = "Pick the user's photos and documents, read and write text files, make PDF documents and take photos with the camera.",
            keywords = listOf("file", "document", "doc", "pdf", "note", "photo", "picture", "pic", "image", "selfie", "camera", "gallery", "save", "write", "read", "report", "folder", "txt", "csv", "attachment", "attach", "letter", "resume", "draft"),
            instructions = "Files: you can only use files the user picks with pick_files, photos from take_photo, and files you made with write_text_file or create_pdf. Use the paths these tools return. Never guess a path.",
            tools = group("files"),
        )

    val device =
        Skill(
            name = "device",
            description = "Phone status and hardware: battery, network, storage, location, sensors, flashlight, vibration and settings screens such as Wi-Fi or Bluetooth.",
            keywords = listOf("battery", "charge", "charging", "wifi", "bluetooth", "network", "internet connection", "storage", "space", "memory", "ram", "location", "gps", "where am", "sensor", "compass", "light", "flashlight", "torch", "vibrate", "brightness", "settings", "hotspot", "airplane", "mobile data", "nfc", "android version", "model"),
            tools = group("device"),
        )

    val alarmsMedia =
        Skill(
            name = "alarms_media",
            description = "Alarms, timers, volume, music and video playback controls, and speaking text aloud.",
            keywords = listOf("alarm", "wake", "timer", "countdown", "remind", "reminder", "volume", "loud", "quiet", "mute", "music", "song", "play", "pause", "resume", "next", "skip", "previous", "track", "speak", "aloud", "say", "voice"),
            tools = group("alarms_media"),
        )

    val web =
        Skill(
            name = "web",
            description = "Search the internet and read web pages in the background for facts, news, prices and answers, or open a page in Chrome.",
            keywords = listOf("search", "internet", "web", "google", "online", "look up", "lookup", "news", "weather", "latest", "today", "website", "site", "page", "url", "link", "article", "wikipedia", "chrome", "browser", "research", "fact", "score", "result"),
            instructions = "For questions that need facts from the internet, use search_internet and then read_web_page; they work in the background. Open Chrome only when the user wants to see the page.",
            tools = group("web"),
        )

    val social =
        Skill(
            name = "social",
            description = "Post photos and videos to Instagram, Facebook or X, open social profiles, and search YouTube videos.",
            keywords = listOf("instagram", "insta", "facebook", "fb", "twitter", "x", "post", "story", "reel", "profile", "follow", "youtube", "video", "watch", "caption", "social"),
            tools = group("social"),
        )

    val shoppingTravel =
        Skill(
            name = "shopping_travel",
            description = "Shop on Amazon, order food or groceries in apps, book rides with Uber, Ola or Rapido, find places and directions in Maps, and find apps on the Play Store.",
            keywords = listOf("amazon", "buy", "shop", "shopping", "order", "product", "price", "cart", "food", "zomato", "swiggy", "blinkit", "grocery", "groceries", "deliver", "delivery", "uber", "ola", "rapido", "ride", "cab", "taxi", "auto", "navigate", "directions", "map", "route", "near", "nearby", "restaurant", "place", "play store", "install", "app store"),
            instructions = "Shopping and food: amazon_search, or open_app for apps like Zomato, Swiggy or Blinkit. Rides: ride_to. The user chooses, orders and pays in that app.",
            tools = group("shopping_travel"),
        )

    /** Routable skill packs, in priority order for router ties. Add new packs to this list. */
    @Volatile var all: List<Skill> = listOf(communication, files, device, alarmsMedia, web, social, shoppingTravel)

    fun named(name: String): Skill? = if (name == core.name) core else all.firstOrNull { it.name.equals(name.trim(), true) }
}
