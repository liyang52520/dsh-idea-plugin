package com.yg.dsh.idea.ui.actions

import com.yg.dsh.idea.i18n.I18nBundle
import com.yg.dsh.idea.settings.DshSettingsConfigurable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil

class OpenSettingsAction : AnAction(I18nBundle.message("action.settings"), null, com.intellij.icons.AllIcons.General.Settings) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(project, DshSettingsConfigurable::class.java)
    }
}
