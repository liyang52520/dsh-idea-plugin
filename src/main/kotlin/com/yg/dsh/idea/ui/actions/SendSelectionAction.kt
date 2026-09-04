package com.yg.dsh.idea.ui.actions

import com.yg.dsh.idea.i18n.I18nBundle
import com.yg.dsh.idea.ui.ToolWindowPanel
import com.yg.dsh.idea.util.LanguageDetector
import com.yg.dsh.idea.util.Notifications
import com.yg.dsh.idea.util.TextUtils
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project

class SendSelectionAction : AnAction(I18nBundle.message("action.sendSelection")) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null &&
            (editor.selectionModel.hasSelection() || editor.document.text.isNotBlank())
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val data = ReadAction.compute<SelectionData, Throwable> {
            val doc = editor.document
            val sel = editor.selectionModel
            val vf = FileDocumentManager.getInstance().getFile(doc)
            val raw = if (sel.hasSelection()) sel.selectedText ?: "" else doc.text
            val capped = TextUtils.truncateUtf8(raw)
            val language = vf?.let { LanguageDetector.languageOf(it, project) }
            SelectionData(
                filePath = vf?.path,
                language = language,
                selection = capped,
                lineStart = if (sel.hasSelection()) doc.getLineNumber(sel.selectionStart) + 1 else 0,
                lineEnd = if (sel.hasSelection()) doc.getLineNumber(sel.selectionEnd) + 1 else 0,
            )
        }

        val panel = ToolWindowPanel.find(project)
        if (panel == null) {
            LOG.warn("Dsh tool window panel not available; clipboard fallback")
            ApplicationManager.getApplication().invokeLater {
                Notifications.copyToClipboard(data.selection)
                Notifications.show(project, I18nBundle.message("sendSelection.clipboard"))
            }
            return
        }
        ApplicationManager.getApplication().invokeLater {
            panel.sendSelection(data.filePath, data.language, data.selection, data.lineStart, data.lineEnd)
        }
    }

    private data class SelectionData(
        val filePath: String?,
        val language: String?,
        val selection: String,
        val lineStart: Int,
        val lineEnd: Int,
    )

    companion object {
        private val LOG = Logger.getInstance(SendSelectionAction::class.java)
    }
}