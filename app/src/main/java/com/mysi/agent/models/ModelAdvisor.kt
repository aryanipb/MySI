package com.mysi.agent.models

/** What the user mainly wants MySI for (first setup question). */
enum class Use(val label: String) {
    CHAT("Everyday chat and writing"),
    SENSES("Photos and voice too"),
    RESEARCH("Research and long documents"),
}

/** What matters more to the user (second setup question). [BALANCED] is the default when they skip it. */
enum class Priority(val label: String) {
    FASTER("Faster replies"),
    BETTER("Better answers"),
    BALANCED("No preference"),
}

/** One model the user may download, with why it suits (or what to watch for). */
data class Choice(
    val entry: ModelEntry,
    /** Plain-English reason, e.g. "Fits your 8 GB phone with room to spare. Can see photos and hear audio; works with MySI's tools." */
    val reason: String,
    /** Things to know first, e.g. "You're on mobile data. This is a 2.6 GB download." */
    val warnings: List<String>,
    /** True when the advisor would pick it on its own: verified, and the phone meets its minimum. */
    val recommendable: Boolean,
)

/** A model this phone cannot use, with every reason (the first is the main one). */
data class Exclusion(val entry: ModelEntry, val reasons: List<String>) {
    val reason: String get() = reasons.first()
}

data class Advice(
    /** The one model to offer as "Recommended for your phone"; null when none qualifies. */
    val recommended: Choice?,
    /** When [recommended] is null: why, in plain English. */
    val noRecommendation: String?,
    /** Other models tested with MySI that this phone can use, best first. */
    val options: List<Choice>,
    /** Models not tested with MySI that this phone can use. Never recommended; shown only when the user asks. */
    val untested: List<Choice>,
    /** Models this phone cannot use, verified first, with reasons. */
    val excluded: List<Exclusion>,
)

/**
 * Chooses the best model for this phone and what the user wants. Pure and deterministic: the same
 * inputs always give the same advice, so every rule is covered by unit tests.
 *
 * The rules, in order:
 * 1. Hard limits exclude a model, with the reason: a 32-bit phone; an Android Go (low-RAM) phone;
 *    the user wants photos and voice and the model is text only; the model's memory need is more
 *    than this phone can give one app ([Thresholds.APP_MEMORY_SHARE]); or there is not enough free
 *    storage for the file plus room to run ([Thresholds.requiredStorage]).
 * 2. A model is recommendable only if it is verified with MySI and the phone has at least the
 *    model's minimum memory ([ModelEntry.minRamGb], Google's own figure for Gemma 4). Unverified
 *    models are never recommended; they appear only in the untested list the user opens.
 * 3. Among recommendable models: "Faster replies" picks the lightest; "Better answers" (and
 *    research, which gains most from a stronger model) picks the best answers; with no preference
 *    it picks the best model the phone runs with room to spare ([ModelEntry.comfortableRamGb]).
 *    Ties break on lower memory, then on id, so the order is stable.
 */
object ModelAdvisor {

    /** Every threshold the advisor uses, with its source. Estimates are to be tuned on real phones. */
    object Thresholds {
        /**
         * Share of the RAM Android manages that one foreground app can count on. Estimate: Android
         * documents no fixed per-app limit for native memory; the system, the launcher and
         * background services keep roughly the other half, and the low-memory killer starts
         * closing apps before RAM is exhausted. 50% keeps a margin on every phone size.
         */
        const val APP_MEMORY_SHARE = 0.5

        /**
         * Storage that must stay free after the download, at the least. Estimate: twice Android's
         * own low-storage warning cap (AOSP StorageManager: the smaller of 10% of the volume and
         * 500 MB), so the phone keeps working normally.
         */
        const val STORAGE_FLOOR_BYTES = 1_000_000_000L

        /**
         * Room for LiteRT-LM's cache beside the model, as a share of the model's size. Estimate:
         * MySI gives the engine a cache folder; on the processor path it may store a repacked copy
         * of the weights there, whose size Google does not document.
         */
        const val ENGINE_CACHE_SHARE = 0.5

        /** Free storage a download of [sizeBytes] needs: the file plus the larger of the floor and the cache allowance. */
        fun requiredStorage(sizeBytes: Long): Long = sizeBytes + maxOf(STORAGE_FLOOR_BYTES, (sizeBytes * ENGINE_CACHE_SHARE).toLong())

        /** Memory one app can count on for the model on [device]. */
        fun appBudget(device: DeviceProfile): Long = (device.totalRamBytes * APP_MEMORY_SHARE).toLong()
    }

