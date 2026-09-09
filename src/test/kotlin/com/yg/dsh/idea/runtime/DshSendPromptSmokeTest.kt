package com.yg.dsh.idea.runtime

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
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 集成冒烟（真实 dsh）：一键发送链路——插件不经网页 DOM，直接 POST `/api/` JSON-RPC
 * （session/list + session/create + session/prompt）。
 *
 * 验证：
 * 1. workspace/create（WorkspaceInitializer，typert gateway 契约：cookie 认证 + 斜杠路径）真实可用；
 * 2. sendMessage 在无会话时自动 session/create + session/prompt 被 accepted；
 * 3. 第二次 sendMessage 复用同 cwd 的既有会话（不重复建会话）。
 *
 * prompt 真正跑起来需要可用的 LLM key（本测试用 dummy key，agent 执行会失败），
 * 但 session.prompt 的契约是"入队消息"——accepted:true 即契约成立。
 *
 * 直接用 ProcessBuilder 拉起 dsh（纯 JVM，无 IntelliJ Application）。
 * 未设置 DSH_IDEA_NODE / DSH_IDEA_DSH 时跳过。
 */
class DshSendPromptSmokeTest {

    @TempDir
    lateinit var tempDir: Path

    private val procs = mutableListOf<Process>()

    @BeforeEach
    fun setUp() {
        assumeTrue(TestRuntime.available(), "${TestRuntime.ENV_NODE}/${TestRuntime.ENV_DSH} not set; skipping send-prompt smoke test")
    }

    @AfterEach
    fun tearDown() {
        procs.forEach { runCatching { it.destroy() } }
        procs.forEach { runCatching { it.waitFor(3, TimeUnit.SECONDS) } }
        unlinkJunctions(tempDir)
    }

    @Test
    fun `sendMessage creates session then prompt, and reuses session on second call`() {
        val nodeExe = TestRuntime.nodeExe()!!
        val dshBin = TestRuntime.dshBin()!!
        assertTrue(nodeExe.isFile, "node missing (${TestRuntime.ENV_NODE}): $nodeExe")
        assertTrue(dshBin.isFile, "dsh bin missing (${TestRuntime.ENV_DSH}): $dshBin")

        // 1) 最小 DSH_HOME（本测试不涉及 MCP，patch 为空列表）
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
        Files.writeString(home.resolve("ide.yml"), "[]\n", StandardCharsets.UTF_8)
        val credFile = home.resolve(".credentials.yaml")
        Files.writeString(credFile, "DEEPSEEK_API_KEY: sk-dummy-for-test\n", StandardCharsets.UTF_8)
        runCatching {
            Files.setPosixFilePermissions(credFile, java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            ))
        }

        // 2) 启动 dsh web
        val workDir = File(System.getProperty("user.dir"))
        val pb = ProcessBuilder(
            listOf(
                nodeExe.absolutePath, dshBin.absolutePath,
                "--profile", "web",
                "--patch", home.resolve("ide.yml").toString(),
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
        assertNotNull(webUrl, "dsh web should boot; see process log")
        // 带 token 的握手 URL 应答 303（Set-Cookie 后重定向）；跟随重定向反而会拿到 401。
        assertEquals(303, httpStatus(webUrl!!), "token handshake should answer 303 at $webUrl")

        // 3) 与生产一致：先注册 workspace（ProcessManager 启动后自动做）
        val projectDir = Files.createDirectory(tempDir.resolve("proj-prompt")).toFile().absolutePath
        assertTrue(WorkspaceInitializer.ensureWorkspace(webUrl, projectDir), "workspace.create should succeed")

        // 4) 第一次发送：无会话 → session.create + session.prompt
        assertTrue(DshApiClient.sendMessage(webUrl, projectDir, "smoke test: explain this log please"),
            "sendMessage should create session and prompt accepted")
        val firstSession = DshApiClient.ensureSession(webUrl, projectDir)
        assertNotNull(firstSession, "session for cwd should exist after sendMessage")

        // 5) 第二次发送：必须复用同一会话（session/list 命中，不重复 create）
        assertTrue(DshApiClient.sendMessage(webUrl, projectDir, "smoke test: follow-up message"),
            "second sendMessage should be accepted")
        val sessionsForCwd = sessionsForCwd(webUrl, projectDir)
        assertEquals(1, sessionsForCwd, "ensureSession must reuse the existing session, not create duplicates")
    }

    // ---- 辅助 ----

    private fun sessionsForCwd(base: String, cwd: String): Int {
        val res = DshApiClient.rpc(base, "session/list", null)
        check(res.ok) { "session/list failed: ${res.errorText}" }
        val canonical = File(cwd).canonicalPath
        val items = (res.value["items"] as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
        return items.count { row ->
            (row["cwd"] as? String)?.let { runCatching { File(it).canonicalPath == canonical }.getOrDefault(false) } == true
        }
    }

    private fun waitForDshWeb(proc: Process): String? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        val re = Regex("""dsh web: (http://127\.0\.0\.1:\d+/\?token=\S+)""")
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
                re.find(line)?.let { return it.groupValues[1].trim() }
            }
            Thread.sleep(300)
        }
        System.err.println("dsh boot timeout; log tail:\n${log.takeLast(4000)}")
        return null
    }

    private fun httpStatus(url: String): Int {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = false
        try {
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
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
}
