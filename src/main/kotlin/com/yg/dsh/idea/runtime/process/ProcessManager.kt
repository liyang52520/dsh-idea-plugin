package com.yg.dsh.idea.runtime.process

import com.yg.dsh.idea.runtime.WorkspaceInitializer
import com.yg.dsh.idea.util.Constants
import com.yg.dsh.idea.util.Platform
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ProcessManager(
    private val nodeExe: File,
    private val dshBin: File,
    private val workDir: File,
    private val homeDir: File,
    private val patchFile: File,
    private val projectPath: String = "",
    /** 用户自定义 DSH 启动参数（由调用方从 SettingsState 解析后注入，保持本类与平台服务解耦）。 */
    private val extraArgs: List<String> = emptyList(),
) : Disposable {

    enum class State { STOPPED, STARTING, RUNNING, CRASHED, GAVE_UP }

    interface Listener {
        fun onStateChanged(oldState: State, newState: State) = Unit
        fun onUrlReady(url: String) = Unit
        /** Called after all automatic restart attempts are exhausted. */
        fun onStartupFailed(reason: String) = Unit
    }

    companion object {
        private val LOG = Logger.getInstance(ProcessManager::class.java)
        const val MAX_RESTART_ATTEMPTS = 3
        private val RESTART_DELAYS_MS = longArrayOf(500, 2000, 5000)
        private const val HEALTH_MAX_TRIES = 6
        private const val HEALTH_INTERVAL_MS = 500L
        /** Max time to wait for the DSH process to print its web URL before treating it as a failure. */
        private const val PORT_DISCOVERY_TIMEOUT_MS = 30_000L
        /** How many of the last DSH output lines to retain for crash diagnostics. */
        private const val MAX_OUTPUT_LINES = 30
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { r ->
        Thread(r, "dsh-process").apply { isDaemon = true }
    }
    private val stopRequested = AtomicBoolean(false)
    private val restartScheduled = AtomicBoolean(false)

    @Volatile private var process: Process? = null
    @Volatile private var currentState: State = State.STOPPED
    @Volatile private var endpoint: WebEndpoint? = null
    @Volatile private var lastFailureReason: String? = null
    private var restartAttempts = 0
    private var portTimeoutFuture: ScheduledFuture<*>? = null

    /** Recent DSH stdout/stderr lines, surfaced when the process dies unexpectedly. */
    private val recentOutput = java.util.concurrent.ConcurrentLinkedDeque<String>()

    fun currentState(): State = currentState
    fun webPort(): Int? = endpoint?.port
    fun webUrl(): String? = endpoint?.url

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onStateChanged(State.STOPPED, currentState)
    }

    fun start() {
        synchronized(this) {
            if (currentState == State.RUNNING || currentState == State.STARTING) return
            setState(State.STARTING)
        }
        spawn()
    }

    /** restart 防重入：spawn 在 synchronized 块外，连续两次 restart 可能双 spawn。 */
    private val restarting = AtomicBoolean(false)

    fun restart() {
        if (!restarting.compareAndSet(false, true)) return
        try {
            synchronized(this) {
                stopProcessQuietly()
                restartAttempts = 0
                setState(State.STARTING)
            }
            spawn()
        } finally {
            restarting.set(false)
        }
    }

    override fun dispose() {
        stopRequested.set(true)
        stopProcessQuietly()
        executor.shutdownNow()
    }

    private fun spawn() {
        val cmd = mutableListOf(
            nodeExe.absolutePath,
            dshBin.absolutePath,
            "--profile", "web",
            "--patch", patchFile.absolutePath,
            "--host", Constants.LOOPBACK_HOST,
            "--port", "0",
            "--no-open",
        )
        cmd.addAll(extraArgs)
        val pb = ProcessBuilder(cmd)
        pb.directory(workDir)
        pb.environment()["DSH_HOME"] = homeDir.absolutePath
        pb.redirectErrorStream(true)

        val p = try {
            pb.start()
        } catch (e: Exception) {
            LOG.error("failed to start dsh process", e)
            handleStartupFailure("failed to launch node: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        process = p
        endpoint = null
        recentOutput.clear()
        LOG.info("dsh process started pid=${p.pid()} cwd=${workDir.absolutePath} home=$homeDir")
        readAsync(p.inputStream)
        schedulePortDiscoveryTimeout()
        p.onExit().whenComplete { _, err -> onProcessExit(p, err) }
    }

    private fun readAsync(stream: InputStream) {
        executor.execute {
            stream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    recentOutput.addLast(line.take(500))
                    while (recentOutput.size > MAX_OUTPUT_LINES) recentOutput.pollFirst()
                    if (line.contains("dsh web:")) {
                        LOG.info("[dsh] $line")
                    } else {
                        LOG.debug("[dsh] $line")
                    }
                    PortParser.parse(line)?.let { ep -> onEndpointFound(ep) }
                }
            }
        }
    }

    private fun onEndpointFound(ep: WebEndpoint) {
        if (endpoint != null || stopRequested.get()) return
        endpoint = ep
        portTimeoutFuture?.cancel(false)
        executor.execute { waitHealthy(ep) }
    }

    /**
     * Guards against a process that stays alive but never announces its web URL
     * (e.g. a hung node process or a DSH version that no longer prints the
     * expected banner). Without this the panel would spin forever.
     */
    private fun schedulePortDiscoveryTimeout() {
        portTimeoutFuture?.cancel(false)
        portTimeoutFuture = executor.schedule({
            if (stopRequested.get() || webPort() != null) return@schedule
            if (currentState != State.STARTING) return@schedule
            LOG.warn("dsh did not announce a web port within ${PORT_DISCOVERY_TIMEOUT_MS}ms; killing the process")
            destroyCurrentProcess()
            handleStartupFailure("timed out waiting for the DSH web URL (${PORT_DISCOVERY_TIMEOUT_MS / 1000}s)")
        }, PORT_DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun waitHealthy(ep: WebEndpoint) {
        repeat(HEALTH_MAX_TRIES) {
            if (stopRequested.get()) return
            if (isHealthy(ep.url)) {
                synchronized(this) {
                    restartScheduled.set(false)
                    restartAttempts = 0
                    setState(State.RUNNING)
                }
                LOG.info("dsh web ready: ${ep.origin}")
                listeners.forEach { it.onUrlReady(ep.url) }
                if (projectPath.isNotBlank()) {
                    WorkspaceInitializer.ensureWorkspace(ep.url, projectPath)
                }
                return
            }
            try { Thread.sleep(HEALTH_INTERVAL_MS) } catch (_: InterruptedException) { return }
        }
        LOG.warn("dsh health check timed out on port ${ep.port}")
        if (!stopRequested.get()) {
            destroyCurrentProcess()
            handleStartupFailure("web server did not become healthy on port ${ep.port}")
        }
    }

    /**
     * Health probe. The URL carries the per-process `?token=` and DSH answers
     * 303 (See Other + Set-Cookie) when the token is accepted; redirects must
     * NOT be followed — the redirected GET has no cookie yet and would 401,
     * making a perfectly healthy server look down.
     */
    private fun isHealthy(url: String): Boolean = try {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = false
        val code = conn.responseCode
        conn.disconnect()
        code in 200..399
    } catch (_: Exception) {
        false
    }

    private fun onProcessExit(p: Process, err: Throwable?) {
        if (process !== p) return
        process = null
        portTimeoutFuture?.cancel(false)
        if (stopRequested.get()) {
            synchronized(this) { setState(State.STOPPED) }
        } else {
            val exitCode = runCatching { p.exitValue() }.getOrNull()
            LOG.warn("dsh process exited unexpectedly pid=${p.pid()} exitCode=$exitCode", err)
            val tail = recentOutput.toList()
            if (tail.isNotEmpty()) {
                LOG.warn("dsh last output before exit:\n${tail.joinToString("\n") { "  [dsh] $it" }}")
            }
            handleStartupFailure(
                if (exitCode != null) "dsh process exited with code $exitCode"
                else "dsh process exited unexpectedly"
            )
        }
    }

    /**
     * Records the failure reason, flips the state to CRASHED, and schedules an
     * automatic restart. Once [MAX_RESTART_ATTEMPTS] are exhausted the state
     * becomes GAVE_UP and listeners get [Listener.onStartupFailed], so the UI
     * can surface an error card instead of spinning forever.
     *
     * CRASHED 状态必须覆盖 RUNNING：进程在运行中意外退出（崩溃/被杀）时
     * 若直接 return，状态会永远停留在 RUNNING，既不自动重启也不上报告警，
     * 面板只会守着一个已死的端口。仅 STOPPED（调用方有意停止）时跳过。
     */
    private fun handleStartupFailure(reason: String) {
        if (stopRequested.get()) return
        lastFailureReason = reason
        synchronized(this) {
            if (currentState == State.STOPPED) return
            setState(State.CRASHED)
        }
        scheduleRestart()
    }

    private fun scheduleRestart() {
        if (stopRequested.get()) return
        val exhausted: Boolean
        synchronized(this) {
            if (restartScheduled.get()) return
            exhausted = restartAttempts >= MAX_RESTART_ATTEMPTS
            if (!exhausted) {
                restartAttempts++
                restartScheduled.set(true)
            }
        }
        if (exhausted) {
            val reason = lastFailureReason ?: "unknown error"
            LOG.warn("dsh startup failed after $MAX_RESTART_ATTEMPTS attempts; giving up: $reason")
            synchronized(this) { setState(State.GAVE_UP) }
            listeners.forEach { it.onStartupFailed(reason) }
            return
        }
        val attempt = restartAttempts
        val delay = RESTART_DELAYS_MS[(attempt - 1).coerceAtMost(RESTART_DELAYS_MS.size - 1)]
        LOG.info("scheduling dsh restart attempt $attempt in ${delay}ms")
        executor.schedule({
            restartScheduled.set(false)
            if (stopRequested.get()) return@schedule
            synchronized(this) { setState(State.STARTING) }
            spawn()
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun destroyCurrentProcess() {
        portTimeoutFuture?.cancel(false)
        val p = process ?: return
        process = null
        destroyProcess(p)
    }

    private fun destroyProcess(p: Process) {
        try {
            p.destroy()
            p.waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        if (p.isAlive) killTree(p.pid())
    }

    private fun stopProcessQuietly() {
        portTimeoutFuture?.cancel(false)
        val p = process
        process = null
        if (p != null) destroyProcess(p)
        synchronized(this) { setState(State.STOPPED) }
    }

    private fun killTree(pid: Long) {
        try {
            if (Platform.current().os == Platform.Os.WINDOWS) {
                ProcessBuilder(listOf("taskkill", "/PID", pid.toString(), "/T", "/F"))
                    .start().waitFor(3, TimeUnit.SECONDS)
            } else {
                killProcessTree(pid)
            }
        } catch (e: Exception) {
            LOG.warn("failed to kill process tree $pid", e)
        }
    }

    private fun killProcessTree(pid: Long) {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return
        handle.descendants().forEach { runCatching { it.destroyForcibly() } }
        runCatching { handle.destroyForcibly() }
    }

    private fun setState(newState: State) {
        val old = currentState
        if (old == newState) return
        currentState = newState
        listeners.forEach { it.onStateChanged(old, newState) }
    }
}