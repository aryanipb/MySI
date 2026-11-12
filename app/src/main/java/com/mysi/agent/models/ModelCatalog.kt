package com.mysi.agent.models

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.mysi.agent.web.WebUrl
import java.io.StringReader

/** What a model can do. Only [tools] says "works with MySI's tools"; it is true only for models tested with them. */
data class Abilities(val text: Boolean, val images: Boolean, val audio: Boolean, val tools: Boolean)

/**
 * One downloadable model, pinned to an exact file: a commit of a Hugging Face repository in
 * Google's `litert-community` organisation, its size and its SHA-256.
 *
 * Per-model figures and where they come from (each entry's [memoryBasis] and [ramBasis] say the
 * same in the catalogue itself, since JSON has no comments):
 * - Gemma 4 E2B and E4B: memory from Google's model cards
 *   (huggingface.co/litert-community/gemma-4-E2B-it-litert-lm and -E4B-it-litert-lm, "Gemma 4 …
 *   Performance on LiteRT-LM", Android table, measured at a 2,048-token context). MySI runs a
 *   16,384-token window with float32 graphics activations ([com.mysi.agent.Agent]), so the
 *   catalogue adds about 50%: that margin is an estimate. Minimum phone memory (8 GB and 12 GB)
 *   is Google's own, from the AI Edge Gallery model list
 *   (github.com/google-ai-edge/gallery, model_allowlists/1_0_23.json, minDeviceMemoryInGb),
 *   compared like Gallery does, against [android.app.ActivityManager.MemoryInfo.advertisedMem].
 *   The generic `.litertlm` file is used because it is the one Google benchmarks on Android; the
 *   `-gpu` file is the card's web build and the chip-named files are for NPUs, which MySI's engine
 *   setup does not use.
 * - Gemma 4 12B: Google's card lists computers only (about 7.3 to 7.9 GB of graphics memory at a
 *   4,096-token context). Every phone figure for it is an estimate, and it is marked unverified.
 * - SmolLM3 3B, Qwen3 1.7B and Qwen3 0.6B: memory from their community model cards in the same
 *   organisation, plus a margin (estimate); phone memory minimums are estimates. Qwen3 0.6B is
 *   pinned to revision e6bb3e4a because the card says the newer file crashes the graphics path of
 *   LiteRT-LM 0.18.0/0.18.1 on Android (LiteRT-LM#3986), and MySI tries the graphics chip first.
 *   Qwen3 4B is left out for the same reason (its 0.18-compatible file has a 2,048-token context).
 * - [quality] is an editorial ordering by model size and family, not a measured score.
 */
data class ModelEntry(
    val id: String,
    val name: String,
    val publisher: String,
    val licence: String,
    val repo: String,
    val revision: String,
    val file: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val abilities: Abilities,
    /** Working memory MySI needs with this model loaded, in binary MB (MiB). */
    val memoryMb: Int,
    val memoryEstimated: Boolean,
    val memoryBasis: String,
    /** Smallest phone (advertised memory, GB) it should run on. */
    val minRamGb: Int,
    /** Phone size (GB) at which it runs with room to spare. */
    val comfortableRamGb: Int,
    val ramBasis: String,
    /** 1 to 100, higher is better answers. Editorial, see above. */
    val quality: Int,
    /** Tested with MySI's tools, photos and audio. Only verified models are ever recommended. */
    val verified: Boolean,
    val notes: String,
    /** A download-path test entry from the debug build's own catalogue; never in release. */
    val testOnly: Boolean = false,
) {
    val memoryBytes: Long get() = memoryMb * Format.MIB
    val textOnly: Boolean get() = !abilities.images && !abilities.audio

    /** "Gemma 4 E2B · 2.6 GB". */
    val label: String get() = "$name · ${Format.size(sizeBytes)}"

    /** "Can see photos and hear audio", "Text only". */
    fun abilitiesText(): String {
        val parts = buildList {
            if (abilities.images) add("see photos")
            if (abilities.audio) add("hear audio")
        }
        val senses = when (parts.size) {
            0 -> "Text only"
            1 -> "Can ${parts[0]}"
            else -> "Can ${parts[0]} and ${parts[1]}"
        }
        return senses + if (abilities.tools) "; works with MySI's tools" else ""
    }

    /** "Apache 2.0". */
    val licenceText: String get() = licence.replace('-', ' ')
}

