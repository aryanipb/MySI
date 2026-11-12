package com.mysi.agent.models

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.mysi.agent.Agent
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where model setup stands; the screen draws itself from this. */
sealed class SetupState {
    object Idle : SetupState()

    data class Downloading(
        val name: String,
        val done: Long,
        val total: Long,
        val bytesPerSecond: Double,
        val secondsLeft: Long?,
        /** Why it is paused (waiting for Wi-Fi…), or null while it runs. */
        val paused: String?,
    ) : SetupState()

    data class Verifying(val name: String, val done: Long, val total: Long) : SetupState()

    data class Loading(val name: String) : SetupState()

    /** The short speed test after loading. */
    data class Checking(val name: String) : SetupState()

    data class Done(val modelId: String, val name: String, val path: String, val verdict: FitVerdict?) : SetupState()

    data class Failed(val message: String) : SetupState()

    /** States in which work is still going on. */
    val busy: Boolean get() = this is Downloading || this is Verifying || this is Loading || this is Checking
}

/**
 * Runs one model setup from download to loaded, outliving the screen (a rotation, or the user
 * leaving it) for as long as the process lives. If Android kills the process, the download
 * manager carries on, and [resume] picks the saved [PendingDownload] up again where it stopped:
 * still downloading, finished, moved, or checked. See [DownloadRules.resume].
 *
 * Steps: download (Android's download manager) → move out of the download manager's folder →
 * check size and SHA-256 → load with [Agent.load] → delete other model copies → speed test.
 * Any failure deletes every file of the attempt; a failed load puts the previous model back.
 */
object ModelSetup {
    private const val POLL_MS = 1_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<(SetupState) -> Unit>()
    private var job: Job? = null
    private val meter = SpeedMeter()

    var state: SetupState = SetupState.Idle
        private set

    /** Model work is under way in this process. */
    val running: Boolean get() = job?.isActive == true

