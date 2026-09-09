package com.yg.dsh.idea.runtime

import com.intellij.openapi.diagnostic.Logger

/**
 * Registers the current project as a DSH workspace on startup
 * (workspace/create over the typert gateway RPC).
 *
 * Newer DSH builds expose no workspace-list/reorder RPC (workspace order is
 * projected to clients via the event stream), so registration is the whole
 * job here: once created, the project shows up in the DSH sidebar.
 */
object WorkspaceInitializer {

    private val LOG = Logger.getInstance(WorkspaceInitializer::class.java)

    fun ensureWorkspace(webUrl: String, projectPath: String): Boolean {
        if (projectPath.isBlank()) return false
        return try {
            val path = projectPath.replace('\\', '/')
            val created = DshApiClient.rpc(webUrl, "workspace/create", mapOf("path" to path))
            if (!created.ok) {
                LOG.warn("workspace/create failed: ${created.errorText}")
                return false
            }
            val workspaceId = extractWorkspaceId(created.value)
            val isNew = created.value["created"] == true
            LOG.info("workspace.ensureWorkspace ok for $projectPath (id=$workspaceId, created=$isNew)")
            true
        } catch (e: Exception) {
            LOG.warn("workspace.ensureWorkspace error for $projectPath", e)
            false
        }
    }

    private fun extractWorkspaceId(value: Map<String, Any?>): String? =
        (value["workspace"] as? Map<*, *>)?.get("workspaceId") as? String
}
