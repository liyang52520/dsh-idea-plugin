package com.yg.dsh.idea.runtime

import com.yg.dsh.idea.util.JsonCodec
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * DSH web 后端 `/api/<method>` JSON-RPC 客户端（信封：`client-request`，
 * 见 WorkspaceInitializer 既有契约）。零 DOM 依赖——插件以"后端调用方"身份
 * 直接发指令，不模拟用户在网页上点鼠标。
 */
object DshApiClient {

    private val LOG = Logger.getInstance(DshApiClient::class.java)

    data class RpcResult(
        val ok: Boolean,
        val value: Map<String, Any?> = emptyMap(),
        val errorText: String = "",
    )

    fun rpc(base: String, method: String, payload: Map<String, Any?>): RpcResult {
        val rpcId = "dsh-idea-" + UUID.randomUUID().toString()
        val body = JsonCodec.encode(
            mapOf(
                "type" to "client-request",
                "rpcId" to rpcId,
                "method" to method,
                "payload" to payload,
            )
        )
        val conn = URI("$base/api/$method").toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val resp = if (code in 200..299) {
                conn.inputStream.use { it.bufferedReader().readText() }
            } else {
                conn.errorStream?.use { it.bufferedReader().readText() } ?: ""
            }
            if (code !in 200..299) return RpcResult(false, errorText = "http $code: ${resp.take(200)}")
            val parsed = runCatching { JsonCodec.decodeObject(resp) }.getOrNull()
                ?: return RpcResult(false, errorText = "unparseable response: ${resp.take(200)}")
            val result = parsed["result"] as? Map<*, *>
                ?: return RpcResult(false, errorText = "unexpected response: ${resp.take(200)}")
            return if (result["ok"] == true) {
                @Suppress("UNCHECKED_CAST")
                RpcResult(true, (result["value"] as? Map<String, Any?>) ?: emptyMap())
            } else {
                val err = result["error"]
                val errText = when (err) {
                    is String -> err
                    is Map<*, *> -> (err["message"] as? String) ?: resp
                    else -> resp
                }
                RpcResult(false, errorText = errText.take(500))
            }
        } catch (e: Exception) {
            return RpcResult(false, errorText = e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 找到绑定到 [cwd] 的最近会话；没有则新建。跳过 subagent 会话。
     * 路径按 canonical 形式比较（DSH 落盘/上报可能是 realpath）。
     */
    fun ensureSession(base: String, cwd: String): String? {
        if (cwd.isBlank()) return null
        val canonicalCwd = runCatching { File(cwd).canonicalPath }.getOrDefault(cwd)
        val list = rpc(base, "session.list", emptyMap())
        if (list.ok) {
            val match = (list.value["items"] as? List<*>)
                ?.filterIsInstance<Map<*, *>>()
                ?.filter { it["origin"] != "subagent" }
                ?.mapNotNull { row ->
                    val sid = row["sessionId"] as? String ?: return@mapNotNull null
                    val rowCwd = row["cwd"] as? String ?: return@mapNotNull null
                    val same = runCatching { File(rowCwd).canonicalPath == canonicalCwd }.getOrDefault(false)
                    if (!same) return@mapNotNull null
                    val updated = (row["updatedAt"] as? Number)?.toLong() ?: 0L
                    sid to updated
                }
                ?.maxByOrNull { it.second }
            if (match != null) return match.first
        } else {
            LOG.warn("session.list failed: ${list.errorText}")
        }
        val created = rpc(base, "session.create", mapOf("cwd" to cwd.replace('\\', '/')))
        if (!created.ok) {
            LOG.warn("session.create failed: ${created.errorText}")
            return null
        }
        return created.value["sessionId"] as? String
    }

    /**
     * 向 [cwd] 对应会话发送一条用户消息（session.prompt，mode=queue），会话不存在则创建。
     * 用于"一键解释"这类意图明确的自动发送；草稿式注入（用户还要补充）不走这里——
     * 草稿只存在于网页前端内存，后端没有 draft 接口。
     *
     * 注意不是 goal.create：会话自带 active goal 壳，goal.create 对已有 active goal
     * 会报 GOAL_ALREADY_EXISTS；发消息的正确契约是 session.prompt。
     */
    fun sendMessage(base: String, cwd: String, text: String): Boolean {
        if (text.isBlank()) return false
        val sessionId = ensureSession(base, cwd) ?: return false
        val res = rpc(
            base,
            "session.prompt",
            mapOf(
                "sessionId" to sessionId,
                "mode" to "queue",
                "content" to listOf(mapOf("type" to "text", "text" to text)),
            ),
        )
        if (res.ok) {
            LOG.info("session.prompt accepted session=$sessionId")
        } else {
            LOG.warn("session.prompt failed: ${res.errorText}")
        }
        return res.ok
    }
}
