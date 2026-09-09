package com.yg.dsh.idea.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 运行实例登记逻辑测试。
 * 注：RuntimeRegistry 是 applicationService，需要 Application 实例；
 * 此处直接实例化以测试登记/释放语义（已无并发上限）。
 */
class RuntimeRegistryTest {

    @Test
    fun `register any number of projects`() {
        val registry = RuntimeRegistry()
        val handle = Any()
        registry.register("proj-1", handle)
        registry.register("proj-2", handle)
        registry.register("proj-3", handle)
        registry.register("proj-4", handle)
        assertEquals(4, registry.runningCount())
        assertTrue(registry.isRunning("proj-4"))
    }

    @Test
    fun `release removes a registration`() {
        val registry = RuntimeRegistry()
        val handle = Any()
        registry.register("a", handle)
        registry.register("b", handle)
        registry.release("a")
        assertEquals(1, registry.runningCount())
        assertFalse(registry.isRunning("a"))
        // 释放后可再登记新项目
        registry.register("c", handle)
        assertEquals(2, registry.runningCount())
    }

    @Test
    fun `same project is idempotent`() {
        val registry = RuntimeRegistry()
        val handle = Any()
        registry.register("dup", handle)
        registry.register("dup", handle) // 幂等：已有实例
        assertEquals(1, registry.runningCount())
        registry.release("dup")
        assertEquals(0, registry.runningCount())
    }
}
