package com.yg.dsh.idea.ui

import com.yg.dsh.idea.i18n.I18nBundle
import com.yg.dsh.idea.util.TextUtils
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.ScalableIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.SwingConstants

object CardBuilder {

    private const val CENTER_ALIGNMENT = 0.5f

    /** Logical width (px) of the brand logo on the startup cards. */
    private const val LOGO_WIDTH = 56

    /**
     * The DeepSeek SVG ships with a large intrinsic size (~272x200), so it must be
     * scaled down for in-panel use. Scaling the vector keeps it crisp on any DPI.
     */
    private fun scaled(icon: Icon, factor: Float): Icon =
        (icon as? ScalableIcon)?.scale(factor) ?: icon

    private fun logo(): Icon {
        val base = IconLoader.getIcon("/icons/deepseek.svg", CardBuilder::class.java)
        val factor = LOGO_WIDTH.toFloat() / base.iconWidth.toFloat()
        return scaled(base, factor)
    }

    private fun titleLabel(text: String): JBLabel =
        JBLabel(text, SwingConstants.CENTER).apply {
            font = font.deriveFont(Font.BOLD, JBUI.scaleFontSize(15f).toFloat())
            foreground = UIUtil.getLabelForeground()
            alignmentX = CENTER_ALIGNMENT
        }

    private fun hex(color: Color): String = String.format("#%06X", color.rgb and 0xFFFFFF)

    /**
     * First-run guide card shown when the Node.js / DSH paths are not configured
     * yet. Points the user at the standard IDE Settings page.
     */
    fun buildGuideCard(onOpenSettings: () -> Unit): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(40, 28)
        }
        panel.add(Box.createVerticalGlue())

        panel.add(JBLabel(logo()).apply { alignmentX = CENTER_ALIGNMENT })
        panel.add(Box.createVerticalStrut(JBUI.scale(18)))

        panel.add(titleLabel(I18nBundle.message("settings.guide.title")))
        panel.add(Box.createVerticalStrut(JBUI.scale(8)))

        val desc = "<html><div style='width:250px;text-align:center;font-size:11.5px;color:" +
            hex(UIUtil.getLabelDisabledForeground()) + ";'>" +
            TextUtils.escapeHtml(I18nBundle.message("settings.guide.desc")) +
            "</div></html>"
        panel.add(JBLabel(desc, SwingConstants.CENTER).apply { alignmentX = CENTER_ALIGNMENT })
        panel.add(Box.createVerticalStrut(JBUI.scale(20)))

        panel.add(JButton(I18nBundle.message("settings.guide.openSettings")).apply {
            alignmentX = CENTER_ALIGNMENT
            addActionListener { onOpenSettings() }
        })
        panel.add(Box.createVerticalGlue())
        return panel
    }

    fun buildLoadingCard(initialStatus: String, initialStepText: String): LoadingCard {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(40, 28)
        }
        panel.add(Box.createVerticalGlue())

        panel.add(JBLabel(logo()).apply { alignmentX = CENTER_ALIGNMENT })
        panel.add(Box.createVerticalStrut(JBUI.scale(18)))

        panel.add(titleLabel("DeepSeek Harness"))
        panel.add(Box.createVerticalStrut(JBUI.scale(6)))

        val statusLabel = JBLabel(initialStatus, SwingConstants.CENTER).apply {
            font = font.deriveFont(JBUI.scaleFontSize(12.5f).toFloat())
            foreground = UIUtil.getLabelDisabledForeground()
            alignmentX = CENTER_ALIGNMENT
        }
        panel.add(statusLabel)
        panel.add(Box.createVerticalStrut(JBUI.scale(18)))

        val progressBar = JProgressBar().apply {
            isIndeterminate = true
            alignmentX = CENTER_ALIGNMENT
            val width = JBUI.scale(200)
            val height = JBUI.scale(4)
            preferredSize = Dimension(width, height)
            maximumSize = Dimension(width, height)
        }
        panel.add(progressBar)
        panel.add(Box.createVerticalStrut(JBUI.scale(16)))

        val stepLabel = JBLabel(initialStepText, SwingConstants.CENTER).apply {
            alignmentX = CENTER_ALIGNMENT
            foreground = UIUtil.getLabelDisabledForeground()
        }
        panel.add(stepLabel)
        panel.add(Box.createVerticalGlue())
        return LoadingCard(panel, statusLabel, stepLabel)
    }

    data class LoadingCard(
        val component: JComponent,
        val statusLabel: JBLabel,
        val stepLabel: JBLabel,
    )

    fun buildErrorCard(errorLabel: JBLabel, onRetry: () -> Unit): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(40, 28)
        }
        panel.add(Box.createVerticalGlue())

        val errorIcon = scaled(AllIcons.General.Error, 1.9f)
        panel.add(JBLabel(errorIcon).apply { alignmentX = CENTER_ALIGNMENT })
        panel.add(Box.createVerticalStrut(JBUI.scale(14)))

        errorLabel.alignmentX = CENTER_ALIGNMENT
        errorLabel.foreground = UIUtil.getLabelForeground()
        panel.add(errorLabel)
        panel.add(Box.createVerticalStrut(JBUI.scale(20)))

        panel.add(JButton(I18nBundle.message("action.restart"), AllIcons.Actions.Restart).apply {
            alignmentX = CENTER_ALIGNMENT
            addActionListener { onRetry() }
        })
        panel.add(Box.createVerticalGlue())
        return panel
    }

    /**
     * Horizontal step indicator: completed steps get a green check, the running
     * step is a bold accent-colored dot, upcoming steps are muted hollow dots.
     * Colors are resolved from the current IDE theme at build time.
     */
    fun buildStepText(loadingSteps: List<String>, current: Int): String {
        val doneColor = hex(JBColor(0x4CB050, 0x69C47F))
        val activeColor = hex(JBUI.CurrentTheme.Link.Foreground.ENABLED)
        val mutedColor = hex(UIUtil.getLabelDisabledForeground())
        val sb = StringBuilder("<html><div style='font-size:11px;text-align:center;white-space:nowrap;'>")
        for ((i, step) in loadingSteps.withIndex()) {
            if (i > 0) {
                sb.append("<span style='color:").append(mutedColor).append("'>&nbsp;&rarr;&nbsp;</span>")
            }
            val name = TextUtils.escapeHtml(step)
            when {
                i < current -> sb.append("<span style='color:").append(doneColor).append("'>&#10003;</span>")
                    .append(" <span style='color:").append(mutedColor).append("'>").append(name).append("</span>")
                i == current -> sb.append("<b style='color:").append(activeColor).append("'>&#9679; ")
                    .append(name).append("</b>")
                else -> sb.append("<span style='color:").append(mutedColor).append("'>&#9675; ")
                    .append(name).append("</span>")
            }
        }
        sb.append("</div></html>")
        return sb.toString()
    }
}
