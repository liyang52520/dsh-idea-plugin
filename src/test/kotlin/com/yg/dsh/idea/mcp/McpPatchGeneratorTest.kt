package com.yg.dsh.idea.mcp

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpPatchGeneratorTest {

    private val token = "a".repeat(64)

    @Test
    fun `generates insert syntax with dynamic port and token header`() {
        val patch = PatchGenerator.generate(3187, token)
        assertTrue(patch.contains("- insert:"), "must use insert syntax: $patch")
        assertTrue(patch.contains("id: mcp.ide"), "must declare mcp.ide id")
        assertTrue(patch.contains("name: '@deepseek-ai/dsh-mcp-client'"), "must reference the mcp-client plugin")
        assertTrue(patch.contains("serverName: ide"))
        assertTrue(patch.contains("transport: streamable-http"))
        assertTrue(patch.contains("url: http://127.0.0.1:3187/mcp"), "must contain dynamic mcp port")
        assertTrue(patch.contains("headers:"), "must declare headers map")
        assertTrue(patch.contains("X-DSH-IDE-Token: $token"), "must pass MCP endpoint token via header")
        assertTrue(patch.contains("toolCallTimeoutMs: 60000"))
        assertTrue(patch.contains("reconnect:"))
        assertTrue(patch.contains("maxAttempts: 3"))
    }

    @Test
    fun `port appears exactly once in url`() {
        val patch = PatchGenerator.generate(9999, token)
        // url 中的端口恰好出现一次（生成逻辑不重复拼接）
        val urlMatches = Regex("""http://127\.0\.0\.1:9999/mcp""").findAll(patch).count()
        assertTrue(urlMatches == 1, "url with port should appear exactly once: $patch")
    }

    @Test
    fun `strict variant enables failOnStartupError`() {
        val patch = PatchGenerator.generateStrict(1234, token)
        assertTrue(patch.contains("failOnStartupError: true"))
        assertTrue(patch.contains("X-DSH-IDE-Token: $token"))
    }

    @Test
    fun `generate and strict share base shape`() {
        val base = PatchGenerator.generate(5000, token)
        val strict = PatchGenerator.generateStrict(5000, token)
        assertTrue(base.contains("url: http://127.0.0.1:5000/mcp"))
        assertTrue(strict.contains("url: http://127.0.0.1:5000/mcp"))
        assertTrue(!base.contains("failOnStartupError"))
    }

    @Test
    fun `patch never includes settings-credentials directives`() {
        val patch = PatchGenerator.generate(8080, token)
        assertTrue(!patch.contains("\$settings"), "no \$settings directive: $patch")
        assertTrue(!patch.contains("\$credentials"), "no \$credentials directive: $patch")
        assertTrue(!patch.contains("settings.yaml"), "no settings.yaml path: $patch")
    }
}
