package com.mysi.agent.senses

import com.mysi.agent.AgentLoop
import com.mysi.agent.Attachment
import com.mysi.agent.Attachments
import com.mysi.agent.Call
import com.mysi.agent.ContextManager
import com.mysi.agent.Entry
import com.mysi.agent.ModelChat
import com.mysi.agent.ModelReply
import com.mysi.agent.Tokens
import com.mysi.agent.ToolOut
import com.mysi.agent.Transcript
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadPlannerTest {
    private fun p(text: Accel, vision: Accel?, audio: Accel?) = EnginePlan(text, vision, audio)

    @Test
    fun aFullModelTriesEverySenseFirstAndEndsTextOnlyOnEachBackend() {
        val plans = LoadPlanner.plans(Modalities(vision = true, audio = true))
        val gpu = listOf(
            p(Accel.GPU, Accel.GPU, Accel.CPU), p(Accel.GPU, Accel.CPU, Accel.CPU), p(Accel.GPU, null, Accel.CPU),
            p(Accel.GPU, Accel.GPU, null), p(Accel.GPU, Accel.CPU, null), p(Accel.GPU, null, null),
        )
        assertEquals(gpu + gpu.map { it.copy(text = Accel.CPU) }, plans)
        // Unknown modalities are treated as "maybe both".
        assertEquals(plans, LoadPlanner.plans(null))
    }

    @Test
    fun aTextOnlyModelNeverAsksForEncoders() {
        assertEquals(listOf(p(Accel.GPU, null, null), p(Accel.CPU, null, null)), LoadPlanner.plans(Modalities(vision = false, audio = false)))
        assertEquals(
            listOf(p(Accel.GPU, Accel.GPU, null), p(Accel.GPU, Accel.CPU, null), p(Accel.GPU, null, null), p(Accel.CPU, Accel.GPU, null), p(Accel.CPU, Accel.CPU, null), p(Accel.CPU, null, null)),
            LoadPlanner.plans(Modalities(vision = true, audio = false)),
        )
    }

    @Test
    fun backendsTheModelListsNarrowTheChoices() {
        val plans = LoadPlanner.plans(Modalities(vision = true, audio = true, visionAccels = setOf(Accel.CPU), audioAccels = setOf(Accel.GPU)))
        assertTrue(plans.none { it.vision == Accel.GPU })
        assertTrue(plans.filter { it.audio != null }.all { it.audio == Accel.GPU })
        assertEquals(p(Accel.GPU, Accel.CPU, Accel.GPU), plans.first())
    }

    @Test
    fun firstWorkingFallsBackToTextOnlyAndSkipsABrokenBackend() {
        val tried = mutableListOf<EnginePlan>()
        val failures = mutableListOf<EnginePlan>()
        // The GPU cannot run the model at all; on the CPU the vision encoder fails but audio works.
        val r = LoadPlanner.firstWorking(
            LoadPlanner.plans(Modalities(vision = true, audio = true)),
            start = { plan ->
                tried += plan
                if (plan.text == Accel.GPU || plan.vision != null) throw IllegalStateException("no")
                "engine"
            },
            failed = { plan, _ -> failures += plan },
        )
        assertNotNull(r)
        assertEquals(p(Accel.CPU, null, Accel.CPU), r!!.first)
        assertEquals("engine", r.second)
        assertTrue(r.first.canHear && !r.first.canSee)
        assertEquals(failures, tried.dropLast(1))
        // After the text-only GPU plan failed nothing else ran on the GPU.
        assertEquals(p(Accel.GPU, null, null), tried.last { it.text == Accel.GPU })
    }

    @Test
    fun firstWorkingStopsOnTheFirstSuccessAndReportsTotalFailure() {
        var calls = 0
        val ok = LoadPlanner.firstWorking(LoadPlanner.plans(null), start = { calls++; 1 })
        assertEquals(1, calls)
        assertEquals(p(Accel.GPU, Accel.GPU, Accel.CPU), ok!!.first)
        assertNull(LoadPlanner.firstWorking(LoadPlanner.plans(null), start = { throw RuntimeException("broken file") }))
        // Out of memory on one plan is a failure of that plan, not of the app.
        val oom = LoadPlanner.firstWorking(LoadPlanner.plans(Modalities(true, false)), start = { if (it.vision != null) throw OutOfMemoryError() else 2 })
        assertEquals(p(Accel.GPU, null, null), oom!!.first)
    }

    @Test
    fun describeNamesEachBackend() {
        assertEquals("GPU · vision GPU · audio CPU", p(Accel.GPU, Accel.GPU, Accel.CPU).describe())
        assertEquals("CPU · text only", p(Accel.CPU, null, null).describe())
        assertEquals("GPU · audio CPU", p(Accel.GPU, null, Accel.CPU).describe())
    }
}

