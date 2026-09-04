package com.yg.dsh.idea.runtime

import com.yg.dsh.idea.mcp.PatchGenerator
import com.yg.dsh.idea.util.JsonCodec
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 集成冒烟：JVM mock MCP 终结点 + 真实 dsh web 进程（failOnStartupError patch）。
 *
 * MCP 终结点现在驻留 IDE 进程（生产代码为 IdeMcpServer/McpRpcHandler），DSH 经
 * streamable-http 直连、token 由 patch 的 headers 透传。本测试用 JDK HttpServer
 * 复刻该终结点的 JSON-RPC 行为，验证：
 * 1. dsh-mcp-client 启动时完成 initialize + tools/list（headers 中的 token 被接受）；
 * 2. failOnStartupError=true 时，MCP 链路不通 dsh 会拒绝启动——能起来即证明链路通；
 * 3. ide.yml patch（url + headers + reconnect）配置形态被 dsh 正确解析。
 *
 * 直接用 ProcessBuilder 拉起 dsh（不经过 ProcessManager/SettingsState，冒烟测试
 * 运行在无 IntelliJ Application 的纯 JVM 中）。未设置 DSH_IDEA_NODE / DSH_IDEA_DSH
 * 时跳过。
 */
class DshMcpEndpointSmokeTest {

    @TempDir
    lateinit var tempDir: Path

    private var mockMcp: HttpServer? = null
    private val procs = mutableListOf<Process>()
    private val toolsListed = AtomicInteger(0)
    private val token = "smoke-token-" + "b".repeat(48)

    @BeforeEach
    fun setUp() {
        assumeTrue(TestRuntime.available(), "${TestRuntime.ENV_NODE}/${TestRuntime.ENV_DSH} not set; skipping MCP smoke test")
    }

    @AfterEach
    fun tearDown() {
        procs.forEach { runCatching { it.destroy() } }
        procs.forEach { runCatching { it.waitFor(3, TimeUnit.SECONDS) } }
        mockMcp?.stop(0)
        // dsh 自愈创建的 profiles/node_modules junction 指向运行时树；
        // 必须先断链再让 @TempDir 清理，否则递归删除会清空 runtime 的 node_modules。
        unlinkJunctions(tempDir)
    }

