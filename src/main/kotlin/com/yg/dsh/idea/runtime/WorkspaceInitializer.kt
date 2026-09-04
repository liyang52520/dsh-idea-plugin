package com.yg.dsh.idea.runtime

import com.intellij.openapi.diagnostic.Logger

object WorkspaceInitializer {

    private val LOG = Logger.getInstance(WorkspaceInitializer::class.java)

    fun ensureWorkspace(webUrl: String, projectPath: String): Boolean {
        if (projectPath.isBlank()) return false
        return try {
            val base = webUrl.trimEnd('/')
            val path = projectPath.replace('\\', '/')
            val created = DshApiClient.rpc(base, "workspace.create", mapOf("path" to path))
            if (!created.ok) {
                LOG.warn("workspace.create failed: ${created.errorText}")
                return false
            }
            val workspaceId = extractWorkspaceId(created.value)
            if (workspaceId != null) bringToFront(base, workspaceId)
            LOG.info("workspace.ensureWorkspace ok for $projectPath")
            true
        } catch (e: Exception) {
            LOG.warn("workspace.ensureWorkspace error for $projectPath", e)
            false
        }
    }

    fun computeBringToFront(currentOrder: List<String>, targetId: String): Pair<String, String>? {
        val first = currentOrder.firstOrNull() ?: return null
        return if (first == targetId) null else targetId to first
    }

    private fun extractWorkspaceId(value: Map<String, Any?>): String? =
        (value["workspace"] as? Map<*, *>)?.get("workspaceId") as? String

    private fun bringToFront(base: String, workspaceId: String) {
        val list = DshApiClient.rpc(base, "workspace.list", emptyMap())
        if (!list.ok) {
            LOG.warn("workspace.list failed: ${list.errorText}")
            return
        }
        val order = (list.value["items"] as? List<*>)
            ?.mapNotNull { (it as? Map<*, *>)?.get("workspaceId") as? String }
            .orEmpty()
        val move = computeBringToFront(order, workspaceId) ?: return
        val moved = DshApiClient.rpc(
            base,
            "workspace.insertBefore",
            mapOf("workspaceId" to move.first, "beforeWorkspaceId" to move.second),
        )
        if (moved.ok) {
            LOG.info("workspace $workspaceId moved to front (order=${moved.value["workspaceIds"]})")
        } else {
            LOG.warn("workspace.insertBefore failed: ${moved.errorText}")
        }
    }
}
