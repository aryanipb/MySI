package com.mysi.agent.models

import com.mysi.agent.models.ModelFixtures.GIB
import com.mysi.agent.models.ModelFixtures.builtIn
import com.mysi.agent.models.ModelFixtures.entry
import com.mysi.agent.models.ModelFixtures.phone12
import com.mysi.agent.models.ModelFixtures.phone16
import com.mysi.agent.models.ModelFixtures.phone8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FitCheckTest {
    private val e2b = entry("gemma-4-e2b")
    private val e4b = entry("gemma-4-e4b")

    private fun m(tps: Double, firstMs: Long = 1_200, avail: Long = 4 * GIB, low: Boolean = false, id: String = "gemma-4-e2b") =
        FitMeasurement(id, firstMs, tps, avail, 15 * GIB, low, 0)

    @Test
    fun goodSpeedOnAnEightGbPhoneIsGood() {
        val v = FitCheck.decide(m(12.0), e2b, builtIn, phone8)
        assertEquals(FitVerdict.Good("Runs well on this phone (replies start in about 1.2 s and come at about 9 words a second)."), v)
    }

    @Test
    fun slowE4bSuggestsE2bButNeverSwitches() {
        val v = FitCheck.decide(m(2.5, id = "gemma-4-e4b"), e4b, builtIn, phone12)
        assertTrue(v is FitVerdict.TooSlow)
        assertEquals("gemma-4-e2b", (v as FitVerdict.TooSlow).suggest!!.id)
        assertEquals("Replies are slow on this phone (replies start in about 1.2 s and come at about 1.9 words a second). Gemma 4 E2B is smaller and faster.", v.message)
    }

    @Test
    fun slowFirstReplyAloneIsSlow() {
        val v = FitCheck.decide(m(10.0, firstMs = 12_000, id = "gemma-4-e4b"), e4b, builtIn, phone12)
        assertTrue(v is FitVerdict.TooSlow)
    }

    @Test
    fun slowestTestedModelHasNothingSmallerToOffer() {
        val v = FitCheck.decide(m(2.0), e2b, builtIn, phone8)
        assertTrue(v is FitVerdict.TooSlow)
        assertNull((v as FitVerdict.TooSlow).suggest)
        assertTrue(v.message.endsWith("It is the smallest tested model, so replies will take a while."))
    }

    @Test
    fun tightMemoryBeatsSpeed() {
        val v = FitCheck.decide(m(30.0, avail = 300L shl 20, id = "gemma-4-e4b"), e4b, builtIn, phone12)
        assertTrue(v is FitVerdict.Tight)
        assertEquals("gemma-4-e2b", (v as FitVerdict.Tight).suggest!!.id)
        val low = FitCheck.decide(m(30.0, low = true), e2b, builtIn, phone8)
        assertEquals(FitVerdict.Tight("This phone is short of memory with Gemma 4 E2B loaded. Close other apps if MySI stops.", null), low)
    }

    @Test
    fun fastWithRoomMentionsTheLargerModel() {
        val v = FitCheck.decide(m(40.0, avail = 6 * GIB), e2b, builtIn, phone16)
        assertTrue(v is FitVerdict.Roomy)
        assertEquals("gemma-4-e4b", (v as FitVerdict.Roomy).suggest.id)
        assertTrue(v.message.endsWith("Gemma 4 E4B gives better answers if you'd like to try it (3.7 GB download)."))
    }

    @Test
    fun noUpgradeWhenThePhoneCannotHoldIt() {
        // An 8 GB phone cannot give an app E4B's memory, however fast E2B runs.
        assertTrue(FitCheck.decide(m(40.0, avail = 6 * GIB), e2b, builtIn, phone8) is FitVerdict.Good)
        // Not enough free memory now for the larger model's extra need plus spare.
        assertTrue(FitCheck.decide(m(40.0, avail = 2 * GIB), e2b, builtIn, phone16) is FitVerdict.Good)
        // Just under the speed bar.
        assertTrue(FitCheck.decide(m(14.9, avail = 6 * GIB), e2b, builtIn, phone16) is FitVerdict.Good)
        // Never an unverified model, even the best one.
        val v = FitCheck.decide(m(40.0, avail = 30 * GIB, id = "gemma-4-e4b"), e4b, builtIn, ModelFixtures.phone(32, 31.4))
        assertTrue(v is FitVerdict.Good)
    }

    @Test
    fun thresholdsAreWhereTheyAreDocumented() {
        assertEquals(4.0, FitCheck.Thresholds.SLOW_TOKENS_PER_SECOND, 0.0)
        assertEquals(10_000L, FitCheck.Thresholds.SLOW_FIRST_REPLY_MS)
        assertEquals(15.0, FitCheck.Thresholds.ROOMY_TOKENS_PER_SECOND, 0.0)
        // Exactly at the slow bar is not slow.
        assertTrue(FitCheck.decide(m(4.0), e2b, builtIn, phone8) is FitVerdict.Good)
        assertTrue(FitCheck.decide(m(4.0, firstMs = 10_000), e2b, builtIn, phone8) is FitVerdict.Good)
    }

    @Test
    fun speedFromACappedReply() {
        // A long reply hit the 32-token cap: 31 tokens after the first, in 3.1 s.
        assertEquals(10.0, FitCheckRunner.tokensPerSecond("1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,", 4_100, 1_000), 0.001)
        // A reply that stopped early is counted with the app's own estimate (here 1 token, so 1 per second at least).
        assertEquals(1.0, FitCheckRunner.tokensPerSecond("1, 2", 2_000, 1_000), 0.001)
        // Never divides by zero.
        assertTrue(FitCheckRunner.tokensPerSecond("", 1_000, 1_000) > 0)
    }

    @Test
    fun describeUsesWords() {
        assertEquals("replies start in about 0.4 s and come at about 15 words a second", FitCheck.describe(m(20.0, firstMs = 400)))
        assertEquals("replies start in about 3 s and come at about 3 words a second", FitCheck.describe(m(4.0, firstMs = 3_000)))
    }
}
