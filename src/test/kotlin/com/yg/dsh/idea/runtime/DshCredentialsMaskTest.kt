package com.yg.dsh.idea.runtime

import com.yg.dsh.idea.config.Credentials
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class CredentialsMaskTest {

    @Test
    fun `empty key masks to empty`() {
        assertEquals("", Credentials.maskApiKey(""))
    }

    @Test
    fun `null key masks to empty`() {
        assertEquals("", Credentials.maskApiKey(null))
    }

    @Test
    fun `long key keeps first and last six chips`() {
        // 32 位 key：前 6 位 + 掩码 + 后 6 位，中段全部隐藏
        val key = "sk-1234567890abcdefghijklmnopqrstuv"
        assertEquals("sk-123******qrstuv", Credentials.maskApiKey(key))
    }

    @Test
    fun `exactly 12 chained key is fully masked`() {
        // 长度恰为 12 → 无法同时保留前后各 6 位且中间有掩码 → 整段脱敏
        assertEquals("******", Credentials.maskApiKey("abcdefghijkl"))
    }

    @Test
    fun `short key is fully masked`() {
        assertEquals("******", Credentials.maskApiKey("abc"))
    }

    @Test
    fun `first and last six chips differ for a real key`() {
        val key = "sk-deepseek-someverylongkeyvalue-1234567890"
        val masked = Credentials.maskApiKey(key)
        assertEquals(key.take(6), masked.take(6))
        assertEquals(key.takeLast(6), masked.takeLast(6))
        assertEquals(6 + 6 + 6, masked.length) // 前6 + "******" + 后6
    }

    // ---- readApiKeyFromCredentialFile（从 DSH_HOME/.credentials.yaml 回退读取）----

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `reads key from credentials yaml`() {
        val f = tmp.resolve(".credentials.yaml")
        Files.writeString(f, "DEEPSEEK_API_KEY: sk-abcdef123456\n", StandardCharsets.UTF_8)
        assertEquals("sk-abcdef123456", Credentials.readApiKeyFromCredentialFile(f))
    }

    @Test
    fun `reads quoted key from credentials yaml`() {
        val f = tmp.resolve(".credentials.yaml")
        Files.writeString(f, "DEEPSEEK_API_KEY: \"sk-quoted-123456\"\n", StandardCharsets.UTF_8)
        assertEquals("sk-quoted-123456", Credentials.readApiKeyFromCredentialFile(f))
    }

    @Test
    fun `missing file or key returns null`() {
        // 文件不存在
        assertNull(Credentials.readApiKeyFromCredentialFile(tmp.resolve("none.yaml")))
        // 无该键
        val f = tmp.resolve(".credentials.yaml")
        Files.writeString(f, "other: value\n", StandardCharsets.UTF_8)
        assertNull(Credentials.readApiKeyFromCredentialFile(f))
    }

    @Test
    fun `ignores other lines and non-empty value`() {
        val f = tmp.resolve(".credentials.yaml")
        Files.writeString(
            f,
            "kind: api-key\nDEEPSEEK_API_KEY: sk-abc123456789\nother: x\n",
            StandardCharsets.UTF_8
        )
        assertEquals("sk-abc123456789", Credentials.readApiKeyFromCredentialFile(f))
    }
}
