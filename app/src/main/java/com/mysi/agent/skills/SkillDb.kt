package com.mysi.agent.skills

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import com.mysi.agent.search.Posting
import com.mysi.agent.skills.core.KbChunk
import com.mysi.agent.skills.core.KbChunkInput
import com.mysi.agent.skills.core.KbDoc
import com.mysi.agent.skills.core.KbStore
import com.mysi.agent.skills.core.MemoryItem
import com.mysi.agent.skills.core.MemoryStore
import com.mysi.agent.skills.core.Note
import com.mysi.agent.skills.core.PlannerStore
import com.mysi.agent.skills.core.Task
import java.io.File

/**
 * The skills' one SQLite file in the app's private files folder (excluded from backup and
 * device transfer by data_extraction_rules.xml). Only SQL lives here; the logic is in core.
 */
internal class SkillDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx, File(ctx.filesDir, FILE).path, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE kb_docs(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, path TEXT NOT NULL, type TEXT NOT NULL, chunks INTEGER NOT NULL, chars INTEGER NOT NULL, added INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE kb_chunks(id INTEGER PRIMARY KEY, doc_id INTEGER NOT NULL REFERENCES kb_docs(id) ON DELETE CASCADE, location TEXT NOT NULL, text TEXT NOT NULL, tokens INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX kb_chunks_doc ON kb_chunks(doc_id)")
        db.execSQL("CREATE TABLE kb_terms(term TEXT NOT NULL, chunk_id INTEGER NOT NULL REFERENCES kb_chunks(id) ON DELETE CASCADE, tf INTEGER NOT NULL, PRIMARY KEY(term, chunk_id)) WITHOUT ROWID")
        db.execSQL("CREATE INDEX kb_terms_chunk ON kb_terms(chunk_id)")
        db.execSQL("CREATE TABLE memories(id INTEGER PRIMARY KEY, text TEXT NOT NULL, topic TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE tasks(id INTEGER PRIMARY KEY, title TEXT NOT NULL, due INTEGER, due_has_time INTEGER NOT NULL, done INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, completed INTEGER)")
        db.execSQL("CREATE TABLE notes(id INTEGER PRIMARY KEY, title TEXT NOT NULL, text TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first schema; later versions add migrations here.
    }

    companion object {
        private const val FILE = "skills.db"
        private const val VERSION = 1

        @Volatile private var instance: SkillDb? = null

        fun get(ctx: Context): SkillDb =
            instance ?: synchronized(this) { instance ?: SkillDb(ctx.applicationContext).also { instance = it } }
    }
}

/** Runs [block] on a query's cursor and always closes it. */
internal inline fun <T> SQLiteDatabase.read(sql: String, args: Array<String>?, block: (Cursor) -> T): T {
    val c = rawQuery(sql, args)
    try {
        return block(c)
    } finally {
        c.close()
    }
}

internal class SqlKbStore(private val helper: SkillDb) : KbStore {
    private val db get() = helper.writableDatabase

    override val documentCount: Int get() = DatabaseUtils.queryNumEntries(db, "kb_chunks").toInt()

    override val averageLength: Double
        get() = db.read("SELECT AVG(tokens) FROM kb_chunks", null) { c -> if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0) else 0.0 }

    override fun postings(term: String): List<Posting> =
        db.read("SELECT t.chunk_id, t.tf, c.tokens FROM kb_terms t JOIN kb_chunks c ON c.id = t.chunk_id WHERE t.term = ?", arrayOf(term)) { c ->
            val out = ArrayList<Posting>(c.count)
            while (c.moveToNext()) out += Posting(c.getLong(0), c.getInt(1), c.getInt(2))
            out
        }

    override fun documents(): List<KbDoc> =
        db.read("SELECT id, name, path, type, chunks, chars, added FROM kb_docs", null) { c ->
            val out = mutableListOf<KbDoc>()
            while (c.moveToNext()) out += KbDoc(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getLong(5), c.getLong(6))
            out
        }

    override fun replace(name: String, path: String, type: String, chars: Long, added: Long, chunks: List<KbChunkInput>): Long =
        db.transaction {
            // Deleting the old row cascades to its chunks and terms.
            delete("kb_docs", "name = ?", arrayOf(name))
            val docId = insertOrThrow(
                "kb_docs", null,
                ContentValues().apply {
                    put("name", name)
                    put("path", path)
                    put("type", type)
                    put("chunks", chunks.size)
                    put("chars", chars)
                    put("added", added)
                },
            )
            val chunkSql = compileStatement("INSERT INTO kb_chunks(doc_id, location, text, tokens) VALUES (?, ?, ?, ?)")
            val termSql = compileStatement("INSERT INTO kb_terms(term, chunk_id, tf) VALUES (?, ?, ?)")
            try {
                for (ch in chunks) {
                    chunkSql.clearBindings()
                    chunkSql.bindLong(1, docId)
                    chunkSql.bindString(2, ch.location)
                    chunkSql.bindString(3, ch.text)
                    chunkSql.bindLong(4, ch.length.toLong())
                    val chunkId = chunkSql.executeInsert()
                    for ((term, tf) in ch.terms) {
                        termSql.clearBindings()
                        termSql.bindString(1, term)
                        termSql.bindLong(2, chunkId)
                        termSql.bindLong(3, tf.toLong())
                        termSql.executeInsert()
                    }
                }
            } finally {
                chunkSql.close()
                termSql.close()
            }
            docId
        }

    override fun remove(name: String): Boolean = db.delete("kb_docs", "name = ?", arrayOf(name)) > 0

    override fun chunks(ids: List<Long>): List<KbChunk> {
        if (ids.isEmpty()) return emptyList()
        val wanted = ids.take(MAX_IDS)
        val marks = wanted.joinToString(",") { "?" }
        return db.read(
            "SELECT c.id, d.name, c.location, c.text FROM kb_chunks c JOIN kb_docs d ON d.id = c.doc_id WHERE c.id IN ($marks)",
            wanted.map { it.toString() }.toTypedArray(),
        ) { c ->
            val out = mutableListOf<KbChunk>()
            while (c.moveToNext()) out += KbChunk(c.getLong(0), c.getString(1), c.getString(2), c.getString(3))
            out
        }
    }

    override fun totalChunks(): Int = documentCount

    private companion object {
        const val MAX_IDS = 50
    }
}

