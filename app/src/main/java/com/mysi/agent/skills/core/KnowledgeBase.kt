package com.mysi.agent.skills.core

import com.mysi.agent.search.Bm25Ranker
import com.mysi.agent.search.MemoryIndex
import com.mysi.agent.search.Ranker
import com.mysi.agent.search.TermIndex
import com.mysi.agent.search.Tokens
import java.time.Instant
import java.time.ZoneId

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

    fun list(): List<KbDoc> = store.documents().sortedWith(compareBy<KbDoc> { it.name.lowercase() }.thenBy { it.id })

    /** kb_list's answer from [offset], within the result budget, with the offset of the next page. */
    fun listText(offset: Int, zone: ZoneId): String {
        val docs = list()
        if (docs.isEmpty()) return "The knowledge base is empty. Add files with kb_add."
        val start = offset.coerceAtLeast(0)
        if (start >= docs.size) return "The knowledge base has only ${docs.size} documents."
        val lines = docs.drop(start).map { d ->
            val day = Instant.ofEpochMilli(d.added).atZone(zone).toLocalDate()
            "- ${d.name} (${d.type}, ${d.chunks} passages, added $day)"
        }
        val n = Budget.fit(lines, Budget.CHARS - 120)
        val head = if (start == 0 && n == docs.size) "${docs.size} document${if (docs.size == 1) "" else "s"}:" else "Documents ${start + 1}-${start + n} of ${docs.size}:"
        val text = head + "\n" + lines.take(n).joinToString("\n")
        return if (start + n < docs.size) "$text\n[More: kb_list(offset=${start + n})]" else text
    }

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

        /** Each passage shown gets at least this much text; fewer passages are shown rather than less. */
        private const val MIN_PASSAGE = 300

        /**
         * kb_search's answer: numbered passages with their source, all within [maxChars]. When
         * not every passage fits with [MIN_PASSAGE] characters of text, the weakest are left out
         * and the answer says so.
         */
        fun format(passages: List<Passage>, maxChars: Int = Budget.CHARS): String {
            if (passages.isEmpty()) return "No passages match. Try other words, or check kb_list for what is indexed."
            val heads = passages.mapIndexed { i, p -> "[${i + 1}] ${p.document.take(120)}, ${p.location.take(60)}:\n" }
            fun overhead(n: Int) = heads.take(n).sumOf { it.length + 2 } + NOTE_ROOM
            var n = passages.size
            while (n > 1 && overhead(n) + n * MIN_PASSAGE > maxChars) n--
            val per = ((maxChars - overhead(n)) / n).coerceAtLeast(MIN_PASSAGE)
            val body = (0 until n).joinToString("\n\n") { i ->
                val text = passages[i].text.replace(Regex("\\s+"), " ").let { if (it.length > per) it.take(per - 1).trimEnd() + "…" else it }
                heads[i] + text
            }
            return if (n < passages.size) "$body\n\n(${passages.size - n} weaker passages left out; search with more specific words to see others.)" else body
        }

        private const val NOTE_ROOM = 90
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

    /** Newest change first; ties by number, so paging by offset is stable. */
    fun list(offset: Int = 0, limit: Int = 50): List<MemoryItem> =
        store.all().sortedWith(compareByDescending<MemoryItem> { it.updated }.thenByDescending { it.id })
            .drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 200))

    /** One page for list_memories, within the result budget, with the offset of the next page. */
    fun page(offset: Int): String {
        val total = count()
        if (total == 0) return "No memories saved yet."
        val start = offset.coerceAtLeast(0)
        if (start >= total) return "There are only $total memories."
        val lines = list(start, PAGE).map { line(it) }
        val n = Budget.fit(lines, Budget.CHARS - 120)
        val text = "Memories ${start + 1}-${start + n} of $total:\n" + lines.take(n).joinToString("\n")
        return if (start + n < total) "$text\n[More: list_memories(offset=${start + n})]" else text
    }

    /** recall's answer, within the result budget. */
    fun recallText(query: String, count: Int): String {
        val found = recall(query, count)
        if (found.isEmpty()) return "No saved memory matches '${query.trim()}'."
        val lines = found.map { line(it) }
        val n = Budget.fit(lines, Budget.CHARS - 100)
        return lines.take(n).joinToString("\n") + if (n < found.size) "\n(${found.size - n} more matches not shown; ask a narrower question.)" else ""
    }

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
        const val PAGE = 30

        fun line(m: MemoryItem) = "#${m.id} [${m.topic}] ${m.text}"

        fun format(items: List<MemoryItem>): String = items.joinToString("\n") { line(it) }
    }
}
