package com.mysi.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultStoreTest {
    private val text = (0 until 6_000).joinToString("") { ('a' + it % 26).toString() }

    @Test
    fun shortResultsPassThroughUnstored() {
        val store = ResultStore()
        val out = store.present("get_datetime", "Monday")
        assertEquals(ToolOut("get_datetime", "Monday", "Monday"), out)
        assertNull(store.get(1))
    }

    @Test
    fun longResultsGetExcerptAndHandle() {
        val store = ResultStore()
        val out = store.present("read_web_page", text)
        assertTrue(out.text.startsWith(text.take(ResultStore.EXCERPT_CHARS)))
        assertTrue(out.text.endsWith("[result id=1, 4000 more chars; call read_result(id=1, offset=2000)]"))
        assertTrue(out.stub.endsWith("[result id=1, 5700 more chars; call read_result(id=1, offset=300)]"))
        assertEquals(text, store.get(1))
    }

    @Test
    fun readResultPagesToTheEnd() {
        val store = ResultStore()
        val id = store.put(text)
        val p1 = store.read(id, 2_000)
        assertTrue(p1.startsWith(text.substring(2_000, 4_500)))
        assertTrue(p1.endsWith("call read_result(id=1, offset=4500)]"))
        val p2 = store.read(id, 4_500)
        assertTrue(p2.startsWith(text.substring(4_500)))
        assertTrue(p2.endsWith("[end of result id=1]"))
        // Paging covers the text exactly once.
        assertEquals(text.substring(2_000), p1.substringBeforeLast("\n") + p2.substringBeforeLast("\n"))
    }

    @Test
    fun badIdsAndOffsetsExplainThemselves() {
        val store = ResultStore()
        val id = store.put(text)
        assertTrue(store.read(99, 0).startsWith("No result with id=99"))
        assertTrue(store.read(id, 6_000).contains("offset must be from 0 to 5999"))
        assertTrue(store.read(id, -1).contains("offset must be"))
    }

    @Test
    fun oldestResultsAreEvicted() {
        val store = ResultStore(maxEntries = 2)
        val a = store.put("first")
        store.put("second")
        store.put("third")
        assertNull(store.get(a))
        val byChars = ResultStore(maxChars = 10)
        val x = byChars.put("12345678")
        val y = byChars.put("12345678")
        assertNull(byChars.get(x))
        assertEquals("12345678", byChars.get(y))
    }

    @Test
    fun clearForgetsEverything() {
        val store = ResultStore()
        val id = store.put(text)
        store.clear()
        assertNull(store.get(id))
    }
}
