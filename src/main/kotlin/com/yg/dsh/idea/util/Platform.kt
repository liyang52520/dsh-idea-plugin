package com.yg.dsh.idea.util

object Platform {
    enum class Os { WINDOWS, MACOS, LINUX, UNKNOWN }
    enum class Arch { X64, ARM64, UNKNOWN }

    data class Target(val os: Os, val arch: Arch) {
        val id: String get() = os.id + "-" + arch.id
        val nodeBinName: String get() = if (os == Os.WINDOWS) "node.exe" else "node"
    }

    fun current(): Target = Target(fromOsName(System.getProperty("os.name", "")), fromOsArch(System.getProperty("os.arch", "")))

    fun fromOsName(osName: String): Os {
        val n = osName.trim().lowercase()
        return when {
            n.startsWith("win") -> Os.WINDOWS
            n.startsWith("mac") || n.startsWith("darwin") -> Os.MACOS
            n.startsWith("linux") || n.contains("linux") -> Os.LINUX
            else -> Os.UNKNOWN
        }
    }

    fun fromOsArch(osArch: String): Arch {
        val a = osArch.lowercase()
        return when {
            a.contains("aarch64") || a.contains("arm64") || a == "arm" -> Arch.ARM64
            a.contains("x86_64") || a.contains("amd64") || a.contains("x64") || a == "x86" -> Arch.X64
            else -> Arch.UNKNOWN
        }
    }

    private val Os.id: String get() = when (this) {
        Os.WINDOWS -> "win"
        Os.MACOS -> "macos"
        Os.LINUX -> "linux"
        Os.UNKNOWN -> "unknown"
    }

    private val Arch.id: String get() = when (this) {
        Arch.X64 -> "x64"
        Arch.ARM64 -> "arm64"
        Arch.UNKNOWN -> "unknown"
    }
}