    private fun unlinkJunctions(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { p ->
                runCatching {
                    if (Files.isSymbolicLink(p) || isJunction(p)) Files.delete(p)
                }
            }
        }
    }

    private fun isJunction(p: Path): Boolean = try {
        Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS).isOther
    } catch (e: Exception) {
        false
    }

    @Test
    fun `dsh boots with strict patch against in-process mcp endpoint and syncs tools`() {
        val nodeExe = TestRuntime.nodeExe()!!
        val dshBin = TestRuntime.dshBin()!!
        assertTrue(nodeExe.isFile, "node missing (${TestRuntime.ENV_NODE}): $nodeExe")
        assertTrue(dshBin.isFile, "dsh bin missing (${TestRuntime.ENV_DSH}): $dshBin")

        // 1) mock MCP 终结点（JDK HttpServer，JSON-RPC 2.0，token 校验）
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            try {
                if (ex.requestMethod != "POST" || ex.requestURI.path != "/mcp") {
                    if (ex.requestURI.path == "/mcp") {
                        writeJson(ex, 405, mapOf("jsonrpc" to "2.0", "id" to null,
                            "error" to mapOf("code" to -32000, "message" to "Method not allowed.")))
                    } else {
                        writeJson(ex, 404, mapOf("error" to "not found"))
                    }
                    return@createContext
                }
                if (ex.requestHeaders.getFirst("X-DSH-IDE-Token") != token) {
                    writeJson(ex, 401, mapOf("error" to "unauthorized", "code" to "unauthorized"))
                    return@createContext
                }
                val msg = JsonCodec.decodeObject(ex.requestBody.bufferedReader().readText())
                val method = msg["method"] as? String
                val hasId = msg.containsKey("id")
                val id = msg["id"]
                when (method) {
                    "initialize" -> {
                        val requested = (msg["params"] as? Map<*, *>)?.get("protocolVersion") as? String
                        rpcResult(ex, id, mapOf(
                            "protocolVersion" to (requested ?: "2025-11-25"),
                            "capabilities" to mapOf("tools" to mapOf("listChanged" to false)),
                            "serverInfo" to mapOf("name" to "mock-ide", "version" to "0.0.0"),
                        ))
                    }
                    "tools/list" -> {
                        toolsListed.incrementAndGet()
                        rpcResult(ex, id, mapOf("tools" to MOCK_TOOLS))
                    }
                    "tools/call" -> rpcResult(ex, id, mapOf(
                        "content" to listOf(mapOf("type" to "text", "text" to "{\"ok\":true}"))))
                    "ping" -> rpcResult(ex, id, emptyMap<String, Any?>())
                    else -> {
                        if (!hasId || method?.startsWith("notifications/") == true) {
                            ex.sendResponseHeaders(202, -1)
                        } else {
                            rpcError(ex, id, -32601, "Method not found: $method")
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runCatching { writeJson(ex, 500, mapOf("error" to (e.message ?: "internal"))) }
            } finally {
                ex.close()
            }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
        mockMcp = server

        // 2) 最小 DSH_HOME（与 DshHomeManager.ensureHome 相同结构）+ strict patch
        val home = tempDir.resolve("dsh-home")
        val web = home.resolve("profiles/web")
        Files.createDirectories(web)
        Files.writeString(
            web.resolve("package.json"),
            """{"name":"dsh-profile-web","private":true,"dependencies":{},"dsh":{"profile":{"bundles":["@deepseek-ai/dsh-base","@deepseek-ai/dsh-web-app"]}}}""",
            StandardCharsets.UTF_8
        )
        Files.writeString(web.resolve("cordis.yml"), "[]\n", StandardCharsets.UTF_8)
        Files.writeString(web.resolve("cordis.patch.yml"), "[]\n", StandardCharsets.UTF_8)
        val credFile = home.resolve(".credentials.yaml")
        Files.writeString(credFile, "DEEPSEEK_API_KEY: sk-dummy-for-test\n", StandardCharsets.UTF_8)
        // dsh-credentials-local 强制密钥文件 owner-only（与生产 FileUtils.chmod600 一致）
        runCatching {
            Files.setPosixFilePermissions(credFile, java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            ))
        }
        val patchFile = home.resolve("ide.yml")
        Files.writeString(patchFile, PatchGenerator.generateStrict(server.address.port, token), StandardCharsets.UTF_8)

        // 3) 启动 dsh web（failOnStartupError：MCP 初始化失败即拒绝启动）
        val workDir = File(System.getProperty("user.dir"))
        val pb = ProcessBuilder(
            listOf(
                nodeExe.absolutePath, dshBin.absolutePath,
                "--profile", "web",
                "--patch", patchFile.toString(),
                "--host", "127.0.0.1",
                "--port", "0",
                "--no-open",
            )
        )
        pb.directory(workDir)
        pb.environment()["DSH_HOME"] = home.toString()
        pb.redirectErrorStream(true)
        val dshProc = pb.start()
        procs.add(dshProc)

        val webUrl = waitForDshWeb(dshProc)
        assertNotNull(webUrl, "dsh web should boot with strict mcp patch (failOnStartupError); see process log")
        assertEquals(200, httpStatus(webUrl!!), "web ui should answer 200 at $webUrl")
        assertTrue(toolsListed.get() > 0,
            "dsh mcp-client should sync tools/list at startup (token header accepted)")
    }

    // ---- 辅助 ----

    /** 等待 dsh web 端口行（最多 90s），返回 URL 或 null（进程退出也返回 null）。 */
    private fun waitForDshWeb(proc: Process): String? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        val re = Regex("""dsh web: http://127\.0\.0\.1:(\d+)""")
        val reader = proc.inputStream.bufferedReader()
        val log = StringBuilder()
        while (System.nanoTime() < deadline) {
            if (!proc.isAlive) {
                reader.forEachLine { log.appendLine(it) }
                System.err.println("dsh exited early; log tail:\n${log.takeLast(4000)}")
                return null
            }
            while (reader.ready()) {
                val line = reader.readLine() ?: return null
                log.appendLine(line)
                re.find(line)?.let { return "http://127.0.0.1:${it.groupValues[1]}" }
            }
            Thread.sleep(300)
        }
        System.err.println("dsh boot timeout; log tail:\n${log.takeLast(4000)}")
        return null
    }

    private fun rpcResult(ex: HttpExchange, id: Any?, result: Any?) {
        writeJson(ex, 200, mapOf("jsonrpc" to "2.0", "id" to id, "result" to result))
    }

    private fun rpcError(ex: HttpExchange, id: Any?, code: Int, message: String) {
        writeJson(ex, 200, mapOf("jsonrpc" to "2.0", "id" to id,
            "error" to mapOf("code" to code, "message" to message)))
    }

    private fun writeJson(ex: HttpExchange, status: Int, body: Any) {
        val bytes = JsonCodec.encode(body).toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun httpStatus(url: String): Int {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.requestMethod = "GET"
        try {
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private val MOCK_TOOLS: List<Map<String, Any?>> = listOf(
            "ide_get_selection", "ide_get_open_files", "ide_get_project_tree", "ide_get_sent_selection",
            "ide_open_file", "ide_reveal_file", "ide_refresh_files",
        ).map {
            mapOf("name" to it, "description" to "mock $it",
                "inputSchema" to mapOf("type" to "object", "properties" to emptyMap<String, Any?>()))
        }
    }
}
