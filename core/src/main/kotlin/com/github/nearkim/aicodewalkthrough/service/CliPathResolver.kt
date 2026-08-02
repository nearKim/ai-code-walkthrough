package com.github.nearkim.aicodewalkthrough.service

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

object CliPathResolver {

    private val cache = ConcurrentHashMap<String, String>()

    fun resolve(nameOrPath: String): String {
        val configured = nameOrPath.trim()
        if (configured.isEmpty()) return nameOrPath
        val path = runCatching { Path.of(configured) }.getOrNull()
        if (path?.isAbsolute == true || configured.contains(File.separatorChar)) return configured
        return cache.getOrPut(configured) { findOnPath(configured) ?: configured }
    }

    private fun findOnPath(name: String): String? {
        val candidates = executableNames(name)
        val configuredPaths = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
        val home = System.getProperty("user.home")
        val commonPaths = listOf(
            "$home/.local/bin",
            "$home/.npm-global/bin",
            "/usr/local/bin",
            "/opt/homebrew/bin",
        )
        for (directory in (configuredPaths + commonPaths).filter(String::isNotBlank).distinct()) {
            for (candidate in candidates) {
                val resolved = runCatching { Path.of(directory, candidate) }.getOrNull() ?: continue
                if (Files.isRegularFile(resolved) && Files.isExecutable(resolved)) {
                    return resolved.toAbsolutePath().normalize().toString()
                }
            }
        }
        return null
    }

    private fun executableNames(name: String): List<String> {
        val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        return if (isWindows) listOf(name, "$name.exe", "$name.cmd", "$name.bat") else listOf(name)
    }
}
