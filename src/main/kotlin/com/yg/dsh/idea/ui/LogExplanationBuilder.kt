package com.yg.dsh.idea.ui

import com.yg.dsh.idea.util.TextUtils

object LogExplanationBuilder {

    fun buildMessage(prefix: String, log: String): String {
        if (log.isBlank()) return ""
        val body = TextUtils.truncateUtf8(log)
        return "$prefix\n\n$body"
    }
}