package com.yg.dsh.idea.config

import com.yg.dsh.idea.util.Constants
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import java.nio.file.Files
import java.nio.file.Path

object Credentials {
    const val DEEPSEEK_API_KEY = Constants.DEEPSEEK_API_KEY
    const val USER_NAME = "deepseek-api-key"

    private val ATTRIBUTES = CredentialAttributes("DshSettings", USER_NAME)

    private fun passwordSafe(): PasswordSafe =
        ApplicationManager.getApplication().getService(PasswordSafe::class.java)

    fun readApiKey(): String? = passwordSafe().getPassword(ATTRIBUTES)

    fun writeApiKey(key: String) = passwordSafe().setPassword(ATTRIBUTES, key)

    /**
     * 从凭据文件读取 DeepSeek 密钥，同时兼容两种布局：
     * 旧版扁平文件（顶层 `DEEPSEEK_API_KEY: ...`）与现行 version: 1 文件
     * （`refs:` 段内 `  'DEEPSEEK_API_KEY': '...'`）。records 段内不会出现
     * 同名键，无需额外分段判断。
     */
    fun readApiKeyFromCredentialFile(file: Path): String? {
        if (!Files.isReadable(file)) return null
        return Files.readAllLines(file).asSequence()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) return@mapNotNull null
                val name = line.substring(0, idx).trim().trim('\'', '"')
                if (name != DEEPSEEK_API_KEY) return@mapNotNull null
                line.substring(idx + 1).trim().trim('\'', '"').takeIf { it.isNotEmpty() }
            }
            .firstOrNull()
    }

    fun readApiKeyWithFallback(credentialFile: Path?): String? =
        readApiKey() ?: credentialFile?.let { readApiKeyFromCredentialFile(it) }

    fun maskApiKey(key: String?): String {
        if (key.isNullOrEmpty()) return ""
        if (key.length <= 12) return "******"
        return key.take(6) + "******" + key.takeLast(6)
    }
}