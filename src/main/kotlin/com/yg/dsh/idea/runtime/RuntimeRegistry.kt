package com.yg.dsh.idea.runtime

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks live DSH runtimes (one per project). There is no concurrency cap:
 * every open project may run its own DSH instance; the registry only keeps
 * track of them for lifecycle cleanup and diagnostics.
 */
@Service(Service.Level.APP)
class RuntimeRegistry : Disposable {

    private val running = ConcurrentHashMap<String, Any>()

    companion object {
        private val LOG = Logger.getInstance(RuntimeRegistry::class.java)

        fun getInstance(): RuntimeRegistry =
            ApplicationManager.getApplication().getService(RuntimeRegistry::class.java)
    }

    /**
     * Registers a running instance for [projectName]. Idempotent: re-registering
     * the same project (e.g. a restarted panel) keeps the existing entry.
     */
    fun register(projectName: String, handle: Any) {
        val previous = running.putIfAbsent(projectName, handle)
        if (previous == null) {
            LOG.info("DSH instance registered: $projectName (total=${running.size})")
        }
    }

    fun release(projectName: String) {
        if (running.remove(projectName) != null) {
            LOG.info("DSH instance released: $projectName (total=${running.size})")
        }
    }

    fun isRunning(projectName: String): Boolean = running.containsKey(projectName)
    fun runningCount(): Int = running.size

    override fun dispose() {
        val count = running.size
        running.clear()
        LOG.info("DSH runtime registry disposed ($count instances)")
    }
}
