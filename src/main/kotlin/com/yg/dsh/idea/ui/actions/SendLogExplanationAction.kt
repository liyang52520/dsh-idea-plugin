package com.yg.dsh.idea.ui.actions

import com.yg.dsh.idea.i18n.I18nBundle
import com.yg.dsh.idea.ui.ToolWindowPanel
import com.yg.dsh.idea.ui.LogExplanationBuilder
import com.yg.dsh.idea.util.Notifications
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

class SendLogExplanationAction : AnAction(I18nBundle.message("action.sendLogExplanation")) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null && editor.selectionModel.hasSelection()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val selected = ReadAction.compute<String, Throwable> {
            editor.selectionModel.selectedText ?: ""
        }
        if (selected.isBlank()) return
        val message = LogExplanationBuilder.buildMessage(
            I18nBundle.message("sendLogExplanation.prompt"),
            selected,
        )
        if (message.isBlank()) return

        val panel = ToolWindowPanel.find(project)
        if (panel == null) {
            LOG.warn("Dsh tool window panel not available; clipboard fallback")
            ApplicationManager.getApplication().invokeLater {
                Notifications.copyToClipboard(message)
                Notifications.show(project, I18nBundle.message("sendLogExplanation.notRunning"))
            }
            return
        }
        ApplicationManager.getApplication().invokeLater {
            panel.sendQuestion(message)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(SendLogExplanationAction::class.java)
    }
}