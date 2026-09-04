package com.yg.dsh.idea.mcp

import com.yg.dsh.idea.util.JsonCodec
import com.yg.dsh.idea.util.LanguageDetector
import com.yg.dsh.idea.util.ProjectIgnoreRules
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.sun.net.httpserver.HttpExchange
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * JVM 内 MCP 终结点：无状态 streamable-http 的 JSON-RPC 2.0。
 *
 * DSH 的 dsh-mcp-client 直接 POST 到本进程（ide.yml patch 里配置 url 与
 * X-DSH-IDE-Token header），不再经过独立的 node 翻译进程。无状态模式只需：
 * initialize / notifications/initialized / ping / tools/list / tools/call。
 *
 * 线程模型：请求在 [IdeMcpServer] 的 HTTP worker 线程上处理——同步 VFS 刷新
 * 与文件 IO 在 worker 线程完成（不得在 EDT 上做同步刷新），纯 UI 动作
 * （打开/定位文件）经 invokeLater 异步派发到 EDT，RPC 不因 EDT 阻塞。
 *
 * 工具（模型侧名 mcp__ide__<raw>）：
 * ide_get_selection / ide_get_open_files / ide_get_project_tree / ide_get_sent_selection
 * / ide_open_file / ide_reveal_file / ide_refresh_files
 */
