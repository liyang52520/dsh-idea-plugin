package com.yg.dsh.idea.ui

import com.yg.dsh.idea.i18n.I18nBundle
import com.yg.dsh.idea.settings.ModelConfig
import com.yg.dsh.idea.settings.ProviderConfig
import com.yg.dsh.idea.settings.SUPPORTED_PROTOCOLS
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.net.HttpURLConnection
import java.net.URI
import javax.swing.JComponent

/**
 * A dialog for creating or editing a custom provider, built with the IntelliJ
 * Kotlin UI DSL (native alignment, spacing and validation):
 *   - Provider ID (lowercase identifier, unique among configured providers)
 *   - Display name
 *   - Base URL
 *   - API protocol (dropdown)
 *   - API key (masked password field)
 *   - Models table with inline editing; add / remove / fetch via a native
 *     toolbar decorator
 *
 * [existingIds] holds the IDs of other providers (excluding the one being edited)
 * so duplicate IDs are rejected at validation time.
 */
class ProviderEditorDialog(
    private val existing: ProviderConfig? = null,
    private val existingIds: Set<String> = emptySet(),
) : DialogWrapper(null, true) {

    private val idField = JBTextField().apply {
        emptyText.text = I18nBundle.message("settings.provider.placeholder.id")
        toolTipText = I18nBundle.message("settings.provider.idHint")
    }
    private val displayNameField = JBTextField().apply {
        emptyText.text = I18nBundle.message("settings.provider.placeholder.displayName")
    }
    private val baseUrlField = JBTextField().apply {
        emptyText.text = I18nBundle.message("settings.provider.placeholder.baseUrl")
    }
    private val protocolCombo = ComboBox(SUPPORTED_PROTOCOLS.toTypedArray())
    private val apiKeyField = JBPasswordField().apply {
        emptyText.text = I18nBundle.message("settings.provider.placeholder.apiKey")
    }
    private val statusLabel = JBLabel(" ").apply {
        foreground = UIUtil.getContextHelpForeground()
        font = font.deriveFont(12f)
    }

    @Volatile
    private var fetching = false

    private val modelTableModel = ListTableModel<ModelConfig>(
        object : ColumnInfo<ModelConfig, String>(I18nBundle.message("settings.provider.modelId")) {
            override fun valueOf(item: ModelConfig): String = item.id
            override fun setValue(item: ModelConfig, value: String?) {
                item.id = value.orEmpty().trim()
            }
            override fun isCellEditable(item: ModelConfig): Boolean = true
        },
        object : ColumnInfo<ModelConfig, String>(I18nBundle.message("settings.provider.modelDisplayName")) {
            override fun valueOf(item: ModelConfig): String = item.name
            override fun setValue(item: ModelConfig, value: String?) {
                item.name = value.orEmpty().trim()
            }
            override fun isCellEditable(item: ModelConfig): Boolean = true
        },
    )
    private val modelTable = TableView<ModelConfig>(modelTableModel).apply {
        emptyText.text = I18nBundle.message("settings.provider.models.empty")
        columnModel.getColumn(0).preferredWidth = JBUI.scale(240)
        columnModel.getColumn(1).preferredWidth = JBUI.scale(280)
    }
    private val modelsPanel: JComponent = ToolbarDecorator.createDecorator(modelTable)
        .setAddAction { modelTableModel.addRow(ModelConfig()) }
        .setRemoveAction {
            modelTable.selectedRows.sortedDescending().forEach { modelTableModel.removeRow(it) }
        }
        .setAddActionName(I18nBundle.message("settings.provider.addModel"))
        .setRemoveActionName(I18nBundle.message("settings.provider.removeModel"))
        .addExtraAction(
            object : AnAction(
                I18nBundle.message("settings.provider.fetchModels"),
                null,
                AllIcons.Actions.Download,
            ) {
                override fun actionPerformed(e: AnActionEvent) = handleFetchModels()
            },
        )
        .disableUpDownActions()
        .setPreferredSize(JBUI.size(560, 180))
        .createPanel()

    /** Commit any in-progress table cell edit so its value is not lost. */
    private fun stopModelEditing() {
        val editor = modelTable.cellEditor
        if (editor != null && modelTable.isEditing) editor.stopCellEditing()
    }

    val providerConfig: ProviderConfig
        get() {
            stopModelEditing()
            val p = ProviderConfig()
            p.id = idField.text.trim()
            p.displayName = displayNameField.text.trim()
            p.baseUrl = baseUrlField.text.trim()
            p.apiProtocol = protocolCombo.selectedItem as? String ?: "openai-completions"
            p.apiKey = String(apiKeyField.password).trim()
            for (m in modelTableModel.items) {
                val mid = m.id.trim()
                if (mid.isNotEmpty()) {
                    p.models.add(ModelConfig(id = mid, name = m.name.trim().ifEmpty { mid }))
                }
            }
            return p
        }

    init {
        title = if (existing != null)
            I18nBundle.message("settings.provider.editTitle")
        else
            I18nBundle.message("settings.provider.createTitle")

        existing?.let {
            idField.text = it.id
            displayNameField.text = it.displayName
            baseUrlField.text = it.baseUrl
            if (it.apiProtocol in SUPPORTED_PROTOCOLS) protocolCombo.selectedItem = it.apiProtocol
            apiKeyField.text = it.apiKey
            modelTableModel.addRows(it.models.map { m -> ModelConfig(m.id, m.name) })
        }

        init()
        setResizable(true)
    }

    override fun createCenterPanel(): JComponent = panel {
        row(I18nBundle.message("settings.provider.id")) {
            cell(idField).align(AlignX.FILL)
        }.rowComment(I18nBundle.message("settings.provider.idHint"))
        row(I18nBundle.message("settings.provider.displayName")) {
            cell(displayNameField).align(AlignX.FILL)
        }
        row(I18nBundle.message("settings.provider.baseUrl")) {
            cell(baseUrlField).align(AlignX.FILL)
        }
        row(I18nBundle.message("settings.provider.apiProtocol")) {
            cell(protocolCombo)
        }
        row(I18nBundle.message("settings.provider.apiKey")) {
            cell(apiKeyField).align(AlignX.FILL)
        }
        group(I18nBundle.message("settings.provider.models")) {
            row {
                cell(modelsPanel).align(AlignX.FILL)
            }
            row {
                cell(statusLabel)
            }
        }
    }.apply {
        border = JBUI.Borders.empty(8)
        preferredSize = Dimension(JBUI.scale(580), preferredSize.height)
    }

    override fun doValidate(): ValidationInfo? {
        val id = idField.text.trim()
        if (id.isEmpty()) {
            return ValidationInfo(I18nBundle.message("settings.provider.validation.idEmpty"), idField)
        }
        if (!id.first().isLetter()) {
            return ValidationInfo(
                I18nBundle.message("settings.provider.validation.idMustStartWithLetter"),
                idField,
            )
        }
        if (id.any { !it.isLetterOrDigit() && it != '-' && it != '_' }) {
            return ValidationInfo(I18nBundle.message("settings.provider.validation.idChars"), idField)
        }
        if (id in existingIds) {
            return ValidationInfo(
                I18nBundle.message("settings.provider.validation.idDuplicate", id),
                idField,
            )
        }
        val baseUrl = baseUrlField.text.trim()
        if (baseUrl.isNotEmpty()) {
            try {
                val uri = URI(baseUrl)
                if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) {
                    return ValidationInfo(I18nBundle.message("settings.validation.invalidUrl"), baseUrlField)
                }
            } catch (_: Exception) {
                return ValidationInfo(I18nBundle.message("settings.validation.invalidUrl"), baseUrlField)
            }
        }
        stopModelEditing()
        val seenIds = HashSet<String>()
        for (m in modelTableModel.items) {
            val mid = m.id.trim()
            if (mid.isNotEmpty() && !seenIds.add(mid)) {
                return ValidationInfo(
                    I18nBundle.message("settings.provider.validation.modelDuplicate", mid),
                    modelTable,
                )
            }
        }
        return null
    }

    // -- fetch models --

    private fun handleFetchModels() {
        if (fetching) return
        val baseUrl = baseUrlField.text.trim()
        val apiKey = String(apiKeyField.password).trim()

        if (baseUrl.isEmpty()) {
            statusLabel.text = I18nBundle.message("settings.provider.fetch.needBaseUrl")
            return
        }
        if (apiKey.isEmpty()) {
            statusLabel.text = I18nBundle.message("settings.provider.fetch.needApiKey")
            return
        }

        statusLabel.text = I18nBundle.message("settings.provider.fetch.fetching")
        fetching = true

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val modelsUrl = "${baseUrl.trimEnd('/')}/models"
                val conn = URI(modelsUrl).toURL().openConnection() as HttpURLConnection
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.requestMethod = "GET"

                val code = conn.responseCode
                if (code == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val parsed = parseModelIds(body)
                    ApplicationManager.getApplication().invokeLater {
                        if (parsed.isEmpty()) {
                            statusLabel.text = I18nBundle.message("settings.provider.fetch.noModels")
                        } else {
                            stopModelEditing()
                            val fetched: List<ModelConfig> = parsed.map { ModelConfig(id = it, name = it) }
                            modelTableModel.setItems(fetched)
                            statusLabel.text =
                                I18nBundle.message("settings.provider.fetch.success", parsed.size)
                        }
                        fetching = false
                    }
                } else if (code == 401 || code == 403) {
                    ApplicationManager.getApplication().invokeLater {
                        statusLabel.text = I18nBundle.message("settings.provider.fetch.unauthorized")
                        fetching = false
                    }
                } else {
                    ApplicationManager.getApplication().invokeLater {
                        statusLabel.text = I18nBundle.message("settings.provider.fetch.failed", code)
                        fetching = false
                    }
                }
            } catch (ex: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    statusLabel.text = I18nBundle.message(
                        "settings.provider.fetch.error",
                        ex.message ?: ex.javaClass.simpleName,
                    )
                    fetching = false
                }
            }
        }
    }

    /**
     * Parse model IDs from an OpenAI-compatible /models JSON response.
     * Handles both `{"data":[{"id":"m1"},...]}` and `{"object":"list","data":[...]}`.
     */
    private fun parseModelIds(body: String): List<String> {
        val ids = mutableListOf<String>()
        var inData = false
        var depth = 0
        var i = 0
        while (i < body.length) {
            when (body[i]) {
                '{' -> depth++
                '}' -> depth--
                '"' -> {
                    val end = body.indexOf('"', i + 1)
                    if (end < 0) break
                    val key = body.substring(i + 1, end)
                    i = end + 1
                    if (key == "data" && !inData) {
                        while (i < body.length && body[i].isWhitespace()) i++
                        if (i < body.length && body[i] == ':') {
                            i++
                            while (i < body.length && body[i].isWhitespace()) i++
                            if (i < body.length && body[i] == '[') {
                                inData = true
                                depth = 0
                            }
                        }
                    } else if (key == "id" && inData) {
                        while (i < body.length && body[i].isWhitespace()) i++
                        if (i < body.length && body[i] == ':') {
                            i++
                            while (i < body.length && body[i].isWhitespace()) i++
                            if (i < body.length && body[i] == '"') {
                                val idEnd = body.indexOf('"', i + 1)
                                if (idEnd > i) {
                                    ids.add(body.substring(i + 1, idEnd))
                                    i = idEnd
                                }
                            }
                        }
                    }
                }
                ']' -> {
                    if (inData && depth == 0) inData = false
                }
            }
            i++
        }
        return ids
    }
}
