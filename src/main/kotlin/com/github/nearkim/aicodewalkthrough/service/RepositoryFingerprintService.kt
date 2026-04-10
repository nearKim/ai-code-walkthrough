package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.RepositoryFingerprint
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

fun interface RepositoryReviewCommandRunner {
    fun run(command: List<String>, workingDirectory: Path): String?
}

@Service(Service.Level.PROJECT)
class RepositoryFingerprintService(
    private val project: Project,
    private val commandRunner: RepositoryReviewCommandRunner = RepositoryReviewCommandRunner { command, workingDirectory ->
        runProcess(command, workingDirectory)
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    constructor(
        projectRoot: String,
        commandRunner: RepositoryReviewCommandRunner = RepositoryReviewCommandRunner { command, workingDirectory ->
            runProcess(command, workingDirectory)
        },
        clock: () -> Long = System::currentTimeMillis,
    ) : this(projectProxy(projectRoot), commandRunner, clock)

    fun capture(): RepositoryFingerprint = capture(project.basePath, commandRunner, clock)

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

private fun projectProxy(basePath: String): Project {
    val handler = InvocationHandler { _, method: Method, args: Array<Any?>? ->
        when (method.name) {
            "getBasePath" -> basePath
            "toString" -> "RepositoryFingerprintServiceProjectProxy($basePath)"
            "hashCode" -> basePath.hashCode()
            "equals" -> args?.firstOrNull() === method
            else -> defaultReturn(method.returnType)
        }
    }
    return Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
        handler,
    ) as Project
}

private fun defaultReturn(returnType: Class<*>): Any? = when {
    returnType == java.lang.Boolean.TYPE -> false
    returnType == java.lang.Byte.TYPE -> 0.toByte()
    returnType == java.lang.Short.TYPE -> 0.toShort()
    returnType == java.lang.Integer.TYPE -> 0
    returnType == java.lang.Long.TYPE -> 0L
    returnType == java.lang.Float.TYPE -> 0f
    returnType == java.lang.Double.TYPE -> 0.0
    returnType == java.lang.Character.TYPE -> '\u0000'
    else -> null
}
