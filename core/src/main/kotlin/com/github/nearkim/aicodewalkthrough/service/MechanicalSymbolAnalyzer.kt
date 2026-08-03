package com.github.nearkim.aicodewalkthrough.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal data class MechanicalSymbolInventory(
    val payload: JsonObject,
    val tool: String,
    val filesScanned: Int,
    val symbolCount: Int,
)

internal object MechanicalSymbolAnalyzer {

    suspend fun analyze(projectRoot: Path, json: Json): MechanicalSymbolInventory? = withContext(Dispatchers.IO) {
        if (!isPythonProject(projectRoot)) return@withContext null

        val script = MechanicalSymbolAnalyzer::class.java
            .getResourceAsStream("/symbol-analysis/python_ast_inventory.py")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: throw IllegalStateException("Bundled Python symbol analyzer is missing")

        var missingExecutable: IOException? = null
        for (executable in listOf("python3", "python")) {
            try {
                return@withContext runAnalyzer(executable, projectRoot, script, json)
            } catch (error: IOException) {
                missingExecutable = error
            }
        }
        throw IllegalStateException(
            "Python symbol analysis requires python3 or python on PATH",
            missingExecutable,
        )
    }

    private fun isPythonProject(projectRoot: Path): Boolean {
        val markers = listOf("pyproject.toml", "setup.py", "setup.cfg", "requirements.txt", "Pipfile")
        if (markers.any { Files.isRegularFile(projectRoot.resolve(it)) }) return true
        return Files.list(projectRoot).use { paths ->
            paths.anyMatch { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".py") }
        }
    }

    private fun runAnalyzer(
        executable: String,
        projectRoot: Path,
        script: String,
        json: Json,
    ): MechanicalSymbolInventory {
        val processBuilder = ProcessBuilder(executable, "-", projectRoot.toString())
            .directory(projectRoot.toFile())
            .redirectErrorStream(false)
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

        val payload = try {
            json.parseToJsonElement(stdout.toString()).jsonObject
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("Python symbol analyzer returned invalid JSON", error)
        }
        val tool = payload["tool"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Python symbol analyzer omitted tool")
        val filesScanned = payload["files_scanned"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: throw IllegalStateException("Python symbol analyzer omitted files_scanned")
        val symbolCount = payload["symbol_count"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: throw IllegalStateException("Python symbol analyzer omitted symbol_count")
        return MechanicalSymbolInventory(payload, tool, filesScanned, symbolCount)
    }
}
