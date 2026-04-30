package com.github.nearkim.aicodewalkthrough.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object CliPathResolver {

    private val cache = ConcurrentHashMap<String, String>()

    /**
     * Returns the absolute path for a CLI binary name.
     * If the configured value is already an absolute path, returns it as-is.
     * Otherwise, shells out via a login shell to resolve it the same way the
     * user's terminal would — picking up nvm, Homebrew, npm globals, etc.
     * Results are cached for the lifetime of the IDE process.
     */
    fun resolve(nameOrPath: String): String {
        if (nameOrPath.startsWith("/")) return nameOrPath
        return cache.getOrPut(nameOrPath) { lookupViaLoginShell(nameOrPath) ?: nameOrPath }
    }

    private fun lookupViaLoginShell(name: String): String? {
        val shells = listOf("/bin/zsh", "/bin/bash")
        for (shell in shells) {
            try {
                val process = ProcessBuilder(shell, "-l", "-c", "which $name")
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText().trim()
                val finished = process.waitFor(5, TimeUnit.SECONDS)
                if (!finished) { process.destroyForcibly(); continue }
                if (process.exitValue() == 0 && output.startsWith("/")) return output
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }
}
