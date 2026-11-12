package com.mysi.agent.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionReportTest {
    private fun d(label: String, score: Float, l: Float, t: Float, r: Float = l + 10, b: Float = t + 10) = Detection(label, score, Box(l, t, r, b))

    @Test
    fun countsAreExactSortedAndThresholded() {
        val all = listOf(
            d("person", 0.9f, 0f, 0f), d("cup", 0.8f, 5f, 5f), d("person", 0.6f, 50f, 0f), d("person", 0.55f, 90f, 0f),
            d("dog", 0.49f, 0f, 50f), d("???", 0.9f, 0f, 0f), d("chair", 0.7f, 0f, 90f), d("", 0.9f, 0f, 0f),
        )
        val kept = DetectionReport.kept(all)
        assertEquals(5, kept.size)
        assertEquals(listOf("person" to 3, "chair" to 1, "cup" to 1), DetectionReport.counts(kept))
        assertEquals("3 person, 1 chair, 1 cup", DetectionReport.summary(kept))
        assertEquals("", DetectionReport.summary(emptyList()))
    }

    @Test
    fun formatListsCountsBoxesInReadingOrderAndTheAnnotatedCopy() {
        val all = listOf(d("cup", 0.81f, 300f, 205f, 340f, 260f), d("person", 0.93f, 10.4f, 20.6f, 120f, 400f), d("person", 0.62f, 200f, 22f, 280f, 390f))
        val text = DetectionReport.format(all, 640, 480, "kitchen.jpg", "/ws/Vision/kitchen_objects.jpg")
        assertTrue(text, text.startsWith("Objects detected in kitchen.jpg (640 x 480 pixels), exact counts at 50% confidence or more:\n2 person, 1 cup.\n"))
        val boxes = text.lines().filter { it.startsWith("- ") }
        assertEquals(listOf("- person 93%: 10, 21, 120, 400", "- person 62%: 200, 22, 280, 390", "- cup 81%: 300, 205, 340, 260"), boxes)
        assertTrue(text.contains("Copy with the boxes drawn: /ws/Vision/kitchen_objects.jpg"))
        assertTrue(text.contains("look_at_image"))
    }

    @Test
    fun formatSaysWhenNothingWasFoundAndCapsTheBoxList() {
        val none = DetectionReport.format(listOf(d("person", 0.2f, 0f, 0f)), 100, 100, "a.jpg", null)
        assertTrue(none, none.contains("none of the 80 common object types"))
        assertFalse(none.contains("Copy with"))
        val many = (0 until 45).map { d("car", 0.9f, it * 10f, 0f) }
        val text = DetectionReport.format(many, 1000, 100, "road.jpg", null)
        assertTrue(text.contains("45 car."))
        assertEquals(DetectionReport.MAX_BOXES, text.lines().count { it.startsWith("- car") })
        assertTrue(text.contains("(5 more boxes not listed)"))
    }
}

class SoundReportTest {
    private fun w(sec: Double, vararg labels: Pair<String, Float>) = SoundWindow((sec * 1000).toLong(), labels.toList())

    @Test
    fun neighbouringWindowsMergeIntoSpans() {
        val windows = listOf(
            w(0.0, "Speech" to 0.9f, "Music" to 0.2f),
            w(0.975, "Speech" to 0.8f),
            w(1.95, "Dog" to 0.7f, "Speech" to 0.35f),
            w(4.875, "Speech" to 0.6f),
            w(5.85, "Dog" to 0.95f),
        )
        val heard = SoundReport.summarize(windows)
        assertEquals(listOf("Speech", "Dog"), heard.map { it.label })
        val speech = heard[0]
        assertEquals(4, speech.windows)
        assertEquals(0.9f, speech.peak)
        assertEquals(listOf(0L..2925L, 4875L..5850L), speech.spans)
        assertEquals(listOf(1950L..2925L, 5850L..6825L), heard[1].spans)
    }

    @Test
    fun formatReadsLikeATimeline() {
        val text = SoundReport.format(listOf(w(0.0, "Siren" to 0.8f), w(0.975, "Siren" to 0.7f)), "street.m4a", 2.0, truncated = false)
        assertTrue(text, text.startsWith("Sounds in street.m4a (0:02 long):\n- Siren: 0:00-0:01 (peak 80%)\n"))
        assertTrue(text.contains("transcribe_audio"))
        val quiet = SoundReport.format(emptyList(), "q.wav", 1.0, truncated = true)
        assertTrue(quiet, quiet.contains("only the start was analysed") && quiet.contains("nothing recognised"))
        assertEquals("1:01:05", SoundReport.clock(3_665_000))
        assertEquals("0:59", SoundReport.clock(59_999))
    }
}
