package com.github.nearkim.aicodewalkthrough.web

import com.github.nearkim.aicodewalkthrough.model.WalkthroughSettings
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class WebSettingsStore(
    private val path: Path = Path.of(System.getProperty("user.home"), ".ai-code-walkthrough", "settings.json"),
) {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @Volatile
    private var settings = loadFromDisk()

    fun get(): WalkthroughSettings = settings

    @Synchronized
    @Throws(IOException::class)
    fun save(value: WalkthroughSettings): WalkthroughSettings {
        val normalized = value.normalized()
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, "settings", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(normalized))
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
            settings = normalized
            return normalized
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun loadFromDisk(): WalkthroughSettings {
        if (!Files.isRegularFile(path)) return WalkthroughSettings()
        return try {
            json.decodeFromString<WalkthroughSettings>(Files.readString(path)).normalized()
        } catch (_: IOException) {
            WalkthroughSettings()
        } catch (_: SerializationException) {
            WalkthroughSettings()
        }
    }
}
