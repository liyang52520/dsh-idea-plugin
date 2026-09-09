package com.yg.dsh.idea.runtime

import com.yg.dsh.idea.runtime.process.PortParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PortParserTest {

    @Test
    fun `parses banner line with token`() {
        val ep = PortParser.parse("dsh web: http://127.0.0.1:54451/?token=ATgx-4NHooP2AAU6sVN1DHwjRL4uYMNzuFGxSY-3ZQI")
        assertEquals(54451, ep?.port)
        assertEquals("ATgx-4NHooP2AAU6sVN1DHwjRL4uYMNzuFGxSY-3ZQI", ep?.token)
        assertEquals("http://127.0.0.1:54451/?token=ATgx-4NHooP2AAU6sVN1DHwjRL4uYMNzuFGxSY-3ZQI", ep?.url)
    }

    @Test
    fun `parses banner line with lan suffix`() {
        val ep = PortParser.parse("dsh web: http://127.0.0.1:3080/?token=abc_123-XYZ (LAN: http://192.168.1.5:3080)")
        assertEquals(3080, ep?.port)
        assertEquals("abc_123-XYZ", ep?.token)
    }

    @Test
    fun `parses banner even with leading log noise`() {
        val ep = PortParser.parse("2026-01-01 10:00:00 INFO dsh web: http://127.0.0.1:8080/?token=tok")
        assertEquals(8080, ep?.port)
        assertEquals("tok", ep?.token)
    }

    @Test
    fun `returns null for tokenless legacy banner`() {
        assertNull(PortParser.parse("dsh web: http://127.0.0.1:54451"))
    }

    @Test
    fun `returns null for unrelated lines`() {
        assertNull(PortParser.parse("node:internal/modules/cjs/loader:123"))
        assertNull(PortParser.parse(""))
        assertNull(PortParser.parse("http://127.0.0.1:9999/?token=x without marker"))
    }
}
