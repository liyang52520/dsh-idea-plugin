package com.yg.dsh.idea.review

import com.yg.dsh.idea.util.HashUtils
import com.yg.dsh.idea.util.ProjectIgnoreRules
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class SnapshotManager(private val project: Project) {

    data class Entry(
        val relativePath: String,
        val md5: String,
        val bytes: ByteArray,
    )

    private val snapshot: LinkedHashMap<String, Entry> = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean {
            return size > MAX_SNAPSHOT_ENTRIES
        }
    }
    private val inMemoryBytes = AtomicLong(0)
    private val building = AtomicBoolean(false)

    fun snapshotDir(): Path =
        Path.of(FileUtil.getTempDirectory(), "dsh-idea", "snapshots", FileUtil.sanitizeFileName(project.name))

    fun hasSnapshot(): Boolean = snapshot.isNotEmpty() || Files.exists(indexFile())

    fun buildIfAbsent(): Map<String, Entry> {
        if (building.compareAndSet(false, true)) {
            try {
                if (snapshot.isEmpty() && loadFromDisk()) return snapshot
                if (snapshot.isEmpty()) scanProject()
                saveIndex()
            } finally {
                building.set(false)
            }
        }
        return snapshot
    }

    fun rebuild(): Map<String, Entry> {
        synchronized(snapshot) {
            snapshot.clear()
            inMemoryBytes.set(0)
        }
        deleteSnapshotFiles()
        return buildIfAbsent()
    }

    fun entries(): Map<String, Entry> = synchronized(snapshot) { LinkedHashMap(snapshot) }

    fun entryBytesFor(relativePath: String): ByteArray? {
        val e = synchronized(snapshot) { snapshot[relativePath] } ?: return null
        if (e.bytes.isNotEmpty()) return e.bytes
        return readSpilled(relativePath, e.md5)
    }

    fun drop(relativePath: String) {
        synchronized(snapshot) {
            snapshot.remove(relativePath)?.let { inMemoryBytes.addAndGet(-it.bytes.size.toLong()) }
        }
    }

    private fun scanProject() {
        val base = project.basePath ?: return
        val root = LocalFileSystem.getInstance().findFileByPath(base) ?: return
        val visitor = object : com.intellij.openapi.vfs.VirtualFileVisitor<Any?>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (file.isDirectory) {
                    return !ProjectIgnoreRules.isIgnoredDir(file.name)
                }
                val rel = FileUtil.getRelativePath(root.path, file.path, '/')
                if (rel != null && !ProjectIgnoreRules.isIgnoredFile(file.name, file.length)) {
                    addEntry(rel, file)
                }
                return true
            }
        }
        com.intellij.openapi.vfs.VfsUtilCore.visitChildrenRecursively(root, visitor)
    }

    private fun addEntry(rel: String, file: VirtualFile) {
        val bytes = try { file.contentsToByteArray() } catch (e: Exception) { return }
        val md5 = HashUtils.md5(bytes)
        val entry = Entry(rel, md5, bytes)
        synchronized(snapshot) {
            snapshot[rel]?.let { inMemoryBytes.addAndGet(-it.bytes.size.toLong()) }
            snapshot[rel] = entry
            inMemoryBytes.addAndGet(bytes.size.toLong())
        }
        evictSpillIfNeeded()
    }

    private fun evictSpillIfNeeded() {
        if (inMemoryBytes.get() <= MAX_TOTAL_BYTES) return
        synchronized(snapshot) {
            val it = snapshot.entries.iterator()
            while (it.hasNext() && inMemoryBytes.get() > MAX_TOTAL_BYTES) {
                val (rel, e) = it.next()
                if (e.bytes.isEmpty()) continue
                if (spill(rel, e.md5, e.bytes)) {
                    inMemoryBytes.addAndGet(-e.bytes.size.toLong())
                    snapshot[rel] = Entry(rel, e.md5, ByteArray(0))
                } else {
                    break
                }
            }
        }
    }

    private fun spill(rel: String, md5: String, bytes: ByteArray): Boolean = try {
        Files.createDirectories(contentsDir())
        Files.write(contentsDir().resolve("$md5.bin"), bytes)
        true
    } catch (e: Exception) {
        LOG.warn("failed to spill snapshot content $rel", e)
        false
    }

    private fun readSpilled(rel: String, md5: String): ByteArray? = try {
        val f = contentsDir().resolve("$md5.bin")
        if (Files.exists(f)) Files.readAllBytes(f) else null
    } catch (e: Exception) {
        LOG.warn("failed to read spilled content $rel", e)
        null
    }

    private fun indexFile(): Path = snapshotDir().resolve("index.txt")
    private fun contentsDir(): Path = snapshotDir().resolve("contents")

    private fun saveIndex() {
        try {
            val dir = snapshotDir()
            Files.createDirectories(dir)
            val sb = StringBuilder()
            synchronized(snapshot) {
                for (e in snapshot.values) {
                    sb.append(e.relativePath).append('\u0000').append(e.md5).append('\n')
                }
            }
            Files.writeString(indexFile(), sb.toString())
        } catch (e: Exception) {
            LOG.warn("failed to save snapshot index", e)
        }
    }

    private fun loadFromDisk(): Boolean {
        val index = indexFile()
        if (!Files.exists(index)) return false
        return try {
            var count = 0
            Files.readAllLines(index).forEach { line ->
                val parts = line.split('\u0000')
                if (parts.size == 2) {
                    synchronized(snapshot) {
                        snapshot[parts[0]] = Entry(parts[0], parts[1], ByteArray(0))
                    }
                    count++
                }
            }
            count > 0
        } catch (e: Exception) {
            LOG.warn("failed to load snapshot index", e)
            false
        }
    }

    private fun deleteSnapshotFiles() {
        try {
            val dir = snapshotDir()
            if (Files.exists(dir)) {
                Files.walk(dir).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
                }
            }
        } catch (e: Exception) {
            LOG.warn("failed to delete snapshot files", e)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(SnapshotManager::class.java)
        private const val MAX_TOTAL_BYTES = 200L * 1024 * 1024
        private const val MAX_SNAPSHOT_ENTRIES = 50_000
    }
}