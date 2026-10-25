package com.mysi.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Shared skills for delivery, grocery and ride apps. They drive each app's own screens through
 * accessibility, so they work across apps but depend on what each app currently shows. They go
 * as far as the payment page and stop; paying and booking always need the user.
 */
object ShopTools {
    private const val AMAZON_IN = "in.amazon.mShop.android.shopping"

    private val apps =
        mapOf(
            "zomato" to listOf("com.application.zomato"),
            "swiggy" to listOf("in.swiggy.android"),
            "blinkit" to listOf("com.grofers.customerapp"),
            "bigbasket" to listOf("com.bigbasket.mobileapp"),
            "amazon_now" to listOf(AMAZON_IN, "com.amazon.mShop.android.shopping"),
            "uber" to listOf("com.ubercab"),
            "ola" to listOf("com.olacabs.customer"),
            "rapido" to listOf("com.rapido.passenger"),
        )

    private val ADD = Regex("^(add|add\\+|add to cart|add item)$", RegexOption.IGNORE_CASE)
    private val MORE = Regex("^(\\+|increase.*|add one more.*)$", RegexOption.IGNORE_CASE)
    private val CART = Regex("^(view cart|go to cart|cart|checkout|proceed( to checkout)?|continue|next|proceed to buy.*)$", RegexOption.IGNORE_CASE)
    private val PAYMENT = Regex("pay ₹|pay rs|place order|proceed to pay|payment method|select payment|choose payment|pay using|upi|add payment", RegexOption.IGNORE_CASE)
    private val WHERE_TO = Regex("where to|where are you going|enter destination|search destination|drop location|drop", RegexOption.IGNORE_CASE)

