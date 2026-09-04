package com.yg.dsh.idea.runtime.process

object PortParser {
    private val RE = Regex("""dsh web: http://127\.0\.0\.1:(\d+)""")

    fun parsePort(line: String): Int? =
        RE.find(line)?.groupValues?.get(1)?.toIntOrNull()
}