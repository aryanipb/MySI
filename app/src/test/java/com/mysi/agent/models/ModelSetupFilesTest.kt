package com.mysi.agent.models

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps the manifest and the notices in step with the model setup feature. */
class ModelSetupFilesTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun setupScreenIsPrivate() {
        val block = Regex("<activity\\s+android:name=\"\\.models\\.ModelSetupActivity\"[^>]*/>").find(manifest)?.value
        assertTrue("ModelSetupActivity is not registered", block != null)
        assertTrue(block!!.contains("android:exported=\"false\""))
    }

    @Test
    fun noNewPermissions() {
        val permissions = Regex("<uses-permission android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.READ_CONTACTS",
                "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION", "android.permission.POST_NOTIFICATIONS",
                "android.permission.VIBRATE", "com.android.alarm.permission.SET_ALARM", "android.permission.CAMERA", "android.permission.RECORD_AUDIO",
            ),
            permissions,
        )
    }

    @Test
    fun noticesNameEveryCatalogueModelWithItsLicenceAndCommit() {
        val notices = File("../THIRD_PARTY_NOTICES.md").readText()
        assertTrue(notices.contains("## Models"))
        for (m in ModelFixtures.builtIn.models) {
            val row = notices.lines().firstOrNull { it.contains("`${m.file}`") } ?: error("THIRD_PARTY_NOTICES.md has no row for ${m.file}")
            assertTrue(row, row.contains("https://huggingface.co/${m.repo}/tree/${m.revision}"))
            assertTrue(row, row.contains(m.licence))
        }
    }
}