    /** Opens the app by package, falling back to a launcher label match (e.g. renamed apps). */
    private suspend fun open(ctx: Context, app: String): String? {
        val pm = ctx.packageManager
        val pkg = apps[app]?.firstOrNull { runCatching { pm.getPackageInfo(it, 0) }.isSuccess }
            ?: pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.ResolveInfoFlags.of(0))
                .firstOrNull { it.loadLabel(pm).toString().replace(" ", "").contains(app.replace("_", ""), true) }
                ?.activityInfo?.packageName
            ?: return null
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return null
        if (Tools.launch(ctx, intent).startsWith("Could not")) return null
        val a11y = AgentAccessibilityService.instance ?: return pkg
        for (i in 0 until 16) {
            if (a11y.packageOnScreen() == pkg) break
            delay(500)
        }
        delay(1500) // splash screens and location prompts settle
        if (app == "amazon_now")
            withContext(Dispatchers.Main) {
                a11y.findNode { Regex("^(amazon now|now)$", RegexOption.IGNORE_CASE).matches(a11y.label(it)) }?.let { a11y.click(it) }
            }.also { delay(2000) }
        return pkg
    }

    private fun a11y() = AgentAccessibilityService.instance

    private suspend fun typeAndSubmit(s: AgentAccessibilityService, text: String): Boolean {
        for (attempt in 0 until 3) {
            if (s.typeText(text) == "Typed.") {
                delay(600)
                withContext(Dispatchers.Main) { s.submit() }
                return true
            }
            withContext(Dispatchers.Main) { s.focusSearch() }
            delay(1000)
        }
        return false
    }

    private fun words(text: String) = text.lowercase().split(' ', ',').filter { it.length > 1 }

    private fun matchesAll(s: AgentAccessibilityService, n: AccessibilityNodeInfo, words: List<String>) =
        s.label(n).lowercase().let { l -> words.isNotEmpty() && words.all { it in l } }

    /** Finds a button matching [pattern] inside the same row/card as [item], climbing a few levels. */
    internal fun buttonNear(s: AgentAccessibilityService, item: AccessibilityNodeInfo, pattern: Regex): AccessibilityNodeInfo? {
        var scope: AccessibilityNodeInfo? = item
        repeat(6) {
            scope = scope?.parent ?: return null
            fun walk(n: AccessibilityNodeInfo?, d: Int): AccessibilityNodeInfo? {
                if (n == null || d > 8) return null
                if (pattern.matches(s.label(n))) return n
                for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)?.let { return it }
                return null
            }
            walk(scope, 0)?.let { return it }
        }
        return null
    }

    fun all(ctx: Context): List<ToolDef> =
        listOf(
            ToolDef(
                "app_search",
                "Search inside a food, grocery or shopping app and return what is on screen (items, prices).",
                listOf(
                    Param("app", "string", "Which app", true, listOf("zomato", "swiggy", "blinkit", "bigbasket", "amazon_now")),
                    Param("query", "string", "Dish, restaurant or product to search for"),
                ),
            ) { a ->
                val s = a11y() ?: return@ToolDef "Screen control is off. Ask the user to enable it."
                open(ctx, a.str("app")) ?: return@ToolDef "${a.str("app")} is not installed."
                if (!withContext(Dispatchers.Main) { s.focusSearch() }) {
                    delay(1500)
                    withContext(Dispatchers.Main) { s.focusSearch() }
                }
                delay(1000)
                if (!typeAndSubmit(s, a.str("query"))) return@ToolDef "Could not find the search box. Screen now:\n" + s.readScreen().take(2000)
                delay(3500)
                "Results in ${a.str("app")} for '${a.str("query")}':\n" + s.readScreen().take(3000) +
                    "\nTo add something, call add_item with words from its name. If results list restaurants, tap_text the restaurant first."
            },
            ToolDef(
                "add_item",
                "Add a dish or product shown on screen to the cart by tapping its ADD button; scrolls to find it.",
                listOf(
                    Param("item", "string", "A few words from the item's name as shown on screen"),
                    Param("quantity", "integer", "How many, default 1", false),
                ),
            ) { a ->
                val s = a11y() ?: return@ToolDef "Screen control is off. Ask the user to enable it."
                val w = words(a.str("item"))
                for (attempt in 0 until 8) {
                    val node = s.findNode { matchesAll(s, it, w) && !it.isEditable }
                    val add = node?.let { buttonNear(s, it, ADD) }
                    if (add != null && withContext(Dispatchers.Main) { s.click(add) }) {
                        delay(1500)
                        // Customisation sheets (size, toppings) end with their own Add button.
                        s.findNode { Regex("^(add item|add to cart|add item to cart).*", RegexOption.IGNORE_CASE).matches(s.label(it)) }
                            ?.let { withContext(Dispatchers.Main) { s.click(it) }; delay(1200) }
                        val extra = a.int("quantity", 1).coerceIn(1, 20) - 1
                        var added = 0
                        repeat(extra) {
                            val again = s.findNode { matchesAll(s, it, w) && !it.isEditable }?.let { buttonNear(s, it, MORE) }
                            if (again != null && withContext(Dispatchers.Main) { s.click(again) }) {
                                added++
                                delay(900)
                            }
                        }
                        return@ToolDef "Added '${s.label(node).take(80)}' × ${added + 1}" +
                            (if (added < extra) " (wanted ${extra + 1}; the + button was not found for the rest)" else "") + "."
                    }
                    if (node != null && add == null && attempt == 0) {
                        // Some apps open a product page first; its Add button is then on screen.
                        withContext(Dispatchers.Main) { s.click(node) }
                        delay(2500)
                        val pageAdd = s.findNode { ADD.matches(s.label(it)) }
                        if (pageAdd != null && withContext(Dispatchers.Main) { s.click(pageAdd) }) {
                            delay(1200)
                            return@ToolDef "Added '${s.label(node).take(80)}' from its page."
                        }
                    }
                    s.scroll("down")
                    delay(900)
                }
                "Could not find '${a.str("item")}' with an ADD button. It may be sold out or named differently; use read_screen."
            },
            ToolDef(
                "go_to_checkout",
                "Open the cart and go through checkout steps until the payment page, then stop. Never pays.",
            ) { _ ->
                val s = a11y() ?: return@ToolDef "Screen control is off. Ask the user to enable it."
                for (step in 0 until 6) {
                    if (s.screenHas(PAYMENT)) {
                        return@ToolDef "Reached the payment page. The user must choose a payment method and pay. Screen:\n" + s.readScreen().take(2000)
                    }
                    val next = s.findNode { CART.matches(s.label(it)) && !Tools.MONEY.containsMatchIn(s.label(it)) }
                        ?: s.findNode { Regex("view cart|go to cart|checkout|proceed", RegexOption.IGNORE_CASE).containsMatchIn(s.label(it)) && !Tools.MONEY.containsMatchIn(s.label(it)) }
                    if (next == null || !withContext(Dispatchers.Main) { s.click(next) }) {
                        return@ToolDef "Stopped: no cart or checkout button found (step ${step + 1}). The app may need an address or a choice; use read_screen. Screen:\n" + s.readScreen().take(1800)
                    }
                    delay(2500)
                }
                "Went through 6 steps without reaching payment. Screen:\n" + s.readScreen().take(2000)
            },
            ToolDef(
                "book_ride",
                "Set a destination in Uber, Ola or Rapido from the current location and return ride options with prices. If ride_type is given, selects it and asks the user before booking.",
                listOf(
                    Param("app", "string", "Ride app", true, listOf("uber", "ola", "rapido")),
                    Param("destination", "string", "Where to go"),
                    Param("ride_type", "string", "Optional ride to choose, e.g. Auto, Bike, Mini, Uber Go", false),
                ),
            ) { a -> bookRide(ctx, a) },
        )

    private suspend fun bookRide(ctx: Context, a: Args): String {
        val s = a11y() ?: return "Screen control is off. Ask the user to enable it."
        val app = a.str("app")
        val dest = a.str("destination")
        if (app == "uber") {
            // Uber's documented universal link pre-fills pickup (current location) and drop-off.
            val link = "https://m.uber.com/ul/?action=setPickup&pickup=my_location&dropoff[formatted_address]=" + Uri.encode(dest)
            Tools.launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage("com.ubercab"))
            delay(5000)
        }
        if (app != "uber" || s.packageOnScreen() != "com.ubercab" || s.screenHas(WHERE_TO)) {
            open(ctx, app) ?: return "$app is not installed."
            val box = s.findNode { WHERE_TO.containsMatchIn(s.label(it)) || WHERE_TO.containsMatchIn(it.hintText?.toString().orEmpty()) }
            if (box == null || !withContext(Dispatchers.Main) { s.click(box) }) return "Could not find the destination box in $app. Screen:\n" + s.readScreen().take(1800)
            delay(1500)
            if (s.typeText(dest) != "Typed.") return "Could not type the destination. Screen:\n" + s.readScreen().take(1800)
            delay(2500)
            // Pick the first suggestion that mentions the destination, but not the text field itself.
            val first = words(dest).first()
            val suggestion = s.findNode { !it.isEditable && s.label(it).lowercase().contains(first) && s.label(it).length > first.length }
            if (suggestion == null || !withContext(Dispatchers.Main) { s.click(suggestion) })
                return "Typed the destination but no matching suggestion appeared. Screen:\n" + s.readScreen().take(1800)
            delay(4500)
        }
        val type = a.str("ride_type")
        if (type.isBlank()) return "Ride options in $app to $dest (not booked):\n" + s.readScreen().take(2500) + "\nAsk the user which ride to book."
        val option = s.findNode { s.label(it).contains(type, true) }
        if (option == null || !withContext(Dispatchers.Main) { s.click(option) })
            return "No '$type' option visible. Options:\n" + s.readScreen().take(2200)
        delay(1500)
        val book = s.findNode { Regex("^(book|confirm|request|choose)\\b.*", RegexOption.IGNORE_CASE).matches(s.label(it)) }
            ?: return "Selected $type but found no Book button. Screen:\n" + s.readScreen().take(1800)
        val price = s.findNode { s.label(it).contains(type, true) && s.label(it).contains("₹") }?.let { s.label(it) }.orEmpty()
        if (!Agent.approve("Book this ride?", "$app · $type to $dest\n${price.take(120)}\n\nThe app will tap \"${s.label(book)}\"."))
            return "The user declined the booking. $type is selected in $app; nothing was booked."
        withContext(Dispatchers.Main) { s.click(book) }
        delay(3000)
        return "Tapped \"${s.label(book)}\" in $app. Screen now:\n" + s.readScreen().take(1800)
    }
}
