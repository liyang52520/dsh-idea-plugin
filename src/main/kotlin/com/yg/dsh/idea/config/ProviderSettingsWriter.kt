package com.yg.dsh.idea.config

import com.yg.dsh.idea.settings.SettingsState
import com.yg.dsh.idea.settings.ProviderConfig
import com.yg.dsh.idea.util.Constants
import com.yg.dsh.idea.util.FileUtils
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path

object ProviderSettingsWriter {

    private val LOG = Logger.getInstance(ProviderSettingsWriter::class.java)

    fun sync(globalConfigHome: Path, welcomeNoticeVersion: String, locale: String) {
        val settings = SettingsState.getInstance()
        val providers = settings.providers.filter { it.id.isNotBlank() }
        if (providers.isEmpty()) return

        writeSettingsYaml(globalConfigHome, providers, welcomeNoticeVersion, locale)
        writeCredentialsYaml(globalConfigHome, providers)
    }

    /**
     * YAML 单引号标量：内部 `'` 转义为 `''`。所有用户可控值（id/displayName/
     * baseUrl/model/ApiKey）必须经此转义——裸写时值中含 `: `、`#`、换行会
     * 破坏 YAML 结构，ApiKey 含换行甚至可向凭据文件注入伪造条目。
     */
    private fun yamlScalar(v: String): String = "'" + v.replace("'", "''") + "'"

    /** 读取侧配套：剥掉单引号包装并还原 `''`（与 Credentials 文件读取口径一致）。 */
    internal fun unquoteScalar(v: String): String {
        val t = v.trim()
        if (t.length >= 2 && t.startsWith("'") && t.endsWith("'")) {
            return t.substring(1, t.length - 1).replace("''", "'")
        }
        return t
    }

    private fun writeSettingsYaml(home: Path, providers: List<ProviderConfig>, welcomeNoticeVersion: String, locale: String) {
        val yaml = buildString {
            appendLine("ui-onboarding:")
            appendLine("  welcomeNoticeVersion: \"$welcomeNoticeVersion\"")
            appendLine("locale:")
            appendLine("  preference: $locale")
            appendLine("llm-pi-ai:")
            appendLine("  providers:")
            for (p in providers) {
                val envName = p.credentialEnvName
                appendLine("    ${yamlScalar(p.id)}:")
                if (p.displayName.isNotBlank()) {
                    appendLine("      displayName: ${yamlScalar(p.displayName)}")
                }
                appendLine("      api: ${p.apiProtocol.ifEmpty { "openai-completions" }}")
                appendLine("      baseURL: ${yamlScalar(p.baseUrl.ifEmpty { Constants.DEFAULT_BASE_URL })}")
                appendLine("      apiKeyEnv: ${yamlScalar(envName)}")
                appendLine("      models:")
                if (p.models.isEmpty()) {
                    appendLine("        - id: ${yamlScalar(Constants.DEFAULT_MODEL)}")
                    appendLine("          name: ${yamlScalar(Constants.DEFAULT_MODEL)}")
                } else {
                    for (m in p.models) {
                        appendLine("        - id: ${yamlScalar(m.id)}")
                        if (m.name.isNotBlank() && m.name != m.id) {
                            appendLine("          name: ${yamlScalar(m.name)}")
                        }
                    }
                }
            }
            val first = providers.first()
            appendLine("agent-default-model:")
            appendLine("  provider: ${yamlScalar(first.id)}")
            val firstModel = first.models.firstOrNull()?.id ?: Constants.DEFAULT_MODEL
            appendLine("  model: ${yamlScalar(firstModel)}")
        }
        val f = home.resolve("settings.yaml")
        if (Files.exists(f) && Files.readString(f) == yaml) return
        try {
            FileUtils.writeUtf8(f, yaml)
            LOG.info("wrote provider settings to $f")
        } catch (e: Exception) {
            LOG.warn("failed to sync provider settings", e)
        }
    }

    // region credentials document (version: 1 layout)

    /**
     * 解析后的凭据文档：
     * - [refs]：凭据引用（POSIX 标识符 → 密钥），对应 DSH 的 `refs:` 段；
     * - [recordsBlock]：`records:` 段的原始文本（含段头）。该段由 DSH 自行
     *   管理（如 browser-session grant），插件任何写入都必须原样保留，
     *   否则会把网页登录态等记录静默抹掉。
     */
    internal data class CredentialDocument(
        val refs: LinkedHashMap<String, String>,
        val recordsBlock: String? = null,
    )

