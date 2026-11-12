package com.mysi.agent.skills.core

import com.mysi.agent.search.Posting

/** In-memory stand-ins for the SQLite stores, so the logic above SQL is tested on the JVM. */
class FakeKbStore : KbStore {
    private val docs = LinkedHashMap<String, KbDoc>()
    private val chunks = LinkedHashMap<Long, Triple<Long, KbChunkInput, String>>()
    private var nextDoc = 1L
    private var nextChunk = 1L

    override val documentCount get() = chunks.size
    override val averageLength get() = if (chunks.isEmpty()) 0.0 else chunks.values.sumOf { it.second.length }.toDouble() / chunks.size

    override fun postings(term: String) =
        chunks.mapNotNull { (id, c) -> c.second.terms[term]?.let { Posting(id, it, c.second.length) } }

    override fun documents() = docs.values.toList()

    override fun replace(name: String, path: String, type: String, chars: Long, added: Long, chunks: List<KbChunkInput>): Long {
        remove(name)
        val id = nextDoc++
        docs[name] = KbDoc(id, name, path, type, chunks.size, chars, added)
        for (c in chunks) this.chunks[nextChunk++] = Triple(id, c, name)
        return id
    }

    override fun remove(name: String): Boolean {
        val d = docs.remove(name) ?: return false
        chunks.entries.removeIf { it.value.first == d.id }
        return true
    }

    override fun chunks(ids: List<Long>) = ids.mapNotNull { id -> chunks[id]?.let { KbChunk(id, it.third, it.second.location, it.second.text) } }

    override fun totalChunks() = chunks.size
}

class FakeMemoryStore : MemoryStore {
    private val items = LinkedHashMap<Long, MemoryItem>()
    private var next = 1L

    override fun all() = items.values.toList()

    override fun get(id: Long) = items[id]

    override fun insert(text: String, topic: String, now: Long): Long {
        val id = next++
        items[id] = MemoryItem(id, text, topic, now, now)
        return id
    }

    override fun update(id: Long, text: String, topic: String, now: Long): Boolean {
        val old = items[id] ?: return false
        items[id] = old.copy(text = text, topic = topic, updated = now)
        return true
    }

    override fun delete(id: Long) = items.remove(id) != null

    override fun clear(): Int = items.size.also { items.clear() }
}

class FakePlannerStore : PlannerStore {
    private val tasks = LinkedHashMap<Long, Task>()
    private val notes = LinkedHashMap<Long, Note>()
    private var next = 1L

    override fun addTask(title: String, due: Long?, dueHasTime: Boolean, now: Long): Long {
        val id = next++
        tasks[id] = Task(id, title, due, dueHasTime, false, now, null)
        return id
    }

    override fun tasks() = tasks.values.toList()

    override fun completeTask(id: Long, now: Long): Boolean {
        val t = tasks[id] ?: return false
        tasks[id] = t.copy(done = true, completed = now)
        return true
    }

    override fun addNote(title: String, text: String, now: Long): Long {
        val id = next++
        notes[id] = Note(id, title, text, now, now)
        return id
    }

    override fun notes() = notes.values.toList()
}
