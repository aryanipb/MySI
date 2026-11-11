package com.mysi.agent.senses

import android.content.Context
import com.mysi.agent.Args
import com.mysi.agent.Skill
import com.mysi.agent.Skills
import com.mysi.agent.TestContext
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensesSkillsTest {
    private val ctx: Context get() = TestContext.unsafe

    private fun tools(s: Skill) = s.tools(ctx).associateBy { it.name }

    @Test
    fun theSkillsExposeExactlyTheseToolsAllPrivateNoneOutbound() {
        assertEquals(listOf("look_at_image", "detect_objects", "read_text_in_image"), VisionSkill.skill.tools(ctx).map { it.name })
        assertEquals(listOf("record_audio", "transcribe_audio", "listen_to_audio", "identify_sounds"), HearingSkill.skill.tools(ctx).map { it.name })
        val all = tools(VisionSkill.skill) + tools(HearingSkill.skill)
        assertTrue(all.values.all { it.privateData })
        assertTrue(all.values.none { it.outbound })
        assertNotNull(Skills.named("vision"))
        assertNotNull(Skills.named("hearing"))
    }

    @Test
    fun recordingAlwaysAsksFirstAndNothingElseDoes() {
        val all = tools(VisionSkill.skill) + tools(HearingSkill.skill)
        val record = all.getValue("record_audio")
        val q = record.confirm!!.invoke(Args(mapOf("seconds" to 20)))
        assertEquals("Record 20 seconds from the microphone? The recording stays on this phone.", q)
        // Out-of-range lengths are clamped in the question exactly as in the recording.
        assertEquals("Record ${HearingSkill.MAX_RECORD_SECONDS} seconds from the microphone? The recording stays on this phone.", record.confirm!!.invoke(Args(mapOf("seconds" to 9999))))
        assertEquals("Record 30 seconds from the microphone? The recording stays on this phone.", record.confirm!!.invoke(Args(emptyMap())))
        assertTrue(all.filterKeys { it != "record_audio" }.values.all { it.confirm == null })
    }

    @Test
    fun descriptionsAreShortPlainAndUseful() {
        for (s in listOf(VisionSkill.skill, HearingSkill.skill)) {
            assertTrue(s.name, s.description.length in 40..200)
            assertTrue(s.name, s.keywords.size >= 8)
            assertTrue(s.name, s.instructions.length in 100..900)
            for (t in s.tools(ctx)) {
                assertTrue("${t.name}: ${t.description.length}", t.description.length in 40..330)
                assertTrue(t.name, t.params.all { it.description.isNotBlank() })
            }
        }
    }
}

class SensesManifestTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun mediaPipeUsageReportsStayUnregistered() {
        val service = manifest.substringAfter("com.google.android.datatransport.runtime.backends.TransportBackendDiscovery").substringBefore("</service>")
        assertTrue(service, service.contains("backend:com.google.android.datatransport.cct.CctBackendFactory"))
        assertTrue(service, service.contains("tools:node=\"remove\""))
    }

    @Test
    fun theMicrophoneIsDeclaredOnceAndOptional() {
        assertEquals(1, Regex("uses-permission android:name=\"android.permission.RECORD_AUDIO\"").findAll(manifest).count())
        assertTrue(manifest.contains("<uses-feature android:name=\"android.hardware.microphone\" android:required=\"false\" />"))
    }
}
