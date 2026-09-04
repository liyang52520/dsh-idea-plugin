package com.yg.dsh.idea.mcp

import com.yg.dsh.idea.util.Constants

object PatchGenerator {

    const val SERVER_NAME = "ide"
    const val TOKEN_HEADER = "X-DSH-IDE-Token"
    private const val PLUGIN = "@deepseek-ai/dsh-mcp-client"
    private const val TOOL_TIMEOUT_MS = 60000
    private const val RECONNECT_MAX_ATTEMPTS = 3

    /**
     * 生成 ide.yml patch：DSH 的 dsh-mcp-client 以 streamable-http 直连 IDE 进程内的
     * MCP 终结点（http://<host>:<port>/mcp），鉴权 token 经 headers 透传。
     */
    fun generate(port: Int, token: String, host: String = Constants.LOOPBACK_HOST): String {
        val url = "http://$host:$port/mcp"
        return buildString {
            appendLine("- insert:")
            appendLine("    - id: mcp.$SERVER_NAME")
            appendLine("      name: '$PLUGIN'")
            appendLine("      config:")
            appendLine("        serverName: $SERVER_NAME")
            appendLine("        transport: streamable-http")
            appendLine("        url: $url")
            appendLine("        headers:")
            appendLine("          $TOKEN_HEADER: $token")
            appendLine("        toolCallTimeoutMs: $TOOL_TIMEOUT_MS")
            appendLine("        reconnect:")
            appendLine("          enabled: true")
            appendLine("          maxAttempts: $RECONNECT_MAX_ATTEMPTS")
        }
    }

    fun generateStrict(port: Int, token: String, host: String = Constants.LOOPBACK_HOST): String {
        val base = generate(port, token, host)
        return base.replace(
            "        toolCallTimeoutMs: $TOOL_TIMEOUT_MS\n",
            "        toolCallTimeoutMs: $TOOL_TIMEOUT_MS\n        failOnStartupError: true\n"
        )
    }
}