/** A whole catalogue: [version] increases with every published change, so a newer copy wins. */
data class Catalog(val schema: Int, val version: Int, val updated: String, val models: List<ModelEntry>) {
    fun byId(id: String): ModelEntry? = models.firstOrNull { it.id == id }
}

/**
 * Strict reader for `models.json` (the copy built into the app, the copy on GitHub, and the debug
 * build's test entry). Anything unexpected rejects the whole catalogue, and the caller falls back to
 * the built-in one: unknown keys, wrong types, numbers out of range, a file anywhere but
 * `https://huggingface.co/litert-community/…/resolve/<40-hex commit>/<file>.litertlm`, a SHA-256
 * that is not 64 hex characters, or a licence outside [LICENCES]. So a remote catalogue can never
 * make the app download from another host or skip the hash check.
 */
object CatalogParser {
    const val SCHEMA = 1

    /** Bigger than any honest catalogue; a remote body over this is refused unread. */
    const val MAX_BYTES = 256 * 1024
    const val MAX_MODELS = 40
    const val HOST = "huggingface.co"
    const val ORG = "litert-community"
    val LICENCES = setOf("Apache-2.0", "MIT")

    /** Sane sizes: no real model is smaller than 50 MB, and no phone could hold one over 20 GB. */
    const val MIN_SIZE = 50_000_000L
    const val MAX_SIZE = 20_000_000_000L

    private val ID = Regex("^[a-z0-9][a-z0-9.-]{1,47}$")
    private val REPO = Regex("^$ORG/[A-Za-z0-9][A-Za-z0-9._-]{0,95}$")
    private val REVISION = Regex("^[0-9a-f]{40}$")
    private val FILE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.litertlm$")
    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    private val TOP_KEYS = setOf("schema", "version", "updated", "models")
    private val ENTRY_KEYS = setOf(
        "id", "name", "publisher", "licence", "repo", "revision", "file", "url", "sizeBytes", "sha256", "abilities",
        "memoryMb", "memoryEstimated", "memoryBasis", "minRamGb", "comfortableRamGb", "ramBasis", "quality", "verified", "notes", "testOnly",
    )
    private val ABILITY_KEYS = setOf("text", "images", "audio", "tools")

    sealed class Result {
        data class Valid(val catalog: Catalog) : Result()
        data class Invalid(val problem: String) : Result()
    }

    private class Bad(message: String) : Exception(message)

    /** The download address for a pinned file. */
    fun url(repo: String, revision: String, file: String) = "https://$HOST/$repo/resolve/$revision/$file"

    /**
     * Parses and validates [json]. [allowTestOnly] admits `testOnly` entries (the debug build's own
     * file only); [requireVerified] demands at least one verified model, which every catalogue
     * that can stand on its own must have.
     */
    fun parse(json: String, allowTestOnly: Boolean = false, requireVerified: Boolean = true): Result =
        try {
            if (json.length > MAX_BYTES) throw Bad("The catalogue is too large.")
            Result.Valid(read(strictTree(json), allowTestOnly, requireVerified))
        } catch (e: Bad) {
            Result.Invalid(e.message ?: "Invalid catalogue.")
        } catch (e: Exception) {
            // Malformed JSON, nesting too deep, numbers that do not fit…
            Result.Invalid("The catalogue is not valid JSON.")
        }

    /** Strict JSON (no comments, single quotes, unquoted names or trailing data). */
    private fun strictTree(json: String): JsonElement {
        val reader = JsonReader(StringReader(json)).apply { strictness = Strictness.STRICT }
        val tree = Gson().getAdapter(JsonElement::class.java).read(reader)
        if (reader.peek() != JsonToken.END_DOCUMENT) throw Bad("The catalogue has data after its end.")
        return tree
    }

