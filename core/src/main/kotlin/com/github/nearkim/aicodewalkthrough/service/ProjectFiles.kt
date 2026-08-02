package com.github.nearkim.aicodewalkthrough.service

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

class ProjectFiles(projectBasePath: Path) {

    val root: Path = projectBasePath.toAbsolutePath().normalize()
    private val realRoot = root.toRealPath()
    private val lineCache = mutableMapOf<Path, List<String>>()

    fun resolveExisting(path: String, requireRegularFile: Boolean = false): Path? {
        val resolved = resolve(path) ?: return null
        if (!Files.exists(resolved)) return null
        val realPath = runCatching { resolved.toRealPath() }.getOrNull() ?: return null
        if (!realPath.startsWith(realRoot)) return null
        if (requireRegularFile && !Files.isRegularFile(realPath)) return null
        return resolved
    }

    fun normalizeExisting(path: String, requireRegularFile: Boolean = false): String? {
        val resolved = resolveExisting(path, requireRegularFile) ?: return null
        return root.relativize(resolved)
            .joinToString("/") { it.toString() }
            .ifBlank { "." }
    }

    fun readLines(path: String): List<String>? {
        val resolved = resolveExisting(path, requireRegularFile = true) ?: return null
        lineCache[resolved]?.let { return it }
        return try {
            Files.readAllLines(resolved).also { lineCache[resolved] = it }
        } catch (_: IOException) {
            null
        }
    }

    private fun resolve(path: String): Path? {
        if (path.isBlank()) return null
        return try {
            val requested = Path.of(path)
            val resolved = if (requested.isAbsolute) requested.normalize() else root.resolve(requested).normalize()
            resolved.takeIf { it.startsWith(root) }
        } catch (_: InvalidPathException) {
            null
        }
    }
}