    /**
     * 解析凭据文件，同时兼容两种历史形态：
     * 1. 旧版扁平布局：无 `version`，顶层即 `'KEY': 'value' 映射；
     * 2. 现行 version: 1 布局：`version: 1` + `refs:`（+ `records:`）。
     *
     * 逐行解析（不引入 YAML 依赖）：只提取形如 `key: value` 的引用条目，
     * 无法识别的内容在扁平化时自然丢弃——输出永远是 DSH 可直接读取的干净文档。
     */
    internal fun parseCredentialDocument(text: String): CredentialDocument {
        val lines = text.split("\n")
        val versioned = lines.any { line ->
            line.isNotBlank() && !line.startsWith(" ") && !line.startsWith("\t") &&
                line.substringBefore(':').trim() == "version"
        }
        if (!versioned) {
            val refs = LinkedHashMap<String, String>()
            for (line in lines) {
                if (line.isBlank() || line.startsWith(" ") || line.startsWith("\t")) continue
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val key = unquoteScalar(line.substring(0, idx))
                val value = unquoteScalar(line.substring(idx + 1))
                if (key.isNotBlank() && value.isNotEmpty()) refs[key] = value
            }
            return CredentialDocument(refs)
        }
        val refs = LinkedHashMap<String, String>()
        val recordsLines = mutableListOf<String>()
        var section = ""
        for (line in lines) {
            val topLevel = line.isNotBlank() && !line.startsWith(" ") && !line.startsWith("\t")
            if (topLevel) {
                val header = line.substringBefore(':').trim()
                section = if (line.trimEnd().endsWith(":")) header else ""
                if (header == "records" && line.trimEnd().endsWith(":")) recordsLines.add(line)
                continue
            }
            when (section) {
                "refs" -> {
                    if (line.isBlank()) continue
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val key = unquoteScalar(line.substring(0, idx))
                        val value = unquoteScalar(line.substring(idx + 1))
                        if (key.isNotBlank() && value.isNotEmpty()) refs[key] = value
                    }
                }
                "records" -> recordsLines.add(line)
            }
        }
        val recordsBlock = recordsLines.joinToString("\n").trimEnd().ifEmpty { null }
        return CredentialDocument(refs, recordsBlock)
    }

    /** 渲染现行 version: 1 布局。输出恒为 DSH 可直接加载的合法文档。 */
    internal fun renderCredentialDocument(doc: CredentialDocument): String = buildString {
        appendLine("version: 1")
        appendLine("refs:")
        for ((key, value) in doc.refs) {
            appendLine("  ${yamlScalar(key)}: ${yamlScalar(value)}")
        }
        if (doc.recordsBlock != null) {
            appendLine(doc.recordsBlock)
        }
    }

    /**
     * 在现有凭据文件上执行一次更新并在内容变化时写盘。文件不存在视为空文档；
     * 写入恒为 version: 1 布局（扁平旧文件借此被原地升级），chmod 600 由
     * [FileUtils.writeUtf8] 统一处理。
     *
     * @return true 表示文件实际发生了变化。
     */
    private fun updateCredentialFile(
        credFile: Path,
        update: (CredentialDocument) -> CredentialDocument,
    ): Boolean {
        try {
            Files.createDirectories(credFile.parent)
            val current = if (Files.isRegularFile(credFile)) {
                parseCredentialDocument(Files.readString(credFile))
            } else {
                CredentialDocument(LinkedHashMap())
            }
            val content = renderCredentialDocument(update(current))
            if (Files.isRegularFile(credFile) && Files.readString(credFile) == content) return false
            FileUtils.writeUtf8(credFile, content)
            return true
        } catch (e: Exception) {
            LOG.warn("failed to update credential file $credFile", e)
            return false
        }
    }

    /**
     * 合并单个凭据引用（其他引用与 records 段原样保留），不存在则新增。
     * 供 DshHomeManager.syncCredentials 与 CredentialFileWatcher 使用——
     * 以往两处都用单条目内容整体覆盖文件，会静默吞掉其他 provider 的密钥。
     */
    fun mergeCredential(home: Path, refName: String, value: String): Boolean =
        updateCredentialFile(home.resolve(Constants.CREDENTIALS_FILE)) { doc ->
            val refs = LinkedHashMap(doc.refs).apply { put(refName, value) }
            doc.copy(refs = refs)
        }

    /**
     * 把全局凭据的 refs 传播到项目 DSH home：
     * - refs 以全局文件为准（插件设置里的增删改完整跟随）；
     * - 项目文件的 records 段（browser-session grant 等）原样保留；
     * - 全局文件不存在时仅原地规范化项目文件（扁平旧布局 → version 1）。
     */
    fun propagateCredentials(globalHome: Path, projectHome: Path) {
        val globalCred = globalHome.resolve(Constants.CREDENTIALS_FILE)
        val destCred = projectHome.resolve(Constants.CREDENTIALS_FILE)
        val globalRefs: Map<String, String>? = if (Files.isRegularFile(globalCred)) {
            parseCredentialDocument(Files.readString(globalCred)).refs
        } else {
            null
        }
        updateCredentialFile(destCred) { doc ->
            doc.copy(refs = LinkedHashMap(globalRefs ?: doc.refs))
        }
    }

    // endregion

    private fun writeCredentialsYaml(home: Path, providers: List<ProviderConfig>) {
        val credFile = home.resolve(Constants.CREDENTIALS_FILE)
        val wrote = updateCredentialFile(credFile) { doc ->
            val refs = LinkedHashMap(doc.refs)
            for (p in providers) {
                if (p.apiKey.isNotEmpty()) refs[p.credentialEnvName] = p.apiKey
            }
            doc.copy(refs = refs)
        }
        if (wrote) LOG.info("wrote provider credentials to $credFile")
    }
}