    private fun read(tree: JsonElement, allowTestOnly: Boolean, requireVerified: Boolean): Catalog {
        val top = tree as? JsonObject ?: throw Bad("The catalogue is not a JSON object.")
        keys(top, TOP_KEYS, "catalogue")
        val schema = int(top, "schema", 1, 1_000)
        if (schema != SCHEMA) throw Bad("Catalogue schema $schema is not supported (this app reads schema $SCHEMA).")
        val version = int(top, "version", 1, 1_000_000)
        val updated = string(top, "updated", 10)
        if (!DATE.matches(updated)) throw Bad("updated must be a YYYY-MM-DD date.")
        val list = top.get("models")?.takeIf { it.isJsonArray }?.asJsonArray ?: throw Bad("models must be a list.")
        if (list.size() == 0) throw Bad("The catalogue lists no models.")
        if (list.size() > MAX_MODELS) throw Bad("The catalogue lists too many models.")
        val models = list.mapIndexed { i, e -> entry(e as? JsonObject ?: throw Bad("Model ${i + 1} is not an object."), i + 1, allowTestOnly) }
        models.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { throw Bad("Model id '$it' appears twice.") }
        if (requireVerified && models.none { it.verified && !it.testOnly }) throw Bad("The catalogue has no verified model.")
        return Catalog(schema, version, updated, models)
    }

    private fun entry(o: JsonObject, n: Int, allowTestOnly: Boolean): ModelEntry {
        keys(o, ENTRY_KEYS, "model $n")
        val id = string(o, "id", 48)
        if (!ID.matches(id)) throw Bad("Model $n has an invalid id.")
        fun bad(what: String): Nothing = throw Bad("Model '$id': $what")
        val repo = string(o, "repo", 112)
        if (!REPO.matches(repo) || ".." in repo) bad("repo must be in huggingface.co/$ORG.")
        val revision = string(o, "revision", 40)
        if (!REVISION.matches(revision)) bad("revision must be a 40-character commit id.")
        val file = string(o, "file", 140)
        if (!FILE.matches(file) || ".." in file) bad("file must be a plain .litertlm file name.")
        val url = string(o, "url", 400)
        if (url != url(repo, revision, file)) bad("url must be the pinned huggingface.co address for its repo, revision and file.")
        // Belt and braces: the same address through the app's strict URL parser.
        val parsed = WebUrl.parse(url) ?: bad("url is not a valid address.")
        if (!parsed.https || parsed.host != HOST || parsed.explicitPort != -1 || parsed.rawQuery != null || parsed.rawFragment != null)
            bad("url must be https on $HOST.")
        val size = long(o, "sizeBytes", MIN_SIZE, MAX_SIZE, id)
        val sha = string(o, "sha256", 64)
        if (!SHA256.matches(sha)) bad("sha256 must be 64 hexadecimal characters.")
        val licence = string(o, "licence", 20)
        if (licence !in LICENCES) bad("licence must be one of ${LICENCES.joinToString()}.")
        val a = o.get("abilities") as? JsonObject ?: bad("abilities must be an object.")
        keys(a, ABILITY_KEYS, "model '$id' abilities")
        val abilities = Abilities(bool(a, "text"), bool(a, "images"), bool(a, "audio"), bool(a, "tools"))
        if (!abilities.text) bad("every model must handle text.")
        val minRam = int(o, "minRamGb", 1, 64, id)
        val comfortable = int(o, "comfortableRamGb", 1, 64, id)
        if (comfortable < minRam) bad("comfortableRamGb is below minRamGb.")
        val verified = bool(o, "verified")
        val testOnly = if (o.has("testOnly")) bool(o, "testOnly") else false
        if (testOnly && !allowTestOnly) bad("test entries are not allowed here.")
        if (testOnly && verified) bad("a test entry cannot be verified.")
        if (verified && !abilities.tools) bad("a verified model must work with MySI's tools.")
        if (!verified && abilities.tools) bad("only verified models can claim to work with MySI's tools.")
        return ModelEntry(
            id = id,
            name = text(o, "name", 60, id),
            publisher = text(o, "publisher", 40, id),
            licence = licence,
            repo = repo,
            revision = revision,
            file = file,
            url = url,
            sizeBytes = size,
            sha256 = sha.lowercase(),
            abilities = abilities,
            memoryMb = int(o, "memoryMb", 100, 65_536, id),
            memoryEstimated = bool(o, "memoryEstimated"),
            memoryBasis = text(o, "memoryBasis", 400, id),
            minRamGb = minRam,
            comfortableRamGb = comfortable,
            ramBasis = text(o, "ramBasis", 400, id),
            quality = int(o, "quality", 1, 100, id),
            verified = verified,
            notes = text(o, "notes", 400, id, allowEmpty = true),
            testOnly = testOnly,
        )
    }

