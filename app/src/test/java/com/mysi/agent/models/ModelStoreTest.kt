package com.mysi.agent.models

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelStoreTest {
    private class MapKv : KeyValue {
        val map = HashMap<String, String>()

        override fun get(key: String) = map[key]

        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    private val root = Files.createTempDirectory("store").toFile().apply { deleteOnExit() }
    private val files = File(root, "files").apply { mkdirs() }
    private val external = File(root, "external").apply { mkdirs() }
    private val kv = MapKv()
    private val store = ModelStore(files, external, kv)
    private val e2b = ModelFixtures.entry("gemma-4-e2b")

    private fun pending(stamp: Long = 1): PendingDownload {
        val (d, f) = store.plan(e2b, stamp)!!
        return PendingDownload(42, e2b.id, e2b.name, e2b.sizeBytes, e2b.sha256, d.path, f.path, wifiOnly = true)
    }

    private fun touch(f: File) = f.apply { parentFile.mkdirs(); writeText("x") }

    @Test
    fun planPutsEachModelInItsOwnFolder() {
        val (d, f) = store.plan(e2b, 1234)!!
        assertEquals(File(external, "model-downloads/1234/gemma-4-E2B-it.litertlm"), d)
        assertEquals(File(external, "models/1234/gemma-4-E2B-it.litertlm"), f)
        assertNull(ModelStore(files, null, kv).plan(e2b, 1))
    }

    @Test
    fun pendingSurvivesARoundTrip() {
        val p = pending()
        store.savePending(p)
        assertEquals(p, ModelStore(files, external, kv).loadPending())
        store.clearPending()
        assertNull(store.loadPending())
    }

    @Test
    fun damagedOrForeignPendingIsDropped() {
        kv.put("pending_download", "{not json")
        assertNull(store.loadPending())
        assertTrue(kv.map.isEmpty())
        kv.put("pending_download", "{\"downloadId\":1}")
        assertNull(store.loadPending())
        // Paths outside the model folders are never trusted (so never deleted).
        store.savePending(pending().copy(finalPath = "/data/data/com.mysi.agent/files/important.db"))
        assertNull(store.loadPending())
        store.savePending(pending().copy(downloadPath = File(external, "model-downloads/../../x").path))
        assertNull(store.loadPending())
    }

    @Test
    fun moveRenamesWithinExternalStorage() {
        val p = pending()
        touch(File(p.downloadPath))
        assertTrue(store.move(p))
        assertTrue(File(p.finalPath).isFile)
        assertFalse(File(p.downloadPath).exists())
        // Nothing to move the second time.
        assertFalse(store.move(p))
    }

    @Test
    fun discardLeavesNothingBehind() {
        val p = pending()
        touch(File(p.downloadPath))
        touch(File(p.finalPath))
        store.discard(p)
        assertFalse(File(p.downloadPath).parentFile.exists())
        assertFalse(File(p.finalPath).parentFile.exists())
        assertTrue(store.downloadsDir!!.exists() && store.modelsDir!!.exists())
    }

    @Test
    fun keepOnlyDeletesEveryOtherCopyIncludingImports() {
        val keep = touch(File(external, "models/5/new.litertlm"))
        val oldDownloaded = touch(File(external, "models/3/old.litertlm"))
        val partial = touch(File(external, "model-downloads/4/partial.litertlm"))
        val imported = touch(File(files, "models/2/picked.litertlm"))
        val unrelated = touch(File(files, "workspace/notes.txt"))
        store.keepOnly(keep)
        assertTrue(keep.isFile)
        assertFalse(oldDownloaded.exists())
        assertFalse(partial.exists())
        assertFalse(imported.exists())
        assertTrue(unrelated.isFile)
    }

    @Test
    fun pruneKeepsTheLoadedModelTheDownloadAndImports() {
        val loaded = touch(File(external, "models/5/in-use.litertlm"))
        val stale = touch(File(external, "models/3/stale.litertlm"))
        val p = pending(9)
        val active = touch(File(p.downloadPath))
        val orphan = touch(File(external, "model-downloads/8/orphan.litertlm"))
        val imported = touch(File(files, "models/2/picked.litertlm"))
        store.pruneUnused(loaded.path, p)
        assertTrue(loaded.isFile)
        assertTrue(active.isFile)
        assertTrue(imported.isFile)
        assertFalse(stale.exists())
        assertFalse(orphan.exists())
        // With an imported model in use, every downloaded copy is unused.
        store.pruneUnused(imported.path, null)
        assertFalse(loaded.exists())
        assertFalse(active.exists())
        assertTrue(imported.isFile)
    }

    @Test
    fun anImportThatNeverFinishedLoadingIsDeletedOnceWithAMessage() {
        val older = touch(File(files, "models/1/old.litertlm"))
        val crashed = touch(File(files, "models/2/embeddinggemma.litertlm"))
        store.markImportLoading(crashed)
        // A new process: the load never returned.
        val fresh = ModelStore(files, external, kv)
        assertEquals(
            "MySI closed while starting embeddinggemma.litertlm, so the file was deleted to be safe. Try again, or choose another model.",
            fresh.recoverInterruptedImport(older.path),
        )
        assertFalse(crashed.parentFile.exists())
        assertTrue(older.isFile)
        // Never retried, never told twice.
        assertNull(fresh.recoverInterruptedImport(older.path))
        assertTrue(kv.map.isEmpty())
    }

    @Test
    fun anImportThatLoadedIsKeptEvenIfTheMarkWasLeft() {
        val loaded = touch(File(files, "models/2/good.litertlm"))
        store.markImportLoading(loaded)
        assertNull(store.recoverInterruptedImport(loaded.path))
        assertTrue(loaded.isFile)
        assertTrue(kv.map.isEmpty())
    }

    @Test
    fun aClearedOrForeignMarkDeletesNothing() {
        val loaded = touch(File(files, "models/2/good.litertlm"))
        store.markImportLoading(loaded)
        store.clearImportLoading()
        assertNull(store.recoverInterruptedImport(null))
        assertTrue(loaded.isFile)
        // Only a folder of the import directory is ever deleted.
        val elsewhere = touch(File(root, "elsewhere/3/x.litertlm"))
        val download = touch(File(external, "models/4/d.litertlm"))
        for (f in listOf(elsewhere, download, File(files, "models/top.litertlm"))) {
            store.markImportLoading(f)
            assertNull(store.recoverInterruptedImport(null))
        }
        assertTrue(elsewhere.isFile)
        assertTrue(download.isFile)
        assertTrue(kv.map.isEmpty())
    }

    @Test
    fun withoutExternalStorageNothingBreaks() {
        val s = ModelStore(files, null, kv)
        s.pruneUnused(null, null)
        s.keepOnly(touch(File(files, "models/1/m.litertlm")))
        assertNotNull(s.importDir)
        assertNull(s.loadPending())
    }
}
