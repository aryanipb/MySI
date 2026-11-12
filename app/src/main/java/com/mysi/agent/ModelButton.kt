package com.mysi.agent

import android.app.Activity
import com.mysi.agent.models.ModelSetupActivity

/**
 * The chat screen's model button and status line, and what to do when the model setup screen
 * ([ModelSetupActivity]) closes. Plain logic, so every case is unit-tested.
 */
object ModelButton {
    const val GET = "Get a model"
    const val CHANGE = "Change model"
    const val SHOW_DOWNLOAD = "Show download"

    const val NO_MODEL =
        "No model loaded. Tap Get a model: MySI picks one that suits this phone, downloads it and starts it, or you can use a .litertlm file you already have."
    const val DOWNLOADING =
        "A model is still downloading. MySI starts it when it's done; tap Show download to see how far it has got."

    /** The button's label: an unfinished download comes first, since it needs the setup screen to finish. */
    fun label(ready: Boolean, setupPending: Boolean): String =
        when {
            setupPending -> SHOW_DOWNLOAD
            ready -> CHANGE
            else -> GET
        }

    /** What the chat screen does with the setup screen's result. */
    sealed class After {
        /** A model was loaded during the visit: show it as ready. */
        object Loaded : After()

        /** The user wants to pick a .litertlm file they already have: open the file picker. */
        object PickOwnFile : After()

        /** No new model yet, but a download carries on in the background: say so. */
        object DownloadContinues : After()

        /** The user left without a change. */
        object Nothing : After()
    }

    fun afterSetup(resultCode: Int, setupPending: Boolean): After =
        when (resultCode) {
            Activity.RESULT_OK -> After.Loaded
            ModelSetupActivity.RESULT_USE_OWN_FILE -> After.PickOwnFile
            else -> if (setupPending) After.DownloadContinues else After.Nothing
        }

    /**
     * Whether the chat screen opens the setup screen by itself: when there is no model at all (so
     * a new user gets one without looking for a button), or when a download or its check is
     * unfinished (for example after MySI was closed or killed, so the setup screen can pick it up
     * and finish it). Only once per process: after that, or once the user has left the setup
     * screen, the button and status line offer it instead, so the user is never sent back to a
     * screen they just left.
     */
    fun openSetupOnItsOwn(setupPending: Boolean, alreadyShown: Boolean, noModel: Boolean = false): Boolean =
        (setupPending || noModel) && !alreadyShown

    /**
     * Whether the setup screen starts the recommended download without a tap: only when the chat
     * screen opened it because there was no model, at most once per visit, with nothing already
     * running, a recommendation for this phone, and Wi-Fi (or another unmetered connection), so
     * gigabytes are never spent on mobile data without the user choosing to.
     */
    fun startDownloadOnItsOwn(openedForNoModel: Boolean, alreadyTried: Boolean, idle: Boolean, hasRecommendation: Boolean, unmetered: Boolean): Boolean =
        openedForNoModel && !alreadyTried && idle && hasRecommendation && unmetered

    /**
     * Whether a setup result came from the screen itself. When it closes on its own it sets either
     * [ModelSetupActivity.RESULT_USE_OWN_FILE] or a result with an Intent (finishWithState).
     * RESULT_CANCELED without an Intent is Android cleaning up after the app died with the setup
     * screen open (seen on the emulator when the low-memory killer stopped MySI while a model was
     * starting): the user never left it, so the chat screen may open it once more, where the
     * interrupted setup reports what happened.
     */
    fun closedByUser(resultCode: Int, hasResultIntent: Boolean): Boolean =
        resultCode != Activity.RESULT_CANCELED || hasResultIntent
}
