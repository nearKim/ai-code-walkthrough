package com.github.nearkim.aicodewalkthrough.web

import com.github.nearkim.aicodewalkthrough.model.AiProvider
import com.github.nearkim.aicodewalkthrough.service.ClaudeCliProvider
import com.github.nearkim.aicodewalkthrough.service.CodexCliProvider
import com.github.nearkim.aicodewalkthrough.service.LlmProvider
import com.github.nearkim.aicodewalkthrough.service.ProjectFiles
import com.github.nearkim.aicodewalkthrough.service.WalkthroughEngine
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.awt.Desktop
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

fun main(args: Array<String>) {
    val options = parseOptions(args).getOrElse { error ->
        System.err.println(error.message)
        printUsage()
        return
    }
    val projectRoot = try {
        Path.of(options.repository).toRealPath()
    } catch (error: InvalidPathException) {
        System.err.println("Invalid repository path: ${error.input}")
        return
    } catch (error: IOException) {
        System.err.println("Cannot open repository: ${error.message}")
        return
    }
    if (!Files.isDirectory(projectRoot)) {
        System.err.println("Repository path is not a directory: $projectRoot")
        return
    }

    val settings = WebSettingsStore()
    val providers = mutableMapOf<AiProvider, LlmProvider>()
    providers[AiProvider.CODEX_CLI] = CodexCliProvider(projectRoot, settings::get)
    providers[AiProvider.CLAUDE_CLI] = ClaudeCliProvider(projectRoot, settings::get)
    val engine = WalkthroughEngine(projectRoot, settings::get) { provider ->
        requireNotNull(providers[provider]) { "Unsupported provider: $provider" }
    }
    val session = WebSession(projectRoot, settings, engine)
    val dependencies = WebDependencies(session, settings, engine, ProjectFiles(projectRoot))
    val port = options.port.takeIf { it != 0 } ?: availablePort()
    val server = embeddedServer(CIO, host = LOOPBACK_HOST, port = port) {
        configureWebApplication(dependencies)
    }
    Runtime.getRuntime().addShutdownHook(Thread {
        session.close()
        server.stop(500, 2_000)
    })
    server.start(wait = false)

    val url = "http://$LOOPBACK_HOST:$port/"
    println("AI Code Walkthrough is serving $projectRoot at $url")
    if (!options.noOpen) openBrowser(url)
    Thread.currentThread().join()
}

internal data class LaunchOptions(
    val repository: String,
    val port: Int = 0,
    val noOpen: Boolean = false,
)

internal fun parseOptions(args: Array<String>): Result<LaunchOptions> = runCatching {
    var repository: String? = null
    var port = 0
    var noOpen = false
    var index = 0
    while (index < args.size) {
        when (val argument = args[index]) {
            "--no-open" -> noOpen = true
            "--port" -> {
                val value = args.getOrNull(++index)
                    ?: throw IllegalArgumentException("--port requires a value")
                port = value.toIntOrNull()?.takeIf { it in 0..65535 }
                    ?: throw IllegalArgumentException("Invalid port: $value")
            }
            else -> {
                if (argument.startsWith("-")) throw IllegalArgumentException("Unknown option: $argument")
                if (repository != null) throw IllegalArgumentException("Only one repository path may be supplied")
                repository = argument
            }
        }
        index++
    }
    LaunchOptions(
        repository = repository ?: throw IllegalArgumentException("A repository path is required"),
        port = port,
        noOpen = noOpen,
    )
}

private fun availablePort(): Int = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }

private fun openBrowser(url: String) {
    if (!Desktop.isDesktopSupported()) return
    try {
        Desktop.getDesktop().browse(URI.create(url))
    } catch (error: IOException) {
        System.err.println("Could not open the browser: ${error.message}")
    } catch (error: UnsupportedOperationException) {
        System.err.println("Could not open the browser: ${error.message}")
    } catch (error: SecurityException) {
        System.err.println("Could not open the browser: ${error.message}")
    }
}

private fun printUsage() {
    System.err.println("Usage: ai-code-walkthrough <repository-path> [--port <0-65535>] [--no-open]")
}

private const val LOOPBACK_HOST = "127.0.0.1"
