package com.yg.dsh.idea.runtime

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.yg.dsh.idea.config.CredentialFileWatcher
import com.yg.dsh.idea.config.DshHomeManager
import com.yg.dsh.idea.mcp.IdeMcpServer
import com.yg.dsh.idea.runtime.process.ProcessManager
import com.yg.dsh.idea.settings.SettingsState
import java.io.File

/** 一次成功启动的 DSH 会话产物：进程内 MCP 终结点 + DSH Node 进程。 */
class DshSession(
    val mcpServer: IdeMcpServer,
    val processManager: ProcessManager,
)

/**
 * DSH 会话启动编排（从 ToolWindowPanel 抽出，使 panel 只负责卡片呈现）：
 * ensureHome → MCP patch → 凭据 → 进程。
 *
 * 同步执行且包含文件 IO，必须在 pooled thread 上调用。任一步骤失败即抛出，
 * 由调用方负责错误呈现与实例登记（RuntimeRegistry）回滚。
 */
class DshSessionLauncher(private val project: Project) {

    enum class Phase { MCP, CONFIG, RUNTIME }

    fun launch(
        parent: Disposable,
        processListener: ProcessManager.Listener,
        onPhase: (Phase) -> Unit = {},
    ): DshSession {
        val projectRoot = project.basePath ?: ""
        val homeManager = DshHomeManager.getInstance()
        val homePath = homeManager.homeDir(projectRoot)
        homeManager.ensureHome(projectRoot)

        // MCP 终结点驻留 IDE 进程（IdeMcpServer/McpRpcHandler）：构造即就绪，
        // patch 同步写入，无子进程、无端口等待、无静默降级。
        onPhase(Phase.MCP)
        val server = IdeMcpServer(project, homePath)
        Disposer.register(parent, server)
        val patchFile = server.writePatch()

        homeManager.syncCredentials()
        CredentialFileWatcher.register(
            project.name, homePath.resolve(".credentials.yaml")
        )
        onPhase(Phase.CONFIG)

        onPhase(Phase.RUNTIME)
        val customArgs = SettingsState.getInstance().dshArgs
            .split("\\s+".toRegex()).filter { it.isNotBlank() }
        val manager = ProcessManager(
            nodeExe = homeManager.nodeExe().toFile(),
            dshBin = homeManager.dshBin().toFile(),
            workDir = File(projectRoot.ifEmpty { System.getProperty("user.home") }),
            homeDir = homePath.toFile(),
            patchFile = patchFile.toFile(),
            projectPath = projectRoot,
            extraArgs = customArgs,
        )
        manager.addListener(processListener)
        Disposer.register(parent, manager)
        manager.start()
        return DshSession(server, manager)
    }
}
