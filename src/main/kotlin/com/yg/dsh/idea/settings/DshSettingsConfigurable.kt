package com.yg.dsh.idea.settings

import com.yg.dsh.idea.config.Credentials
import com.yg.dsh.idea.config.DshHomeManager
import com.yg.dsh.idea.i18n.I18nBundle
import com.yg.dsh.idea.runtime.PanelRegistry
import com.yg.dsh.idea.ui.DeepSeekKeyDialog
import com.yg.dsh.idea.ui.ProviderEditorDialog
import com.yg.dsh.idea.util.Constants
import com.intellij.icons.AllIcons
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.validation.DialogValidation
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.BottomGap
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Placeholder
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JSpinner

/**
 * IDE Settings → Tools → DeepSeek Harness.
 *
 * Built entirely with the IntelliJ Kotlin UI DSL v2: native alignment/spacing,
 * inline validation, OK/Cancel/Apply lifecycle and settings-search indexing.
 *
 * Layout (card list + editor dialogs):
 *  - Runtime group: Node.js path, DSH path (file chooser, full-width fields,
 *    hints rendered below via rowComment)
 *  - Model Providers group: the built-in DeepSeek provider plus custom
 *    providers rendered as cards (name + status badges, summary line,
 *    hover-style icon actions); add/edit happens in [ProviderEditorDialog],
 *    the DeepSeek key in [DeepSeekKeyDialog]
 *  - Advanced group: extra DSH arguments, concurrent instance cap
 */
class DshSettingsConfigurable : Configurable {

    private val log = Logger.getInstance(DshSettingsConfigurable::class.java)

    private var dialogPanel: DialogPanel? = null

    private lateinit var nodePathField: TextFieldWithBrowseButton
    private lateinit var dshPathField: TextFieldWithBrowseButton
    private lateinit var dshArgsField: JBTextField
    private lateinit var maxInstancesSpinner: JSpinner

    /** Working copy of custom providers while the settings dialog is open. */
    private val workingProviders = mutableListOf<ProviderConfig>()
    private lateinit var providersPlaceholder: Placeholder

    /** Set when the DeepSeek key was changed via [DeepSeekKeyDialog]. */
    private var deepSeekKeyChanged = false

    override fun getDisplayName(): String = I18nBundle.message("settings.page.title")

    override fun createComponent(): JComponent {
        val state = SettingsState.getInstance()

        nodePathField = createPathBrowser(state.nodePath.orEmpty())
        dshPathField = createPathBrowser(state.dshPath.orEmpty())
        dshArgsField = JBTextField(state.dshArgs)

        workingProviders.clear()
        workingProviders.addAll(state.providers.map { copyProvider(it) })

        val p = panel {
            group(I18nBundle.message("settings.group.runtime")) {
                row(I18nBundle.message("settings.runtime.nodePath")) {
                    cell(nodePathField).align(AlignX.FILL)
                        .validationOnApply(pathValidation(nodePathField, executable = true))
                }.rowComment(I18nBundle.message("settings.runtime.nodePath.hint"))
                row(I18nBundle.message("settings.runtime.dshPath")) {
                    cell(dshPathField).align(AlignX.FILL)
                        .validationOnApply(pathValidation(dshPathField, executable = false))
                }.rowComment(I18nBundle.message("settings.runtime.dshPath.hint"))
            }

            group(I18nBundle.message("settings.group.providers")) {
                row {
                    button(I18nBundle.message("settings.provider.add")) { addProvider() }
                        .apply { component.icon = AllIcons.General.Add }
                }.bottomGap(BottomGap.SMALL)
                row {
                    providersPlaceholder = placeholder().align(AlignX.FILL)
                }
            }

            group(I18nBundle.message("settings.group.advanced")) {
                row(I18nBundle.message("settings.card.dshArgs")) {
                    cell(dshArgsField).align(AlignX.FILL)
                }
                row(I18nBundle.message("settings.advanced.maxInstances")) {
                    maxInstancesSpinner = spinner(
                        SettingsState.MIN_MAX_INSTANCES..SettingsState.MAX_MAX_INSTANCES,
                    ).component
                    maxInstancesSpinner.value = state.maxInstances
                        .coerceIn(SettingsState.MIN_MAX_INSTANCES, SettingsState.MAX_MAX_INSTANCES)
                }.rowComment(I18nBundle.message("settings.advanced.maxInstances.hint"))
            }
        }
        providersPlaceholder.component = buildProvidersPanel()
        dialogPanel = p
        return p
    }

