package com.mysi.agent.models

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import com.google.gson.Gson
import com.mysi.agent.Agent
import com.mysi.agent.Tokens
import com.mysi.agent.complete
import java.util.Locale

/** One short speed test of the loaded model, kept on the phone only. */
data class FitMeasurement(
    val modelId: String,
    /** Time until a reply starts: a one-token reply, so prompt reading plus the first token (approximate). */
    val firstReplyMs: Long,
    /** Reply speed in tokens per second (approximate, see [FitCheckRunner]). */
    val tokensPerSecond: Double,
    val availableRamBytes: Long,
    val totalRamBytes: Long,
    val lowMemory: Boolean,
    val measuredAt: Long,
)

/** What the speed test means for the user. The app never switches model on its own: [suggest] is only offered. */
sealed class FitVerdict {
    abstract val message: String

    data class Good(override val message: String) : FitVerdict()

    /** Replies are too slow; [suggest] is a smaller tested model, if any. */
    data class TooSlow(override val message: String, val suggest: ModelEntry?) : FitVerdict()

    /** Memory is tight; [suggest] is a smaller tested model, if any. */
    data class Tight(override val message: String, val suggest: ModelEntry?) : FitVerdict()

    /** Fast with memory to spare; [suggest] is a larger tested model the phone can run. */
    data class Roomy(override val message: String, val suggest: ModelEntry) : FitVerdict()
}

/**
 * Decides what a speed test means. Pure, so the decisions are unit-tested. Every threshold is an
 * estimate to tune on real phones: no source documents what feels "too slow" for MySI.
 */
object FitCheck {
    object Thresholds {
        /** Below this, replies come slower than most people read (about 3 words a second). Estimate. */
        const val SLOW_TOKENS_PER_SECOND = 4.0

        /** A reply that takes longer than this to start feels broken. Estimate. */
        const val SLOW_FIRST_REPLY_MS = 10_000L

        /** At or above this, a larger model would still reply at a comfortable speed. Estimate. */
        const val ROOMY_TOKENS_PER_SECOND = 15.0

        /** Free memory below this, after loading, means Android may soon close MySI. Estimate. */
        const val TIGHT_FREE_BYTES = 600L * 1024 * 1024

        /** Free memory a larger model must leave over its extra need before it is suggested. Estimate. */
        const val UPGRADE_SPARE_BYTES = 1024L * 1024 * 1024

        /** Rough English words per token, to describe speed in words. Estimate. */
        const val WORDS_PER_TOKEN = 0.75
    }

    fun decide(m: FitMeasurement, current: ModelEntry, catalog: Catalog, device: DeviceProfile): FitVerdict {
        val speed = describe(m)
        val usable = catalog.models.filter { it.verified && !it.testOnly && it.id != current.id && ModelAdvisor.exclusions(device, it, Use.CHAT).isEmpty() }
        val smaller = usable.filter { it.memoryMb < current.memoryMb }.maxWithOrNull(compareBy<ModelEntry> { it.quality }.thenBy { it.id })
        val tight = m.lowMemory || m.availableRamBytes < Thresholds.TIGHT_FREE_BYTES
        val slow = m.tokensPerSecond < Thresholds.SLOW_TOKENS_PER_SECOND || m.firstReplyMs > Thresholds.SLOW_FIRST_REPLY_MS
        if (tight) {
            val tail = smaller?.let { " ${it.name} is smaller and leaves more room." } ?: " Close other apps if MySI stops."
            return FitVerdict.Tight("This phone is short of memory with ${current.name} loaded.$tail", smaller)
        }
        if (slow) {
            val tail = smaller?.let { " ${it.name} is smaller and faster." } ?: " It is the smallest tested model, so replies will take a while."
            return FitVerdict.TooSlow("Replies are slow on this phone ($speed).$tail", smaller)
        }
        if (m.tokensPerSecond >= Thresholds.ROOMY_TOKENS_PER_SECOND) {
            val larger = usable.filter {
                it.quality > current.quality && device.ramGb >= it.minRamGb &&
                    m.availableRamBytes >= (it.memoryBytes - current.memoryBytes) + Thresholds.UPGRADE_SPARE_BYTES
            }.minWithOrNull(compareBy<ModelEntry> { it.memoryMb }.thenBy { it.id })
            if (larger != null)
                return FitVerdict.Roomy("Runs well ($speed) with memory to spare. ${larger.name} gives better answers if you'd like to try it (${Format.size(larger.sizeBytes)} download).", larger)
        }
        return FitVerdict.Good("Runs well on this phone ($speed).")
    }

    /** "replies start in about 1.2 s and come at about 9 words a second". */
    fun describe(m: FitMeasurement): String {
        val words = (m.tokensPerSecond * Thresholds.WORDS_PER_TOKEN).let {
            if (it >= 10) String.format(Locale.US, "%.0f", it) else String.format(Locale.US, "%.1f", it).removeSuffix(".0")
        }
        return "replies start in about ${Format.seconds(m.firstReplyMs)} and come at about $words words a second"
    }
}

/**
 * Runs the speed test through the normal pipeline API ([com.mysi.agent.complete]), which returns
 * only the finished text, so both figures are approximations:
 * - Time to first reply: a call capped at one output token, so it measures creating a
 *   conversation, reading the prompt and producing one token.
 * - Speed: a second call capped at [OUTPUT_TOKENS]. The prompt asks for far more than that, so
 *   the reply stops at the cap; tokens per second is (cap - 1) over the time beyond the first
 *   call's. When the reply is clearly shorter, the app's own token estimate is used instead.
 * Results are kept in this app's private preferences and never sent anywhere.
 */
object FitCheckRunner {
    const val OUTPUT_TOKENS = 32
    private const val SYSTEM = "You are a counting machine. Reply with the numbers only."
    private const val PROMPT = "Count from 1 to 100, separated by commas."

    suspend fun measure(context: Context, model: ModelEntry): FitMeasurement {
        val t0 = SystemClock.elapsedRealtime()
        Agent.complete(SYSTEM, PROMPT, maxOutputTokens = 1)
        val first = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1)
        val t1 = SystemClock.elapsedRealtime()
        val text = Agent.complete(SYSTEM, PROMPT, maxOutputTokens = OUTPUT_TOKENS)
        val total = (SystemClock.elapsedRealtime() - t1).coerceAtLeast(1)
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return FitMeasurement(model.id, first, tokensPerSecond(text, total, first), mem.availMem, mem.totalMem, mem.lowMemory, System.currentTimeMillis())
    }

    /** Tokens per second for a capped reply [text] that took [totalMs], given [firstMs] for the first token. */
    internal fun tokensPerSecond(text: String, totalMs: Long, firstMs: Long): Double {
        // "1, 2, … 16," is about 60 characters for 32 tokens; far shorter means the model stopped early.
        val tokens = if (text.trim().length >= 40) OUTPUT_TOKENS else Tokens.estimate(text).coerceIn(1, OUTPUT_TOKENS)
        val decodeMs = (totalMs - firstMs).coerceAtLeast(1)
        return (tokens - 1).coerceAtLeast(1) * 1000.0 / decodeMs
    }

    fun save(context: Context, m: FitMeasurement) {
        context.getSharedPreferences("model_fit", Context.MODE_PRIVATE).edit().putString(m.modelId, Gson().toJson(m)).apply()
    }
}
