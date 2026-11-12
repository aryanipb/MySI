package com.mysi.agent.models

import android.content.Context
import com.mysi.agent.web.HttpClient
import com.mysi.agent.web.HttpRequest
import com.mysi.agent.web.WebSkill
import com.mysi.agent.web.WebUrl
import java.io.File
import java.io.IOException

/**
 * Fetches the published catalogue through the app's one HTTP client ([WebSkill.http]), so the same
 * rules apply as for every other request: public addresses only, a fixed honest User-Agent, no
 * cookies or anything about the user. The answer must come from [HOST] over https, even after
 * redirects, and must pass [CatalogParser].
 */
object CatalogFetcher {
    const val URL = "https://raw.githubusercontent.com/aryanipb/MySI/main/models.json"
    const val HOST = "raw.githubusercontent.com"

    sealed class Outcome {
        data class Fetched(val json: String, val catalog: Catalog) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    /** Blocking; call off the main thread. Never throws. */
    fun fetch(client: HttpClient): Outcome =
        try {
            val url = WebUrl.parse(URL) ?: return Outcome.Failed("Bad catalogue address.")
            client.open(HttpRequest(url, accept = HttpRequest.ACCEPT_ANY)).use { r ->
                when {
                    !r.url.https || r.url.host != HOST -> Outcome.Failed("The catalogue was redirected away from $HOST.")
                    r.status != 200 -> Outcome.Failed("The catalogue server answered ${r.status}.")
                    r.contentLength > CatalogParser.MAX_BYTES -> Outcome.Failed("The catalogue is too large.")
                    else -> {
                        val (bytes, truncated) = client.readBody(r, CatalogParser.MAX_BYTES)
                        if (truncated) Outcome.Failed("The catalogue is too large.")
                        else {
                            val json = String(bytes, Charsets.UTF_8)
                            when (val p = CatalogParser.parse(json)) {
                                is CatalogParser.Result.Valid -> Outcome.Fetched(json, p.catalog)
                                is CatalogParser.Result.Invalid -> Outcome.Failed(p.problem)
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Outcome.Failed(e.message ?: "Could not reach the catalogue.")
        } catch (e: RuntimeException) {
            Outcome.Failed(e.message ?: "Could not read the catalogue.")
        }
}

/**
 * The catalogue the screen shows: the built-in copy (assets/models.json, identical to models.json
 * at the repository root), replaced by a fetched copy only when that one is valid and at least as
 * new ([CatalogPolicy.choose]). Debug builds add the test entry from assets/models-debug.json,
 * which release builds do not contain.
 */
class CatalogRepository(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val app = context.applicationContext
    private val cacheFile = File(app.filesDir, "model-catalog.json")
    private val prefs = app.getSharedPreferences("model_catalog", Context.MODE_PRIVATE)

    val builtIn: Catalog by lazy {
        val json = app.assets.open(BUILT_IN).use { String(it.readBytes(), Charsets.UTF_8) }
        when (val p = CatalogParser.parse(json)) {
            is CatalogParser.Result.Valid -> p.catalog
            // Unit tests keep the shipped file valid; failing loudly beats running without one.
            is CatalogParser.Result.Invalid -> throw IllegalStateException("Built-in model catalogue is invalid: ${p.problem}")
        }
    }

    private val testEntries: List<ModelEntry> by lazy {
        val json = runCatching { app.assets.open(DEBUG).use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull() ?: return@lazy emptyList()
        when (val p = CatalogParser.parse(json, allowTestOnly = true, requireVerified = false)) {
            is CatalogParser.Result.Valid -> p.catalog.models.filter { it.testOnly }
            is CatalogParser.Result.Invalid -> emptyList()
        }
    }

    private fun cached(): Catalog? {
        val json = runCatching { cacheFile.takeIf { it.isFile && it.length() <= CatalogParser.MAX_BYTES }?.readText() }.getOrNull() ?: return null
        return (CatalogParser.parse(json) as? CatalogParser.Result.Valid)?.catalog
    }

    /** Built-in or cached, plus debug test entries. Reads small files; fine on the main thread. */
    fun current(): Catalog = CatalogPolicy.withTestEntries(CatalogPolicy.choose(builtIn, cached()), testEntries)

    /**
     * Fetches a new copy when [CatalogPolicy.shouldFetch] says so. Blocking; call off the main
     * thread. Returns true when the catalogue changed.
     */
    fun refresh(client: HttpClient = WebSkill.http): Boolean {
        val t = now()
        if (!CatalogPolicy.shouldFetch(t, prefs.getLong(KEY_SUCCESS, 0), prefs.getLong(KEY_ATTEMPT, 0))) return false
        prefs.edit().putLong(KEY_ATTEMPT, t).apply()
        val before = current()
        return when (val o = CatalogFetcher.fetch(client)) {
            is CatalogFetcher.Outcome.Fetched -> {
                val tmp = File(cacheFile.path + ".tmp")
                val saved = runCatching {
                    tmp.writeText(o.json)
                    tmp.renameTo(cacheFile) || throw IOException("rename failed")
                }.isSuccess
                if (!saved) tmp.delete()
                prefs.edit().putLong(KEY_SUCCESS, t).apply()
                current() != before
            }
            is CatalogFetcher.Outcome.Failed -> false
        }
    }

    companion object {
        const val BUILT_IN = "models.json"
        const val DEBUG = "models-debug.json"
        private const val KEY_SUCCESS = "last_success"
        private const val KEY_ATTEMPT = "last_attempt"
    }
}
