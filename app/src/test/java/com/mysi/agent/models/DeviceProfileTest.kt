package com.mysi.agent.models

import com.mysi.agent.models.ModelFixtures.GIB
import com.mysi.agent.models.ModelFixtures.phone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {
    @Test
    fun ramUsesTheAdvertisedSizeWhenKnown() {
        assertEquals(8, phone(8, 7.4).ramGb)
        assertEquals(12, phone(12, 11.2).ramGb)
    }

    @Test
    fun withoutAdvertisedSizeTotalRoundsUpToACommonSize() {
        fun total(gib: Double) = phone(8, gib).copy(advertisedRamBytes = 0).ramGb
        assertEquals(4, total(3.6))
        assertEquals(6, total(5.5))
        assertEquals(8, total(7.4))
        assertEquals(12, total(11.2))
        assertEquals(16, total(14.9))
        assertEquals(8, total(8.0))
        assertEquals(40, total(39.5))
    }

    @Test
    fun chipNameFallsBackHonestly() {
        assertEquals("Qualcomm SM8650", phone(8, 7.4).chip)
        assertEquals("Google Tensor G3", phone(8, 7.4, socManufacturer = "Google", socModel = "Tensor G3").chip)
        val unknown = phone(8, 7.4, socManufacturer = "unknown", socModel = "unknown").copy(hardware = "")
        assertEquals("Unknown chip", unknown.chip)
        assertEquals("ranchu", phone(8, 7.4, socManufacturer = "", socModel = "").copy(hardware = "ranchu").chip)
    }

    @Test
    fun sixtyFourBitOnly() {
        assertTrue(phone(8, 7.4).supported64Bit)
        assertTrue(phone(8, 7.4, abis = listOf("x86_64", "x86")).supported64Bit)
        assertFalse(phone(8, 7.4, abis = listOf("armeabi-v7a")).supported64Bit)
    }

    @Test
    fun advertisedSizeRoundsToWholeGb() {
        assertEquals(6, phone(6, 5.5).copy(advertisedRamBytes = 6 * GIB - 100).ramGb)
    }
}
