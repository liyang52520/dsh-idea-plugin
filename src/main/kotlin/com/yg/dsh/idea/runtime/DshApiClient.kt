package com.yg.dsh.idea.runtime

import com.yg.dsh.idea.util.JsonCodec
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * DSH web 后端 JSON-RPC 客户端，适配新版 DSH（typert gateway）契约：
 *
 * 1. **认证**：DSH 每个进程启动时打印 `http://127.0.0.1:<port>/?token=<tok>`。
 *    首次 GET 该 URL 会得到 303 + `Set-Cookie: dsh-auth-...`，之后所有
 *    `/api/...` 请求都必须携带该 cookie（query token 对 /api 无效）。
 * 2. **路径**：`POST /api/<namespace>/<method>`（斜杠分隔，如 `session/list`）。
 * 3. **信封**：`{type:"client-request", rpcId, method, payload:{args:{...}}}`；
 *    无业务参数的方法 args 为 `{_request:{}}`，有业务参数时包一层 `request`。
 *
 * [base] 入参为带 token 的完整 URL（即 ProcessManager.webUrl() 的返回值）。
 */
object DshApiClient {

    private val LOG = Logger.getInstance(DshApiClient::class.java)

    data class RpcResult(
        val ok: Boolean,
        val value: Map<String, Any?> = emptyMap(),
        val errorText: String = "",
    )

    /** Cached auth cookie headers keyed by server origin (scheme://host:port). */
    private val authCookies = ConcurrentHashMap<String, String>()

    private data class Endpoint(val origin: String, val token: String?)

    private fun parseEndpoint(base: String): Endpoint? {
        val uri = runCatching { URI(base) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val origin = "${uri.scheme}://$host:${uri.port}"
        val token = uri.query?.split("&")
            ?.mapNotNull { kv ->
                val idx = kv.indexOf('=')
                if (idx > 0 && kv.substring(0, idx) == "token") kv.substring(idx + 1) else null
            }
            ?.firstOrNull()
        return Endpoint(origin, token)
    }

    /**
     * Performs one RPC call. [method] uses the slash form (e.g. "session/create").
     * [request] holds the business arguments (sent as `payload.args.request`);
     * pass null for argument-less methods (sent as `payload.args._request`).
     */
    fun rpc(base: String, method: String, request: Map<String, Any?>?): RpcResult {
        val ep = parseEndpoint(base) ?: return RpcResult(false, errorText = "invalid base url: $base")
        return rpcWithCookie(ep, method, request, retryOnAuth = true)
    }

    private fun rpcWithCookie(ep: Endpoint, method: String, request: Map<String, Any?>?, retryOnAuth: Boolean): RpcResult {
        val cookie = ensureCookie(ep)
        val rpcId = "dsh-idea-" + UUID.randomUUID().toString()
        val args: Map<String, Any?> = if (request == null) mapOf("_request" to emptyMap<String, Any?>()) else mapOf("request" to request)
        val body = JsonCodec.encode(
            mapOf(
                "type" to "client-request",
                "rpcId" to rpcId,
                "method" to method,
                "payload" to mapOf("args" to args),
            )
        )
        val conn = URI("${ep.origin}/api/$method").toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (cookie != null) conn.setRequestProperty("Cookie", cookie)
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val resp = if (code in 200..299) {
                conn.inputStream.use { it.bufferedReader().readText() }
            } else {
                conn.errorStream?.use { it.bufferedReader().readText() } ?: ""
            }
            // Cookie expired/rejected: exchange it once and retry the same call.
            if (code == 401 && retryOnAuth) {
                LOG.info("dsh rpc 401 for $method; refreshing auth cookie")
                authCookies.remove(ep.origin)
                return rpcWithCookie(ep, method, request, retryOnAuth = false)
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
     * Exchanges the banner token for the `dsh-auth-*` session cookie.
     * The token handshake answers 303 with Set-Cookie; redirects are disabled
     * because the redirect target carries neither token nor cookie.
     */
    private fun ensureCookie(ep: Endpoint): String? {
        authCookies[ep.origin]?.let { return it }
        val token = ep.token ?: return null
        return try {
            val conn = URI("${ep.origin}/?token=$token").toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            // HttpURLConnection canonicalizes response header names (e.g. "Set-Cookie" → "Set-cookie"),
            // so match case-insensitively.
            val cookies = conn.headerFields
                ?.filterKeys { it?.equals("Set-Cookie", ignoreCase = true) == true }
                ?.values
                ?.flatten()
                ?.mapNotNull { header ->
                    header.substringBefore(';').takeIf { it.startsWith("dsh-auth") }
                }
                .orEmpty()
            conn.disconnect()
            cookies.firstOrNull()?.also { authCookies[ep.origin] = it }
        } catch (e: Exception) {
            LOG.warn("dsh auth cookie exchange failed for ${ep.origin}: ${e.message}")
            null
        }
    }

    /**
     * 找到绑定到 [cwd] 的最近会话；没有则新建。
     * 路径按 canonical 形式比较（DSH 落盘/上报可能是 realpath）。
     */
    fun ensureSession(base: String, cwd: String): String? {
        if (cwd.isBlank()) return null
        val canonicalCwd = runCatching { File(cwd).canonicalPath }.getOrDefault(cwd)
        val list = rpc(base, "session/list", null)
        if (list.ok) {
            val match = (list.value["items"] as? List<*>)
                ?.filterIsInstance<Map<*, *>>()
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
            LOG.warn("session/list failed: ${list.errorText}")
        }
        val created = rpc(base, "session/create", mapOf("cwd" to cwd.replace('\\', '/')))
        if (!created.ok) {
            LOG.warn("session/create failed: ${created.errorText}")
            return null
        }
        return created.value["sessionId"] as? String
    }

    /**
     * 向 [cwd] 对应会话发送一条用户消息（session/prompt），会话不存在则创建。
     * 用于"一键解释"这类意图明确的自动发送；草稿式注入（用户还要补充）不走这里——
     * 草稿只存在于网页前端内存，后端没有 draft 接口。
     */
    fun sendMessage(base: String, cwd: String, text: String): Boolean {
        if (text.isBlank()) return false
        val sessionId = ensureSession(base, cwd) ?: return false
        val res = rpc(
            base,
            "session/prompt",
            mapOf(
                "requestId" to UUID.randomUUID().toString(),
                "sessionId" to sessionId,
                "mode" to "queue",
                "content" to listOf(mapOf("type" to "text", "text" to text)),
            ),
        )
        if (res.ok) {
            LOG.info("session/prompt accepted session=$sessionId")
        } else {
            LOG.warn("session/prompt failed: ${res.errorText}")
        }
        return res.ok
    }
}
