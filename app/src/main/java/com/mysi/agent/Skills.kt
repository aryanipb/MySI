package com.mysi.agent

import android.content.Context
import com.mysi.agent.senses.HearingSkill
import com.mysi.agent.senses.VisionSkill
import com.mysi.agent.skills.DataSkill
import com.mysi.agent.skills.DocumentsSkill
import com.mysi.agent.skills.KnowledgeSkill
import com.mysi.agent.skills.MemorySkill
import com.mysi.agent.skills.PlannerSkill
import com.mysi.agent.web.WebSkill

/**
 * Every skill MySI has. [core] is always loaded; the router picks from [all] for each request and
 * the model can load more with use_skill. Adding a skill pack is one line in [all].
 *
 * Every tool name belongs to exactly one skill: the runtime finds a tool by name, so a second
 * tool with the same name would never run. [problems] finds such clashes; a unit test and the
 * runtime (see Agent) both check the real registry with it.
 */
object Skills {
    /** The built-in tools (Tools.kt, AppTools.kt) of each skill, by tool name. A unit test checks every built-in tool is listed exactly once. */
    internal val builtinGroups: Map<String, List<String>> =
        linkedMapOf(
            // pick_files and list_files are the only way to reach files, and the documents, knowledge
            // base, data and sharing tools all need their paths, so they are always loaded.
            "core" to listOf("get_datetime", "open_app", "list_apps", "clipboard_read", "clipboard_write", "pick_files", "list_files"),
            "communication" to listOf("whatsapp_message", "whatsapp_files", "compose_sms", "dial_number", "search_contacts", "compose_email", "share_to_app"),
            "files" to listOf("take_photo", "read_text_file", "write_text_file"),
            // The documents pack's own tools, plus the built-in PDF writer.
            "documents" to listOf("create_pdf"),
            "browser" to listOf("open_in_chrome", "google_app_search"),
            "device" to listOf("device_status", "read_sensors", "get_location", "flashlight", "vibrate", "open_settings"),
            "alarms_media" to listOf("set_alarm", "set_timer", "set_volume", "media_control", "speak"),
            "social" to listOf("post_to_social", "open_profile", "youtube_search"),
            "shopping_travel" to listOf("amazon_search", "maps", "ride_to", "play_store_search"),
        )

    /** The names of the runtime's own tools in [core]. */
    internal val metaToolNames = listOf("use_skill", "read_result")

    @Volatile private var builtinCache: Map<String, ToolDef>? = null

    private fun builtin(ctx: Context): Map<String, ToolDef> =
        builtinCache ?: (Tools.all(ctx) { Agent.host } + AppTools.all(ctx)).associateBy { it.name }.also { builtinCache = it }

    private fun group(name: String): (Context) -> List<ToolDef> = { ctx ->
        val tools = builtin(ctx)
        builtinGroups.getValue(name).map { tools[it] ?: error("Skill '$name' lists unknown tool '$it'.") }
    }

    /** use_skill and read_result. Building them does not touch [Agent]; running them does. */
    private fun metaTools(): List<ToolDef> =
        listOf(
            ToolDef(
                "use_skill",
                "Load another skill's tools when the request needs a tool that is not loaded. The skills are listed in your instructions.",
                listOf(Param("name", "string", "Skill name, e.g. web or documents")),
            ) { a -> Agent.useSkill(a.str("name")) },
            ToolDef(
                "read_result",
                "Read more of a long tool result that was cut short, using the id and offset from its [result id=…] note.",
                listOf(
                    Param("id", "integer", "Result id"),
                    Param("offset", "integer", "Character offset to read from, default 0", false),
                ),
            ) { a -> Agent.readResult(a.int("id", -1), a.int("offset", 0)) },
        ).also { tools -> check(tools.map { it.name } == metaToolNames) }

    val core =
        Skill(
            name = "core",
            description = "Date and time, opening installed apps, the clipboard, picking the user's files, listing MySI's own files, loading other skills and reading long tool results.",
            tools = { ctx -> group("core")(ctx) + metaTools() },
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
            description = "Take photos with the camera, and read or write plain text files in MySI's workspace.",
            // Words about looking at a picture belong to the vision skill; these are about taking one.
            keywords = listOf("selfie", "camera", "snap", "click", "txt", "text file", "save", "write", "folder", "workspace"),
            instructions = "Files: take_photo returns the photo's path for the sharing tools. write_text_file saves plain text; for Word, Excel, PDF or other documents use the documents skill.",
            tools = group("files"),
        )

    /** The documents pack plus the built-in create_pdf, so reading and writing every document type is one skill. */
    val documents =
        DocumentsSkill.skill.let { pack ->
            Skill(pack.name, pack.description, pack.keywords, pack.instructions) { ctx -> pack.tools(ctx) + group("documents")(ctx) }
        }

    val browser =
        Skill(
            name = "browser",
            description = "Show a web page or a Google search on screen in Chrome or the Google app, for the user to look at.",
            keywords = listOf("chrome", "browser", "show me the page", "open the site", "open website", "google app", "on screen", "tab"),
            instructions = "Browser: use open_in_chrome or google_app_search only when the user wants to see a page themselves; to find or read facts use the web skill, which works in the background.",
            tools = group("browser"),
        )

    val device =
        Skill(
            name = "device",
            description = "Phone status and hardware: battery, network, storage, location, sensors, flashlight, vibration and settings screens such as Wi-Fi or Bluetooth.",
            keywords = listOf("battery", "charge", "charging", "wifi", "bluetooth", "network", "internet connection", "storage", "space", "ram", "location", "gps", "where am", "sensor", "compass", "light", "flashlight", "torch", "vibrate", "brightness", "settings", "hotspot", "airplane", "mobile data", "nfc", "android version", "model"),
            tools = group("device"),
        )

    val alarmsMedia =
        Skill(
            name = "alarms_media",
            description = "Alarms, timers, volume, music and video playback controls, and speaking text aloud.",
            keywords = listOf("alarm", "wake", "timer", "countdown", "remind", "reminder", "volume", "loud", "quiet", "mute", "music", "song", "play", "pause", "resume", "next", "skip", "previous", "track", "speak", "aloud", "say", "voice"),
            tools = group("alarms_media"),
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
    @Volatile var all: List<Skill> =
        listOf(
            communication, files, documents, KnowledgeSkill.skill, DataSkill.skill, WebSkill.skill, browser,
            MemorySkill.skill, PlannerSkill.skill, VisionSkill.skill, HearingSkill.skill, device, alarmsMedia, social, shoppingTravel,
        )

    fun named(name: String): Skill? = if (name == core.name) core else all.firstOrNull { it.name.equals(name.trim(), true) }

    /**
     * What is wrong with a registry given as (skill name, its tool names) pairs: skill names used
     * twice (case-insensitively, as [named] matches them) and tool names that appear more than
     * once. Empty when the registry is sound.
     */
    fun problems(registry: List<Pair<String, List<String>>>): List<String> {
        val out = mutableListOf<String>()
        registry.groupBy { it.first.lowercase() }.filter { it.value.size > 1 }.forEach { (name, _) -> out += "skill name '$name' is used more than once" }
        val owners = LinkedHashMap<String, MutableList<String>>()
        for ((skill, tools) in registry) for (t in tools) owners.getOrPut(t) { mutableListOf() } += skill
        owners.filter { it.value.size > 1 }.forEach { (tool, skills) -> out += "tool '$tool' is in ${skills.joinToString(" and ")}" }
        return out
    }
}
