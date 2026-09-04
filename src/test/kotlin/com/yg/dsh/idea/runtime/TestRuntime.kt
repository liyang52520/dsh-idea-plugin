package com.yg.dsh.idea.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * 冒烟测试用的真实运行时来源，契约与插件一致（仅路径，无下载/无内嵌运行时）：
 *
 * - [DSH_IDEA_NODE]：Node.js 可执行文件（对应设置里的 Node.js path）；
 * - [DSH_IDEA_DSH]：DSH 入口 `…/@deepseek-ai/dsh/lib/bin.js`（对应设置里的 DSH path）。
 *
 * 任一未设置或不存在时 [available] 为 false，测试自动跳过（CI 无运行时环境）。
 */
object TestRuntime {

    const val ENV_NODE = "DSH_IDEA_NODE"
    const val ENV_DSH = "DSH_IDEA_DSH"

    fun available(): Boolean = nodeExe() != null && dshBin() != null

    fun nodeExe(): File? =
        env(ENV_NODE)?.let { Path.of(it) }?.takeIf { Files.isRegularFile(it) }?.toFile()

    fun dshBin(): File? =
        env(ENV_DSH)?.let { Path.of(it) }?.takeIf { Files.isRegularFile(it) }?.toFile()

    private fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
}
