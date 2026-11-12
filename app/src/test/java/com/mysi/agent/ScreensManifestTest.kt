package com.mysi.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat, Skills and Memory screens add no permission, no exported entry and no speech service. */
class ScreensManifestTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun thePermissionsAreExactlyThePlayStoreSet() {
        val declared = Regex("<uses-permission android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.READ_CONTACTS",
                "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION", "android.permission.POST_NOTIFICATIONS",
                "android.permission.VIBRATE", "com.android.alarm.permission.SET_ALARM", "android.permission.CAMERA", "android.permission.RECORD_AUDIO",
            ),
            declared,
        )
    }

    @Test
    fun theSkillsAndMemoryScreensAreNotExported() {
        for (name in listOf(".SkillsActivity", ".MemoryActivity")) {
            val decl = manifest.substringAfter("android:name=\"$name\"").substringBefore("/>")
            assertTrue(decl, decl.contains("android:exported=\"false\""))
            assertFalse(decl, decl.contains("intent-filter"))
        }
    }

    @Test
    fun theMicrophoneNeverUsesTheSystemSpeechRecogniser() {
        // RecognizerIntent can send the user's voice to Google; MySI records on the phone instead.
        val src = File("src/main/java/com/mysi/agent").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse(src.contains("RecognizerIntent"))
        assertFalse(src.contains("android.speech.SpeechRecognizer"))
    }
}
