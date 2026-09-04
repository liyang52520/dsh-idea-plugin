package com.yg.dsh.idea.config

import com.yg.dsh.idea.runtime.LegacySessionMigrator
import com.yg.dsh.idea.settings.SettingsState
import com.yg.dsh.idea.util.Constants
import com.yg.dsh.idea.util.FileUtils
import com.yg.dsh.idea.util.HashUtils
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * DSH_HOME 维护与运行时路径解析（只管目录与配置，不管理进程——进程生命周期
 * 归 runtime 包的 ProcessManager/RuntimeRegistry）。
 *
 * 运行时（Node.js + DeepSeek Harness）**仅**来自插件设置里配置的两个路径：
 * - [SettingsState.nodePath]：Node.js 可执行文件；
 * - [SettingsState.dshPath]：DSH 入口 `@deepseek-ai/dsh/lib/bin.js`。
 *
 * 插件不再内嵌（fat bundle）也不按平台下载运行时——未配置即视为不可用，
 * 工具窗口会引导到设置页填写路径。
 */
@Service(Service.Level.APP)
class DshHomeManager : Disposable {

    companion object {
        private val LOG = Logger.getInstance(DshHomeManager::class.java)
        const val WELCOME_NOTICE_VERSION = "2026-08-13.1"

        /**
         * DSH web UI 语言（dsh-client-locale 的 `locale.preference`，取值 zh/en）。
         * 跟随系统语言：中文环境 → zh，其余 → en。持久化在 settings.yaml，
         * 替代以往向 JCEF 页面注入 navigator.language hack。
         */
        val DSH_LOCALE: String =
            if (java.util.Locale.getDefault().language.startsWith("zh")) "zh" else "en"

        private val WEB_PROFILE_MANIFEST =
            """{"name":"dsh-profile-web","private":true,"dependencies":{},"dsh":{"profile":{"bundles":["@deepseek-ai/dsh-base","@deepseek-ai/dsh-web-app"]}}}"""

        fun getInstance(): DshHomeManager =
            ApplicationManager.getApplication().getService(DshHomeManager::class.java)
    }

    /** Node.js 可执行文件（仅设置路径；未配置/不存在由 [hasRuntime] 提前拦截）。 */
    fun nodeExe(): Path {
        val node = configured(SettingsState.getInstance().nodePath, "Node.js path")
        return Path.of(node)
    }

    /** DSH 入口（仅设置路径，指向 `…/@deepseek-ai/dsh/lib/bin.js`）。 */
    fun dshBin(): Path {
        val dsh = configured(SettingsState.getInstance().dshPath, "DSH path")
        return Path.of(dsh)
    }

    /** 运行时可用 ⇔ 设置里同时配置了 node 与 dsh 两个路径且文件都存在。 */
    fun hasRuntime(): Boolean {
        val settings = SettingsState.getInstance()
        val node = settings.nodePath?.trim()?.takeIf { it.isNotEmpty() }
        val dsh = settings.dshPath?.trim()?.takeIf { it.isNotEmpty() }
        return node != null && dsh != null &&
            Files.isRegularFile(Path.of(node)) &&
            Files.isRegularFile(Path.of(dsh))
    }

    private fun configured(value: String?, label: String): String {
        val v = value?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException(
                "$label is not configured; open DeepSeek Harness settings and set the Node.js and DSH paths"
            )
        return v
    }

    fun globalConfigHome(): Path = PathManager.getConfigDir().resolve("dsh-idea").resolve("dsh-home")

    fun homeDir(projectPath: String): Path {
        val safe = if (projectPath.isBlank()) "default" else HashUtils.md5(projectPath).take(16)
        return globalConfigHome().resolve(safe)
    }

    fun ensureHome(projectPath: String): Path {
        val ghome = globalConfigHome()
        Files.createDirectories(ghome)
        prefillAcknowledgeWelcomeNotice()

        val home = homeDir(projectPath)
        val web = home.resolve("profiles/web")
        Files.createDirectories(web)

        writeIfAbsent(web.resolve("package.json"), WEB_PROFILE_MANIFEST)
        writeIfAbsent(web.resolve("cordis.yml"), "[]\n")
        writeIfAbsent(web.resolve("cordis.patch.yml"), "# patched by plugin\n[]\n")
        writeIfAbsent(home.resolve(Constants.IDE_PATCH_FILE), "[]\n")
        syncProviderSettings()
        copyGlobalConfigTo(home)
        migrateLegacySessions(home, projectPath)
        return home
    }

    private fun migrateLegacySessions(home: Path, projectPath: String) {
        if (projectPath.isBlank()) return
        val sentinel = home.resolve(".migration-done")
        if (Files.exists(sentinel)) return
        val oldRoot = globalConfigHome()
        if (!Files.isDirectory(oldRoot.resolve("sessions"))) {
            runCatching { Files.createFile(sentinel) }
            return
        }
        try {
            LegacySessionMigrator.migrateProject(oldRoot, home, projectPath)
            LegacySessionMigrator.migrateProjectionCache(oldRoot, home, projectPath)
            runCatching { Files.createFile(sentinel) }
        } catch (e: Exception) {
            LOG.warn("legacy session migration failed for $projectPath", e)
        }
    }

    private fun copyGlobalConfigTo(home: Path) {
        val g = globalConfigHome()
        for (name in listOf(Constants.CREDENTIALS_FILE, "settings.yaml")) {
            val src = g.resolve(name)
            if (!Files.exists(src)) continue
            val dest = home.resolve(name)
            if (Files.exists(dest) && Files.readString(dest) == Files.readString(src)) continue
            Files.createDirectories(home)
            Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            if (name == Constants.CREDENTIALS_FILE) {
                FileUtils.chmod600(dest)
            }
        }
    }

    private fun prefillAcknowledgeWelcomeNotice() {
        val f = globalConfigHome().resolve("settings.yaml")
        if (Files.exists(f)) return
        FileUtils.writeUtf8(
            f,
            "ui-onboarding:\n  welcomeNoticeVersion: \"$WELCOME_NOTICE_VERSION\"\n" +
                "locale:\n  preference: $DSH_LOCALE\n",
        )
        LOG.info("prefilled settings.yaml welcomeNoticeVersion=$WELCOME_NOTICE_VERSION locale=$DSH_LOCALE")
    }

    fun syncCredentials(): Boolean {
        val key = Credentials.readApiKey() ?: return false
        val credFile = globalConfigHome().resolve(Constants.CREDENTIALS_FILE)
        val content = "${Constants.DEEPSEEK_API_KEY}: $key\n"
        return try {
            if (!Files.exists(credFile) || Files.readString(credFile) != content) {
                FileUtils.writeUtf8(credFile, content)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            LOG.warn("failed to sync credentials to DSH_HOME", e)
            false
        }
    }

    fun syncProviderSettings() {
        ProviderSettingsWriter.sync(globalConfigHome(), WELCOME_NOTICE_VERSION, DSH_LOCALE)
    }

    private fun writeIfAbsent(path: Path, content: String) {
        if (!Files.exists(path)) FileUtils.writeUtf8(path, content)
    }

    override fun dispose() = Unit
}
