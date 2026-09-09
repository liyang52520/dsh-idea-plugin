package com.yg.dsh.idea.runtime

import com.yg.dsh.idea.runtime.process.ProcessManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
 * 集成冒烟：使用真实 dsh 运行时启动 dsh web，验证端口发现、健康检查与 RUNNING 状态流转。
 *
 * 运行时与插件契约一致（仅路径）：DSH_IDEA_NODE / DSH_IDEA_DSH 环境变量分别指向
 * Node.js 可执行文件与 DSH 入口 `lib/bin.js`；未设置时自动跳过（CI 无运行时环境）。
 */
class DshBootstrapSmokeTest {

    @TempDir
    lateinit var tempDir: Path

    private var manager: ProcessManager? = null

    @BeforeEach
    fun setUp() {
        assumeTrue(TestRuntime.available(), "${TestRuntime.ENV_NODE}/${TestRuntime.ENV_DSH} not set; skipping real-boot smoke test")
    }

    @AfterEach
    fun tearDown() {
        manager?.dispose()
        manager = null
        // 重要：dsh 自愈创建的 profiles/node_modules junction 指向运行时树；
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
        // Windows junction 在默认（FOLLOW）读取时 isOther=false、isDirectory=true，
        // 必须用 NOFOLLOW_LINKS 才能识别（实测 dsh 自愈 junction）。
        Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS).isOther
    } catch (e: Exception) {
        false
    }

    @Test
    fun `boots real dsh web and serves http`() {
        val nodeExe = TestRuntime.nodeExe()!!
        val dshBin = TestRuntime.dshBin()!!
        assertTrue(nodeExe.isFile, "node missing (${TestRuntime.ENV_NODE}): $nodeExe")
        assertTrue(dshBin.isFile, "dsh bin missing (${TestRuntime.ENV_DSH}): $dshBin")

        // 构造最小 DSH_HOME（与 DshHomeManager.ensureHome 相同结构，但落在临时目录）
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
        val patchFile = home.resolve("ide.yml")
        Files.writeString(patchFile, "[]\n", StandardCharsets.UTF_8)
        val credFile = home.resolve(".credentials.yaml")
        Files.writeString(credFile, "DEEPSEEK_API_KEY: sk-dummy-for-test\n", StandardCharsets.UTF_8)
        // dsh-credentials-local 强制密钥文件 owner-only（与生产 FileUtils.chmod600 一致）
        runCatching {
            Files.setPosixFilePermissions(credFile, java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            ))
        }

        val workDir = File(System.getProperty("user.dir"))

        var url: String? = null
        manager = ProcessManager(
            nodeExe = nodeExe,
            dshBin = dshBin,
            workDir = workDir,
            homeDir = home.toFile(),
            patchFile = patchFile.toFile(),
            projectPath = workDir.absolutePath,
        ).also { m ->
            m.addListener(object : ProcessManager.Listener {
                override fun onUrlReady(u: String) {
                    url = u
                }
            })
            m.start()
        }

        // 等待 RUNNING（最长 90s）
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        while (System.nanoTime() < deadline) {
            if (manager?.currentState() == ProcessManager.State.RUNNING) break
            Thread.sleep(500)
        }

        assertEquals(ProcessManager.State.RUNNING, manager?.currentState(), "dsh did not reach RUNNING")
        val webUrl = url ?: manager?.webUrl()
        assertTrue(webUrl != null, "web url not discovered")
        // 带 token 的握手 URL 应答 303（Set-Cookie 后重定向）；跟随重定向反而会拿到 401。
        assertEquals(303, httpStatus(webUrl!!), "token handshake should answer 303 at $webUrl")

        // FR-04.2：等待 workspace.create 落地（异步），项目应注册为默认工作区
        val wsFile = home.resolve("storages/workspace.json")
        val wsDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        var wsRegistered = false
        while (System.nanoTime() < wsDeadline) {
            if (Files.exists(wsFile) && Files.readString(wsFile).contains(workDir.absolutePath.replace("\\", "\\\\"))) {
                wsRegistered = true
                break
            }
            Thread.sleep(300)
        }
        assertTrue(wsRegistered, "project should be registered as default workspace in workspace.json")
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
}
