package com.yg.dsh.idea.mcp

import com.yg.dsh.idea.util.Constants
import com.yg.dsh.idea.util.JsonCodec
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程内 IDE MCP 终结点：loopback-only HTTP server，与 IDE 同进程、随机端口。
 *
 * 两条路由：
 * - POST /mcp：MCP streamable-http JSON-RPC 终结点（[McpRpcHandler]），DSH 经
 *   ide.yml patch 直连，工具实现直接访问 PSI/编辑器/VFS，无跨进程序列化；
 * - GET /health：诊断探活。
 *
 * 鉴权：所有请求必须携带 X-DSH-IDE-Token（patch 的 headers 配置透传给 DSH）。
 *
 * 终结点随构造即就绪（无独立子进程、无端口发现），[writePatch] 可在 DSH 进程
 * 启动前同步写好，因此不存在"等待 MCP 超时 → 空 patch 静默降级"的失败模式。
 */
class IdeMcpServer(
    private val project: Project,
    private val homeDir: Path,
) : Disposable, HttpHandler {

    private val token: String = randomToken()
    private val server: HttpServer = HttpServer.create(InetSocketAddress(Constants.LOOPBACK_HOST, 0), 0)
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "dsh-ide-mcp").apply { isDaemon = true } }
    private val disposed = AtomicBoolean(false)
    private val sentQueue = SentSelectionQueue()
    private val mcp = McpRpcHandler(project, sentQueue)

    init {
        server.createContext("/", this)
        server.executor = executor
        server.start()
    }

    fun pushSentSelection(filePath: String?, language: String?, selection: String, lineStart: Int = 0, lineEnd: Int = 0): String =
        sentQueue.push(filePath, language, selection, lineStart, lineEnd)

    /** 同步生成 ide.yml（端口在构造时已确定）。 */
    fun writePatch(): Path {
        val patch = PatchGenerator.generate(server.address.port, token)
        val file = homeDir.resolve("ide.yml")
        Files.createDirectories(file.parent)
        Files.writeString(file, patch, StandardCharsets.UTF_8)
        LOG.info("wrote ide.yml (mcpPort=${server.address.port}) -> $file")
        return file
    }

    override fun handle(exchange: HttpExchange) {
        try {
            if (!constantTimeEquals(exchange.requestHeaders.getFirst("X-DSH-IDE-Token"), token)) {
                exchange.respondJson(401, mapOf("error" to "unauthorized", "code" to "unauthorized"))
                return
            }
            val path = exchange.requestURI.path
            when {
                exchange.requestMethod == "GET" && path == "/health" ->
                    exchange.respondJson(200, mapOf("ok" to true, "project" to project.name, "pid" to ProcessHandle.current().pid()))
                exchange.requestMethod == "POST" && path == "/mcp" ->
                    mcp.handlePost(exchange)
                (exchange.requestMethod == "GET" || exchange.requestMethod == "DELETE") && path == "/mcp" ->
                    mcp.handleMethodNotAllowed(exchange)
                else ->
                    exchange.respondJson(404, mapOf("error" to "not found", "code" to "not_found"))
            }
        } catch (e: Exception) {
            LOG.warn("mcp server handler error", e)
            try { exchange.respondJson(500, mapOf("error" to (e.message ?: "internal error"), "code" to "internal")) } catch (_: IOException) {}
        } finally {
            exchange.close()
        }
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        server.stop(0)
        executor.shutdownNow()
    }

    // ---- helpers ----

    private fun constantTimeEquals(a: String?, b: String): Boolean {
        if (a == null) return false
        val digestA = MessageDigest.getInstance("SHA-256").digest(a.toByteArray(StandardCharsets.UTF_8))
        val digestB = MessageDigest.getInstance("SHA-256").digest(b.toByteArray(StandardCharsets.UTF_8))
        return MessageDigest.isEqual(digestA, digestB)
    }

    companion object {
        private val LOG = Logger.getInstance(IdeMcpServer::class.java)

        fun randomToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/** mcp 包内共享的 JSON HTTP 响应 helper（[IdeMcpServer] 与 [McpRpcHandler] 共用）。 */
internal fun HttpExchange.respondJson(status: Int, body: Any) {
    val bytes = JsonCodec.encode(body).toByteArray(StandardCharsets.UTF_8)
    responseHeaders.set("Content-Type", "application/json; charset=utf-8")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
