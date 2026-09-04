package com.yg.dsh.idea.config

import com.yg.dsh.idea.util.Constants
import com.intellij.openapi.diagnostic.Logger
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CredentialFileWatcher(private val projectCredFile: Path) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private var watchService: java.nio.file.WatchService? = null
    private var executor: java.util.concurrent.ExecutorService? = null

    fun start() {
        if (closed.get()) return
        if (watchService != null) return
        val dir = projectCredFile.parent ?: return
        try {
            val ws = FileSystems.getDefault().newWatchService()
            dir.register(ws, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE)
            watchService = ws
            val ex = Executors.newSingleThreadExecutor { r -> Thread(r, "dsh-cred-sync").apply { isDaemon = true } }
            executor = ex
            ex.execute { loop(ws) }
            LOG.info("watching project credentials: $projectCredFile")
        } catch (e: Exception) {
            LOG.warn("failed to watch project credentials $projectCredFile", e)
        }
    }

    private fun loop(ws: java.nio.file.WatchService) {
        while (!closed.get()) {
            try {
                val key: WatchKey = ws.take()
                if (closed.get()) break
                val fileName = projectCredFile.fileName.toString()
                var relevant = false
                for (event in key.pollEvents()) {
                    val ctx = event.context() as? Path
                    if (ctx != null && ctx.fileName.toString() == fileName) relevant = true
                }
                key.reset()
                if (relevant && Files.isRegularFile(projectCredFile)) {
                    runCatching { onFileChanged() }
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                if (!closed.get()) LOG.warn("credential watch loop error", e)
            }
        }
    }

    internal fun onFileChanged() {
        val projectKey = Credentials.readApiKeyFromCredentialFile(projectCredFile) ?: return
        val globalCred = DshHomeManager.getInstance().globalConfigHome().resolve(Constants.CREDENTIALS_FILE)
        val globalKey = Credentials.readApiKeyFromCredentialFile(globalCred)
        if (projectKey == globalKey) return

        Credentials.writeApiKey(projectKey)
        writeGlobalCredential(globalCred, projectKey)
        LOG.info("dsh Web UI updated API key; synced to global (PasswordSafe + ${globalCred.fileName})")
    }

    private fun writeGlobalCredential(globalCred: Path, key: String) {
        try {
            Files.createDirectories(globalCred.parent)
            Files.writeString(globalCred, "${Constants.DEEPSEEK_API_KEY}: $key\n", StandardCharsets.UTF_8)
        } catch (e: Exception) {
            LOG.warn("failed to write global credential $globalCred", e)
        }
    }

    internal fun resolveSync(projectKey: String?, globalKey: String?): String? =
        if (projectKey.isNullOrEmpty() || projectKey == globalKey) null else projectKey

    fun closeProject() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { watchService?.close() }
        watchService = null
        runCatching { executor?.shutdownNow() }
        executor = null
    }

    override fun close() = closeProject()

    companion object {
        private val LOG = Logger.getInstance(CredentialFileWatcher::class.java)

        private val active = ConcurrentHashMap<String, CredentialFileWatcher>()

        fun register(projectName: String, credFile: Path): CredentialFileWatcher {
            val existing = active[projectName]
            if (existing != null) return existing
            val watcher = CredentialFileWatcher(credFile)
            watcher.start()
            active[projectName] = watcher
            return watcher
        }

        fun release(projectName: String) {
            active.remove(projectName)?.closeProject()
        }
    }
}