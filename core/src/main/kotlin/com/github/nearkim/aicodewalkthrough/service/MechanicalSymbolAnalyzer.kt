package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.FlowStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

internal data class MechanicalSymbolInventory(
    val payload: JsonObject,
    val tool: String,
    val filesScanned: Int,
    val symbolCount: Int,
    val pythonExecutable: String,
    val cacheHit: Boolean,
)

internal object MechanicalSymbolAnalyzer {

    suspend fun analyze(
        projectRoot: Path,
        json: Json,
        analysisRoot: Path = defaultAnalysisRoot(),
    ): MechanicalSymbolInventory? = withContext(Dispatchers.IO) {
        if (!isPythonProject(projectRoot)) return@withContext null

        val script = MechanicalSymbolAnalyzer::class.java
            .getResourceAsStream("/symbol-analysis/python_ast_inventory.py")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: throw IllegalStateException("Bundled Python symbol analyzer is missing")

        var missingExecutable: IOException? = null
        for (executable in pythonExecutables(projectRoot)) {
            try {
                val fingerprint = runScript(executable, projectRoot, script, json, "--fingerprint")
                val cacheFile = cacheFile(analysisRoot, projectRoot)
                readCache(cacheFile, fingerprint, executable, json)?.let { return@withContext it }

                val payload = runScript(executable, projectRoot, script, json)
                writeCache(cacheFile, payload)
                return@withContext inventory(payload, executable, cacheHit = false)
            } catch (error: IOException) {
                missingExecutable = error
            }
        }
        throw IllegalStateException(
            "Python symbol analysis requires python3 or python on PATH",
            missingExecutable,
        )
    }

    suspend fun enrich(
        inventory: MechanicalSymbolInventory,
        projectRoot: Path,
        steps: List<FlowStep>,
        semanticToolResults: List<ProviderToolResult>,
        json: Json,
        analysisRoot: Path = defaultAnalysisRoot(),
    ): MechanicalSymbolInventory = withContext(Dispatchers.IO) {
        val additions = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        if (semanticToolResults.isNotEmpty()) {
            additions["semantic_tool_results"] = buildJsonArray {
                semanticToolResults.take(MAX_SEMANTIC_RESULTS).forEach { result ->
                    add(buildJsonObject {
                        put("tool", result.name)
                        put("input", result.input)
                        result.output?.let { put("output", it.take(MAX_TOOL_OUTPUT)) }
                    })
                }
            }
        }
        val pathTargets = crossHairTargets(inventory.payload, steps)
        val cachedPathAnalysis = reusablePathAnalysis(inventory.payload, pathTargets)
        additions["path_analysis"] = cachedPathAnalysis
            ?: runCrossHair(inventory.pythonExecutable, projectRoot, pathTargets)

        val payload = JsonObject(inventory.payload + additions)
        writeCache(cacheFile(analysisRoot, projectRoot), payload)
        inventory(payload, inventory.pythonExecutable, inventory.cacheHit)
    }

    fun defaultAnalysisRoot(): Path = Path.of(
        System.getProperty("user.home"),
        ".ai-code-walkthrough",
        "analysis",
    )

    private fun isPythonProject(projectRoot: Path): Boolean {
        val markers = listOf("pyproject.toml", "setup.py", "setup.cfg", "requirements.txt", "Pipfile")
        if (markers.any { Files.isRegularFile(projectRoot.resolve(it)) }) return true
        return Files.find(projectRoot, PYTHON_DETECTION_DEPTH, { path, attributes ->
            attributes.isRegularFile &&
                path.fileName.toString().endsWith(".py") &&
                projectRoot.relativize(path).none { it.toString() in IGNORED_PYTHON_DIRECTORIES }
        }).use { paths ->
            paths.findAny().isPresent
        }
    }

    private fun pythonExecutables(projectRoot: Path): List<String> = buildList {
        listOf(".venv/bin/python", "venv/bin/python", ".venv/Scripts/python.exe", "venv/Scripts/python.exe")
            .map(projectRoot::resolve)
            .filter(Files::isExecutable)
            .mapTo(this, Path::toString)
        add("python3")
        add("python")
    }.distinct()

    private fun runScript(
        executable: String,
        projectRoot: Path,
        script: String,
        json: Json,
        mode: String? = null,
    ): JsonObject {
        val command = buildList {
            add(executable)
            add("-")
            add(projectRoot.toString())
            mode?.let(::add)
        }
        val processBuilder = ProcessBuilder(command)
            .directory(projectRoot.toFile())
            .redirectErrorStream(false)
        configurePythonPath(processBuilder, projectRoot)
        CliEnvironment.augmentPath(processBuilder)
        val process = processBuilder.start()
        process.outputStream.bufferedWriter().use { it.write(script) }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        CliProcessRunner.runUntilExit(
            process = process,
            onStdoutLine = { stdout.appendLine(it) },
            onStderrLine = { stderr.appendLine(it) },
        )
        if (process.exitValue() != 0) {
            val detail = stderr.toString().trim().take(1_000)
            throw IllegalStateException(
                "Python symbol analyzer exited with code ${process.exitValue()}" +
                    detail.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty(),
            )
        }

        return try {
            json.parseToJsonElement(stdout.toString()).jsonObject
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("Python symbol analyzer returned invalid JSON", error)
        }
    }

