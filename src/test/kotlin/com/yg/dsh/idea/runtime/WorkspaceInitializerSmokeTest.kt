package com.yg.dsh.idea.runtime

import com.yg.dsh.idea.runtime.process.ProcessManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 集成冒烟（真实 dsh）：验证 workspace/create 在新版 typert gateway 契约下
 * （cookie 认证 + 斜杠路径 + request 信封）可用，且重复注册幂等。
 *
 * 新版 DSH 不再暴露 workspace 列表/排序的 unary RPC（顺序经事件流投影），
 * 因此 ensureWorkspace 的职责就是"确保项目已注册"。
 *
 * 未设置 DSH_IDEA_NODE / DSH_IDEA_DSH 时自动跳过。
 */
class WorkspaceInitializerSmokeTest {

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
        unlinkJunctions(tempDir)
    }

    @Test
    fun `ensureWorkspace registers projects and is idempotent`() {
        val nodeExe = TestRuntime.nodeExe()!!
        val dshBin = TestRuntime.dshBin()!!
        assertTrue(nodeExe.isFile, "node missing (${TestRuntime.ENV_NODE}): $nodeExe")
        assertTrue(dshBin.isFile, "dsh bin missing (${TestRuntime.ENV_DSH}): $dshBin")

        val home = tempDir.resolve("dsh-home")
        bootstrapHome(home)
        val workDir = File(System.getProperty("user.dir"))

        var url: String? = null
        manager = ProcessManager(
            nodeExe = nodeExe,
            dshBin = dshBin,
            workDir = workDir,
            homeDir = home.toFile(),
            patchFile = home.resolve("ide.yml").toFile(),
            // 手动控制注册流程，避免异步注册干扰断言
            projectPath = "",
        ).also { m ->
            m.addListener(object : ProcessManager.Listener {
                override fun onUrlReady(u: String) {
                    url = u
                }
            })
            m.start()
        }
        val webUrl = waitRunning(url)

        val dirA = Files.createDirectory(tempDir.resolve("projA")).toFile().absolutePath
        val dirB = Files.createDirectory(tempDir.resolve("projB")).toFile().absolutePath

        // 注册 A / B，重复注册均应成功（幂等）
        assertTrue(WorkspaceInitializer.ensureWorkspace(webUrl, dirA), "register workspace A")
        assertTrue(WorkspaceInitializer.ensureWorkspace(webUrl, dirB), "register workspace B")
        assertTrue(WorkspaceInitializer.ensureWorkspace(webUrl, dirA), "re-register workspace A (idempotent)")

        // 两个项目都应落到 workspace.json
        awaitWorkspaceRegistered(home, dirA)
        awaitWorkspaceRegistered(home, dirB)
    }

    // ---- helpers ----

    private fun waitRunning(urlRef: String?): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        var url = urlRef
        while (System.nanoTime() < deadline) {
            if (manager?.currentState() == ProcessManager.State.RUNNING) {
                url = url ?: manager?.webUrl()
                if (url != null) return url
            }
            Thread.sleep(500)
        }
        throw AssertionError("dsh did not reach RUNNING with url (state=${manager?.currentState()})")
    }

    private fun bootstrapHome(home: Path) {
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
        // dsh-credentials-local 强制密钥文件 owner-only（与生产 FileUtils.chmod600 一致）
        runCatching {
            Files.setPosixFilePermissions(credFile, java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            ))
        }
    }

    /** 等待 workspace.json 出现且包含 [expected] 路径。 */
    private fun awaitWorkspaceRegistered(home: Path, expected: String) {
        val wsFile = home.resolve("storages/workspace.json")
        val needle = canonical(expected)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            if (Files.exists(wsFile) && Files.readString(wsFile).contains(needle.replace("\\", "\\\\"))) return
            Thread.sleep(300)
        }
        throw AssertionError("workspace not registered in workspace.json; expected=$needle")
    }

    /** 与 dsh 落盘格式一致：realpath 规范化，保留系统分隔符（dsh 存反斜杠，实测）。 */
    private fun canonical(p: String): String = Path.of(p).toRealPath().toString()

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