    private fun keys(o: JsonObject, allowed: Set<String>, where: String) {
        o.keySet().firstOrNull { it !in allowed }?.let { throw Bad("Unknown field '${it.take(40)}' in $where.") }
    }

    private fun primitive(o: JsonObject, key: String): JsonPrimitive =
        o.get(key) as? JsonPrimitive ?: throw Bad("'$key' is missing or not a single value.")

    private fun string(o: JsonObject, key: String, max: Int): String {
        val p = primitive(o, key)
        if (!p.isString) throw Bad("'$key' must be text.")
        val s = p.asString
        if (s.length > max) throw Bad("'$key' is too long.")
        return s
    }

    /** Display text: no control or bidirectional-override characters, trimmed, length-limited. */
    private fun text(o: JsonObject, key: String, max: Int, id: String, allowEmpty: Boolean = false): String {
        val s = string(o, key, max)
        if (!allowEmpty && s.isBlank()) throw Bad("Model '$id': '$key' is empty.")
        if (s.any { it.isISOControl() || it in '‪'..'‮' || it in '⁦'..'⁩' || it == '‎' || it == '‏' })
            throw Bad("Model '$id': '$key' contains control characters.")
        return s.trim()
    }

    private fun bool(o: JsonObject, key: String): Boolean {
        val p = primitive(o, key)
        if (!p.isBoolean) throw Bad("'$key' must be true or false.")
        return p.asBoolean
    }

    private fun long(o: JsonObject, key: String, min: Long, max: Long, id: String = ""): Long {
        val p = primitive(o, key)
        if (!p.isNumber) throw Bad("'$key' must be a number.")
        val s = p.asString
        // Whole numbers only: no fractions, exponents or signs.
        if (!Regex("^\\d{1,19}$").matches(s)) throw Bad("'$key' must be a whole number.")
        val v = s.toLongOrNull() ?: throw Bad("'$key' is out of range.")
        if (v !in min..max) throw Bad(if (id.isEmpty()) "'$key' is out of range." else "Model '$id': '$key' is out of range.")
        return v
    }

    private fun int(o: JsonObject, key: String, min: Int, max: Int, id: String = ""): Int = long(o, key, min.toLong(), max.toLong(), id).toInt()
}

/**
 * Which catalogue to use and when to refresh it. Pure, so the rules are tested on the JVM; the
 * Android side ([CatalogRepository]) only reads and writes files.
 */
object CatalogPolicy {
    /** A successful fetch is reused for a day. */
    const val FRESH_MS = 24 * 60 * 60 * 1000L

    /** After a failed fetch (offline, 404…), try again at most hourly. */
    const val RETRY_MS = 60 * 60 * 1000L

    /** The fetched copy wins only when it is at least as new as the one built into this app version. */
    fun choose(builtIn: Catalog, remote: Catalog?): Catalog = if (remote != null && remote.version >= builtIn.version) remote else builtIn

    fun shouldFetch(now: Long, lastSuccess: Long, lastAttempt: Long): Boolean {
        if (now < lastAttempt || now < lastSuccess) return true // clock moved backwards
        if (now - lastSuccess < FRESH_MS) return false
        return now - lastAttempt >= RETRY_MS
    }

    /** Adds the debug build's test entries (if any) after the real ones; a clashing id is dropped. */
    fun withTestEntries(catalog: Catalog, extra: List<ModelEntry>): Catalog =
        catalog.copy(models = catalog.models + extra.filter { e -> e.testOnly && catalog.models.none { it.id == e.id } })
}
