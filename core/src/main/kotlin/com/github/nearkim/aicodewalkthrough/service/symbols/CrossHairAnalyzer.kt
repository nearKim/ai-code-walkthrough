package com.github.nearkim.aicodewalkthrough.service.symbols

import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.service.CliEnvironment
import com.github.nearkim.aicodewalkthrough.service.CliProcessRunner
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Path
import java.time.Duration

/**
 * Optional per-symbol path coverage via CrossHair. Every failure mode (missing tool, timeout,
 * unsupported symbol) degrades to a status string rather than failing the surrounding analysis.
 */
internal object CrossHairAnalyzer {

    /** Picks the CrossHair-safe functions among [steps] that the analyzer already verified. */
    fun targets(payload: JsonObject, steps: List<FlowStep>): List<PathTarget> {
        val requested = steps.mapNotNull { step ->
            step.symbol?.substringAfterLast('.')?.let { name -> step.filePath to name }
        }.toSet()
        if (requested.isEmpty()) return emptyList()

        return payload["modules"]?.jsonArray.orEmpty().flatMap { element ->
            val module = element.jsonObject
            val path = module["p"]?.jsonPrimitive?.content ?: return@flatMap emptyList()
            module["f"]?.jsonArray.orEmpty().mapNotNull { functionElement ->
                val function = functionElement.jsonObject
                val name = function["n"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val safe = function["z"]?.jsonPrimitive?.booleanOrNull == true
                if (!safe || (path to name) !in requested) return@mapNotNull null
                val line = function["r"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content?.toIntOrNull()
                    ?: return@mapNotNull null
                PathTarget(path, moduleName(path), name, line)
            }
        }.take(MAX_PATH_TARGETS)
    }

    /** Reuses a persisted run only when it already covers every requested target. */
    fun reusableAnalysis(payload: JsonObject, targets: List<PathTarget>): JsonObject? {
        if (targets.isEmpty()) return null
        val analysis = payload["path_analysis"]?.jsonObject ?: return null
        val completed = analysis["results"]?.jsonArray.orEmpty().mapNotNull { result ->
            result.jsonObject["symbol"]?.jsonPrimitive?.contentOrNull
        }.toSet()
        return analysis.takeIf { targets.all { it.qualifiedName in completed } }
    }

    fun run(executable: String, projectRoot: Path, targets: List<PathTarget>): JsonObject {
        val fingerprint = targets.joinToString("|") { it.qualifiedName }
        if (targets.isEmpty()) return statusOnly("no_safe_target", fingerprint)

        val version = runCommand(
            listOf(executable, "-m", "crosshair", "--version"),
            projectRoot,
            Duration.ofSeconds(5),
        )
        if (!version.finished || version.exitCode != 0) return statusOnly("unavailable", fingerprint)

        val results = buildJsonArray {
            targets.forEach { target ->
                val command = listOf(
                    executable,
                    "-m",
                    "crosshair",
                    "cover",
                    "--coverage_type",
                    "path",
                    "--per_condition_timeout",
                    "3",
                    "--example_output_format",
                    "arg_dictionary",
                    target.qualifiedName,
                )
                val result = runCommand(command, projectRoot, Duration.ofSeconds(TIMEOUT_SECONDS))
                val status = when {
                    !result.finished -> "timeout"
                    result.exitCode != 0 -> "unsupported"
                    result.stdout.isBlank() -> "unknown"
                    else -> "partial_coverage"
                }
                add(buildJsonObject {
                    put("symbol", target.qualifiedName)
                    put("file_path", target.path)
                    put("start_line", target.line)
                    put("status", status)
                    result.stdout.takeIf(String::isNotBlank)?.let { put("output", it.take(MAX_OUTPUT)) }
                    result.stderr.takeIf(String::isNotBlank)?.let { put("detail", it.take(MAX_DETAIL)) }
                })
            }
        }
        return buildJsonObject {
            put("tool", "crosshair")
            put("status", "completed")
            put("version", version.stdout.take(MAX_DETAIL))
            put("target_fingerprint", fingerprint)
            put("per_condition_timeout_seconds", 3)
            put("process_timeout_seconds", TIMEOUT_SECONDS)
            put("results", results)
        }
    }

    private fun statusOnly(status: String, fingerprint: String): JsonObject = buildJsonObject {
        put("tool", "crosshair")
        put("status", status)
        put("target_fingerprint", fingerprint)
        put("results", JsonArray(emptyList()))
    }

    private fun runCommand(
        command: List<String>,
        projectRoot: Path,
        timeout: Duration,
    ): CommandResult {
        val processBuilder = ProcessBuilder(command)
            .directory(projectRoot.toFile())
            .redirectErrorStream(false)
        PythonEnvironment.configurePythonPath(processBuilder, projectRoot)
        CliEnvironment.augmentPath(processBuilder)
        val process = try {
            processBuilder.start()
        } catch (error: IOException) {
            return CommandResult(false, -1, "", error.message.orEmpty())
        }
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val finished = CliProcessRunner.run(
            process,
            timeout,
            onStdoutLine = { if (stdout.length < MAX_OUTPUT) stdout.appendLine(it) },
            onStderrLine = { if (stderr.length < MAX_OUTPUT) stderr.appendLine(it) },
        )
        return CommandResult(
            finished,
            if (finished) process.exitValue() else -1,
            stdout.toString().trim(),
            stderr.toString().trim(),
        )
    }

    private fun moduleName(path: String): String = path.removeSuffix(".py")
        .replace('/', '.')
        .removePrefix("src.")
        .removePrefix("lib.")
        .removeSuffix(".__init__")

    internal data class PathTarget(
        val path: String,
        val module: String,
        val name: String,
        val line: Int,
    ) {
        val qualifiedName: String = listOf(module, name).filter(String::isNotBlank).joinToString(".")
    }

    private data class CommandResult(
        val finished: Boolean,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private const val MAX_PATH_TARGETS = 3
    private const val MAX_OUTPUT = 4_000
    private const val MAX_DETAIL = 500
    private const val TIMEOUT_SECONDS = 12L
}
