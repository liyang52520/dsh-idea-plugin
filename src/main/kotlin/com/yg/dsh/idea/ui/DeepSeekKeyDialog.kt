package com.yg.dsh.idea.ui

import com.yg.dsh.idea.config.Credentials
import com.yg.dsh.idea.i18n.I18nBundle
import com.yg.dsh.idea.util.Constants
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Dimension
import java.net.HttpURLConnection
import java.net.URI
import javax.swing.JComponent

/**
 * Dialog for setting the built-in DeepSeek provider API key.
 *
 * The key is stored in the IDE password safe ([Credentials.writeApiKey]); leaving the
 * field empty keeps the currently stored key. A built-in "Test Connection" button
 * validates the key against the DeepSeek `/models` endpoint.
 */
class DeepSeekKeyDialog : DialogWrapper(null, true) {

    private val keyField = JBPasswordField().apply { columns = 36 }
    private val statusLabel = JBLabel(" ").apply { font = font.deriveFont(12f) }

    /** True when a new key was entered and saved on OK. */
    var keySaved: Boolean = false
        private set

    init {
        title = I18nBundle.message("settings.deepseek.key.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row(I18nBundle.message("settings.deepseek.key.field")) {
                cell(keyField).align(AlignX.FILL)
            }.comment(I18nBundle.message("settings.deepseek.key.hint"))
            row {
                button(I18nBundle.message("settings.card.testConnection")) { testConnection() }
                cell(statusLabel)
            }
        }.apply {
            border = JBUI.Borders.empty(8)
            preferredSize = Dimension(480, preferredSize.height)
        }
    }

    override fun doOKAction() {
        val entered = String(keyField.password).trim()
        if (entered.isNotEmpty()) {
            Credentials.writeApiKey(entered)
            keySaved = true
        }
        super.doOKAction()
    }

    private fun testConnection() {
        val key = String(keyField.password).trim().ifEmpty { Credentials.readApiKey() }
        if (key.isNullOrEmpty()) {
            setStatus(I18nBundle.message("settings.test.enterKeyFirst"), ERROR_COLOR)
            return
        }
        setStatus(I18nBundle.message("settings.test.testing"), UIUtil.getLabelForeground())
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val conn = URI("${Constants.DEFAULT_BASE_URL}/models").toURL().openConnection() as HttpURLConnection
                conn.setRequestProperty("Authorization", "Bearer $key")
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                ApplicationManager.getApplication().invokeLater {
                    when {
                        code == 200 -> setStatus(I18nBundle.message("settings.test.success"), SUCCESS_COLOR)
                        code == 401 -> setStatus(I18nBundle.message("settings.test.unauthorized"), ERROR_COLOR)
                        else -> setStatus(I18nBundle.message("settings.test.failed", code), ERROR_COLOR)
                    }
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    setStatus(
                        I18nBundle.message("settings.test.error", e.message ?: e.javaClass.simpleName),
                        ERROR_COLOR,
                    )
                }
            }
        }
    }

    private fun setStatus(text: String, color: Color) {
        statusLabel.text = text
        statusLabel.foreground = color
    }

    companion object {
        private val ERROR_COLOR = Color(0xC0, 0x28, 0x28)
        private val SUCCESS_COLOR = Color(0x1A, 0x7F, 0x37)
    }
}