    private fun readCache(
        cacheFile: Path,
        fingerprint: JsonObject,
        executable: String,
        json: Json,
    ): MechanicalSymbolInventory? {
        if (!Files.isRegularFile(cacheFile)) return null
        return runCatching {
            val payload = json.parseToJsonElement(Files.readString(cacheFile)).jsonObject
            val expectedVersion = fingerprint["analyzer_version"]?.jsonPrimitive?.content
            val expectedFingerprint = fingerprint["source_fingerprint"]?.jsonPrimitive?.content
            if (payload["analyzer_version"]?.jsonPrimitive?.content != expectedVersion ||
                payload["source_fingerprint"]?.jsonPrimitive?.content != expectedFingerprint
            ) {
                return null
            }
            inventory(payload, executable, cacheHit = true)
        }.getOrNull()
    }

    private fun inventory(payload: JsonObject, executable: String, cacheHit: Boolean): MechanicalSymbolInventory {
        val tool = payload["tool"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Python symbol analyzer omitted tool")
        val filesScanned = payload["files_scanned"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: throw IllegalStateException("Python symbol analyzer omitted files_scanned")
        val symbolCount = payload["symbol_count"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: throw IllegalStateException("Python symbol analyzer omitted symbol_count")
        return MechanicalSymbolInventory(payload, tool, filesScanned, symbolCount, executable, cacheHit)
    }

    private fun cacheFile(analysisRoot: Path, projectRoot: Path): Path {
        val key = sha256(projectRoot.toRealPath().toString())
        return analysisRoot.resolve(key).resolve("python-v2.json")
    }

    private fun writeCache(cacheFile: Path, payload: JsonObject) {
        Files.createDirectories(cacheFile.parent)
        val temporary = Files.createTempFile(cacheFile.parent, "python-v2-", ".tmp")
        try {
            Files.writeString(temporary, payload.toString())
            try {
                Files.move(
                    temporary,
                    cacheFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun crossHairTargets(payload: JsonObject, steps: List<FlowStep>): List<PathTarget> {
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

    private fun reusablePathAnalysis(payload: JsonObject, targets: List<PathTarget>): JsonObject? {
        if (targets.isEmpty()) return null
        val analysis = payload["path_analysis"]?.jsonObject ?: return null
        val completed = analysis["results"]?.jsonArray.orEmpty().mapNotNull { result ->
            result.jsonObject["symbol"]?.jsonPrimitive?.contentOrNull
        }.toSet()
        return analysis.takeIf { targets.all { it.qualifiedName in completed } }
    }

    private fun runCrossHair(executable: String, projectRoot: Path, targets: List<PathTarget>): JsonObject {
        val fingerprint = targets.joinToString("|") { it.qualifiedName }
        if (targets.isEmpty()) {
            return buildJsonObject {
                put("tool", "crosshair")
                put("status", "no_safe_target")
                put("target_fingerprint", fingerprint)
                put("results", JsonArray(emptyList()))
            }
        }

        val version = runCommand(
            listOf(executable, "-m", "crosshair", "--version"),
            projectRoot,
            Duration.ofSeconds(5),
        )
        if (!version.finished || version.exitCode != 0) {
            return buildJsonObject {
                put("tool", "crosshair")
                put("status", "unavailable")
                put("target_fingerprint", fingerprint)
                put("results", JsonArray(emptyList()))
            }
        }

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
                val result = runCommand(command, projectRoot, Duration.ofSeconds(CROSSHAIR_TIMEOUT_SECONDS))
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
                    result.stdout.takeIf(String::isNotBlank)?.let { put("output", it.take(MAX_TOOL_OUTPUT)) }
                    result.stderr.takeIf(String::isNotBlank)?.let { put("detail", it.take(MAX_TOOL_DETAIL)) }
                })
            }
        }
        return buildJsonObject {
            put("tool", "crosshair")
            put("status", "completed")
            put("version", version.stdout.take(MAX_TOOL_DETAIL))
            put("target_fingerprint", fingerprint)
            put("per_condition_timeout_seconds", 3)
            put("process_timeout_seconds", CROSSHAIR_TIMEOUT_SECONDS)
            put("results", results)
        }
    }

    private fun runCommand(
        command: List<String>,
        projectRoot: Path,
        timeout: Duration,
    ): CommandResult {
        val processBuilder = ProcessBuilder(command)
            .directory(projectRoot.toFile())
            .redirectErrorStream(false)
        configurePythonPath(processBuilder, projectRoot)
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
            onStdoutLine = { if (stdout.length < MAX_TOOL_OUTPUT) stdout.appendLine(it) },
            onStderrLine = { if (stderr.length < MAX_TOOL_OUTPUT) stderr.appendLine(it) },
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

    private fun configurePythonPath(processBuilder: ProcessBuilder, projectRoot: Path) {
        val pythonPath = listOf(projectRoot, projectRoot.resolve("src"), projectRoot.resolve("lib"))
            .filter(Files::isDirectory)
            .joinToString(java.io.File.pathSeparator)
        processBuilder.environment()["PYTHONPATH"] = listOf(
            pythonPath,
            processBuilder.environment()["PYTHONPATH"].orEmpty(),
        ).filter(String::isNotBlank).joinToString(java.io.File.pathSeparator)
    }

    private data class PathTarget(
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

    private const val MAX_SEMANTIC_RESULTS = 30
    private const val MAX_PATH_TARGETS = 3
    private const val MAX_TOOL_OUTPUT = 4_000
    private const val MAX_TOOL_DETAIL = 500
    private const val CROSSHAIR_TIMEOUT_SECONDS = 12L
    private const val PYTHON_DETECTION_DEPTH = 8
    private val IGNORED_PYTHON_DIRECTORIES = setOf(
        ".git",
        ".venv",
        "__pycache__",
        "build",
        "dist",
        "node_modules",
        "venv",
    )
}
