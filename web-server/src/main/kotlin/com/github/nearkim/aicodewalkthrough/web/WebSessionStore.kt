package com.github.nearkim.aicodewalkthrough.web

import com.github.nearkim.aicodewalkthrough.model.FlowMap
import com.github.nearkim.aicodewalkthrough.model.ResponseMetadata
import com.github.nearkim.aicodewalkthrough.model.StepAnswer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class WebSessionStore(
    projectRoot: Path,
    private val path: Path = defaultSessionPath(projectRoot),
) {
    private val repositoryPath = projectRoot.toAbsolutePath().normalize().toString()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun load(): StoredWebSession? {
        if (!Files.isRegularFile(path)) return null
        return try {
            json.decodeFromString<StoredWebSession>(Files.readString(path))
                .takeIf { it.repositoryPath == repositoryPath && it.flowMap != null }
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        }
    }

    @Throws(IOException::class)
    fun save(value: StoredWebSession) {
        val directory = path.parent ?: Path.of(".")
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "session", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(value.copy(repositoryPath = repositoryPath)))
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

@Serializable
data class StoredWebSession(
    val repositoryPath: String,
    val state: String,
    val question: String? = null,
    val mode: String,
    val provider: String,
    val flowMap: FlowMap? = null,
    val metadata: ResponseMetadata? = null,
    val currentStepIndex: Int = -1,
    val previewStepIndex: Int = -1,
    val stepAnswer: StepAnswer? = null,
    val stepAnswerError: String? = null,
    val activeStepId: String? = null,
    val activeSectionId: String? = null,
    val activeStageId: String? = null,
    val historyStepIds: List<String> = emptyList(),
    val completedStepIds: List<String> = emptyList(),
)

private fun defaultSessionPath(projectRoot: Path): Path {
    val root = projectRoot.toAbsolutePath().normalize().toString()
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(root.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        .take(24)
    return Path.of(System.getProperty("user.home"), ".ai-code-walkthrough", "sessions", "$digest.json")
}
