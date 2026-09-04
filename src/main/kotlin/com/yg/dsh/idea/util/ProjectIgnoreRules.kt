package com.yg.dsh.idea.util

import java.nio.file.Path

/**
 * 快照/项目树遍历的共享忽略规则（McpRpcHandler 的 ide_get_project_tree 与
 * review 的基线扫描使用同一份，避免两处漂移）。
 *
 * 忽略：`.git`、`node_modules`、`build`、`out`、`.idea`、`target`、`dist`、`.gradle`
 * 及隐藏文件（`.` 开头）、>1MB 文件。
 */
object ProjectIgnoreRules {

    /** 目录名忽略集（任意层级命中即跳过子树）。 */
    val IGNORED_DIRS: Set<String> = setOf(
        ".git", "node_modules", "build", "out", ".idea", "target", "dist", ".gradle",
    )

    /** 单文件大小上限（字节）：>1MB 忽略。 */
    const val MAX_FILE_BYTES = 1024L * 1024L

    /** 目录是否应跳过（隐藏目录或命中忽略集）。 */
    fun isIgnoredDir(name: String): Boolean =
        name.startsWith(".") || IGNORED_DIRS.contains(name)

    /** 文件是否应跳过（隐藏文件或超过大小上限）。 */
    fun isIgnoredFile(name: String, sizeBytes: Long): Boolean =
        name.startsWith(".") || sizeBytes > MAX_FILE_BYTES

    /** 相对路径形式判定（供测试/日志；与逐层判定等价）。 */
    fun isIgnoredPath(relative: Path): Boolean {
        for (part in relative) {
            val name = part.toString()
            if (name.startsWith(".")) return true
            if (IGNORED_DIRS.contains(name)) return true
        }
        return false
    }
}