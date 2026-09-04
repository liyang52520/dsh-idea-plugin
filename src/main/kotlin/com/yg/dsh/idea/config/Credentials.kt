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

    fun readApiKeyFromCredentialFile(file: Path): String? {
        if (!Files.isReadable(file)) return null
        return Files.readAllLines(file).asSequence()
            .map { it.trim() }
            .filter { it.startsWith(DEEPSEEK_API_KEY) }
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx < 0) null else line.substring(idx + 1).trim().trim('"').trim('\'')
            }
            .firstOrNull { it.isNotEmpty() }
    }

    fun readApiKeyWithFallback(credentialFile: Path?): String? =
        readApiKey() ?: credentialFile?.let { readApiKeyFromCredentialFile(it) }

    fun maskApiKey(key: String?): String {
        if (key.isNullOrEmpty()) return ""
        if (key.length <= 12) return "******"
        return key.take(6) + "******" + key.takeLast(6)
    }
}