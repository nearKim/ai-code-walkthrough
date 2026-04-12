package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.RepositoryFingerprint
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.isRegularFile

fun interface RepositoryReviewCommandRunner {
    fun run(command: List<String>, workingDirectory: Path): String?
}

@Service(Service.Level.PROJECT)
class RepositoryFingerprintService(private val project: Project) {

    fun capture(): RepositoryFingerprint = capture(project.basePath)

    companion object {
        fun capture(
            projectRoot: String?,
            commandRunner: RepositoryReviewCommandRunner = RepositoryReviewCommandRunner { command, workingDirectory ->
                runProcess(command, workingDirectory)
            },
            clock: () -> Long = System::currentTimeMillis,
        ): RepositoryFingerprint {
            val root = projectRoot?.takeIf { it.isNotBlank() }?.let(Path::of)
            if (root == null) {
                return RepositoryFingerprint(generatedAtMs = clock())
            }

            val gitHead = runCatching { commandRunner.run(listOf("git", "rev-parse", "HEAD"), root)?.trim() }.getOrNull().orEmpty().ifBlank { null }
            val gitBranch = runCatching { commandRunner.run(listOf("git", "rev-parse", "--abbrev-ref", "HEAD"), root)?.trim() }.getOrNull().orEmpty().ifBlank { null }
            val dirty = runCatching { commandRunner.run(listOf("git", "status", "--porcelain", "--untracked-files=no"), root)?.isNotBlank() }.getOrNull() ?: false
            val trackedFiles = runCatching {
                commandRunner.run(listOf("git", "ls-files"), root)
                    ?.lineSequence()
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.toList()
                    .orEmpty()
            }.getOrElse { emptyList() }

            val fileDigest = trackedFiles
                .asSequence()
                .map { root.resolve(it) }
                .filter { Files.exists(it) && it.isRegularFile() }
                .map { path ->
                    val modified = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)
                    "${root.relativize(path)}:$modified"
                }
                .sorted()
                .toList()
                .takeIf { it.isNotEmpty() }
                ?.let { digestStrings(it) }

            return RepositoryFingerprint(
                repoRoot = root.toString(),
                gitHead = gitHead,
                gitBranch = gitBranch,
                gitDirty = dirty,
                trackedFileCount = trackedFiles.size,
                fileDigest = fileDigest,
                generatedAtMs = clock(),
            )
        }
    }
}

private fun runProcess(command: List<String>, workingDirectory: Path): String? {
    return try {
        val process = ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .redirectInput(File("/dev/null"))
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else if (process.exitValue() == 0) {
            output.ifBlank { null }
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

private fun digestStrings(values: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    values.forEach { digest.update(it.toByteArray()) }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
