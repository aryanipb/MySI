package com.mysi.agent.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HearingTest {
    @Test
    fun recordingLevelIsRms() {
        assertEquals(0f, Recorder.level(ShortArray(10), 10), 0f)
        assertEquals(0.5f, Recorder.level(ShortArray(10) { if (it % 2 == 0) 16384 else -16384 }, 10), 1e-4f)
        assertEquals(0f, Recorder.level(ShortArray(0), 0), 0f)
    }

    @Test
    fun meterHasFiveSteps() {
        assertEquals("▯▯▯▯▯", HearingSkill.meter(0f))
        assertEquals("▮▮▮▮▮", HearingSkill.meter(1f))
        val mid = HearingSkill.meter(0.05f)
        assertEquals(5, mid.length)
        assertTrue(mid, mid.startsWith("▮") && mid.endsWith("▯"))
    }

    @Test
    fun transcriptsSkipSilentPieces() {
        assertEquals("hello there. how are you", Transcripts.join(listOf(" hello there. ", "[no speech]", "", "No speech", "how are you")))
        assertEquals("", Transcripts.join(listOf("[No speech]")))
    }
}
