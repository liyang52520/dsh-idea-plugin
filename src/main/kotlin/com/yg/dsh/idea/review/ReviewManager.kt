package com.yg.dsh.idea.review

import com.yg.dsh.idea.util.HashUtils
import com.yg.dsh.idea.util.ProjectIgnoreRules
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.StandardCharsets

class ReviewManager(
    private val project: Project,
    private val snapshot: SnapshotManager,
) {

    /**
     * 全量刷新对比。文件遍历 + MD5 包在读动作里；必须先调用 [refreshVfs]、
     * 且在非 EDT 线程调用（见 ReviewDialog 的 runProcessWithProgressSynchronously）。
     */
    fun refreshChanges(): List<SnapshotDiff.Change> {
        val current = ApplicationManager.getApplication()
            .runReadAction(Computable { currentMd5Map() })
        val baseline = snapshot.entries().mapValues { it.value.md5 }
        return SnapshotDiff.diff(baseline, current)
    }

    /** 同步刷新项目 VFS（后台线程调用；不持有读/写动作）。 */
    fun refreshVfs() {
        project.basePath?.let { base ->
            LocalFileSystem.getInstance().findFileByPath(base)?.let { root ->
                VfsUtil.markDirtyAndRefresh(false, false, true, root)
            }
        }
    }

    fun showDiff(change: SnapshotDiff.Change) {
        val basePath = project.basePath ?: return
        val currentVf = LocalFileSystem.getInstance().findFileByPath("$basePath/${change.relativePath}")
        val baselineBytes = snapshot.entryBytesFor(change.relativePath)
        val baselineText = baselineBytes?.toString(StandardCharsets.UTF_8) ?: ""
        val factory = DiffContentFactory.getInstance()
        val title = when (change.type) {
            SnapshotDiff.ChangeType.MODIFIED -> "Modified: ${change.relativePath}"
            SnapshotDiff.ChangeType.NEW -> "New: ${change.relativePath}"
            SnapshotDiff.ChangeType.DELETED -> "Deleted: ${change.relativePath}"
        }
        val left: com.intellij.diff.contents.DiffContent = when (change.type) {
            SnapshotDiff.ChangeType.NEW -> factory.createEmpty()
            else -> factory.create(baselineText)
        }
        val right: com.intellij.diff.contents.DiffContent = when (change.type) {
            SnapshotDiff.ChangeType.DELETED -> factory.createEmpty()
            else -> currentVf?.let { factory.create(project, it) } ?: factory.createEmpty()
        }
        val request = SimpleDiffRequest(title, left, right, "Baseline (${change.baselineMd5?.take(8) ?: "—"})", "Current")
        DiffManager.getInstance().showDiff(project, request)
    }

    fun restoreFile(change: SnapshotDiff.Change): Boolean {
        if (change.type == SnapshotDiff.ChangeType.NEW) {
            deleteCurrent(change.relativePath)
            return true
        }
        val bytes = snapshot.entryBytesFor(change.relativePath) ?: return false
        val vf = currentVirtual(change.relativePath)
        if (vf == null) {
            createFile(change.relativePath, bytes)
            return true
        }
        return writeVirtual(vf, bytes)
    }

    fun restoreAll(changes: List<SnapshotDiff.Change>): Int {
        var ok = 0
        for (c in changes) if (restoreFile(c)) ok++
        project.basePath?.let { base ->
            LocalFileSystem.getInstance().findFileByPath(base)?.let { root ->
                VfsUtil.markDirtyAndRefresh(false, false, true, root)
            }
        }
        return ok
    }

    fun ignoreChange(change: SnapshotDiff.Change) = snapshot.drop(change.relativePath)
    fun rebuildBaseline() = snapshot.rebuild()

    private fun currentVirtual(rel: String): VirtualFile? =
        project.basePath?.let { LocalFileSystem.getInstance().findFileByPath("$it/$rel") }

    private fun writeVirtual(vf: VirtualFile, bytes: ByteArray): Boolean = try {
        FileDocumentManager.getInstance().saveAllDocuments()
        VfsUtil.saveText(vf, String(bytes, StandardCharsets.UTF_8))
        true
    } catch (e: Exception) {
        LOG.warn("failed to restore ${vf.path}", e)
        false
    }

    private fun deleteCurrent(rel: String): Boolean = try {
        val vf = currentVirtual(rel) ?: return false
        vf.delete(null)
        true
    } catch (e: Exception) {
        LOG.warn("failed to delete $rel", e)
        false
    }

    private fun createFile(rel: String, bytes: ByteArray): Boolean = try {
        val base = project.basePath ?: return false
        val file = java.io.File(base, rel)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        LocalFileSystem.getInstance().refreshAndFindFileByPath(file.absolutePath)
        true
    } catch (e: Exception) {
        LOG.warn("failed to create $rel", e)
        false
    }

    private fun currentMd5Map(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val base = project.basePath ?: return result
        val root = LocalFileSystem.getInstance().findFileByPath(base) ?: return result
        val visitor = object : com.intellij.openapi.vfs.VirtualFileVisitor<Any?>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (file.isDirectory) {
                    return !ProjectIgnoreRules.isIgnoredDir(file.name)
                }
                val rel = FileUtil.getRelativePath(base, file.path, '/')
                if (rel != null && !ProjectIgnoreRules.isIgnoredFile(file.name, file.length)) {
                    result[rel] = try {
                        HashUtils.md5(file.contentsToByteArray())
                    } catch (e: Exception) {
                        ""
                    }
                }
                return true
            }
        }
        VfsUtilCore.visitChildrenRecursively(root, visitor)
        return result
    }

    companion object {
        private val LOG = Logger.getInstance(ReviewManager::class.java)
    }
}