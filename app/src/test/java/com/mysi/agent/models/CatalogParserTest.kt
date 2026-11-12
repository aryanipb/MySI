package com.mysi.agent.models

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CatalogParserTest {
    private val json = ModelFixtures.builtInJson

    private fun valid(text: String, allowTestOnly: Boolean = false, requireVerified: Boolean = true): Catalog =
        when (val r = CatalogParser.parse(text, allowTestOnly, requireVerified)) {
            is CatalogParser.Result.Valid -> r.catalog
            is CatalogParser.Result.Invalid -> fail("expected valid, got: ${r.problem}") as Nothing
        }

    private fun problem(text: String, allowTestOnly: Boolean = false): String =
        when (val r = CatalogParser.parse(text, allowTestOnly)) {
            is CatalogParser.Result.Valid -> fail("expected invalid") as Nothing
            is CatalogParser.Result.Invalid -> r.problem
        }

    /** The built-in catalogue with [change] applied to its JSON tree. */
    private fun mutate(change: (JsonObject) -> Unit): String {
        val tree = JsonParser.parseString(json).asJsonObject
        change(tree)
        return tree.toString()
    }

    private fun first(tree: JsonObject) = tree.getAsJsonArray("models")[0].asJsonObject

    private fun withFirst(key: String, value: Any?): String = mutate { t ->
        val e = first(t)
        when (value) {
            null -> e.remove(key)
            is String -> e.addProperty(key, value)
            is Number -> e.addProperty(key, value)
            is Boolean -> e.addProperty(key, value)
            is JsonObject -> e.add(key, value)
            is JsonArray -> e.add(key, value)
        }
    }

    // ---------------- the shipped files ----------------

    @Test
    fun builtInCatalogueIsValidAndPinned() {
        val c = valid(json)
        assertEquals(CatalogParser.SCHEMA, c.schema)
        assertEquals(listOf("gemma-4-e2b", "gemma-4-e4b", "gemma-4-12b", "smollm3-3b", "qwen3-1.7b", "qwen3-0.6b"), c.models.map { it.id })
        assertEquals(setOf("gemma-4-e2b", "gemma-4-e4b"), c.models.filter { it.verified }.map { it.id }.toSet())
        c.models.forEach { m ->
            assertTrue(m.url, m.url.startsWith("https://huggingface.co/litert-community/"))
            assertEquals(CatalogParser.url(m.repo, m.revision, m.file), m.url)
            assertEquals(64, m.sha256.length)
            assertEquals("Apache-2.0", m.licence)
            assertFalse(m.testOnly)
        }
        val e2b = c.byId("gemma-4-e2b")!!
        assertEquals(2_588_147_712L, e2b.sizeBytes)
        assertEquals("181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", e2b.sha256)
        assertEquals(8, e2b.minRamGb)
        assertEquals(12, c.byId("gemma-4-e4b")!!.minRamGb)
    }

    @Test
    fun rootCopyIsIdenticalToTheAsset() {
        // models.json at the repository root is what the app fetches from GitHub; it must match what ships.
        assertEquals(File("../models.json").readText(), File("src/main/assets/models.json").readText())
    }

    @Test
    fun debugTestEntryIsValidOnlyWhereTestEntriesAreAllowed() {
        val debug = File("src/debug/assets/models-debug.json").readText()
        val c = valid(debug, allowTestOnly = true, requireVerified = false)
        val t = c.models.single()
        assertTrue(t.testOnly)
        assertFalse(t.verified)
        assertEquals(164_626_432L, t.sizeBytes)
        // The same file is refused as a normal catalogue, so a remote copy can never add test entries.
        assertTrue(problem(debug).contains("test entries are not allowed"))
    }

    @Test
    fun releaseAssetsCarryNoTestEntry() {
        assertFalse(File("src/main/assets/models-debug.json").exists())
        assertFalse(json.contains("testOnly"))
    }

    // ---------------- addresses: never another host ----------------

    @Test
    fun urlMustBeThePinnedHuggingFaceAddress() {
        val e = first(JsonParser.parseString(json).asJsonObject)
        val pinned = e.get("url").asString
        for (bad in listOf(
            pinned.replace("https://", "http://"),
            pinned.replace("huggingface.co", "evil.example"),
            pinned.replace("huggingface.co", "huggingface.co.evil.example"),
            pinned.replace("huggingface.co", "huggingface.co:8443"),
            pinned.replace("https://", "https://user@"),
            "$pinned?download=1",
            "$pinned#x",
            pinned.replace("/resolve/", "/blob/"),
            pinned.replace("/resolve/", "/resolve/main/../"),
            pinned.replace("litert-community", "someone-else"),
            "file:///sdcard/model.litertlm",
            "",
        )) assertTrue(bad, problem(withFirst("url", bad)).contains("url must be"))
    }

    @Test
    fun repoRevisionAndFileAreStrict() {
        assertTrue(problem(withFirst("repo", "someone/gemma")).contains("repo"))
        assertTrue(problem(withFirst("repo", "litert-community/../evil")).contains("repo"))
        assertTrue(problem(withFirst("revision", "main")).contains("revision"))
        assertTrue(problem(withFirst("revision", "2D74AC41A33D7DA82F39F7D5720A04C68514A103")).contains("revision"))
        assertTrue(problem(withFirst("file", "../../evil.litertlm")).contains("file"))
        assertTrue(problem(withFirst("file", "model.apk")).contains("file"))
        assertTrue(problem(withFirst("file", "a/b.litertlm")).contains("file"))
    }

    // ---------------- hash, size, licence ----------------

    @Test
    fun sha256MustBe64Hex() {
        assertTrue(problem(withFirst("sha256", "abc")).contains("sha256"))
        assertTrue(problem(withFirst("sha256", "z".repeat(64))).contains("sha256"))
        assertTrue(problem(withFirst("sha256", "a".repeat(65))).contains("sha256"))
        assertTrue(problem(withFirst("sha256", null)).contains("sha256"))
        // Upper case is accepted and normalised.
        val upper = "181938105E0EEFD105961417E8DA75903EACDA102C4FCE9CE90F50B97139A63C"
        assertEquals(upper.lowercase(), valid(withFirst("sha256", upper)).models[0].sha256)
    }

    @Test
    fun sizeMustBeSane() {
        assertTrue(problem(withFirst("sizeBytes", 1000)).contains("sizeBytes"))
        assertTrue(problem(withFirst("sizeBytes", 50_000_000_000L)).contains("sizeBytes"))
        assertTrue(problem(withFirst("sizeBytes", -5)).contains("sizeBytes"))
        assertTrue(problem(withFirst("sizeBytes", 2.5e9)).contains("whole number"))
        assertTrue(problem(withFirst("sizeBytes", "2588147712")).contains("must be a number"))
        assertTrue(problem(json.replace("\"sizeBytes\": 2588147712", "\"sizeBytes\": 99999999999999999999999")).isNotEmpty())
    }

    @Test
    fun onlyOpenLicences() {
        assertTrue(problem(withFirst("licence", "gemma")).contains("licence"))
        assertTrue(problem(withFirst("licence", "apache-2.0")).contains("licence"))
        assertEquals("MIT", valid(withFirst("licence", "MIT")).models[0].licence)
    }

    // ---------------- verification claims ----------------

    @Test
    fun onlyVerifiedModelsMayClaimTools() {
        val noTools = JsonObject().apply {
            addProperty("text", true); addProperty("images", true); addProperty("audio", true); addProperty("tools", false)
        }
        assertTrue(problem(withFirst("abilities", noTools)).contains("verified model must work with MySI's tools"))
        val untestedWithTools = mutate { t ->
            val e = t.getAsJsonArray("models")[2].asJsonObject
            e.getAsJsonObject("abilities").addProperty("tools", true)
        }
        assertTrue(problem(untestedWithTools).contains("only verified models"))
    }

    @Test
    fun aCatalogueWithoutAVerifiedModelIsRefused() {
        val none = mutate { t ->
            val keep = JsonArray()
            t.getAsJsonArray("models").forEach { if (!it.asJsonObject.get("verified").asBoolean) keep.add(it) }
            t.add("models", keep)
        }
        assertTrue(problem(none).contains("no verified model"))
    }

    // ---------------- structure ----------------

    @Test
    fun schemaVersionMustMatch() {
        assertTrue(problem(mutate { it.addProperty("schema", 2) }).contains("schema 2 is not supported"))
        assertTrue(problem(mutate { it.remove("schema") }).contains("schema"))
    }

    @Test
    fun unknownOrMissingFieldsAreRefused() {
        assertTrue(problem(mutate { it.addProperty("extra", 1) }).contains("Unknown field 'extra'"))
        assertTrue(problem(withFirst("mirror", "https://evil.example/m.litertlm")).contains("Unknown field 'mirror'"))
        assertTrue(problem(withFirst("name", null)).contains("name"))
        assertTrue(problem(withFirst("verified", "yes")).contains("true or false"))
    }

    @Test
    fun duplicatesAndLimits() {
        val dup = mutate { t ->
            val m = t.getAsJsonArray("models")
            m.add(m[0].deepCopy())
        }
        assertTrue(problem(dup).contains("appears twice"))
        val many = mutate { t ->
            val m = t.getAsJsonArray("models")
            val base = m[0].asJsonObject
            repeat(CatalogParser.MAX_MODELS) { i -> m.add(base.deepCopy().apply { addProperty("id", "copy-$i") }) }
        }
        assertTrue(problem(many).contains("too many"))
        assertTrue(problem(mutate { it.add("models", JsonArray()) }).contains("no models"))
        assertTrue(problem(" ".repeat(CatalogParser.MAX_BYTES + 1)).contains("too large"))
        assertTrue(problem(withFirst("comfortableRamGb", 4)).contains("below minRamGb"))
        assertTrue(problem(withFirst("quality", 0)).contains("quality"))
        assertTrue(problem(withFirst("id", "Bad Id")).contains("invalid id"))
    }

    @Test
    fun displayTextCannotCarryControlCharacters() {
        assertTrue(problem(withFirst("name", "Gemma\u0000")).contains("control characters"))
        assertTrue(problem(withFirst("notes", "safe‮evil")).contains("control characters"))
        assertTrue(problem(withFirst("name", "x".repeat(61))).contains("too long"))
    }

    @Test
    fun hostileJsonIsRefusedNotCrashed() {
        for (bad in listOf(
            "",
            "null",
            "[]",
            "\"models\"",
            "{",
            "{'schema': 1}",
            "{\"schema\": 1, // comment\n}",
            "$json trailing",
            "$json$json",
            "[".repeat(10_000) + "]".repeat(10_000),
            "{\"schema\":1,\"version\":1,\"updated\":\"2026-11-12\",\"models\":[1,2,3]}",
            "{\"schema\":1,\"version\":1,\"updated\":\"yesterday\",\"models\":[]}",
            "{\"schema\":NaN}",
        )) assertTrue(bad.take(40), CatalogParser.parse(bad) is CatalogParser.Result.Invalid)
    }

    @Test
    fun displayHelpers() {
        val e2b = ModelFixtures.entry("gemma-4-e2b")
        assertEquals("Can see photos and hear audio; works with MySI's tools", e2b.abilitiesText())
        assertEquals("Text only", ModelFixtures.entry("qwen3-0.6b").abilitiesText())
        assertEquals("Gemma 4 E2B · 2.6 GB", e2b.label)
        assertEquals("Apache 2.0", e2b.licenceText)
    }
}
