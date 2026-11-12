package com.mysi.agent.models

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun sizesUseDecimalUnitsLikeAndroid() {
        assertEquals("0 bytes", Format.size(0))
        assertEquals("999 bytes", Format.size(999))
        assertEquals("1 KB", Format.size(1_000))
        assertEquals("165 MB", Format.size(164_626_432))
        assertEquals("999 MB", Format.size(999_499_999))
        assertEquals("1 GB", Format.size(1_000_000_000))
        assertEquals("2.6 GB", Format.size(2_588_147_712))
        assertEquals("3.7 GB", Format.size(3_659_530_240))
        assertEquals("6.9 GB", Format.size(6_883_278_368))
        assertEquals("64 GB", Format.size(64_000_000_000))
        assertEquals("0 bytes", Format.size(-5))
    }

    @Test
    fun memoryUsesBinaryUnitsLikePhoneMakers() {
        assertEquals("2.5 GB", Format.memory(2600L * Format.MIB))
        assertEquals("4 GB", Format.memory(4L * Format.GIB))
        assertEquals("600 MB", Format.memory(600L * Format.MIB))
    }

    @Test
    fun percentNeverReaches100Early() {
        assertEquals(0, Format.percent(0, 100))
        assertEquals(46, Format.percent(1_200, 2_588))
        assertEquals(99, Format.percent(2_588_147_711, 2_588_147_712))
        assertEquals(100, Format.percent(2_588_147_712, 2_588_147_712))
        assertEquals(0, Format.percent(5, 0))
        assertEquals(0, Format.percent(5, -1))
    }

    @Test
    fun progressAndSpeed() {
        assertEquals("1.2 GB of 2.6 GB", Format.progress(1_200_000_000, 2_588_147_712))
        assertEquals("4.2 MB/s", Format.speed(4_200_000.0))
        assertEquals("12 MB/s", Format.speed(12_300_000.0))
        assertEquals("650 KB/s", Format.speed(650_000.0))
        assertEquals("1 KB/s", Format.speed(10.0))
        assertEquals("0 KB/s", Format.speed(0.0))
        assertEquals("0 KB/s", Format.speed(Double.NaN))
    }

    @Test
    fun timeLeftReadsNaturally() {
        assertEquals("Working out time left…", Format.timeLeft(null))
        assertEquals("Working out time left…", Format.timeLeft(-1))
        assertEquals("Less than a minute left", Format.timeLeft(0))
        assertEquals("Less than a minute left", Format.timeLeft(59))
        assertEquals("About 1 min left", Format.timeLeft(60))
        assertEquals("About 4 min left", Format.timeLeft(4 * 60 + 20))
        assertEquals("About 59 min left", Format.timeLeft(59 * 60))
        assertEquals("About 1 h left", Format.timeLeft(3600))
        assertEquals("About 1 h 5 min left", Format.timeLeft(3600 + 5 * 60))
        assertEquals("More than 2 days left", Format.timeLeft(3 * 86_400))
    }

    @Test
    fun secondsAndRam() {
        assertEquals("1.2 s", Format.seconds(1_234))
        assertEquals("10 s", Format.seconds(10_000))
        assertEquals("8 GB", Format.ramGb(8))
    }
}