    /** Rebuilds the provider card list inside the placeholder (DSL v2 dynamic content). */
    private fun buildProvidersPanel(): DialogPanel = panel {
        renderDeepSeekRow(this)
        workingProviders.forEachIndexed { index, provider ->
            renderProviderRow(this, index, provider)
        }
    }

    // ------------------------------------------------------------
    //  Provider cards
    // ------------------------------------------------------------

    private fun renderDeepSeekRow(rcv: Panel) {
        val key = Credentials.readApiKey()
        val badges = listOf(
            badge(I18nBundle.message("settings.provider.builtIn"), BadgeStyle.NEUTRAL),
            keyBadge(!key.isNullOrBlank()),
        )
        val actions = listOf(
            iconActionButton(I18nBundle.message("settings.deepseek.setKey"), AllIcons.Nodes.Padlock) {
                val dialog = DeepSeekKeyDialog()
                if (dialog.showAndGet()) {
                    if (dialog.keySaved) deepSeekKeyChanged = true
                    reloadProviders()
                }
            },
            iconActionButton(I18nBundle.message("settings.card.testConnection"), AllIcons.Actions.Refresh) {
                testDeepSeek()
            },
        )
        rcv.row {
            cell(
                providerCard(
                    title = I18nBundle.message("settings.deepseek.name"),
                    badges = badges,
                    summary = deepSeekSummary(),
                    actions = actions,
                )
            ).align(AlignX.FILL)
        }.bottomGap(BottomGap.SMALL)
    }

    private fun renderProviderRow(rcv: Panel, index: Int, provider: ProviderConfig) {
        val title = when {
            provider.displayName.isNotBlank() && provider.id.isNotBlank() ->
                "${provider.displayName}  (${provider.id})"
            provider.id.isNotBlank() -> provider.id
            else -> I18nBundle.message("settings.card.providerTitle", index + 1)
        }
        val badges = listOf(keyBadge(provider.apiKey.isNotBlank()))
        val actions = listOf(
            iconActionButton(I18nBundle.message("settings.card.testConnection"), AllIcons.Actions.Refresh) {
                testProvider(provider)
            },
            iconActionButton(I18nBundle.message("settings.provider.edit"), AllIcons.Actions.Edit) {
                editProvider(index)
            },
            iconActionButton(I18nBundle.message("settings.card.removeProvider"), AllIcons.Actions.GC) {
                removeProvider(index)
            },
        )
        rcv.row {
            cell(
                providerCard(
                    title = title,
                    badges = badges,
                    summary = providerSummary(provider),
                    actions = actions,
                )
            ).align(AlignX.FILL)
        }.bottomGap(BottomGap.SMALL)
    }

