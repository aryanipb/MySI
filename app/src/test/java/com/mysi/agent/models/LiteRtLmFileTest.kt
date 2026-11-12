package com.mysi.agent.models

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The header fixtures are the first bytes (magic, versions, header end offset and the FlatBuffer
 * section table, nothing else) of real files: Qwen3-0.6B in its two published versions and
 * SmolLM3-3B (chat models from MySI's catalogue), and embeddinggemma-2-text-270m, the embedding
 * model that aborted the app in LiteRT-LM's nativeCreateEngine.
 */
class LiteRtLmFileTest {
    private fun header(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/litertlm/$name")) { "missing fixture $name" }.use { it.readBytes() }

    private val chatModels = listOf("qwen3-0.6b-v1.7.header", "qwen3-0.6b-v1.6.header", "smollm3-3b.header")

    @Test
    fun chatModelsPass() {
        for (name in chatModels) assertEquals(name, LiteRtLmFile.Result.Ok, LiteRtLmFile.check(header(name)))
    }

    @Test
    fun theEmbeddingModelIsTurnedAwayWithAPlainMessage() {
        val r = LiteRtLmFile.check(header("embeddinggemma-270m.header"))
        assertEquals(LiteRtLmFile.Result.NotChatModel("an embedding model"), r)
        assertEquals(
            "e.litertlm is an embedding model, not a chat model, so MySI can't talk with it. Pick a chat model such as Gemma 4, or tap Get a model.",
            LiteRtLmFile.message(r, "e.litertlm"),
        )
    }

    @Test
    fun aWholeFileIsReadOnlyAsFarAsItsHeader() {
        val dir = Files.createTempDirectory("litertlm").toFile().apply { deleteOnExit() }
        // A real file goes on with its sections after the header; only the header is read.
        val chat = File(dir, "chat.litertlm").apply { writeBytes(header("smollm3-3b.header") + ByteArray(100_000)) }
        val embed = File(dir, "embed.litertlm").apply { writeBytes(header("embeddinggemma-270m.header") + ByteArray(1000)) }
        assertEquals(LiteRtLmFile.Result.Ok, LiteRtLmFile.check(chat))
        assertTrue(LiteRtLmFile.check(embed) is LiteRtLmFile.Result.NotChatModel)
        assertEquals(LiteRtLmFile.Result.Ok, LiteRtLmFile.check(ByteArrayInputStream(chat.readBytes())))
        assertEquals(LiteRtLmFile.Result.Damaged, LiteRtLmFile.check(File(dir, "missing.litertlm")))
    }

    @Test
    fun otherFilesAreNotLiteRtLm() {
        assertEquals(LiteRtLmFile.Result.NotLiteRtLm, LiteRtLmFile.check(ByteArray(0)))
        assertEquals(LiteRtLmFile.Result.NotLiteRtLm, LiteRtLmFile.check("LITERT".toByteArray()))
        assertEquals(LiteRtLmFile.Result.NotLiteRtLm, LiteRtLmFile.check("PK\u0003\u0004 a zip file, not a model".toByteArray()))
        // A .tflite file renamed: the FlatBuffer identifier sits at bytes 4..7, not a LITERTLM magic.
        assertEquals(LiteRtLmFile.Result.NotLiteRtLm, LiteRtLmFile.check(byteArrayOf(0x1c, 0, 0, 0) + "TFL3".toByteArray() + ByteArray(64)))
    }

    @Test
    fun aNewerMajorVersionIsNamed() {
        val h = header("qwen3-0.6b-v1.7.header").copyOf()
        ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 2)
        assertEquals(LiteRtLmFile.Result.UnsupportedVersion(2), LiteRtLmFile.check(h))
        assertEquals(LiteRtLmFile.Result.UnsupportedVersion(2), LiteRtLmFile.check(ByteArrayInputStream(h)))
        assertNotNull(LiteRtLmFile.message(LiteRtLmFile.Result.UnsupportedVersion(2), "x.litertlm"))
    }

    @Test
    fun cutOrInconsistentHeadersAreDamaged() {
        val h = header("qwen3-0.6b-v1.7.header")
        assertEquals(LiteRtLmFile.Result.Damaged, LiteRtLmFile.check(h.copyOf(20)))
        assertEquals(LiteRtLmFile.Result.Damaged, LiteRtLmFile.check(h.copyOf(300)))
        assertEquals(LiteRtLmFile.Result.Damaged, LiteRtLmFile.check(ByteArrayInputStream(h.copyOf(300))))
        for (end in listOf(0L, 32L, -1L, Long.MAX_VALUE, LiteRtLmFile.MAX_HEADER_BYTES + 64L)) {
            val bad = h.copyOf()
            ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putLong(24, end)
            assertEquals("end $end", LiteRtLmFile.Result.Damaged, LiteRtLmFile.check(bad))
            assertEquals("end $end", LiteRtLmFile.Result.Damaged, LiteRtLmFile.check(ByteArrayInputStream(bad)))
        }
    }

    @Test
    fun corruptedSectionTablesNeverThrow() {
        // Every single-byte change, and random noise, in the FlatBuffer: a result, never an exception.
        for (name in chatModels + "embeddinggemma-270m.header") {
            val h = header(name)
            for (i in 32 until h.size) for (v in intArrayOf(0x00, 0xff, 0x7f, 0x80)) {
                val bad = h.copyOf()
                bad[i] = v.toByte()
                LiteRtLmFile.check(bad)
            }
        }
        val rnd = Random(7)
        val base = header("qwen3-0.6b-v1.7.header")
        repeat(2000) {
            val bad = base.copyOf()
            repeat(rnd.nextInt(1, 12)) { bad[rnd.nextInt(32, bad.size)] = rnd.nextInt(256).toByte() }
            LiteRtLmFile.check(bad)
        }
    }

    @Test
    fun agentChecksTheHeaderBeforeAnyNativeCallOrUnloading() {
        val src = File("src/main/java/com/mysi/agent/Agent.kt").readText()
        val load = src.substringAfter("suspend fun load(path: String)")
        val check = load.indexOf("LiteRtLmFile.check(")
        assertTrue(check >= 0)
        for (later in listOf("engine?.close()", "probe.probe(path)", "startEngine(path, p)")) assertTrue(later, load.indexOf(later) > check)
    }

    @Test
    fun onlyOkHasNoMessage() {
        assertNull(LiteRtLmFile.message(LiteRtLmFile.Result.Ok, "m.litertlm"))
        for (r in listOf(LiteRtLmFile.Result.NotLiteRtLm, LiteRtLmFile.Result.Damaged, LiteRtLmFile.Result.NotChatModel("not a chat model"))) {
            val m = LiteRtLmFile.message(r, "m.litertlm")
            assertNotNull(m)
            assertTrue(m!!.startsWith("m.litertlm "))
        }
    }
}