    fun advise(device: DeviceProfile, catalog: Catalog, use: Use = Use.CHAT, priority: Priority = Priority.BALANCED): Advice {
        val excluded = mutableListOf<Exclusion>()
        val usable = mutableListOf<Choice>()
        for (e in catalog.models) {
            val reasons = exclusions(device, e, use)
            if (reasons.isNotEmpty()) excluded += Exclusion(e, reasons)
            else usable += Choice(e, reason(device, e, use), warnings(device, e), recommendable(device, e))
        }
        val order = order(device, effective(use, priority))
        val verified = usable.filter { it.entry.verified && !it.entry.testOnly }
        val recommended = verified.filter { it.recommendable }.sortedWith(compareBy(order) { it.entry }).firstOrNull()
        val options = verified.filter { it !== recommended }
            .sortedWith(compareByDescending<Choice> { it.recommendable }.thenBy(order) { it.entry })
        val untested = usable.filter { !it.entry.verified || it.entry.testOnly }
            .sortedWith(compareBy<Choice> { it.entry.testOnly }.thenByDescending { it.entry.quality }.thenBy { it.entry.id })
        val sortedExcluded = excluded.sortedWith(compareByDescending<Exclusion> { it.entry.verified }.thenByDescending { it.entry.quality }.thenBy { it.entry.id })
        return Advice(
            recommended = recommended,
            noRecommendation = if (recommended != null) null else whyNone(device, options, sortedExcluded),
            options = options,
            untested = untested,
            excluded = sortedExcluded,
        )
    }

    /** Research gains most from a stronger model, so with no stated preference it leans to better answers. */
    internal fun effective(use: Use, priority: Priority): Priority =
        if (priority == Priority.BALANCED && use == Use.RESEARCH) Priority.BETTER else priority

    private fun order(device: DeviceProfile, priority: Priority): Comparator<ModelEntry> {
        val lighter = compareBy<ModelEntry> { it.memoryMb }
        val better = compareByDescending<ModelEntry> { it.quality }
        val byId = compareBy<ModelEntry> { it.id }
        return when (priority) {
            Priority.FASTER -> lighter.then(better).then(byId)
            Priority.BETTER -> better.then(lighter).then(byId)
            Priority.BALANCED -> compareByDescending<ModelEntry> { device.ramGb >= it.comfortableRamGb }.then(better).then(lighter).then(byId)
        }
    }

    internal fun recommendable(device: DeviceProfile, e: ModelEntry) = e.verified && !e.testOnly && device.ramGb >= e.minRamGb

    /** Every hard reason [e] cannot be used on [device]; empty when it can. */
    fun exclusions(device: DeviceProfile, e: ModelEntry, use: Use): List<String> = buildList {
        if (!device.supported64Bit) add("MySI's model engine needs a 64-bit phone.")
        if (device.lowRamDevice) add("This phone runs in low-memory mode, which can't hold a model this size.")
        if (use == Use.SENSES) {
            when {
                !e.abilities.images && !e.abilities.audio -> add("Can't see photos or hear audio, which you asked for.")
                !e.abilities.images -> add("Can't see photos, which you asked for.")
                !e.abilities.audio -> add("Can't hear audio, which you asked for.")
            }
        }
        val budget = Thresholds.appBudget(device)
        if (e.memoryBytes > budget)
            add("Needs about ${Format.memory(e.memoryBytes)} of memory; this ${device.ramGb} GB phone can give an app about ${Format.memory(budget)}.")
        val need = Thresholds.requiredStorage(e.sizeBytes)
        if (device.freeStorageBytes < need)
            add("Needs ${Format.size(need)} free storage (${Format.size(e.sizeBytes)} download plus room to run); you have ${Format.size(device.freeStorageBytes)}.")
    }

    internal fun reason(device: DeviceProfile, e: ModelEntry, use: Use): String {
        val fit = when {
            device.ramGb >= e.comfortableRamGb -> "Fits your ${device.ramGb} GB phone with room to spare."
            device.ramGb >= e.minRamGb -> "Fits your ${device.ramGb} GB phone."
            else -> "Below the ${e.minRamGb} GB of memory suggested for it."
        }
        val senses = if (use == Use.SENSES && e.abilities.images && e.abilities.audio) "Can see photos and hear audio, as you asked" else e.abilitiesText()
        val tools = if (use == Use.SENSES && e.abilities.tools) "; works with MySI's tools" else ""
        return "$fit $senses$tools."
    }

    internal fun warnings(device: DeviceProfile, e: ModelEntry): List<String> = buildList {
        if (!e.verified || e.testOnly) add("Untested with MySI: MySI's tools, photos and audio may not work with it.")
        if (device.ramGb < e.minRamGb)
            add("Suggested minimum is ${e.minRamGb} GB of memory and this phone has ${device.ramGb} GB. It may be slow, or Android may close it.")
        if (device.lowMemoryNow || device.availableRamBytes < e.memoryBytes)
            add("Other apps are using a lot of memory right now. Android may close them while MySI runs.")
        if (!device.online) add("No internet connection right now. The download starts when you're back online.")
        else if (!device.unmetered) add("You're on mobile data. This is a ${Format.size(e.sizeBytes)} download.")
    }

    private fun whyNone(device: DeviceProfile, options: List<Choice>, excluded: List<Exclusion>): String {
        if (options.isNotEmpty())
            return "This phone has ${device.ramGb} GB of memory, less than suggested for the models tested with MySI. " +
                "You can still try one under See other options, but it may be slow or close."
        val main = excluded.filter { it.entry.verified && !it.entry.testOnly }.minByOrNull { it.entry.memoryMb }
            ?: return "None of the models tested with MySI fit this phone."
        return "None of the models tested with MySI fit this phone. ${main.entry.name}: ${main.reason}"
    }
}
