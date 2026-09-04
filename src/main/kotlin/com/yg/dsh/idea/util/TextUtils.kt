package com.yg.dsh.idea.util

import java.nio.charset.StandardCharsets

/**
 * UTF-8 truncation and HTML/JS escaping utilities.
 */
object TextUtils {

    /** 截断上限（64KB），与选中代码发送口径一致。 */
    const val MAX_BYTES = 64 * 1024

    /** 截断注明文本（中文，供 UI 展示）。 */
    const val TRUNCATED_NOTE = "\n…(已截断，超出 64KB)"

    /**
     * 按 UTF-8 字节数截断：逐字符累加实际字节数（ASCII=1、代理对=4、其余 2/3），
     * 在最后一个完整字符边界截断（注明文本计入预算），不拆多字节字符。
     */
    fun truncateUtf8(text: String, maxBytes: Int = MAX_BYTES): String {
        if (text.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) return text
        val budget = (maxBytes - TRUNCATED_NOTE.toByteArray(StandardCharsets.UTF_8).size).coerceAtLeast(0)
        val sb = StringBuilder()
        var used = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val size = when {
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> 4
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                else -> 3
            }
            if (used + size > budget) break
            sb.append(c)
            if (size == 4) sb.append(text[i + 1])
            used += size
            i++
        }
        return sb.toString().trimEnd() + TRUNCATED_NOTE
    }

    /** 最小 HTML 转义（用于 JLabel 的 <html> 前缀场景）。 */
    fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** JS 字符串字面量转义（用于注入 JCEF 页面的脚本）。 */
    fun escapeJs(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}