class McpRpcHandler(
    private val project: Project,
    private val sentQueue: SentSelectionQueue,
) {

    private data class ToolResult(val isError: Boolean, val data: Any?)

    private val toolHandlers: Map<String, (Map<String, Any?>) -> ToolResult> = linkedMapOf(
        "ide_get_selection" to ::toolGetSelection,
        "ide_get_open_files" to ::toolGetOpenFiles,
        "ide_get_project_tree" to ::toolGetProjectTree,
        "ide_get_sent_selection" to ::toolGetSentSelection,
        "ide_open_file" to ::toolOpenFile,
        "ide_reveal_file" to ::toolRevealFile,
        "ide_refresh_files" to ::toolRefreshFiles,
    )

    // ---- JSON-RPC 入口 ----

    fun handlePost(exchange: HttpExchange) {
        val raw = try {
            exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        } catch (e: Exception) {
            LOG.warn("mcp: failed to read request body", e)
            exchange.respondJson(400, rpcError(null, -32700, "Parse error"))
            return
        }
        val msg = JsonCodec.decodeObject(raw)
        val method = msg["method"] as? String
        if (method == null) {
            exchange.respondJson(400, rpcError(null, -32600, "Invalid Request"))
            return
        }
        val isNotification = !msg.containsKey("id")
        val id = msg["id"]
        try {
            when (method) {
                "initialize" -> {
                    val requested = (msg["params"] as? Map<*, *>)?.get("protocolVersion") as? String
                    val negotiated = if (requested != null && requested in SUPPORTED_PROTOCOL_VERSIONS) requested else LATEST_PROTOCOL_VERSION
                    if (negotiated != requested) {
                        exchange.responseHeaders.set("MCP-Protocol-Version", negotiated)
                    }
                    rpcResult(exchange, id, mapOf(
                        "protocolVersion" to negotiated,
                        "capabilities" to mapOf("tools" to mapOf("listChanged" to false)),
                        "serverInfo" to mapOf("name" to SERVER_NAME, "version" to SERVER_VERSION),
                    ))
                }
                "ping" -> rpcResult(exchange, id, emptyMap<String, Any?>())
                "tools/list" -> rpcResult(exchange, id, mapOf("tools" to TOOL_DESCRIPTORS))
                "tools/call" -> handleToolCall(exchange, id, msg["params"])
                else -> {
                    // 通知（含 notifications/initialized）→ 202 空体；其余未知方法 → -32601
                    if (isNotification || method.startsWith("notifications/")) {
                        exchange.sendResponseHeaders(202, -1)
                    } else {
                        rpcError(exchange, id, -32601, "Method not found: $method")
                    }
                }
            }
        } catch (e: Exception) {
            LOG.warn("mcp handler error: $method", e)
            if (isNotification) {
                runCatching { exchange.sendResponseHeaders(202, -1) }
            } else {
                rpcError(exchange, id, -32603, "Internal error: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** streamable-http 规范：无状态模式下 GET/DELETE /mcp 返回 405。 */
    fun handleMethodNotAllowed(exchange: HttpExchange) {
        exchange.respondJson(405, rpcError(null, -32000, "Method not allowed."))
    }

    private fun handleToolCall(exchange: HttpExchange, id: Any?, params: Any?) {
        val p = params as? Map<*, *>
        val name = p?.get("name") as? String
        if (name == null) {
            rpcError(exchange, id, -32602, "Invalid params: missing tool name")
            return
        }
        val handler = toolHandlers[name]
        if (handler == null) {
            rpcError(exchange, id, -32602, "Unknown tool: $name")
            return
        }
        @Suppress("UNCHECKED_CAST")
        val args = (p["arguments"] as? Map<String, Any?>) ?: emptyMap()
        val result = try {
            handler(args)
        } catch (e: Exception) {
            LOG.warn("mcp tool $name failed", e)
            ToolResult(true, mapOf("error" to (e.message ?: "internal error"), "code" to "internal"))
        }
        val content = mapOf("type" to "text", "text" to JsonCodec.encode(result.data))
        val payload = if (result.isError) {
            mapOf("content" to listOf(content), "isError" to true)
        } else {
            mapOf("content" to listOf(content))
        }
        rpcResult(exchange, id, payload)
    }

    // ---- 工具实现（直连 PSI/编辑器/VFS） ----

    private fun toolGetSelection(@Suppress("UNUSED_PARAMETER") args: Map<String, Any?>): ToolResult =
        ToolResult(false, ApplicationManager.getApplication().runReadAction(Computable<Map<String, Any?>> {
            val editor = currentEditor()
            if (editor == null) {
                mapOf("filePath" to null, "language" to null, "selection" to "",
                    "lineStart" to 0, "lineEnd" to 0, "projectName" to project.name)
            } else {
                val doc = editor.document
                val vf = FileDocumentManager.getInstance().getFile(doc)
                val sel = editor.selectionModel
                mapOf(
                    "filePath" to vf?.path,
                    "language" to vf?.let { LanguageDetector.languageOf(it, project) },
                    "selection" to (if (sel.hasSelection()) sel.selectedText ?: "" else ""),
                    "lineStart" to (if (sel.hasSelection()) doc.getLineNumber(sel.selectionStart) + 1 else 0),
                    "lineEnd" to (if (sel.hasSelection()) doc.getLineNumber(sel.selectionEnd) + 1 else 0),
                    "projectName" to project.name,
                )
            }
        }))

    private fun toolGetOpenFiles(@Suppress("UNUSED_PARAMETER") args: Map<String, Any?>): ToolResult =
        ToolResult(false, ApplicationManager.getApplication().runReadAction(Computable<Map<String, Any?>> {
            val fileDocManager = FileDocumentManager.getInstance()
            val files = FileEditorManager.getInstance(project).openFiles.map { vf ->
                val doc = fileDocManager.getDocument(vf)
                mapOf(
                    "path" to vf.path,
                    "language" to LanguageDetector.languageOf(vf, project),
                    "modified" to (doc != null && fileDocManager.isDocumentUnsaved(doc)),
                )
            }
            mapOf("files" to files)
        }))

    private fun toolGetProjectTree(args: Map<String, Any?>): ToolResult {
        val depth = (args["depth"] as? Number)?.toInt()?.coerceIn(1, 10) ?: 4
        val basePath = project.basePath
        if (basePath == null) {
            return ToolResult(false, mapOf("roots" to emptyList<Any>()))
        }
        val root = ApplicationManager.getApplication().runReadAction(Computable<Map<String, Any?>?> {
            LocalFileSystem.getInstance().findFileByPath(basePath)?.let { buildNode(it, depth, 0) }
        })
        return ToolResult(false, mapOf("roots" to listOf(root ?: emptyMap<String, Any?>())))
    }

    private fun toolGetSentSelection(@Suppress("UNUSED_PARAMETER") args: Map<String, Any?>): ToolResult {
        val latest = sentQueue.latest()
            ?: return ToolResult(false, mapOf("error" to "empty", "code" to "empty"))
        return ToolResult(false, latest.toMap())
    }

    private fun toolOpenFile(args: Map<String, Any?>): ToolResult {
        val path = args["path"] as? String
        if (path.isNullOrBlank()) {
            return ToolResult(true, mapOf("error" to "path required", "code" to "bad_request"))
        }
        // 同步 VFS 刷新留在 HTTP worker 线程；打开文件是纯 UI 动作，异步派发即可。
        val vf = refreshPath(path) ?: return fileNotFound(path)
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
        return ToolResult(false, mapOf("ok" to true, "path" to path))
    }

    private fun toolRevealFile(args: Map<String, Any?>): ToolResult {
        val path = args["path"] as? String
        if (path.isNullOrBlank()) {
            return ToolResult(true, mapOf("error" to "path required", "code" to "bad_request"))
        }
        val vf = refreshPath(path) ?: return fileNotFound(path)
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            FileEditorManager.getInstance(project).openFile(vf, true)
            val projectView = ProjectView.getInstance(project)
            if (projectView != null) {
                // 二次延迟：等编辑器打开后再在项目树里定位，避免树尚未刷新。
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    runCatching { projectView.select(null, vf, true) }
                }
            }
        }
        return ToolResult(false, mapOf("ok" to true, "path" to path))
    }

    private fun toolRefreshFiles(args: Map<String, Any?>): ToolResult {
        val requested = (args["paths"] as? List<*>)?.filterIsInstance<String>()?.filter { it.isNotBlank() } ?: emptyList()
        val paths = if (requested.isEmpty()) listOfNotNull(project.basePath) else requested
        val refreshed = mutableListOf<String>()
        val missing = mutableListOf<String>()
        // 全程 worker 线程：同步刷新 + canonical path 检查都不碰 EDT。
        for (path in paths) {
            if (!isProjectPath(path)) {
                missing += path
                continue
            }
            if (refreshPath(path) != null) refreshed += path else missing += path
        }
        return ToolResult(false, mapOf("ok" to true, "refreshed" to refreshed, "missing" to missing))
    }

    // ---- IDE helpers ----

    private fun refreshPath(path: String): VirtualFile? {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: return null
        VfsUtil.markDirtyAndRefresh(false, vf.isDirectory, true, vf)
        return vf
    }

    private fun isProjectPath(path: String): Boolean = try {
        val base = project.basePath ?: return false
        File(path).canonicalFile.toPath().startsWith(File(base).canonicalFile.toPath())
    } catch (_: IOException) {
        false
    }

    private fun currentEditor() =
        (FileEditorManager.getInstance(project).selectedEditor as? TextEditor)?.editor

    private fun buildNode(vf: VirtualFile, maxDepth: Int, depth: Int): Map<String, Any?> {
        val isDir = vf.isDirectory
        var truncated = false
        val children: List<Map<String, Any?>> = if (isDir && depth < maxDepth) {
            val visible = vf.children.asSequence()
                .filter { !ProjectIgnoreRules.isIgnoredDir(it.name) }
                .sortedWith(compareBy({ if (it.isDirectory) 0 else 1 }, { it.name }))
                .toList()
            truncated = visible.size > MAX_CHILDREN
            visible.take(MAX_CHILDREN).map { buildNode(it, maxDepth, depth + 1) }
        } else emptyList()
        return mapOf(
            "path" to vf.path,
            "name" to vf.name,
            "type" to if (isDir) "dir" else "file",
            "truncated" to truncated,
            "children" to children,
        )
    }

    private fun SentSelectionQueue.Item.toMap(): Map<String, Any?> = mapOf(
        "id" to id, "filePath" to filePath, "language" to language,
        "selection" to selection, "lineStart" to lineStart, "lineEnd" to lineEnd, "ts" to ts,
    )

    private fun fileNotFound(path: String) = ToolResult(true, mapOf(
        "ok" to false, "error" to "file not found", "code" to "file_not_found", "path" to path))

    // ---- JSON-RPC 序列化 ----

    private fun rpcResult(exchange: HttpExchange, id: Any?, result: Any?) {
        exchange.respondJson(200, mapOf("jsonrpc" to "2.0", "id" to id, "result" to result))
    }

    private fun rpcError(exchange: HttpExchange, id: Any?, code: Int, message: String) {
        exchange.respondJson(200, rpcError(id, code, message))
    }

    private fun rpcError(id: Any?, code: Int, message: String) =
        mapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to code, "message" to message))

    companion object {
        private val LOG = Logger.getInstance(McpRpcHandler::class.java)

        private const val SERVER_NAME = "dsh-ide-bridge"
        private const val SERVER_VERSION = "0.2.0"
        private const val LATEST_PROTOCOL_VERSION = "2025-11-25"
        private val SUPPORTED_PROTOCOL_VERSIONS =
            setOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05", "2024-10-07")

        /** 目录树单层子节点上限；超出时置 truncated 标记，让模型知道被截断。 */
        private const val MAX_CHILDREN = 200

        private fun toolDescriptor(name: String, description: String, inputSchema: Map<String, Any?>): Map<String, Any?> =
            mapOf("name" to name, "description" to description, "inputSchema" to inputSchema)

        private val TOOL_DESCRIPTORS: List<Map<String, Any?>> = listOf(
            toolDescriptor("ide_get_selection",
                "Get the code currently selected in the IntelliJ editor (file path, language, line numbers)",
                mapOf("type" to "object", "properties" to emptyMap<String, Any?>())),
            toolDescriptor("ide_get_open_files",
                "List files currently open in the IDE (path, language, unsaved flag)",
                mapOf("type" to "object", "properties" to emptyMap<String, Any?>())),
            toolDescriptor("ide_get_project_tree",
                "Get the project directory tree (build output and other noisy directories are ignored)",
                mapOf("type" to "object", "properties" to mapOf(
                    "depth" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 10,
                        "description" to "Traversal depth, default 4")))),
            toolDescriptor("ide_get_sent_selection",
                "Get the code selection most recently sent from the IDE via a send action (context survives even if the web composer injection failed)",
                mapOf("type" to "object", "properties" to emptyMap<String, Any?>())),
            toolDescriptor("ide_open_file",
                "Open the specified file in the IntelliJ editor",
                mapOf("type" to "object",
                    "properties" to mapOf("path" to mapOf("type" to "string", "description" to "Absolute file path")),
                    "required" to listOf("path"))),
            toolDescriptor("ide_reveal_file",
                "Reveal the specified file in the IntelliJ project view",
                mapOf("type" to "object",
                    "properties" to mapOf("path" to mapOf("type" to "string", "description" to "Absolute file path")),
                    "required" to listOf("path"))),
            toolDescriptor("ide_refresh_files",
                "Refresh the IntelliJ VFS so files modified or created by external processes are picked up",
                mapOf("type" to "object", "properties" to mapOf(
                    "paths" to mapOf("type" to "array", "items" to mapOf("type" to "string"),
                        "description" to "Absolute file or directory paths to refresh; empty refreshes the current project")))),
        )
    }
}
