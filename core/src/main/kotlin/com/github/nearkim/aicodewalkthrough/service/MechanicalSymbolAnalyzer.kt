package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.FlowStep
import com.github.nearkim.aicodewalkthrough.service.symbols.CrossHairAnalyzer
import com.github.nearkim.aicodewalkthrough.service.symbols.PythonEnvironment
import com.github.nearkim.aicodewalkthrough.service.symbols.SymbolInventoryCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Path

internal data class MechanicalSymbolInventory(
    val payload: JsonObject,
    val tool: String,
    val filesScanned: Int,
    val symbolCount: Int,
    val pythonExecutable: String,
    val cacheHit: Boolean,
)

/**
 * Runs the bundled Python AST inventory before any model call, so the walkthrough is grounded in
 * facts the plugin computed itself. Delegates interpreter discovery, caching and path analysis.
 */
internal object MechanicalSymbolAnalyzer {

    suspend fun analyze(
        projectRoot: Path,
        json: Json,
        analysisRoot: Path = defaultAnalysisRoot(),
    ): MechanicalSymbolInventory? = withContext(Dispatchers.IO) {
        if (!PythonEnvironment.isPythonProject(projectRoot)) return@withContext null

        val script = MechanicalSymbolAnalyzer::class.java
            .getResourceAsStream("/symbol-analysis/python_ast_inventory.py")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: throw IllegalStateException("Bundled Python symbol analyzer is missing")

        var missingExecutable: IOException? = null
        for (executable in PythonEnvironment.executables(projectRoot)) {
            try {
                val fingerprint = runScript(executable, projectRoot, script, json, "--fingerprint")
                val cacheFile = SymbolInventoryCache.fileFor(analysisRoot, projectRoot)
                SymbolInventoryCache.read(cacheFile, fingerprint, json)?.let {
                    return@withContext inventory(it, executable, cacheHit = true)
                }

                val payload = runScript(executable, projectRoot, script, json)
                SymbolInventoryCache.write(cacheFile, payload)
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
        val additions = mutableMapOf<String, JsonElement>()
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
        val pathTargets = CrossHairAnalyzer.targets(inventory.payload, steps)
        additions["path_analysis"] = CrossHairAnalyzer.reusableAnalysis(inventory.payload, pathTargets)
            ?: CrossHairAnalyzer.run(inventory.pythonExecutable, projectRoot, pathTargets)

        val payload = JsonObject(inventory.payload + additions)
        SymbolInventoryCache.write(SymbolInventoryCache.fileFor(analysisRoot, projectRoot), payload)
        inventory(payload, inventory.pythonExecutable, inventory.cacheHit)
    }

    fun defaultAnalysisRoot(): Path = Path.of(
        System.getProperty("user.home"),
        ".ai-code-walkthrough",
        "analysis",
    )

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
        PythonEnvironment.configurePythonPath(processBuilder, projectRoot)
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

    private fun inventory(payload: JsonObject, executable: String, cacheHit: Boolean): MechanicalSymbolInventory {
        val tool = payload["tool"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Python symbol analyzer omitted tool")
        val filesScanned = payload["files_scanned"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: throw IllegalStateException("Python symbol analyzer omitted files_scanned")
        val symbolCount = payload["symbol_count"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: throw IllegalStateException("Python symbol analyzer omitted symbol_count")
        return MechanicalSymbolInventory(payload, tool, filesScanned, symbolCount, executable, cacheHit)
    }

    private const val MAX_SEMANTIC_RESULTS = 30
    private const val MAX_TOOL_OUTPUT = 4_000
}
