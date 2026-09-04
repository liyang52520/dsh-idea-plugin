package com.yg.dsh.idea.runtime

import com.yg.dsh.idea.ui.ToolWindowPanel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.APP)
class PanelRegistry : ProjectManagerListener {

    private val livePanels = ConcurrentHashMap<String, ToolWindowPanel>()

    fun register() {
        ProjectManager.getInstance().addProjectManagerListener(this)
    }

    fun registerPanel(projectName: String, panel: ToolWindowPanel) {
        livePanels[projectName] = panel
    }

    fun unregisterPanel(projectName: String) {
        livePanels.remove(projectName)
    }

    /** Snapshot of currently live tool-window panels (one per open project). */
    fun livePanels(): Collection<ToolWindowPanel> = livePanels.values.toList()

    override fun projectClosed(project: Project) {
        LOG.info("project closed: ${project.name}")
        unregisterPanel(project.name)
    }

    fun onAppClosing() {
        LOG.info("IDE closing: disposing ${livePanels.size} DSH panel(s)")
        livePanels.values.forEach { panel ->
            runCatching { panel.dispose() }
        }
        livePanels.clear()
        for (p in ProjectManager.getInstance().openProjects) {
            runCatching { RuntimeRegistry.getInstance().release(p.name) }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(PanelRegistry::class.java)

        fun getInstance(): PanelRegistry =
            ApplicationManager.getApplication().getService(PanelRegistry::class.java)
    }
}