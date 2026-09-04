package com.yg.dsh.idea.runtime

import com.yg.dsh.idea.util.JsonCodec
import com.intellij.openapi.diagnostic.Logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object LegacySessionMigrator {

    private val LOG = Logger.getInstance(LegacySessionMigrator::class.java)
    private const val PROJCACHE_FILE = "session_projcache.json"

    fun migrateProject(oldGlobalHome: Path, isolatedHome: Path, projectPath: String): Int {
        if (projectPath.isBlank()) return 0
        val key = dshProjectKey(projectPath)
        val srcDir = oldGlobalHome.resolve("sessions").resolve(key)
        if (!Files.isDirectory(srcDir)) return 0

        var copied = 0
        Files.newDirectoryStream(srcDir).use { entries ->
            for (entry in entries) {
                if (!Files.isDirectory(entry)) continue
                val dstDir = isolatedHome.resolve("sessions").resolve(key).resolve(entry.fileName.toString())
                if (Files.exists(dstDir)) continue
                try {
                    copyRecursively(entry, dstDir)
                    copied++
                } catch (e: Exception) {
                    LOG.warn("failed to migrate legacy session ${entry.fileName} for $projectPath", e)
                }
            }
        }
        if (copied > 0) {
            LOG.info("migrated $copied legacy session dir(s) for $projectPath -> ${isolatedHome.resolve("sessions").resolve(key)}")
        }
        return copied
    }

    fun migrateProjectionCache(oldGlobalHome: Path, isolatedHome: Path, projectPath: String): Int {
        if (projectPath.isBlank()) return 0
        val src = oldGlobalHome.resolve("storages").resolve(PROJCACHE_FILE)
        if (!Files.isRegularFile(src)) return 0

        val srcDoc = JsonCodec.decodeObject(Files.readString(src, StandardCharsets.UTF_8))
        val srcTables = srcDoc["tables"] as? Map<*, *> ?: return 0
        val srcSessions = srcTables["sessions"] as? Map<*, *> ?: return 0

        val want = projectPath.replace('\\', '/')

        val picked = LinkedHashMap<String, Any?>()
        for ((sessionId, sessionRow) in srcSessions) {
            val row = sessionRow as? Map<*, *> ?: continue
            val identity = row["identity"] as? Map<*, *> ?: continue
            val cwd = identity["cwd"] as? String ?: continue
            if (cwd.replace('\\', '/') == want) picked[sessionId.toString()] = row
        }
        if (picked.isEmpty()) return 0

        val dst = isolatedHome.resolve("storages").resolve(PROJCACHE_FILE)
        val merged: LinkedHashMap<String, Any?> = if (Files.isRegularFile(dst)) {
            val existing = JsonCodec.decodeObject(Files.readString(dst, StandardCharsets.UTF_8))
            val existingTables = existing["tables"] as? Map<*, *>
            val existingSessions = existingTables?.get("sessions") as? Map<*, *>
            @Suppress("UNCHECKED_CAST")
            LinkedHashMap(existingSessions?.entries?.associate { it.key.toString() to it.value } ?: emptyMap())
        } else {
            LinkedHashMap()
        }
        val before = merged.size
        picked.forEach { (k, v) -> merged.putIfAbsent(k, v) }
        val added = merged.size - before
        if (added == 0) return merged.size

        val outDoc = LinkedHashMap<String, Any?>()
        outDoc["unit"] = LinkedHashMap<String, Any?>().apply {
            put("name", "session_projcache")
            put("version", 3)
        }
        outDoc["global"] = null
        outDoc["tables"] = LinkedHashMap<String, Any?>().apply {
            put("sessions", merged)
        }
        try {
            Files.createDirectories(dst.parent)
            Files.writeString(dst, JsonCodec.encode(outDoc), StandardCharsets.UTF_8)
            LOG.info("migrated ${merged.size} projection-cache session row(s) for $projectPath -> $dst (added $added)")
        } catch (e: Exception) {
            LOG.warn("failed to migrate projection cache for $projectPath", e)
        }
        return merged.size
    }

    private fun copyRecursively(src: Path, dst: Path) {
        Files.createDirectories(dst)
        Files.walk(src).use { stream ->
            stream.forEach { p ->
                val rel = src.relativize(p)
                val target = dst.resolve(rel)
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    fun dshProjectKey(cwd: String): String {
        if (cwd.isEmpty()) return "--root--"
        val sb = StringBuilder()
        var separatorRun = false
        for (ch in cwd) {
            val code = ch.code
            when {
                ch == '/' || ch == '\\' || ch == ':' -> {
                    if (!separatorRun) sb.append('-')
                    separatorRun = true
                }
                ch != '~' && (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '.' || ch == '_' || ch == '-') -> {
                    sb.append(ch)
                    separatorRun = false
                }
                else -> {
                    sb.append('~').append(code.toString(16).uppercase().padStart(4, '0'))
                    separatorRun = false
                }
            }
        }
        val readable = sb.toString().replace(Regex("^-+"), "").ifEmpty { "root" }.take(251)
        return "--$readable--"
    }
}