    /**
     * A provider card: bold name + status badges on top, gray summary line below,
     * hover-style icon actions vertically centered on the right. Wrapped in a
     * transparent panel that reserves the vertical gap between cards.
     */
    private fun providerCard(
        title: String,
        badges: List<JComponent>,
        summary: String,
        actions: List<JComponent>,
    ): JPanel {
        val nameLabel = JBLabel(title).apply { font = font.deriveFont(Font.BOLD) }
        val titleLine = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(nameLabel)
            badges.forEach { add(it) }
        }
        val summaryLabel = JBLabel(summary).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = font.deriveFont(JBUI.scaleFontSize(12f).toFloat())
            border = JBUI.Borders.emptyTop(4)
        }
        val left = JBUI.Panels.simplePanel().apply {
            isOpaque = false
            addToTop(titleLine)
            addToBottom(summaryLabel)
        }
        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), 0)).apply {
            isOpaque = false
            actions.forEach { add(it) }
        }
        val card = ProviderCardPanel().apply {
            border = JBUI.Borders.empty(11, 14)
            addToLeft(left)
            addToRight(actionsPanel)
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(10)
            add(card, BorderLayout.CENTER)
        }
    }

    private fun badge(text: String, style: BadgeStyle): Badge = Badge(text, style)

    private fun keyBadge(keySet: Boolean): Badge =
        if (keySet) {
            badge(I18nBundle.message("settings.provider.key.statusSet"), BadgeStyle.SUCCESS)
        } else {
            badge(I18nBundle.message("settings.provider.key.statusNotSet"), BadgeStyle.WARNING)
        }

    /** Native toolbar-style icon button with hover background and tooltip. */
    private fun iconActionButton(tooltip: String, icon: Icon, onClick: () -> Unit): ActionButton {
        val action = object : AnAction(tooltip, null, icon) {
            override fun actionPerformed(e: AnActionEvent) = onClick()
        }
        return ActionButton(action, action.templatePresentation, ACTION_PLACE, JBUI.size(28))
    }

    private fun reloadProviders() {
        if (!::providersPlaceholder.isInitialized) return
        providersPlaceholder.component = buildProvidersPanel()
    }

    private fun deepSeekSummary(): String {
        val key = Credentials.readApiKey()
        val masked = if (!key.isNullOrBlank()) "  ·  ${Credentials.maskApiKey(key)}" else ""
        return "${Constants.DEFAULT_BASE_URL}$masked"
    }

    private fun providerSummary(p: ProviderConfig): String {
        val models = if (p.models.isEmpty()) {
            I18nBundle.message("settings.provider.summary.noModels")
        } else {
            I18nBundle.message("settings.provider.summary.models", p.models.size)
        }
        val protocol = p.apiProtocol.ifEmpty { "openai-completions" }
        val url = p.baseUrl.ifEmpty { Constants.DEFAULT_BASE_URL }
        return "$protocol  ·  $url  ·  $models"
    }

    // ------------------------------------------------------------
    //  Add / edit / remove
    // ------------------------------------------------------------

    private fun addProvider() {
        val dialog = ProviderEditorDialog(
            existing = null,
            existingIds = workingProviders.map { it.id }.toSet(),
        )
        if (dialog.showAndGet()) {
            workingProviders.add(dialog.providerConfig)
            reloadProviders()
        }
    }

    private fun editProvider(index: Int) {
        val current = workingProviders.getOrNull(index) ?: return
        val dialog = ProviderEditorDialog(
            existing = current,
            existingIds = workingProviders.map { it.id }.filter { it != current.id }.toSet(),
        )
        if (dialog.showAndGet()) {
            workingProviders[index] = dialog.providerConfig
            reloadProviders()
        }
    }

    private fun removeProvider(index: Int) {
        val provider = workingProviders.getOrNull(index) ?: return
        val confirm = JOptionPane.showConfirmDialog(
            dialogPanel,
            I18nBundle.message("settings.card.removeProviderConfirm"),
            I18nBundle.message("settings.card.removeProvider"),
            JOptionPane.YES_NO_OPTION,
        )
        if (confirm == JOptionPane.YES_OPTION) {
            workingProviders.removeAt(index)
            reloadProviders()
        }
    }

    // ------------------------------------------------------------
    //  Connection tests
    // ------------------------------------------------------------

    private fun testDeepSeek() {
        testConnection(Constants.DEFAULT_BASE_URL, Credentials.readApiKey().orEmpty())
    }

    private fun testProvider(p: ProviderConfig) {
        testConnection(
            baseUrl = p.baseUrl.trim().ifEmpty { Constants.DEFAULT_BASE_URL },
            apiKey = p.apiKey.trim(),
        )
    }

    private fun testConnection(baseUrl: String, apiKey: String) {
        if (apiKey.isBlank()) {
            notify(I18nBundle.message("settings.test.enterKeyFirst"), NotificationType.WARNING)
            return
        }
        notify(I18nBundle.message("settings.test.testing"), NotificationType.INFORMATION)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val conn = URI("${baseUrl.trimEnd('/')}/models").toURL().openConnection() as HttpURLConnection
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                val (message, type) = when {
                    code == 200 -> I18nBundle.message("settings.test.success") to NotificationType.INFORMATION
                    code == 401 -> I18nBundle.message("settings.test.unauthorized") to NotificationType.ERROR
                    else -> I18nBundle.message("settings.test.failed", code) to NotificationType.ERROR
                }
                notify(message, type)
            } catch (e: Exception) {
                notify(
                    I18nBundle.message("settings.test.error", e.message ?: e.javaClass.simpleName),
                    NotificationType.ERROR,
                )
            }
        }
    }

    private fun notify(text: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            Notifications.Bus.notify(Notification("DeepSeek Harness", "", text, type))
        }
    }

    // ------------------------------------------------------------
    //  Configurable lifecycle
    // ------------------------------------------------------------

    override fun isModified(): Boolean {
        val state = SettingsState.getInstance()
        if (state.nodePath.orEmpty() != nodePathField.text.trim()) return true
        if (state.dshPath.orEmpty() != dshPathField.text.trim()) return true
        if (state.dshArgs != dshArgsField.text.trim()) return true
        if (state.maxInstances != maxInstancesSpinner.value) return true
        if (deepSeekKeyChanged) return true
        if (!providersEqual(state.providers, workingProviders)) return true
        return false
    }

    override fun apply() {
        val state = SettingsState.getInstance()
        val newNodePath = nodePathField.text.trim()
        val newDshPath = dshPathField.text.trim()
        val newDshArgs = dshArgsField.text.trim()
        val newProviders = workingProviders.filter { it.id.isNotBlank() }.map { copyProvider(it) }

        val runtimeAffected = state.nodePath.orEmpty() != newNodePath ||
            state.dshPath.orEmpty() != newDshPath ||
            state.dshArgs != newDshArgs ||
            !providersEqual(state.providers, newProviders) ||
            deepSeekKeyChanged

        state.nodePath = newNodePath.ifEmpty { null }
        state.dshPath = newDshPath.ifEmpty { null }
        state.dshArgs = newDshArgs
        state.maxInstances = (maxInstancesSpinner.value as? Int)
            ?.coerceIn(SettingsState.MIN_MAX_INSTANCES, SettingsState.MAX_MAX_INSTANCES)
            ?: SettingsState.DEFAULT_MAX_INSTANCES
        state.providers.clear()
        state.providers.addAll(newProviders)
        deepSeekKeyChanged = false

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                DshHomeManager.getInstance().syncCredentials()
                DshHomeManager.getInstance().syncProviderSettings()
            } catch (e: Exception) {
                log.warn("failed to sync settings after apply", e)
            }
        }

        // Let live tool-window panels react: auto-start when unconfigured, or
        // prompt for restart when settings changed while the harness is running.
        PanelRegistry.getInstance().livePanels().forEach { it.onSettingsApplied(runtimeAffected) }
    }

    override fun reset() {
        val state = SettingsState.getInstance()
        nodePathField.text = state.nodePath.orEmpty()
        dshPathField.text = state.dshPath.orEmpty()
        dshArgsField.text = state.dshArgs
        maxInstancesSpinner.value = state.maxInstances
            .coerceIn(SettingsState.MIN_MAX_INSTANCES, SettingsState.MAX_MAX_INSTANCES)
        workingProviders.clear()
        workingProviders.addAll(state.providers.map { copyProvider(it) })
        deepSeekKeyChanged = false
        reloadProviders()
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }

    // ------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------

    private fun createPathBrowser(initialPath: String): TextFieldWithBrowseButton {
        val field = TextFieldWithBrowseButton()
        field.text = initialPath
        val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
            .withTitle(I18nBundle.message("settings.card.browseTitle"))
        field.addBrowseFolderListener(null as Project?, descriptor)
        return field
    }

    private fun pathValidation(field: TextFieldWithBrowseButton, executable: Boolean): DialogValidation =
        object : DialogValidation {
            override fun validate(): ValidationInfo? = validatePath(field, executable)
        }

    private fun validatePath(field: TextFieldWithBrowseButton, executable: Boolean): ValidationInfo? {
        val path = field.text.trim()
        if (path.isEmpty()) {
            return ValidationInfo(I18nBundle.message("settings.validation.fileNotFound"), field)
        }
        val file = File(path)
        if (!file.exists()) {
            return ValidationInfo(I18nBundle.message("settings.validation.fileNotFound"), field)
        }
        if (executable && !file.canExecute() && !file.name.endsWith(".exe")) {
            return ValidationInfo(I18nBundle.message("settings.validation.notExecutable"), field)
        }
        return null
    }

    /** Deep copy — data class copy() is shallow and would share the models list. */
    private fun copyProvider(p: ProviderConfig): ProviderConfig =
        p.copy(models = p.models.map { it.copy() }.toMutableList())

    private fun providersEqual(a: List<ProviderConfig>, b: List<ProviderConfig>): Boolean {
        if (a.size != b.size) return false
        for ((x, y) in a.zip(b)) {
            if (x.id != y.id ||
                x.displayName != y.displayName ||
                x.baseUrl != y.baseUrl ||
                x.apiProtocol != y.apiProtocol ||
                x.apiKey != y.apiKey
            ) return false
            if (x.models.size != y.models.size) return false
            for ((mx, my) in x.models.zip(y.models)) {
                if (mx.id != my.id || mx.name != my.name) return false
            }
        }
        return true
    }

    // ------------------------------------------------------------
    //  Small visual components
    // ------------------------------------------------------------

    /** Pill-shaped status badge with a tinted background (light/dark aware). */
    private class Badge(text: String, private val style: BadgeStyle) : JBLabel(text) {
        init {
            font = font.deriveFont(JBUI.scaleFontSize(11f).toFloat())
            foreground = style.fg
            border = JBUI.Borders.empty(2, 8)
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = style.bg
            val arc = height - JBUI.scale(1)
            g2.fillRoundRect(0, 0, width, height, arc, arc)
            g2.dispose()
            super.paintComponent(g)
        }
    }

    private enum class BadgeStyle(val bg: Color, val fg: Color) {
        NEUTRAL(
            JBColor(Color(0xE9EBEE), Color(0x34373B)),
            JBColor(Color(0x5C6066), Color(0xAEB3BA)),
        ),
        SUCCESS(
            JBColor(Color(0xE3F2E7), Color(0x26382C)),
            JBColor(Color(0x2F7D4C), Color(0x74C98E)),
        ),
        WARNING(
            JBColor(Color(0xFDF2D9), Color(0x3D3421)),
            JBColor(Color(0x8F6B12), Color(0xDFB74F)),
        ),
    }

    /** Filled rounded card with a 1px outline, painted explicitly (light/dark aware). */
    private class ProviderCardPanel : BorderLayoutPanel() {
        init {
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(10).toDouble()
            val rect = RoundRectangle2D.Double(0.5, 0.5, width - 1.0, height - 1.0, arc, arc)
            g2.color = CARD_BG
            g2.fill(rect)
            g2.color = CARD_BORDER
            g2.draw(rect)
            g2.dispose()
            super.paintComponent(g)
        }

        companion object {
            private val CARD_BG = JBColor(Color(0xFFFFFF), Color(0x2E3033))
            private val CARD_BORDER = JBColor(Color(0xD3D7DE), Color(0x43454A))
        }
    }

    companion object {
        private const val ACTION_PLACE = "DshSettingsConfigurable"
    }
}
