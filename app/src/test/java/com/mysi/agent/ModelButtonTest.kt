package com.mysi.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelButtonTest {
    @Test
    fun theButtonSaysWhatItWillDo() {
        assertEquals("Get a model", ModelButton.label(ready = false, setupPending = false))
        assertEquals("Change model", ModelButton.label(ready = true, setupPending = false))
        // An unfinished download needs the setup screen, whether or not a model is loaded meanwhile.
        assertEquals("Show download", ModelButton.label(ready = false, setupPending = true))
        assertEquals("Show download", ModelButton.label(ready = true, setupPending = true))
    }

    @Test
    fun theSetupResultIsFollowed() {
        // Activity.RESULT_OK = -1, RESULT_CANCELED = 0, ModelSetupActivity.RESULT_USE_OWN_FILE = RESULT_FIRST_USER = 1.
        assertEquals(ModelButton.After.Loaded, ModelButton.afterSetup(-1, setupPending = false))
        // OK wins even when the user then started a second download and left.
        assertEquals(ModelButton.After.Loaded, ModelButton.afterSetup(-1, setupPending = true))
        assertEquals(ModelButton.After.PickOwnFile, ModelButton.afterSetup(1, setupPending = false))
        assertEquals(ModelButton.After.DownloadContinues, ModelButton.afterSetup(0, setupPending = true))
        assertEquals(ModelButton.After.Nothing, ModelButton.afterSetup(0, setupPending = false))
    }

    @Test
    fun anInterruptedDownloadReopensSetupOnlyOnce() {
        assertTrue(ModelButton.openSetupOnItsOwn(setupPending = true, alreadyShown = false))
        assertFalse(ModelButton.openSetupOnItsOwn(setupPending = true, alreadyShown = true))
        assertFalse(ModelButton.openSetupOnItsOwn(setupPending = false, alreadyShown = false))
    }

    @Test
    fun theNoModelTextNoLongerSendsTheUserToTheInternet() {
        assertFalse(ModelButton.NO_MODEL.contains("huggingface", ignoreCase = true))
        assertTrue(ModelButton.NO_MODEL.contains(ModelButton.GET))
        assertTrue(ModelButton.DOWNLOADING.contains(ModelButton.SHOW_DOWNLOAD))
    }

    @Test
    fun theChatScreenFollowsTheSetupContract() {
        val src = File("src/main/java/com/mysi/agent/MainActivity.kt").readText()
        // Its primary action opens model setup; the file picker is reached through setup's result.
        assertTrue(src.contains("button(actions, ModelButton.GET, true, ::openSetup)"))
        assertFalse(src.contains("\"Load model\" to ::pickModel"))
        assertTrue(src.contains("ModelButton.After.PickOwnFile -> pickModel()"))
        assertTrue(src.contains("ModelSetupActivity.hasPendingSetup(this)"))
        assertTrue(src.contains("ModelSetupActivity.pruneUnusedModels(app)"))
        // The import is marked before its native load, and cleared after.
        assertTrue(src.indexOf("store.markImportLoading(dest)") in 0 until src.indexOf("Agent.load(dest.absolutePath)"))
        assertTrue(src.indexOf("store.clearImportLoading()") > src.indexOf("Agent.load(dest.absolutePath)"))
    }
}