    fun observe(listener: (SetupState) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun unobserve(listener: (SetupState) -> Unit) {
        listeners -= listener
    }

    private fun set(s: SetupState) {
        state = s
        listeners.toList().forEach { it(s) }
    }

    private fun post(s: SetupState) = main.post { set(s) }

    /** Clears a finished or failed state, so the screen offers a fresh start. */
    fun acknowledge() {
        if (!running && (state is SetupState.Done || state is SetupState.Failed)) set(SetupState.Idle)
    }

    /** Starts downloading [entry]. [catalog] is used for the speed test's suggestions. */
    fun start(context: Context, entry: ModelEntry, wifiOnly: Boolean, catalog: Catalog) {
        if (running) return
        val app = context.applicationContext
        val store = ModelStore.of(app)
        job = scope.launch {
            val pendingBefore = store.loadPending()
            if (pendingBefore != null) return@launch drive(app, pendingBefore, catalog)
            val stamp = System.currentTimeMillis()
            val plan = store.plan(entry, stamp) ?: return@launch set(SetupState.Failed("Phone storage isn't available right now. Try again later."))
            val free = withContext(Dispatchers.IO) {
                store.pruneUnused(Agent.savedModel, null)
                DeviceProfiles.freeBytes(app, store.externalDir)
            }
            val need = ModelAdvisor.Thresholds.requiredStorage(entry.sizeBytes)
            if (free < need)
                return@launch set(SetupState.Failed("Not enough free storage: ${entry.name} needs ${Format.size(need)} and the phone has ${Format.size(free)}."))
            val id = try {
                withContext(Dispatchers.IO) { ModelDownloader(app).enqueue(entry, plan.first, wifiOnly) }
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { plan.first.parentFile?.deleteRecursively() }
                return@launch set(SetupState.Failed("The download couldn't start. Try again."))
            }
            val p = PendingDownload(id, entry.id, entry.name, entry.sizeBytes, entry.sha256, plan.first.path, plan.second.path, wifiOnly)
            withContext(Dispatchers.IO) { store.savePending(p) }
            meter.reset()
            set(SetupState.Downloading(entry.name, 0, entry.sizeBytes, 0.0, null, null))
            drive(app, p, catalog)
        }
    }

    /** Picks up a saved download after the app was closed or killed. Does nothing when there is none or work is running. */
    fun resume(context: Context, catalog: Catalog) {
        if (running) return
        val app = context.applicationContext
        job = scope.launch {
            val p = withContext(Dispatchers.IO) { ModelStore.of(app).loadPending() } ?: return@launch
            drive(app, p, catalog)
        }
    }

    /** Cancels the download or check in progress and deletes its files. Loading cannot be interrupted. */
    fun cancel(context: Context) {
        val s = state
        if (s is SetupState.Loading || s is SetupState.Checking) return
        val app = context.applicationContext
        scope.launch {
            job?.cancelAndJoin()
            withContext(Dispatchers.IO) {
                val store = ModelStore.of(app)
                store.loadPending()?.let { p ->
                    ModelDownloader(app).remove(p.downloadId)
                    store.discard(p)
                }
                store.clearPending()
            }
            set(SetupState.Idle)
        }
    }

    private suspend fun drive(app: Context, start: PendingDownload, catalog: Catalog) {
        val store = ModelStore.of(app)
        val dm = ModelDownloader(app)
        var p = start
        while (currentCoroutineContext().isActive) {
            val (snap, downloadExists, finalExists) = withContext(Dispatchers.IO) {
                Triple(if (p.moved) DmSnapshot(DmStatus.MISSING, 0, 0, -1) else dm.query(p.downloadId), File(p.downloadPath).isFile, File(p.finalPath).isFile)
            }
            when (val action = DownloadRules.resume(p, snap, downloadExists, finalExists, Agent.savedModel)) {
                ResumeAction.None -> return
                ResumeAction.Finished -> {
                    withContext(NonCancellable + Dispatchers.IO) {
                        dm.remove(p.downloadId)
                        store.clearPending()
                        store.keepOnly(File(p.finalPath))
                    }
                    // A new process has nothing loaded yet; the result contract promises a ready model.
                    if (!Agent.ready) {
                        set(SetupState.Loading(p.modelName))
                        if (!withContext(NonCancellable) { Agent.load(p.finalPath) })
                            return set(SetupState.Failed("MySI couldn't start ${p.modelName}. Try again from the chat screen."))
                    }
                    return set(SetupState.Done(p.modelId, p.modelName, p.finalPath, null))
                }
                ResumeAction.Watch -> {
                    meter.add(snap.done, SystemClock.elapsedRealtime())
                    val total = if (snap.total > 0) snap.total else p.sizeBytes
                    set(SetupState.Downloading(p.modelName, snap.done, total, meter.bytesPerSecond, meter.secondsLeft(snap.done, total), DownloadRules.paused(snap, p.wifiOnly)))
                    delay(POLL_MS)
                }
                ResumeAction.Move -> {
                    val moved = withContext(Dispatchers.IO) { store.move(p) }
                    if (!moved) return discard(app, p, "The model couldn't be saved on this phone. Try again.")
                    p = p.copy(moved = true)
                    withContext(Dispatchers.IO) {
                        store.savePending(p)
                        // The file has left the download manager's folder, so this only forgets the record.
                        dm.remove(p.downloadId)
                    }
                }
                ResumeAction.Verify -> {
                    if (p.moved) withContext(Dispatchers.IO) { dm.remove(p.downloadId) }
                    return verifyAndLoad(app, p, catalog)
                }
                is ResumeAction.Discard -> return discard(app, p, action.message)
            }
        }
    }

    private suspend fun discard(app: Context, p: PendingDownload, message: String) {
        withContext(NonCancellable + Dispatchers.IO) {
            ModelDownloader(app).remove(p.downloadId)
            val store = ModelStore.of(app)
            store.discard(p)
            store.clearPending()
        }
        set(SetupState.Failed(message))
    }

    private suspend fun verifyAndLoad(app: Context, p: PendingDownload, catalog: Catalog) {
        val store = ModelStore.of(app)
        val file = File(p.finalPath)
        set(SetupState.Verifying(p.modelName, 0, p.sizeBytes))
        val me = currentCoroutineContext().job
        val check = withContext(Dispatchers.IO) {
            HashCheck.verify(file, p.sizeBytes, p.sha256, progress = { post(SetupState.Verifying(p.modelName, it, p.sizeBytes)) }, cancelled = { !me.isActive })
        }
        if (check == HashCheck.Result.Cancelled) throw CancellationException("Cancelled while checking")
        if (check != HashCheck.Result.Ok) return discard(app, p, HashCheck.message(check))

        set(SetupState.Loading(p.modelName))
        // Recorded first, so a model that crashes the app is deleted rather than retried (see PendingDownload.loadStarted).
        withContext(NonCancellable + Dispatchers.IO) { store.savePending(p.copy(loadStarted = true)) }
        val previous = Agent.savedModel
        // Loading is not interruptible: the engine starts in native code.
        val loaded = withContext(NonCancellable) { Agent.load(file.path) }
        if (!loaded) {
            withContext(NonCancellable + Dispatchers.IO) {
                store.discard(p)
                store.clearPending()
            }
            // A failed load leaves no model at all, so put the previous one back.
            val restored = withContext(NonCancellable) {
                previous != null && previous != file.path && File(previous).canRead() && Agent.load(previous)
            }
            return set(SetupState.Failed("MySI couldn't start ${p.modelName} on this phone, so it was deleted." + if (restored) " Your previous model is loaded again." else ""))
        }
        withContext(NonCancellable + Dispatchers.IO) {
            store.clearPending()
            store.keepOnly(file)
        }

        val entry = catalog.byId(p.modelId)
        var verdict: FitVerdict? = null
        if (entry != null && !entry.testOnly) {
            set(SetupState.Checking(p.modelName))
            verdict = runCatching {
                val m = FitCheckRunner.measure(app, entry)
                FitCheckRunner.save(app, m)
                FitCheck.decide(m, entry, catalog, withContext(Dispatchers.IO) { DeviceProfiles.read(app, store.externalDir) })
            }.getOrNull()
        }
        set(SetupState.Done(p.modelId, p.modelName, file.path, verdict))
    }
}
