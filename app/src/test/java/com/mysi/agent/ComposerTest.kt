package com.mysi.agent

import com.mysi.agent.PushToTalk.Action
import com.mysi.agent.PushToTalk.Mode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerTest {
    private fun image(n: Int) = Composer.Chip(Attachment.Image(File("/ws/Photos/p$n.jpg")))

    private val voice = Composer.Chip(Attachment.Audio(File("/ws/Recordings/voice_1.wav"), 7.4))
    private val pdf = Composer.Chip(Attachment.Document(File("/cache/picked/1/report.pdf")))

    // ---------- draft (the chips above the text box) ----------

    @Test
    fun draftKeepsOrderAndRemovesChips() {
        val d = Draft()
        assertTrue(d.isEmpty)
        assertNull(d.add(image(1)))
        assertNull(d.add(pdf))
        assertNull(d.add(voice))
        assertEquals(listOf(image(1).attachment, pdf.attachment, voice.attachment), d.attachments())
        assertTrue(d.remove(pdf))
        assertFalse(d.remove(pdf))
        assertEquals(listOf(image(1), voice), d.chips)
        d.clear()
        assertTrue(d.isEmpty)
    }

    @Test
    fun draftRefusesDuplicatesAndTooManyPhotosOrItems() {
        val d = Draft()
        repeat(Attachments.MAX_IMAGES) { assertNull(d.add(image(it))) }
        assertEquals(0, d.imageSlots)
        assertEquals("A message can carry at most 4 photos.", d.add(image(99)))
        assertEquals("report.pdf is already attached.", d.add(pdf).let { d.add(pdf) })
        // The same file twice, even with a different hint, is one attachment.
        assertEquals("p0.jpg is already attached.", d.add(image(0).copy(hint = "Seen: 1 cup")))
        repeat(Composer.MAX_ITEMS) { d.add(Composer.Chip(Attachment.Document(File("/ws/f$it.txt")))) }
        assertEquals(Composer.MAX_ITEMS, d.chips.size)
        assertEquals("A message can carry at most 10 attachments.", d.add(Composer.Chip(Attachment.Document(File("/ws/one-more.txt")))))
    }

    @Test
    fun draftKnowsWhenASpokenRecordingWaits() {
        val d = Draft()
        d.add(Composer.Chip(Attachment.Audio(File("/cache/picked/1/song.mp3"))))
        assertFalse(d.hasSpoken)
        d.add(voice.copy(spoken = true))
        assertTrue(d.hasSpoken)
        d.remove(voice.copy(spoken = true))
        assertFalse(d.hasSpoken)
    }

    @Test
    fun picturesNeedAModelThatCanSeeFromAnyPicker() {
        val photo = image(1).attachment
        assertEquals(Composer.Gate.photos(ready = false, canSee = false), Composer.refusal(photo, ready = false, canSee = false))
        assertNotNull(Composer.refusal(photo, ready = true, canSee = false))
        assertNull(Composer.refusal(photo, ready = true, canSee = true))
        // Files and recordings are named by path, so they need no sense.
        assertNull(Composer.refusal(pdf.attachment, ready = false, canSee = false))
        assertNull(Composer.refusal(voice.attachment, ready = true, canSee = false))
    }

    @Test
    fun draftUpdatesAChipInPlace() {
        val d = Draft()
        val bare = Composer.Chip(Attachment.Audio(File("/cache/picked/1/song.mp3")))
        d.add(pdf)
        d.add(bare)
        d.update(Composer.Chip(Attachment.Audio(File("/cache/picked/1/song.mp3"), 65.0)))
        assertEquals("song.mp3 · 1:05", Composer.label(d.chips[1]))
        assertEquals(pdf, d.chips[0])
    }

    // ---------- what is sent ----------

    @Test
    fun pickedFilesBecomeTheRightKindOfAttachment() {
        val f = File("/cache/picked/1/x")
        assertEquals(Attachment.Image(f), Composer.attachmentFor(f, "image/jpeg"))
        assertEquals(Attachment.Audio(f), Composer.attachmentFor(f, "audio/mpeg"))
        assertEquals(Attachment.Document(f), Composer.attachmentFor(f, "application/pdf"))
        assertEquals(Attachment.Document(f), Composer.attachmentFor(f, "video/mp4"))
        assertEquals(Attachment.Document(f), Composer.attachmentFor(f, "application/octet-stream"))
    }

    @Test
    fun requestTextUsesWhatWasTypedOrAStandIn() {
        assertNull(Composer.requestText("   ", emptyList(), spoken = false))
        assertEquals("hello", Composer.requestText("  hello ", emptyList(), spoken = false))
        assertEquals("what is this?", Composer.requestText("what is this?", listOf(image(1).attachment), spoken = false))
        assertEquals(Composer.VOICE_PROMPT, Composer.requestText("", listOf(image(1).attachment, voice.attachment), spoken = true))
        assertEquals(Composer.ATTACH_PROMPT, Composer.requestText(" ", listOf(pdf.attachment), spoken = false))
        // A picked audio file is content, not the user's request: it never gets the voice prompt.
        assertEquals(Composer.ATTACH_PROMPT, Composer.requestText("", listOf(voice.attachment), spoken = false))
        assertEquals("summarise it", Composer.requestText("summarise it", listOf(voice.attachment), spoken = true))
        // The chat shows what the user wrote, not the stand-ins.
        assertEquals("", Composer.shownText(Composer.VOICE_PROMPT))
        assertEquals("", Composer.shownText(Composer.ATTACH_PROMPT))
        assertEquals("hello", Composer.shownText("hello"))
    }

    // ---------- chips and meter ----------

    @Test
    fun clockAndLabels() {
        assertEquals("0:00", Composer.clock(-3.0))
        assertEquals("0:07", Composer.clock(7.9))
        assertEquals("1:05", Composer.clock(65.0))
        assertEquals("12:40", Composer.clock(760.0))
        assertEquals("voice_1.wav · 0:07", Composer.label(voice))
        assertEquals("report.pdf", Composer.label(pdf))
        assertEquals("p1.jpg", Composer.label(image(1)))
        assertEquals("Recording voice_1.wav · 0:07", Composer.describe(voice))
        assertEquals("Photo p1.jpg. Seen: 2 person", Composer.describe(image(1).copy(hint = Composer.seenHint(" 2 person "))))
        assertEquals("File report.pdf", Composer.describe(pdf))
        assertEquals("", Composer.seenHint(null))
        assertEquals("", Composer.seenHint("  "))
    }

    @Test
    fun meterSpreadsSpeechLevels() {
        assertEquals(0f, Composer.meter(0f), 0f)
        assertEquals(0f, Composer.meter(-1f), 0f)
        assertEquals(1f, Composer.meter(1f), 0f)
        assertEquals(1f, Composer.meter(0.5f), 0f)
        val quiet = Composer.meter(0.02f)
        val speech = Composer.meter(0.1f)
        assertTrue(quiet > 0.1f && quiet < speech && speech < 1f)
    }

    @Test
    fun gatesExplainWhyASenseIsUnavailable() {
        assertEquals("Load a model first, then you can attach photos.", Composer.Gate.photos(ready = false, canSee = false))
        assertTrue(Composer.Gate.photos(ready = true, canSee = false)!!.startsWith("This model cannot see pictures."))
        assertNull(Composer.Gate.photos(ready = true, canSee = true))
        assertNotNull(Composer.Gate.camera(ready = false, canSee = true))
        assertNull(Composer.Gate.camera(ready = true, canSee = true))
        assertEquals("Load a model first, then you can talk to MySI.", Composer.Gate.mic(ready = false, canHear = false))
        assertEquals("This model cannot hear. Load a Gemma model with audio to talk to MySI.", Composer.Gate.mic(ready = true, canHear = false))
        assertNull(Composer.Gate.mic(ready = true, canHear = true))
    }

    // ---------- hold to talk ----------

    @Test
    fun holdAndReleaseSends() {
        val p = PushToTalk()
        assertEquals(Action.START, p.down(1_000))
        assertEquals(Mode.HOLD, p.mode)
        assertEquals(Action.NONE, p.down(1_100))
        assertEquals(Action.FINISH, p.up(3_000))
        assertFalse(p.recording)
    }

    @Test
    fun aQuickTapKeepsRecordingUntilTheNextTap() {
        val p = PushToTalk()
        assertEquals(Action.START, p.down(0))
        assertEquals(Action.NONE, p.up(150))
        assertEquals(Mode.TAP, p.mode)
        assertTrue(p.recording)
        // Sliding does not cancel a hands-free recording; the Cancel button does.
        assertEquals(Action.NONE, p.slideAway())
        assertEquals(Action.NONE, p.down(5_000))
        assertEquals(Action.FINISH, p.up(5_100))
        assertEquals(Mode.IDLE, p.mode)
    }

    @Test
    fun slidingAwayOrCancelDiscards() {
        val p = PushToTalk()
        p.down(0)
        assertEquals(Action.DISCARD, p.slideAway())
        assertEquals(Mode.IDLE, p.mode)
        // The finger lifting after the slide changes nothing.
        assertEquals(Action.NONE, p.up(2_000))
        p.down(0)
        p.up(10)
        assertEquals(Action.DISCARD, p.cancel())
        assertEquals(Action.NONE, p.cancel())
    }

    @Test
    fun clicksToggleForTalkBack() {
        val p = PushToTalk()
        assertEquals(Action.START, p.click())
        assertEquals(Mode.TAP, p.mode)
        assertEquals(Action.FINISH, p.click())
        assertEquals(Mode.IDLE, p.mode)
        // A click while holding also finishes.
        p.down(0)
        assertEquals(Action.FINISH, p.click())
    }

    @Test
    fun aRecordingThatEndsByItselfResets() {
        val p = PushToTalk()
        p.down(0)
        p.ended()
        assertEquals(Mode.IDLE, p.mode)
        assertEquals(Action.NONE, p.up(5_000))
        assertEquals(Action.START, p.down(6_000))
    }

    @Test
    fun aFinishBeforeRecordingStartsIsNotLost() {
        var stops = 0
        val latch = StopLatch { stops++ }
        // Released before the recorder's thread started: its first stop() is overwritten there...
        latch.finish()
        assertEquals(1, stops)
        // ...so every chunk after a finish stops it again.
        latch.chunk()
        latch.chunk()
        assertEquals(3, stops)
        assertEquals(0.2, latch.seconds, 1e-9)
        val running = StopLatch { stops = -100 }
        repeat(25) { running.chunk() }
        assertFalse(running.finishing)
        assertEquals(2.5, running.seconds, 1e-9)
        assertEquals(3, stops)
    }
}
