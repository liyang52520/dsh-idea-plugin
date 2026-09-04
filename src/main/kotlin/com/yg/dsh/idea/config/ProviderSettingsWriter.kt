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

    private fun writeCredentialsYaml(home: Path, providers: List<ProviderConfig>) {
        val credFile = home.resolve(Constants.CREDENTIALS_FILE)
        try {
            Files.createDirectories(credFile.parent)
            val existing = mutableMapOf<String, String>()
            if (Files.isRegularFile(credFile)) {
                Files.readAllLines(credFile).forEach { line ->
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2) {
                        existing[parts[0].trim()] = unquoteScalar(parts[1])
                    }
                }
            }
            var changed = false
            for (p in providers) {
                if (p.apiKey.isNotEmpty()) {
                    val envName = p.credentialEnvName
                    if (existing[envName] != p.apiKey) {
                        existing[envName] = p.apiKey
                        changed = true
                    }
                }
            }
            if (!changed) return
            val content = existing.entries.joinToString("\n") { "${yamlScalar(it.key)}: ${yamlScalar(it.value)}" } + "\n"
            FileUtils.writeUtf8(credFile, content)
            LOG.info("wrote provider credentials to $credFile")
        } catch (e: Exception) {
            LOG.warn("failed to sync provider credentials", e)
        }
    }
}