internal class SqlMemoryStore(private val helper: SkillDb) : MemoryStore {
    private val db get() = helper.writableDatabase

    private fun query(where: String?, args: Array<String>?): List<MemoryItem> =
        db.read("SELECT id, text, topic, created, updated FROM memories" + (where?.let { " WHERE $it" } ?: ""), args) { c ->
            val out = mutableListOf<MemoryItem>()
            while (c.moveToNext()) out += MemoryItem(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4))
            out
        }

    override fun all() = query(null, null)

    override fun get(id: Long) = query("id = ?", arrayOf(id.toString())).firstOrNull()

    override fun insert(text: String, topic: String, now: Long): Long =
        db.insertOrThrow("memories", null, ContentValues().apply {
            put("text", text)
            put("topic", topic)
            put("created", now)
            put("updated", now)
        })

    override fun update(id: Long, text: String, topic: String, now: Long): Boolean =
        db.update("memories", ContentValues().apply {
            put("text", text)
            put("topic", topic)
            put("updated", now)
        }, "id = ?", arrayOf(id.toString())) > 0

    override fun delete(id: Long): Boolean = db.delete("memories", "id = ?", arrayOf(id.toString())) > 0

    // "1" makes SQLite count the deleted rows instead of truncating without a count.
    override fun clear(): Int = db.delete("memories", "1", null)
}

internal class SqlPlannerStore(private val helper: SkillDb) : PlannerStore {
    private val db get() = helper.writableDatabase

    override fun addTask(title: String, due: Long?, dueHasTime: Boolean, now: Long): Long =
        db.insertOrThrow("tasks", null, ContentValues().apply {
            put("title", title)
            if (due != null) put("due", due) else putNull("due")
            put("due_has_time", if (dueHasTime) 1 else 0)
            put("done", 0)
            put("created", now)
        })

    override fun tasks(): List<Task> =
        db.read("SELECT id, title, due, due_has_time, done, created, completed FROM tasks", null) { c ->
            val out = mutableListOf<Task>()
            while (c.moveToNext()) {
                out += Task(
                    c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getLong(2), c.getInt(3) != 0,
                    c.getInt(4) != 0, c.getLong(5), if (c.isNull(6)) null else c.getLong(6),
                )
            }
            out
        }

    override fun completeTask(id: Long, now: Long): Boolean =
        db.update("tasks", ContentValues().apply {
            put("done", 1)
            put("completed", now)
        }, "id = ?", arrayOf(id.toString())) > 0

    override fun addNote(title: String, text: String, now: Long): Long =
        db.insertOrThrow("notes", null, ContentValues().apply {
            put("title", title)
            put("text", text)
            put("created", now)
            put("updated", now)
        })

    override fun notes(): List<Note> =
        db.read("SELECT id, title, text, created, updated FROM notes", null) { c ->
            val out = mutableListOf<Note>()
            while (c.moveToNext()) out += Note(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4))
            out
        }
}
