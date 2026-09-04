package com.yg.dsh.idea.ui

import com.yg.dsh.idea.config.CredentialFileWatcher
import com.yg.dsh.idea.config.DshHomeManager
import com.yg.dsh.idea.i18n.I18nBundle
import com.yg.dsh.idea.runtime.DshApiClient
import com.yg.dsh.idea.runtime.DshSession
import com.yg.dsh.idea.runtime.DshSessionLauncher
import com.yg.dsh.idea.runtime.PanelRegistry
import com.yg.dsh.idea.runtime.RuntimeRegistry
import com.yg.dsh.idea.runtime.process.ProcessManager
import com.yg.dsh.idea.settings.DshSettingsConfigurable
import com.yg.dsh.idea.settings.SettingsState
import com.yg.dsh.idea.util.Constants
import com.yg.dsh.idea.util.Notifications
import com.yg.dsh.idea.util.TextUtils
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import java.awt.CardLayout
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel
import javax.swing.SwingConstants

class ToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), Disposable, ProcessManager.Listener {

    companion object {
        private val LOG = Logger.getInstance(ToolWindowPanel::class.java)
        private const val CARD_LOADING = "loading"
        private const val CARD_BROWSER = "browser"
        private const val CARD_ERROR = "error"
        private const val CARD_GUIDE = "guide"
        const val TOOL_WINDOW_ID = Constants.TOOL_WINDOW_ID

        fun find(project: Project): ToolWindowPanel? {
            val tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
                ?: return null
            if (tw.contentManager.contentCount == 0) return null
            return tw.contentManager.getContent(0)?.component as? ToolWindowPanel
        }
    }

    private val cardPanel = JPanel(CardLayout())
    private val cards = cardPanel.layout as CardLayout

    @Volatile private var session: DshSession? = null
    @Volatile private var browserManager: BrowserManager? = null
    @Volatile private var browserComponent: java.awt.Component? = null
    private val errorLabel = JBLabel(" ", SwingConstants.CENTER)

    private var loadingStatusLabel: JBLabel? = null
    private var loadingStepLabel: JBLabel? = null

    private val loadingSteps = listOf(
        I18nBundle.message("loading.step.mcp"),
        I18nBundle.message("loading.step.config"),
        I18nBundle.message("loading.step.runtime"),
    )

    private val disposed = AtomicBoolean(false)

    /** start 防重入：settings-applied 回调与错误卡 retry 可能并发触发第二次启动。 */
    private val starting = AtomicBoolean(false)

    @Volatile private var loadingStatus: String = I18nBundle.message("loading.initializing")

    init {
        add(cardPanel, BorderLayout.CENTER)

        // Loading card is added first so it is the briefly-visible default before
        // start() switches to the guide/error card.
        val loadingCard = CardBuilder.buildLoadingCard(loadingStatus, CardBuilder.buildStepText(loadingSteps, 0))
        loadingStatusLabel = loadingCard.statusLabel
        loadingStepLabel = loadingCard.stepLabel
        cardPanel.add(loadingCard.component, CARD_LOADING)
        cardPanel.add(CardBuilder.buildErrorCard(errorLabel) { if (session != null) restart() else start() }, CARD_ERROR)
        cardPanel.add(CardBuilder.buildGuideCard { openSettings() }, CARD_GUIDE)
        Disposer.register(project, this)
        PanelRegistry.getInstance().registerPanel(project.name, this)
        start()
    }

