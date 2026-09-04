package com.yg.dsh.idea.ui.actions

import com.yg.dsh.idea.i18n.I18nBundle
import com.yg.dsh.idea.review.ReviewDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class ReviewChangesAction : AnAction(I18nBundle.message("action.review"), null, com.intellij.icons.AllIcons.Actions.Diff) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && !project.isDisposed
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReviewDialog(project).show()
    }
}