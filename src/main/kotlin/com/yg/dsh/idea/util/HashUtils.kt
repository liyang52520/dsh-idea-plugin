package com.yg.dsh.idea.util

import java.security.MessageDigest

object HashUtils {

    fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    fun md5(s: String): String = md5(s.toByteArray(Charsets.UTF_8))
}