class AttachmentsTest {
    private val photo = Attachment.Image(File("/ws/Photos/a.jpg"))
    private val photo2 = Attachment.Image(File("/ws/Photos/b.jpg"))
    private val voice = Attachment.Audio(File("/ws/Recordings/r.wav"), 12.0)
    private val longVoice = Attachment.Audio(File("/ws/Recordings/long.wav"), 95.0)

    @Test
    fun stubsKeepTheKindNameAndPath() {
        assertEquals("[image: a.jpg at /ws/Photos/a.jpg]", Attachments.stub(photo))
        assertEquals("[audio: r.wav at /ws/Recordings/r.wav]", Attachments.stub(voice))
    }

    @Test
    fun sendableRespectsSensesAndLimits() {
        val many = (0 until 6).map { Attachment.Image(File("/ws/p$it.jpg")) }
        assertEquals(many.take(Attachments.MAX_IMAGES), Attachments.sendable(many, canSee = true, canHear = true))
        assertEquals(listOf(voice), Attachments.sendable(listOf(photo, voice, longVoice), canSee = false, canHear = true))
        assertEquals(listOf(photo), Attachments.sendable(listOf(photo, voice), canSee = true, canHear = false))
        assertEquals(emptyList<Attachment>(), Attachments.sendable(listOf(photo, voice), canSee = false, canHear = false))
    }

    @Test
    fun theNoteTellsTheModelWhatItGetsAndWhatItDoesNot() {
        assertEquals("", Attachments.note(emptyList(), emptyList(), true, true))
        val given = listOf(photo, photo2, longVoice)
        val note = Attachments.note(given, Attachments.sendable(given, canSee = false, canHear = true), canSee = false, canHear = true)
        assertTrue(note, note.startsWith("\n\nThe user attached:\n"))
        assertTrue(note.contains("[image: a.jpg at /ws/Photos/a.jpg] (not shown to you: the loaded model cannot see images)"))
        assertTrue(note.contains("[audio: long.wav at /ws/Recordings/long.wav] (you hear the first 30 seconds"))
        val seen = Attachments.note(listOf(photo), listOf(photo), canSee = true, canHear = true)
        assertTrue(seen.endsWith("[image: a.jpg at /ws/Photos/a.jpg] (attached)"))
    }

    @Test
    fun mediaCountsInTheWindowBudget() {
        val plain = Tokens.estimate(Entry.User("what is this"))
        assertEquals(plain + Attachments.IMAGE_TOKENS, Tokens.estimate(Entry.User("what is this", listOf(photo))))
        assertEquals(12 * Attachments.AUDIO_TOKENS_PER_SECOND + 8, Attachments.tokens(voice))
        // The model hears at most one clip's worth, so a long file costs no more than 30 s.
        assertEquals(30 * Attachments.AUDIO_TOKENS_PER_SECOND + 8, Attachments.tokens(longVoice))
    }

    @Test
    fun finishedTurnsReplayWithoutMediaButTheOpenTurnKeepsIt() {
        val t = Transcript()
        t.begin("what is in this photo" + Attachments.note(listOf(photo), listOf(photo), true, true), listOf(photo))
        t.add(Entry.Model("A cat."))
        t.finish()
        t.begin("and this one?", listOf(photo2))
        val replay = ContextManager().select(t, 10_000)
        val users = replay.entries.filterIsInstance<Entry.User>()
        assertEquals(2, users.size)
        assertEquals(emptyList<Attachment>(), users[0].attachments)
        assertTrue(users[0].text.contains("[image: a.jpg at /ws/Photos/a.jpg]"))
        assertEquals(listOf(photo2), users[1].attachments)
        // So a rebuilt conversation never holds more images than one request may carry.
        assertTrue(replay.entries.filterIsInstance<Entry.User>().sumOf { it.attachments.size } <= Attachments.MAX_IMAGES)
    }

    @Test
    fun theLoopSendsTheRequestWithItsAttachments() = runBlocking {
        val sent = mutableListOf<Entry>()
        val chat = object : ModelChat {
            override suspend fun send(input: Entry, onText: (String) -> Unit): ModelReply {
                sent += input
                return if (sent.size == 1) ModelReply("", listOf(Call("detect_objects", mapOf("path" to "/ws/Photos/a.jpg")))) else ModelReply("Two people.")
            }

            override fun invalidate() {}
        }
        val t = Transcript()
        val r = AgentLoop().run(chat, t, "how many people?", {}, listOf(photo)) { c -> ToolOut(c.name, "2 person") }
        assertEquals("Two people.", r.answer)
        assertEquals(Entry.User("how many people?", listOf(photo)), sent.first())
        assertEquals(listOf(photo), (t.turns.single().entries.first() as Entry.User).attachments)
    }
}
