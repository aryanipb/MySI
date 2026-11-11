package com.mysi.agent.skills.core

/** A document in the knowledge base. */
data class KbDoc(val id: Long, val name: String, val path: String, val type: String, val chunks: Int, val chars: Long, val added: Long)

/** A stored passage with the name of its document and its location inside it. */
data class KbChunk(val id: Long, val document: String, val location: String, val text: String)

/** One passage to store: its text, location and term counts. */
class KbChunkInput(val location: String, val text: String, val terms: Map<String, Int>) {
    val length = terms.values.sum()
}

/**
 * Storage for the knowledge base. The SQLite version lives in the Android layer; tests use an
 * in-memory one. [replace] must be atomic: a document is either fully indexed or not at all.
 */
interface KbStore : TermIndex {
    fun documents(): List<KbDoc>

    /** Stores [doc] and its chunks, replacing any document with the same name. Returns its id. */
    fun replace(name: String, path: String, type: String, chars: Long, added: Long, chunks: List<KbChunkInput>): Long

    fun remove(name: String): Boolean

    fun chunks(ids: List<Long>): List<KbChunk>

    fun totalChunks(): Int
}

/** Indexing and BM25 search over local documents, quoting where every passage came from. */
class KnowledgeBase(private val store: KbStore, private val clock: () -> Long = System::currentTimeMillis) {
    data class Passage(val document: String, val location: String, val text: String, val score: Double)

    data class Added(val name: String, val chunks: Int, val replaced: Boolean)

    /** Replaceable so an embedding ranker can be plugged in later. */
    var ranker: Ranker = Bm25Ranker(store)

    fun add(name: String, path: String, type: DocType, parts: List<Part>): Added {
        if (parts.sumOf { it.text.length.toLong() } > MAX_DOC_CHARS)
            throw IllegalArgumentException("$name is too long for the knowledge base (over ${MAX_DOC_CHARS / 1_000_000} million characters).")
        val chunks = Chunker.chunk(parts)
        if (chunks.isEmpty()) throw IllegalArgumentException("$name has no text to index (it may be a scanned image).")
        if (chunks.size > MAX_CHUNKS_PER_DOC) throw IllegalArgumentException("$name is too long for the knowledge base (over $MAX_CHUNKS_PER_DOC passages).")
        val existing = store.documents().firstOrNull { it.name == name }
        if (existing == null && store.documents().size >= MAX_DOCS) throw IllegalArgumentException("The knowledge base already holds $MAX_DOCS documents. Remove some with kb_remove first.")
        if (store.totalChunks() - (existing?.chunks ?: 0) + chunks.size > MAX_TOTAL_CHUNKS)
            throw IllegalArgumentException("The knowledge base is full ($MAX_TOTAL_CHUNKS passages). Remove documents with kb_remove first.")
        val inputs = chunks.map { KbChunkInput(it.location, it.text, Tokens.counts(it.text)) }
        store.replace(name, path, type.label, chunks.sumOf { it.text.length.toLong() }, clock(), inputs)
        return Added(name, chunks.size, existing != null)
    }

    fun list(): List<KbDoc> = store.documents().sortedBy { it.name.lowercase() }

    /** Removes a document by exact name, or by a unique case-insensitive match. */
    fun remove(name: String): String? {
        val docs = store.documents()
        val target = docs.firstOrNull { it.name == name }
            ?: docs.filter { it.name.equals(name, true) }.singleOrNull()
            ?: docs.filter { it.name.contains(name, true) }.singleOrNull()
            ?: return null
        return if (store.remove(target.name)) target.name else null
    }

    fun search(query: String, count: Int): List<Passage> {
        val ranked = ranker.rank(query, count.coerceIn(1, 10)).filter { it.score > 0 }
        if (ranked.isEmpty()) return emptyList()
        val byId = store.chunks(ranked.map { it.id }).associateBy { it.id }
        return ranked.mapNotNull { s -> byId[s.id]?.let { Passage(it.document, it.location, it.text, s.score) } }
    }

    companion object {
        const val MAX_DOCS = 500
        const val MAX_DOC_CHARS = 12_000_000L
        const val MAX_CHUNKS_PER_DOC = 20_000
        const val MAX_TOTAL_CHUNKS = 200_000

        fun format(passages: List<Passage>, maxChars: Int = 3_000): String {
            if (passages.isEmpty()) return "No passages match. Try other words, or check kb_list for what is indexed."
            val per = (maxChars / passages.size).coerceAtLeast(300)
            return passages.mapIndexed { i, p ->
                val text = p.text.replace(Regex("\\s+"), " ").let { if (it.length > per) it.take(per - 1).trimEnd() + "…" else it }
                "[${i + 1}] ${p.document}, ${p.location}:\n$text"
            }.joinToString("\n\n")
        }
    }
}

/** A saved memory. */
data class MemoryItem(val id: Long, val text: String, val topic: String, val created: Long, val updated: Long)

interface MemoryStore {
    fun all(): List<MemoryItem>

    fun get(id: Long): MemoryItem?

    fun insert(text: String, topic: String, now: Long): Long

    fun update(id: Long, text: String, topic: String, now: Long): Boolean

    fun delete(id: Long): Boolean
}

/**
 * Durable facts the user approved. The model may only save through a confirmed tool call; the
 * future Memory screen calls [list], [update] and [delete] directly.
 */
class MemoryBook(private val store: MemoryStore, private val clock: () -> Long = System::currentTimeMillis) {
    data class Saved(val id: Long, val duplicate: Boolean)

    fun clean(text: String) = text.trim().replace(Regex("\\s+"), " ")

    fun cleanTopic(topic: String) = topic.trim().replace(Regex("\\s+"), " ").take(40).ifEmpty { "general" }

    fun remember(text: String, topic: String): Saved {
        val t = clean(text)
        require(t.isNotEmpty()) { "The memory is empty." }
        require(t.length <= MAX_CHARS) { "A memory can be at most $MAX_CHARS characters; save one short fact at a time." }
        store.all().firstOrNull { it.text.equals(t, true) }?.let { old ->
            store.update(old.id, old.text, cleanTopic(topic), clock())
            return Saved(old.id, true)
        }
        require(store.all().size < MAX_COUNT) { "Memory is full ($MAX_COUNT facts). Ask the user which ones to forget." }
        return Saved(store.insert(t, cleanTopic(topic), clock()), false)
    }

    fun recall(query: String, count: Int = 5): List<MemoryItem> {
        val all = store.all()
        val index = MemoryIndex()
        all.forEach { index.add(it.id, it.text + " " + it.topic) }
        val byId = all.associateBy { it.id }
        return Bm25Ranker(index).rank(query, count.coerceIn(1, 20)).filter { it.score > 0 }.mapNotNull { byId[it.id] }
    }

    fun list(offset: Int = 0, limit: Int = 50): List<MemoryItem> =
        store.all().sortedByDescending { it.updated }.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 200))

    fun count() = store.all().size

    fun get(id: Long) = store.get(id)

    fun update(id: Long, text: String, topic: String): Boolean {
        val t = clean(text)
        require(t.isNotEmpty() && t.length <= MAX_CHARS) { "A memory must be 1 to $MAX_CHARS characters." }
        return store.update(id, t, cleanTopic(topic), clock())
    }

    fun delete(id: Long): Boolean = store.delete(id)

    companion object {
        const val MAX_CHARS = 500
        const val MAX_COUNT = 2_000

        fun format(items: List<MemoryItem>): String =
            items.joinToString("\n") { "#${it.id} [${it.topic}] ${it.text}" }
    }
}
