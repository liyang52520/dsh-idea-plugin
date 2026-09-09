package com.yg.dsh.idea.runtime

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class WorkspaceInitializerTest {

    private var server: HttpServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop(0)
    }

    // ---- 链路：cookie 握手 → workspace/create ----

    @Test
    fun `ensureWorkspace posts workspace create rpc with new gateway contract`() {
        var receivedPath: String? = null
        var receivedMethod: String? = null
        var cookieSeen: String? = null
        var tokenHandshake = 0
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // Token → cookie handshake (GET /?token=...)
        srv.createContext("/") { ex ->
            if (ex.requestMethod == "GET" && ex.requestURI.query?.contains("token=") == true) {
                tokenHandshake++
                ex.responseHeaders.set("Set-Cookie", "dsh-auth-test=v1; Path=/; HttpOnly")
                ex.responseHeaders.set("Location", "/")
                ex.sendResponseHeaders(303, -1)
                ex.close()
                return@createContext
            }
            ex.sendResponseHeaders(404, -1)
            ex.close()
        }
        srv.createContext("/api/workspace/create") { ex ->
            cookieSeen = ex.requestHeaders.getFirst("Cookie")
            val body = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            receivedPath = Regex(""""path":"([^"]+)"""").find(body)?.groupValues?.get(1)
            receivedMethod = Regex(""""method":"([^"]+)"""").find(body)?.groupValues?.get(1)
            respond(ex, """{"type":"server-response","rpcId":"x","result":{"ok":true,"value":{"workspace":{"workspaceId":"ws-1","path":"$receivedPath"},"created":true}}}""")
        }
        srv.executor = Executors.newCachedThreadPool()
        srv.start()
        server = srv

        val base = "http://127.0.0.1:${srv.address.port}/?token=test-token"
        val ok = WorkspaceInitializer.ensureWorkspace(base, "D:/proj/MyApp")
        assertTrue(ok, "ensureWorkspace should return true on ok response")
        assertTrue(tokenHandshake == 1, "token handshake should happen once, got $tokenHandshake")
        assertTrue(cookieSeen?.startsWith("dsh-auth") == true, "create call should carry auth cookie, got $cookieSeen")
        assertTrue(receivedMethod == "workspace/create", "should call workspace/create, got $receivedMethod")
        assertTrue(receivedPath == "D:/proj/MyApp", "should send project path, got $receivedPath")
    }

    @Test
    fun `ensureWorkspace returns false on http error`() {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/") { ex ->
            ex.responseHeaders.set("Set-Cookie", "dsh-auth-test=v1; Path=/; HttpOnly")
            ex.sendResponseHeaders(303, -1)
            ex.close()
        }
        srv.createContext("/api/workspace/create") { ex ->
            respond(ex, """{"type":"server-response","rpcId":"x","result":{"ok":false,"error":"boom"}}""")
        }
        srv.start()
        server = srv
        val ok = WorkspaceInitializer.ensureWorkspace("http://127.0.0.1:${srv.address.port}/?token=t", "D:/proj")
        assertTrue(!ok, "should return false on non-ok response")
    }

    @Test
    fun `ensureWorkspace returns false on blank path`() {
        assertTrue(!WorkspaceInitializer.ensureWorkspace("http://127.0.0.1:1/?token=t", ""))
    }

    @Test
    fun `ensureWorkspace returns false when unreachable`() {
        // 端口 1 几乎必然拒绝连接；不抛异常，返回 false
        assertTrue(!WorkspaceInitializer.ensureWorkspace("http://127.0.0.1:1/?token=t", "D:/proj"))
    }

    private fun respond(ex: HttpExchange, resp: String) {
        val bytes = resp.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "application/json")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
