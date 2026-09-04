package com.yg.dsh.idea.ui

import com.yg.dsh.idea.ui.actions.OpenSettingsAction
import com.yg.dsh.idea.ui.actions.RestartAction
import com.yg.dsh.idea.ui.actions.ReviewChangesAction
import com.yg.dsh.idea.util.Constants
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class ToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.contentManager.contents.forEach { old ->
            toolWindow.contentManager.removeContent(old, true)
        }
        val panel = ToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.setSelectedContent(content, true)
        toolWindow.setTitleActions(
            listOf(
                ReviewChangesAction(),
                RestartAction(panel),
                OpenSettingsAction()
            )
        )

        val normalIcon = com.intellij.openapi.util.IconLoader.getIcon("/icons/deepseek.svg", javaClass)
        val selectedIcon = com.intellij.openapi.util.IconLoader.getIcon("/icons/deepseek-light.svg", javaClass)
        val twId = Constants.TOOL_WINDOW_ID
        toolWindow.setIcon(if (toolWindow.isActive) selectedIcon else normalIcon)
        project.messageBus.connect().subscribe(
            com.intellij.openapi.wm.ex.ToolWindowManagerListener.TOPIC,
            object : com.intellij.openapi.wm.ex.ToolWindowManagerListener {
                override fun stateChanged(toolWindowManager: com.intellij.openapi.wm.ToolWindowManager) {
                    val tw = toolWindowManager.getToolWindow(twId)
                    tw?.setIcon(if (tw.isActive) selectedIcon else normalIcon)
                }
            }
        )
    }
}