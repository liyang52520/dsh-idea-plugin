package com.yg.dsh.idea.util

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ProjectIgnoreRulesTest {

    @Test
    fun `ignores known build and vcs directories`() {
        for (name in listOf(".git", "node_modules", "build", "out", ".idea", "target", "dist", ".gradle")) {
            assertTrue(ProjectIgnoreRules.isIgnoredDir(name), "should ignore dir: $name")
        }
    }

    @Test
    fun `keeps normal directories`() {
        for (name in listOf("src", "main", "kotlin", "docs", "scripts")) {
            assertFalse(ProjectIgnoreRules.isIgnoredDir(name), "should keep dir: $name")
        }
    }

    @Test
    fun `ignores hidden files and dirs`() {
        assertTrue(ProjectIgnoreRules.isIgnoredDir(".hidden"))
        assertTrue(ProjectIgnoreRules.isIgnoredFile(".secret", 100))
        assertFalse(ProjectIgnoreRules.isIgnoredFile("Main.kt", 100))
    }

    @Test
    fun `ignores files above 1MB`() {
        assertTrue(ProjectIgnoreRules.isIgnoredFile("big.bin", ProjectIgnoreRules.MAX_FILE_BYTES + 1))
        assertFalse(ProjectIgnoreRules.isIgnoredFile("small.txt", ProjectIgnoreRules.MAX_FILE_BYTES))
        assertFalse(ProjectIgnoreRules.isIgnoredFile("exact.bin", ProjectIgnoreRules.MAX_FILE_BYTES))
    }

    @Test
    fun `ignored path matches any ancestor`() {
        assertTrue(ProjectIgnoreRules.isIgnoredPath(Path.of("src/node_modules/pkg/index.js")))
        assertTrue(ProjectIgnoreRules.isIgnoredPath(Path.of("build/out/app.js")))
        assertTrue(ProjectIgnoreRules.isIgnoredPath(Path.of(".idea/workspace.xml")))
        assertFalse(ProjectIgnoreRules.isIgnoredPath(Path.of("src/main/kotlin/App.kt")))
        assertFalse(ProjectIgnoreRules.isIgnoredPath(Path.of("README.md")))
    }
}
