package com.mysi.agent.models

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Checks, in plain Kotlin and before any native call, that a file is a `.litertlm` chat model the
 * engine can start.
 *
 * Why: LiteRT-LM 0.18.1 aborts the whole process (SIGABRT, "Section not found: LlmMetadataProto",
 * inside `nativeCreateEngine`) when it is given a `.litertlm` that is not a chat model, for example
 * an embedding model. A Kotlin exception cannot catch that, so the file is checked first.
 *
 * The layout read here is LiteRT-LM's own, as written and read by `schema/core/litertlm_read.cc`
 * (`ReadHeaderFromLiteRTLM`) and `schema/core/litertlm_header_schema.fbs` at tag v0.18.1:
 * - bytes 0..7: the magic `LITERTLM`;
 * - bytes 8..19: major, minor and patch version, little-endian uint32 (the reader only accepts
 *   major version 1);
 * - bytes 20..23: padding;
 * - bytes 24..31: the header end offset, little-endian uint64;
 * - bytes 32..end: a FlatBuffer whose root table is `LiteRTLMMetaData`
 *   { system_metadata, section_metadata }, with `SectionMetadata { objects: [SectionObject] }` and
 *   `SectionObject { items, begin_offset: ulong, end_offset: ulong, data_type: ubyte }`.
 * `data_type` is the `AnySectionDataType` enum; the engine needs a `LlmMetadataProto` (5) section
 * and a `TFLiteModel` (3) section to start a chat model. Every chat model in MySI's catalogue has
 * both; the embedding model that crashed the app has `EmbeddingMetadataProto` (8) instead.
 *
 * Every read is bounds-checked: a damaged or hostile file gives [Result.Damaged], never an
 * exception or a huge allocation.
 */
object LiteRtLmFile {
    sealed class Result {
        object Ok : Result()

        /** Not a `.litertlm` file at all (wrong magic or too short). */
        object NotLiteRtLm : Result()

        /** A `.litertlm` of a format version this app's engine cannot read. */
        data class UnsupportedVersion(val major: Long) : Result()

        /** A `.litertlm` that holds something other than a chat model, e.g. an embedding model. */
        data class NotChatModel(val kind: String) : Result()

        /** The header is cut short or inconsistent. */
        object Damaged : Result()
    }

    const val MAGIC = "LITERTLM"
    const val SUPPORTED_MAJOR = 1L
    private const val FIXED_HEADER = 32

    /** No real header comes near this (the catalogue's largest is under 2 KB); stops absurd reads. */
    const val MAX_HEADER_BYTES = 1 shl 20

    // AnySectionDataType values, in schema order.
    const val TFLITE_MODEL = 3
    const val LLM_METADATA = 5
    const val EMBEDDING_METADATA = 8
    const val TTS_METADATA = 10
    const val ASR_METADATA = 11

    fun check(file: File): Result =
        try {
            file.inputStream().use(::check)
        } catch (e: IOException) {
            Result.Damaged
        } catch (e: SecurityException) {
            Result.Damaged
        }

    /** Reads only the header from the start of [input] (a file or a picked document); does not close it. */
    fun check(input: InputStream): Result {
        val fixed = ByteArray(FIXED_HEADER)
        val n = readUpTo(input, fixed)
        if (n < MAGIC.length || String(fixed, 0, MAGIC.length, Charsets.ISO_8859_1) != MAGIC) return Result.NotLiteRtLm
        if (n < FIXED_HEADER) return Result.Damaged
        val end = ByteBuffer.wrap(fixed).order(ByteOrder.LITTLE_ENDIAN).getLong(24)
        val size = end - FIXED_HEADER
        // The version is still worth reporting when it is the reason the rest makes no sense.
        if (size <= 0 || size > MAX_HEADER_BYTES) return versionProblem(fixed) ?: Result.Damaged
        val rest = ByteArray(size.toInt())
        if (readUpTo(input, rest) != rest.size) return Result.Damaged
        return check(fixed + rest)
    }

