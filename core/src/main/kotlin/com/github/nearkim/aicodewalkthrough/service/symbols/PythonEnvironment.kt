package com.github.nearkim.aicodewalkthrough.service.symbols

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Decides whether a repository is Python at all, and which interpreter to drive it with. */
internal object PythonEnvironment {

    fun isPythonProject(projectRoot: Path): Boolean {
        val markers = listOf("pyproject.toml", "setup.py", "setup.cfg", "requirements.txt", "Pipfile")
        if (markers.any { Files.isRegularFile(projectRoot.resolve(it)) }) return true
        return Files.find(projectRoot, DETECTION_DEPTH, { path, attributes ->
            attributes.isRegularFile &&
                path.fileName.toString().endsWith(".py") &&
                projectRoot.relativize(path).none { it.toString() in IGNORED_DIRECTORIES }
        }).use { paths ->
            paths.findAny().isPresent
        }
    }

    fun executables(projectRoot: Path): List<String> = buildList {
        listOf(".venv/bin/python", "venv/bin/python", ".venv/Scripts/python.exe", "venv/Scripts/python.exe")
            .map(projectRoot::resolve)
            .filter(Files::isExecutable)
            .mapTo(this, Path::toString)
        add("python3")
        add("python")
    }.distinct()

    fun configurePythonPath(processBuilder: ProcessBuilder, projectRoot: Path) {
        val pythonPath = listOf(projectRoot, projectRoot.resolve("src"), projectRoot.resolve("lib"))
            .filter(Files::isDirectory)
            .joinToString(File.pathSeparator)
        processBuilder.environment()["PYTHONPATH"] = listOf(
            pythonPath,
            processBuilder.environment()["PYTHONPATH"].orEmpty(),
        ).filter(String::isNotBlank).joinToString(File.pathSeparator)
    }

    private const val DETECTION_DEPTH = 8
    private val IGNORED_DIRECTORIES = setOf(
        ".git",
        ".venv",
        "__pycache__",
        "build",
        "dist",
        "node_modules",
        "venv",
    )
}
