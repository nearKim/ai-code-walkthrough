package com.github.nearkim.aicodewalkthrough.service.symbols

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Persists the analyzer payload per repository, keyed by the interpreter's own source fingerprint so
 * a stale cache is never reused across edits.
 */
internal object SymbolInventoryCache {

    fun fileFor(analysisRoot: Path, projectRoot: Path): Path {
        val key = sha256(projectRoot.toRealPath().toString())
        return analysisRoot.resolve(key).resolve("python-v2.json")
    }

    /** Returns the cached payload only when it matches the freshly computed [fingerprint]. */
    fun read(cacheFile: Path, fingerprint: JsonObject, json: Json): JsonObject? {
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
            payload
        }.getOrNull()
    }

    fun write(cacheFile: Path, payload: JsonObject) {
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
}
