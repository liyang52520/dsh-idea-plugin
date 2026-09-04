package com.yg.dsh.idea.ui.actions

import com.yg.dsh.idea.i18n.I18nBundle
import com.yg.dsh.idea.ui.ToolWindowPanel
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class RestartAction(private val panel: ToolWindowPanel) : AnAction(I18nBundle.message("action.restart"), null, com.intellij.icons.AllIcons.Actions.Restart) {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = true
    }

    override fun actionPerformed(e: AnActionEvent) = panel.restart()
}