package com.mysi.agent.senses

/** Where a part of the model runs. */
enum class Accel { GPU, CPU }

/**
 * What a model file says it can take, read from its metadata before loading. [visionAccels] and
 * [audioAccels] are the backends the file lists for those encoders; empty means "not listed", so
 * every backend is tried.
 */
data class Modalities(
    val vision: Boolean,
    val audio: Boolean,
    val visionAccels: Set<Accel> = emptySet(),
    val audioAccels: Set<Accel> = emptySet(),
)

/** One way to start the engine: the text model's backend, and the vision and audio encoders' (null = off). */
data class EnginePlan(val text: Accel, val vision: Accel?, val audio: Accel?) {
    val canSee get() = vision != null
    val canHear get() = audio != null

    /** For the status line, e.g. "GPU · vision GPU · audio CPU" or "CPU · text only". */
    fun describe(): String =
        listOfNotNull(text.name, vision?.let { "vision ${it.name}" }, audio?.let { "audio ${it.name}" })
            .let { if (it.size == 1) "${it[0]} · text only" else it.joinToString(" · ") }
}

/** Reads a model file's modalities; null when the file cannot tell (then every option is tried). */
fun interface ModalityProbe {
    fun probe(modelPath: String): Modalities?
}

/**
 * The order in which the engine is started, and the fallback when a part fails. A model that
 * cannot see or hear must still load as text-only, so every plan for a backend ends with that.
 */
object LoadPlanner {
    /** Text backends, best first. */
    val TEXT = listOf(Accel.GPU, Accel.CPU)

    /**
     * Every plan to try, best first: for each text backend, the full set of senses the model
     * supports (vision on the GPU before the CPU, audio on the CPU as Google's samples do), then
     * dropping one sense, then text only. [supported] null means unknown: both senses are tried.
     */
    fun plans(supported: Modalities?): List<EnginePlan> {
        val wantVision = supported?.vision ?: true
        val wantAudio = supported?.audio ?: true
        val visionOptions = if (!wantVision) emptyList() else order(listOf(Accel.GPU, Accel.CPU), supported?.visionAccels.orEmpty())
        // Audio runs on the CPU (as in Google's samples) unless the model lists only the GPU for it.
        val audioOptions = if (!wantAudio) emptyList() else order(listOf(Accel.CPU), supported?.audioAccels.orEmpty(), fallback = listOf(Accel.GPU))
        val out = LinkedHashSet<EnginePlan>()
        for (t in TEXT) {
            val v: List<Accel?> = visionOptions + listOf(null)
            val a: List<Accel?> = audioOptions + listOf(null)
            // Both senses first, then audio without vision, then vision without audio, then neither.
            for (va in v.filterNotNull()) for (aa in a.filterNotNull()) out += EnginePlan(t, va, aa)
            for (aa in a.filterNotNull()) out += EnginePlan(t, null, aa)
            for (va in v.filterNotNull()) out += EnginePlan(t, va, null)
            out += EnginePlan(t, null, null)
        }
        return out.toList()
    }

    /**
     * [preferred] filtered to [listed] when the model lists any; when it lists none of them, the
     * listed ones from [fallback], else [preferred] unchanged.
     */
    private fun order(preferred: List<Accel>, listed: Set<Accel>, fallback: List<Accel> = emptyList()): List<Accel> =
        if (listed.isEmpty()) preferred
        else preferred.filter { it in listed }.ifEmpty { fallback.filter { it in listed } }.ifEmpty { preferred }

    /**
     * Starts the first plan that works. [start] builds and initialises an engine and throws on
     * failure (it must release anything it created first); [failed] hears about each failure.
     * After the text model fails on a backend in its text-only plan, the remaining plans for that
     * backend are already behind it, so the next backend's plans follow naturally.
     */
    fun <E> firstWorking(plans: List<EnginePlan>, start: (EnginePlan) -> E, failed: (EnginePlan, Throwable) -> Unit = { _, _ -> }): Pair<EnginePlan, E>? {
        val brokenText = mutableSetOf<Accel>()
        for (p in plans) {
            if (p.text in brokenText) continue
            try {
                return p to start(p)
            } catch (t: Throwable) {
                if (t is VirtualMachineError && t !is OutOfMemoryError) throw t
                failed(p, t)
                if (p.vision == null && p.audio == null) brokenText += p.text
            }
        }
        return null
    }
}
