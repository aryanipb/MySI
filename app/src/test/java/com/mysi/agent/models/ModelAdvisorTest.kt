package com.mysi.agent.models

import com.mysi.agent.models.ModelFixtures.builtIn
import com.mysi.agent.models.ModelFixtures.phone
import com.mysi.agent.models.ModelFixtures.phone12
import com.mysi.agent.models.ModelFixtures.phone16
import com.mysi.agent.models.ModelFixtures.phone4
import com.mysi.agent.models.ModelFixtures.phone6
import com.mysi.agent.models.ModelFixtures.phone8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAdvisorTest {
    private fun advise(d: DeviceProfile, use: Use = Use.CHAT, priority: Priority = Priority.BALANCED) = ModelAdvisor.advise(d, builtIn, use, priority)

    private fun Advice.excludedIds() = excluded.map { it.entry.id }.toSet()

    private fun Advice.reasonFor(id: String) = excluded.first { it.entry.id == id }.reasons

    // ---------------- the phone table ----------------

    @Test
    fun fourGbPhoneGetsNothingAndSaysWhy() {
        val a = advise(phone4)
        assertNull(a.recommended)
        assertTrue(a.options.isEmpty())
        assertTrue(a.untested.isEmpty())
        assertEquals(builtIn.models.map { it.id }.toSet(), a.excludedIds())
        assertEquals(
            "Needs about 2.5 GB of memory; this 4 GB phone can give an app about 1.8 GB.",
            a.reasonFor("gemma-4-e2b").first(),
        )
        assertEquals(
            "None of the models tested with MySI fit this phone. Gemma 4 E2B: Needs about 2.5 GB of memory; this 4 GB phone can give an app about 1.8 GB.",
            a.noRecommendation,
        )
    }

    @Test
    fun sixGbPhoneIsBelowGooglesMinimumSoNothingIsRecommendedButE2bCanBeTried() {
        val a = advise(phone6)
        assertNull(a.recommended)
        assertEquals(listOf("gemma-4-e2b"), a.options.map { it.entry.id })
        val e2b = a.options.single()
        assertFalse(e2b.recommendable)
        assertTrue(e2b.reason, e2b.reason.startsWith("Below the 8 GB of memory suggested for it."))
        assertTrue(e2b.warnings.contains("Suggested minimum is 8 GB of memory and this phone has 6 GB. It may be slow, or Android may close it."))
        assertTrue(a.noRecommendation!!.startsWith("This phone has 6 GB of memory, less than suggested"))
        assertTrue("gemma-4-e4b" in a.excludedIds())
        // Untested: only the 0.6B fits in what a 6 GB phone can give an app.
        assertEquals(listOf("qwen3-0.6b"), a.untested.map { it.entry.id })
    }

    @Test
    fun eightGbPhoneGetsE2bAndCannotRunE4b() {
        val a = advise(phone8)
        assertEquals("gemma-4-e2b", a.recommended!!.entry.id)
        assertEquals("Fits your 8 GB phone. Can see photos and hear audio; works with MySI's tools.", a.recommended!!.reason)
        assertTrue(a.options.isEmpty())
        assertEquals(
            "Needs about 4.8 GB of memory; this 8 GB phone can give an app about 3.7 GB.",
            a.reasonFor("gemma-4-e4b").single(),
        )
        assertEquals(listOf("smollm3-3b", "qwen3-1.7b", "qwen3-0.6b"), a.untested.map { it.entry.id })
    }

    @Test
    fun twelveGbPhoneFollowsTheUsersPriority() {
        assertEquals("gemma-4-e2b", advise(phone12).recommended!!.entry.id)
        assertEquals("gemma-4-e2b", advise(phone12, priority = Priority.FASTER).recommended!!.entry.id)
        assertEquals("gemma-4-e4b", advise(phone12, priority = Priority.BETTER).recommended!!.entry.id)
        // Research leans to better answers when the user states no preference…
        assertEquals("gemma-4-e4b", advise(phone12, Use.RESEARCH).recommended!!.entry.id)
        // …but "faster replies" still wins.
        assertEquals("gemma-4-e2b", advise(phone12, Use.RESEARCH, Priority.FASTER).recommended!!.entry.id)
        val balanced = advise(phone12)
        assertEquals("Fits your 12 GB phone with room to spare. Can see photos and hear audio; works with MySI's tools.", balanced.recommended!!.reason)
        assertEquals(listOf("gemma-4-e4b"), balanced.options.map { it.entry.id })
        assertTrue(balanced.options.single().recommendable)
    }

    @Test
    fun sixteenGbPhoneGetsE4bButNever12b() {
        val a = advise(phone16)
        assertEquals("gemma-4-e4b", a.recommended!!.entry.id)
        assertTrue(a.recommended!!.reason.startsWith("Fits your 16 GB phone with room to spare."))
        assertEquals("gemma-4-e2b", advise(phone16, priority = Priority.FASTER).recommended!!.entry.id)
        assertTrue("gemma-4-12b" in a.excludedIds())
    }

    @Test
    fun unverifiedModelsAreNeverRecommendedEvenOnHugePhones() {
        for (gb in listOf(24, 32)) {
            val d = phone(gb, gb - 0.6)
            for (u in Use.entries) for (p in Priority.entries) {
                val a = advise(d, u, p)
                assertTrue(a.recommended!!.entry.verified)
                assertTrue(a.options.all { it.entry.verified })
                assertTrue(a.untested.none { it.recommendable })
            }
        }
        // On a 32 GB phone the 12B fits in memory, but only ever as an untested option.
        val big = advise(phone(32, 31.4))
        assertTrue(big.untested.any { it.entry.id == "gemma-4-12b" })
        assertTrue(big.untested.first { it.entry.id == "gemma-4-12b" }.warnings.first().startsWith("Untested with MySI"))
    }

    // ---------------- needs ----------------

    @Test
    fun photosAndVoiceExcludeTextOnlyModels() {
        val a = advise(phone8, Use.SENSES)
        assertEquals("gemma-4-e2b", a.recommended!!.entry.id)
        assertEquals("Fits your 8 GB phone. Can see photos and hear audio, as you asked; works with MySI's tools.", a.recommended!!.reason)
        assertTrue(a.untested.isEmpty())
        for (id in listOf("qwen3-0.6b", "qwen3-1.7b", "smollm3-3b"))
            assertEquals("Can't see photos or hear audio, which you asked for.", a.reasonFor(id).first())
    }

    @Test
    fun everyExclusionReasonIsListed() {
        val d = phone(4, 3.6, freeStorage = 1_000_000_000L)
        val reasons = advise(d, Use.SENSES).reasonFor("qwen3-1.7b")
        assertEquals(3, reasons.size)
        assertTrue(reasons[0].startsWith("Can't see photos"))
        assertTrue(reasons[1].startsWith("Needs about 2.9 GB of memory"))
        assertTrue(reasons[2].startsWith("Needs 2 GB free storage"))
    }

    // ---------------- storage, network, chip ----------------

    @Test
    fun lowStorageExcludesWithExactNumbers() {
        val a = advise(phone(8, 7.4, freeStorage = 3_000_000_000L))
        assertNull(a.recommended)
        assertEquals(
            "Needs 3.9 GB free storage (2.6 GB download plus room to run); you have 3 GB.",
            a.reasonFor("gemma-4-e2b").single(),
        )
        assertTrue(a.noRecommendation!!.endsWith("Gemma 4 E2B: Needs 3.9 GB free storage (2.6 GB download plus room to run); you have 3 GB."))
    }

    @Test
    fun storageHeadroomIsTheLargerOfTheFloorAndTheCacheShare() {
        assertEquals(1_164_626_432L, ModelAdvisor.Thresholds.requiredStorage(164_626_432L))
        assertEquals(3_659_530_240L + 1_829_765_120L, ModelAdvisor.Thresholds.requiredStorage(3_659_530_240L))
        // Exactly enough is enough.
        val need = ModelAdvisor.Thresholds.requiredStorage(ModelFixtures.entry("gemma-4-e2b").sizeBytes)
        assertNotNull(advise(phone(8, 7.4, freeStorage = need)).recommended)
        assertNull(advise(phone(8, 7.4, freeStorage = need - 1)).recommended)
    }

    @Test
    fun meteredAndOfflineAreWarningsNotExclusions() {
        val metered = advise(phone(8, 7.4, unmetered = false))
        assertEquals("gemma-4-e2b", metered.recommended!!.entry.id)
        assertTrue(metered.recommended!!.warnings.contains("You're on mobile data. This is a 2.6 GB download."))
        val offline = advise(phone(8, 7.4, unmetered = false, online = false))
        assertEquals("gemma-4-e2b", offline.recommended!!.entry.id)
        assertTrue(offline.recommended!!.warnings.contains("No internet connection right now. The download starts when you're back online."))
        assertTrue(advise(phone8).recommended!!.warnings.isEmpty())
    }

    @Test
    fun unknownChipChangesNothing() {
        val known = advise(phone8)
        val unknown = advise(phone(8, 7.4, socManufacturer = "unknown", socModel = ""))
        assertEquals(known, unknown)
    }

    @Test
    fun memoryInUseNowIsAWarning() {
        val busy = advise(phone(8, 7.4, available = 1L * ModelFixtures.GIB))
        assertEquals("gemma-4-e2b", busy.recommended!!.entry.id)
        assertTrue(busy.recommended!!.warnings.contains("Other apps are using a lot of memory right now. Android may close them while MySI runs."))
        val low = advise(phone(8, 7.4, lowMemoryNow = true))
        assertTrue(low.recommended!!.warnings.any { it.startsWith("Other apps") })
    }

    @Test
    fun lowRamAnd32BitPhonesGetNothing() {
        val go = advise(phone(16, 14.9, lowRam = true))
        assertNull(go.recommended)
        assertTrue(go.excluded.all { it.reasons.contains("This phone runs in low-memory mode, which can't hold a model this size.") })
        val old = advise(phone(16, 14.9, abis = listOf("armeabi-v7a", "armeabi")))
        assertNull(old.recommended)
        assertTrue(old.excluded.all { it.reason == "MySI's model engine needs a 64-bit phone." })
        // An x86_64 emulator is supported.
        assertNotNull(advise(phone(16, 14.9, abis = listOf("x86_64"))).recommended)
    }

    // ---------------- rules ----------------

    @Test
    fun adviceIsDeterministic() {
        for (d in listOf(phone4, phone6, phone8, phone12, phone16)) for (u in Use.entries) for (p in Priority.entries)
            assertEquals(advise(d, u, p), advise(d, u, p))
    }

    @Test
    fun memoryBudgetIsHalfOfManagedRam() {
        assertEquals(0.5, ModelAdvisor.Thresholds.APP_MEMORY_SHARE, 0.0)
        assertEquals((7.4 * ModelFixtures.GIB / 2).toLong(), ModelAdvisor.Thresholds.appBudget(phone8))
        // Never recommend a model whose need exceeds the budget, on any phone or answer.
        for (gb in listOf(2, 3, 4, 6, 8, 10, 12, 16, 18, 24)) {
            val d = phone(gb, gb * 0.93)
            for (u in Use.entries) for (p in Priority.entries) {
                val a = advise(d, u, p)
                (listOfNotNull(a.recommended) + a.options + a.untested).forEach {
                    assertTrue(it.entry.memoryBytes <= ModelAdvisor.Thresholds.appBudget(d))
                    assertTrue(d.freeStorageBytes >= ModelAdvisor.Thresholds.requiredStorage(it.entry.sizeBytes))
                }
                a.recommended?.let { assertTrue(d.ramGb >= it.entry.minRamGb && it.entry.verified) }
            }
        }
    }

    @Test
    fun testEntriesStayInTheUntestedListLast() {
        val test = ModelFixtures.entry("qwen3-0.6b").copy(id = "test-entry", name = "Test", testOnly = true, quality = 99)
        val catalog = CatalogPolicy.withTestEntries(builtIn, listOf(test))
        val a = ModelAdvisor.advise(phone16, catalog)
        assertEquals("test-entry", a.untested.last().entry.id)
        assertFalse(a.untested.last().recommendable)
        assertEquals("gemma-4-e4b", a.recommended!!.entry.id)
    }

    @Test
    fun researchWithoutPreferenceMeansBetter() {
        assertEquals(Priority.BETTER, ModelAdvisor.effective(Use.RESEARCH, Priority.BALANCED))
        assertEquals(Priority.BALANCED, ModelAdvisor.effective(Use.CHAT, Priority.BALANCED))
        assertEquals(Priority.FASTER, ModelAdvisor.effective(Use.RESEARCH, Priority.FASTER))
    }
}
