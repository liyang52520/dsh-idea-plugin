package com.yg.dsh.idea.mcp

import com.yg.dsh.idea.util.TextUtils
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

class SentSelectionQueue(
    private val maxEntries: Int = 10,
    private val maxBytes: Int = TextUtils.MAX_BYTES,
) {

    data class Item(
        val id: String,
        val filePath: String?,
        val language: String?,
        val selection: String,
        val lineStart: Int,
        val lineEnd: Int,
        val ts: Long,
    )

    private val queue = ConcurrentLinkedDeque<Item>()
    private val seq = AtomicLong(0L)

    fun push(
        filePath: String?,
        language: String?,
        selection: String,
        lineStart: Int = 0,
        lineEnd: Int = 0,
        ts: Long = System.currentTimeMillis(),
    ): String {
        val id = "s${seq.incrementAndGet()}"
        val capped = TextUtils.truncateUtf8(selection, maxBytes)
        queue.addLast(Item(id, filePath, language, capped, lineStart, lineEnd, ts))
        while (queue.size > maxEntries) queue.pollFirst()
        return id
    }

    fun latest(): Item? = queue.peekLast()
    fun size(): Int = queue.size
}
