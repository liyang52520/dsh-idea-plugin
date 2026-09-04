package com.yg.dsh.idea.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SentSelectionQueueTest {

    @Test
    fun `latest returns most recent push`() {
        val q = SentSelectionQueue()
        q.push("A.kt", "kotlin", "aaa")
        q.push("B.kt", "kotlin", "bbb")
        val latest = q.latest()
        assertNotNull(latest)
        assertEquals("B.kt", latest!!.filePath)
        assertEquals("bbb", latest.selection)
        assertEquals(2, q.size())
    }

    @Test
    fun `empty queue returns null`() {
        val q = SentSelectionQueue()
        assertNull(q.latest())
        assertEquals(0, q.size())
    }

    @Test
    fun `ring capacity keeps newest ten`() {
        val q = SentSelectionQueue(maxEntries = 3)
        for (i in 1..10) q.push("F$i.kt", null, "sel$i")
        assertEquals(3, q.size())
        assertEquals("F10.kt", q.latest()!!.filePath)
    }

    @Test
    fun `oversize selection is truncated with marker`() {
        val q = SentSelectionQueue(maxBytes = 1024)
        val big = "x".repeat(5000)
        val id = q.push("Big.kt", null, big)
        val item = q.latest()!!
        assertTrue(item.selection.length < big.length, "should truncate")
        assertTrue(item.selection.contains("已截断"), "should note truncation")
        assertEquals(id, item.id)
    }

    @Test
    fun `ascii truncation keeps nearly the full byte budget`() {
        // 旧实现按字符数 maxBytes/4 截断：纯 ASCII 时 1024 字节预算只保留 256 字节。
        val q = SentSelectionQueue(maxBytes = 1024)
        q.push("A.kt", null, "x".repeat(5000))
        val bytes = q.latest()!!.selection.toByteArray(Charsets.UTF_8).size
        assertTrue(bytes in 950..1024, "expected near-budget truncation, got $bytes bytes")
    }

    @Test
    fun `truncation never splits a multibyte character`() {
        val q = SentSelectionQueue(maxBytes = 100)
        q.push("中.kt", null, "中".repeat(200))
        val body = q.latest()!!.selection.substringBefore("\n…")
        assertTrue(body.isNotEmpty())
        assertTrue(body.all { it == '中' }, "body must contain only whole characters")
    }

    @Test
    fun `ids are unique and sequential`() {
        val q = SentSelectionQueue()
        val id1 = q.push("A.kt", null, "1")
        val id2 = q.push("A.kt", null, "2")
        assertEquals("s1", id1)
        assertEquals("s2", id2)
    }
}
