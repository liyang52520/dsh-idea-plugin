package com.yg.dsh.idea.runtime

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.APP)
class RuntimeRegistry : Disposable {

    private val running = ConcurrentHashMap<String, Any>()

    /**
     * Concurrency cap, synced from Settings → Advanced before each acquire.
     * The registry itself stays settings-agnostic (usable in pure-JVM tests).
     */
    @Volatile
    var maxInstances: Int = DEFAULT_MAX_INSTANCES

    companion object {
        private val LOG = Logger.getInstance(RuntimeRegistry::class.java)

        /** Fallback cap when settings have not been synced yet. */
        const val DEFAULT_MAX_INSTANCES = 3

        fun getInstance(): RuntimeRegistry =
            ApplicationManager.getApplication().getService(RuntimeRegistry::class.java)
    }

    fun tryAcquire(projectName: String, handle: Any): Boolean {
        val limit = maxInstances.coerceAtLeast(1)
        val current = running.putIfAbsent(projectName, handle)
        if (current != null) return true
        if (running.size > limit) {
            running.remove(projectName)
            LOG.warn("DSH instance limit reached ($limit); rejected $projectName")
            return false
        }
        LOG.info("DSH instance registered: $projectName (total=${running.size})")
        return true
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