package com.mysi.agent.models

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HashCheckTest {
    private fun temp(bytes: ByteArray): File = Files.createTempFile("model", ".litertlm").toFile().apply {
        writeBytes(bytes)
        deleteOnExit()
    }

    private fun sha(b: ByteArray) = HashCheck.hex(MessageDigest.getInstance("SHA-256").digest(b))

    // Bigger than the 1 MB read buffer, so the streaming path is exercised.
    private val data = ByteArray(3 * (1 shl 20) + 12_345) { (it * 31 + 7).toByte() }

    @Test
    fun knownVector() {
        // SHA-256 of "abc" (FIPS 180-2 example).
        val f = temp("abc".toByteArray())
        assertEquals(HashCheck.Result.Ok, HashCheck.verify(f, 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
        assertEquals(HashCheck.Result.Ok, HashCheck.verify(f, 3, "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD"))
    }

    @Test
    fun matchingFilePassesWithProgress() {
        val f = temp(data)
        val seen = mutableListOf<Long>()
        val r = HashCheck.verify(f, data.size.toLong(), sha(data), progress = { seen += it }, progressEvery = 1L shl 20)
        assertEquals(HashCheck.Result.Ok, r)
        assertEquals(data.size.toLong(), seen.last())
        assertTrue(seen.size >= 3)
        assertEquals(seen.sorted(), seen)
    }

    @Test
    fun oneFlippedByteFails() {
        val bad = data.copyOf().also { it[2_000_000] = (it[2_000_000] + 1).toByte() }
        val r = HashCheck.verify(temp(bad), data.size.toLong(), sha(data))
        assertTrue(r is HashCheck.Result.WrongHash)
        assertEquals(sha(bad), (r as HashCheck.Result.WrongHash).actual)
        assertEquals("The download didn't match the published file, so it was deleted. Try again.", HashCheck.message(r))
    }

    @Test
    fun wrongSizeFailsBeforeReading() {
        var read = false
        val r = HashCheck.verify(temp(data), data.size + 1L, sha(data), progress = { read = true })
        assertEquals(HashCheck.Result.WrongSize(data.size.toLong()), r)
        assertTrue(!read)
        assertEquals("The download was incomplete or damaged, so it was deleted. Try again.", HashCheck.message(r))
    }

    @Test
    fun missingFile() {
        assertEquals(HashCheck.Result.Missing, HashCheck.verify(File("/nonexistent/model.litertlm"), 1, "a".repeat(64)))
        val dir = Files.createTempDirectory("m").toFile()
        assertEquals(HashCheck.Result.Missing, HashCheck.verify(dir, 1, "a".repeat(64)))
    }

    @Test
    fun cancellationStopsTheCheck() {
        var reads = 0
        val r = HashCheck.verify(temp(data), data.size.toLong(), sha(data), cancelled = { ++reads > 2 })
        assertEquals(HashCheck.Result.Cancelled, r)
    }

    @Test
    fun emptyFileHash() {
        val f = temp(ByteArray(0))
        assertEquals(HashCheck.Result.Ok, HashCheck.verify(f, 0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"))
    }
}