    private fun openSettings() {
        ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, DshSettingsConfigurable::class.java)
    }

    /** Called by [DshSettingsConfigurable.apply] after settings are saved. */
    fun onSettingsApplied(runtimeAffected: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            if (session == null) {
                if (DshHomeManager.getInstance().hasRuntime()) start()
            } else if (runtimeAffected) {
                showRestartNotification()
            }
        }
    }

    private fun showRestartNotification() {
        val notification = Notification(
            "DeepSeek Harness",
            "",
            I18nBundle.message("settings.applied.restart"),
            NotificationType.INFORMATION,
        )
        notification.addAction(object : AnAction(I18nBundle.message("settings.applied.restartAction")) {
            override fun actionPerformed(e: AnActionEvent) {
                notification.expire()
                restart()
            }
        })
        com.intellij.notification.Notifications.Bus.notify(notification, project)
    }

    private fun start() {
        if (!DshHomeManager.getInstance().hasRuntime()) {
            showCard(CARD_GUIDE)
            return
        }
        if (!starting.compareAndSet(false, true)) return
        val registry = RuntimeRegistry.getInstance()
        registry.maxInstances = SettingsState.getInstance().maxInstances
            .coerceIn(SettingsState.MIN_MAX_INSTANCES, SettingsState.MAX_MAX_INSTANCES)
        if (!registry.tryAcquire(project.name, this)) {
            starting.set(false)
            showError(I18nBundle.message("error.concurrencyLimit", registry.maxInstances))
            return
        }
        showCard(CARD_LOADING)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                session = DshSessionLauncher(project).launch(
                    parent = this,
                    processListener = this,
                ) { phase ->
                    val key = when (phase) {
                        DshSessionLauncher.Phase.MCP -> "loading.mcp"
                        DshSessionLauncher.Phase.CONFIG -> "loading.config"
                        DshSessionLauncher.Phase.RUNTIME -> "loading.runtime"
                    }
                    updateLoadingStatus(I18nBundle.message(key), phase.ordinal)
                }
            } catch (e: Exception) {
                LOG.error("failed to bootstrap dsh", e)
                // 启动失败必须释放并发配额，否则失败的尝试会一直占用实例名额，
                // 其他项目会被 concurrency limit 拒绝。
                RuntimeRegistry.getInstance().release(project.name)
                showError(e.message ?: e.toString())
            } finally {
                starting.set(false)
            }
        }
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        session?.processManager?.dispose()
        session?.mcpServer?.dispose()
        session = null
        browserManager?.dispose()
        browserManager = null
        PanelRegistry.getInstance().unregisterPanel(project.name)
        RuntimeRegistry.getInstance().release(project.name)
        CredentialFileWatcher.release(project.name)
    }

    fun restart() {
        val manager = session?.processManager ?: return
        ApplicationManager.getApplication().invokeLater {
            loadingStatus = I18nBundle.message("loading.initializing")
            loadingStatusLabel?.text = loadingStatus
            loadingStepLabel?.text = CardBuilder.buildStepText(loadingSteps, 0)
            cards.show(cardPanel, CARD_LOADING)
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val projectRoot = project.basePath ?: ""
            DshHomeManager.getInstance().ensureHome(projectRoot)
            manager.restart()
        }
    }

    fun isRunning(): Boolean = session?.processManager?.currentState() == ProcessManager.State.RUNNING

    fun sendSelection(filePath: String?, language: String?, selection: String, lineStart: Int, lineEnd: Int) {
        session?.mcpServer?.pushSentSelection(filePath, language, selection, lineStart, lineEnd)
        val ref = buildCompactReference(filePath, lineStart, lineEnd)
        ApplicationManager.getApplication().invokeLater {
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow(TOOL_WINDOW_ID)?.activate(null)
            val injected = browserManager?.injectToComposer(ref) == true
            if (!injected) {
                Notifications.copyToClipboard(ref)
                Notifications.show(project, I18nBundle.message("sendSelection.clipboard"))
            }
        }
    }

    private fun buildCompactReference(filePath: String?, lineStart: Int, lineEnd: Int): String {
        if (filePath.isNullOrBlank()) return ""
        val sb = StringBuilder()
        sb.append('@').append(filePath.replace('\\', '/'))
        if (lineEnd > 0) {
            sb.append("#L").append(lineStart)
            if (lineEnd > lineStart) sb.append('-').append(lineEnd)
        }
        sb.append(' ')
        return sb.toString()
    }

    /**
     * 一键发送（解释日志）：走 DSH 后端 `/api/` JSON-RPC（goal.create），
     * 不经过网页 DOM——自动提交场景无需草稿，后端返回确定的 ok/error。
     * 失败时回退剪贴板，保证文本不丢。
     */
    fun sendQuestion(text: String) {
        val manager = session?.processManager
        val webUrl = manager?.webUrl()
        val projectRoot = project.basePath ?: ""
        if (manager?.currentState() != ProcessManager.State.RUNNING || webUrl == null) {
            Notifications.copyToClipboard(text)
            Notifications.show(project, I18nBundle.message("sendLogExplanation.notRunning"))
            return
        }
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow(TOOL_WINDOW_ID)?.activate(null)
        ApplicationManager.getApplication().executeOnPooledThread {
            val ok = runCatching { DshApiClient.sendMessage(webUrl, projectRoot, text) }.getOrDefault(false)
            ApplicationManager.getApplication().invokeLater {
                if (ok) {
                    Notifications.show(project, I18nBundle.message("sendLogExplanation.done"))
                } else {
                    Notifications.copyToClipboard(text)
                    Notifications.show(project, I18nBundle.message("sendLogExplanation.failed"))
                }
            }
        }
    }

    override fun onStateChanged(oldState: ProcessManager.State, newState: ProcessManager.State) {
        ApplicationManager.getApplication().invokeLater {
            if (newState == ProcessManager.State.CRASHED && oldState != ProcessManager.State.CRASHED) {
                notifyCrash()
            }
        }
    }

    override fun onStartupFailed(reason: String) {
        ApplicationManager.getApplication().invokeLater {
            showError(I18nBundle.message("error.startupFailed", ProcessManager.MAX_RESTART_ATTEMPTS, reason))
            Notifications.show(
                project,
                I18nBundle.message("error.startupFailed.notification"),
                NotificationType.ERROR,
            )
        }
    }

    override fun onUrlReady(url: String) {
        ApplicationManager.getApplication().invokeLater {
            try {
                // 崩溃自动重启会带着新端口再次回调：先释放旧 JCEF 浏览器与旧组件，
                // 否则每次重启泄漏一个重量级浏览器（CardLayout 同名卡片只覆盖映射、不移除组件）。
                browserComponent?.let { cardPanel.remove(it) }
                browserComponent = null
                browserManager?.let { Disposer.dispose(it) }
                browserManager = null
                val bm = BrowserManager().also {
                    browserManager = it
                    Disposer.register(this, it)
                }
                val b = bm.loadUrl(url)
                browserComponent = b.component
                cardPanel.add(b.component, CARD_BROWSER)
                cards.show(cardPanel, CARD_BROWSER)
            } catch (e: Throwable) {
                LOG.warn("JCEF failed to load web ui", e)
                val hint = I18nBundle.message("error.jcef")
                val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                showError("$hint<br><br><b>${TextUtils.escapeHtml(detail)}</b><br><br>" + I18nBundle.message("error.jcef.hint"))
            }
        }
    }

    private fun notifyCrash() {
        Notifications.show(project, I18nBundle.message("crash.autoRestarting"),
            com.intellij.notification.NotificationType.WARNING)
    }

    private fun showCard(card: String) {
        ApplicationManager.getApplication().invokeLater { cards.show(cardPanel, card) }
    }

    private fun showError(message: String) {
        ApplicationManager.getApplication().invokeLater {
            errorLabel.text = "<html>${TextUtils.escapeHtml(message)}</html>"
            cards.show(cardPanel, CARD_ERROR)
        }
    }

    fun updateLoadingStatus(status: String, stepIndex: Int) {
        loadingStatus = status
        ApplicationManager.getApplication().invokeLater {
            loadingStatusLabel?.text = status
            loadingStepLabel?.text = CardBuilder.buildStepText(loadingSteps, stepIndex)
        }
    }
}