    /** Checks the first bytes of a file: the 32-byte fixed part followed by the whole header. */
    fun check(header: ByteArray): Result {
        if (header.size < MAGIC.length || String(header, 0, MAGIC.length, Charsets.ISO_8859_1) != MAGIC) return Result.NotLiteRtLm
        if (header.size < FIXED_HEADER) return Result.Damaged
        versionProblem(header)?.let { return it }
        val le = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val end = le.getLong(24)
        if (end <= FIXED_HEADER || end > header.size) return Result.Damaged
        val types = try {
            sectionTypes(ByteBuffer.wrap(header, FIXED_HEADER, (end - FIXED_HEADER).toInt()).slice().order(ByteOrder.LITTLE_ENDIAN))
        } catch (e: IndexOutOfBoundsException) {
            return Result.Damaged
        } catch (e: IllegalArgumentException) {
            return Result.Damaged
        }
        return when {
            LLM_METADATA in types && TFLITE_MODEL in types -> Result.Ok
            EMBEDDING_METADATA in types -> Result.NotChatModel("an embedding model")
            TTS_METADATA in types -> Result.NotChatModel("a speech synthesis model")
            ASR_METADATA in types -> Result.NotChatModel("a speech recognition model")
            types.isEmpty() -> Result.Damaged
            else -> Result.NotChatModel("not a chat model")
        }
    }

    /** A plain sentence for the user, or null when the file is fine. */
    fun message(r: Result, name: String): String? =
        when (r) {
            Result.Ok -> null
            Result.NotLiteRtLm -> "$name isn't a LiteRT-LM model file. Pick a .litertlm chat model, or tap Get a model."
            is Result.UnsupportedVersion -> "$name was made for a newer version of the model engine than MySI has. Check for a MySI update, or pick another model."
            is Result.NotChatModel ->
                if (r.kind == "not a chat model") "$name isn't a chat model, so MySI can't talk with it. Pick a chat model such as Gemma 4, or tap Get a model."
                else "$name is ${r.kind}, not a chat model, so MySI can't talk with it. Pick a chat model such as Gemma 4, or tap Get a model."
            Result.Damaged -> "$name looks damaged or incomplete. Copy it to the phone again, or tap Get a model."
        }

    private fun versionProblem(fixed: ByteArray): Result? {
        val major = ByteBuffer.wrap(fixed).order(ByteOrder.LITTLE_ENDIAN).getInt(8).toLong() and 0xffffffffL
        return if (major != SUPPORTED_MAJOR) Result.UnsupportedVersion(major) else null
    }

    private fun readUpTo(input: InputStream, into: ByteArray): Int {
        var off = 0
        while (off < into.size) {
            val r = input.read(into, off, into.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    /**
     * The `data_type` of every section in the FlatBuffer [fb] (little-endian, position 0 = the
     * buffer's start). Follows the FlatBuffers binary format: a table starts with a signed offset
     * back to its vtable; the vtable lists each field's offset inside the table (0 = absent, so
     * the default applies); references to tables, vectors and strings are unsigned offsets from
     * where they are stored.
     */
    private fun sectionTypes(fb: ByteBuffer): List<Int> {
        val root = ref(fb, 0)
        val sectionMetadata = field(fb, root, 1)?.let { ref(fb, it) } ?: return emptyList()
        val objects = field(fb, sectionMetadata, 0)?.let { ref(fb, it) } ?: return emptyList()
        val count = u32(fb, objects)
        require(count in 0..((fb.limit() - objects - 4) / 4)) { "section count" }
        return (0 until count.toInt()).map { i ->
            val slot = objects + 4 + 4 * i
            val section = ref(fb, slot)
            // data_type is field 3 of SectionObject, a ubyte defaulting to NONE (0).
            field(fb, section, 3)?.let { fb.get(it).toInt() and 0xff } ?: 0
        }
    }

    /** Position of field [index] of the table at [table], or null when the field is absent. */
    private fun field(fb: ByteBuffer, table: Int, index: Int): Int? {
        val vtable = table - fb.getInt(table)
        require(vtable >= 0 && vtable + 4 <= fb.limit()) { "vtable" }
        val vtableSize = fb.getShort(vtable).toInt() and 0xffff
        val entry = 4 + 2 * index
        if (entry + 2 > vtableSize) return null
        val offset = fb.getShort(vtable + entry).toInt() and 0xffff
        if (offset == 0) return null
        val pos = table + offset
        require(pos < fb.limit()) { "field" }
        return pos
    }

    /** Follows the unsigned offset stored at [pos]. */
    private fun ref(fb: ByteBuffer, pos: Int): Int {
        val target = pos.toLong() + u32(fb, pos)
        require(target in 0 until fb.limit()) { "offset" }
        return target.toInt()
    }

    private fun u32(fb: ByteBuffer, pos: Int): Long = fb.getInt(pos).toLong() and 0xffffffffL
}
