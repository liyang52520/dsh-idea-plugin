package com.yg.dsh.idea.util

import com.intellij.openapi.diagnostic.Logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object FileUtils {
    private val LOG = Logger.getInstance(FileUtils::class.java)

    fun writeUtf8(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content, StandardCharsets.UTF_8)
        if (path.fileName.toString() == Constants.CREDENTIALS_FILE) {
            chmod600(path)
        }
    }

    fun chmod600(path: Path) {
        try {
            path.toFile().setReadable(false, false)
            path.toFile().setWritable(false, false)
            path.toFile().setExecutable(false, false)
            path.toFile().setReadable(true, true)
            path.toFile().setWritable(true, true)
        } catch (e: Exception) {
            LOG.warn("failed to chmod 600 $path", e)
        }
    }
}