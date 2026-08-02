package com.github.nearkim.aicodewalkthrough.service

import java.io.File

internal object CliEnvironment {

    fun nullInput(): File = File(
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "NUL" else "/dev/null",
    )

    fun augmentPath(processBuilder: ProcessBuilder) {
        val environment = processBuilder.environment()
        val currentPath = environment["PATH"].orEmpty()
        val home = System.getProperty("user.home")
        val additions = listOf(
            "$home/.local/bin",
            "$home/.npm-global/bin",
            "$home/.nvm/versions/node/current/bin",
            "/usr/local/bin",
            "/opt/homebrew/bin",
        ).filterNot { path -> currentPath.split(File.pathSeparatorChar).contains(path) }
        if (additions.isNotEmpty()) {
            environment["PATH"] = (listOf(currentPath) + additions).filter(String::isNotBlank)
                .joinToString(File.pathSeparator)
        }
    }
}
