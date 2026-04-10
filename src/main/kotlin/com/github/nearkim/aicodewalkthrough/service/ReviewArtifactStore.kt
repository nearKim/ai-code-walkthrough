package com.github.nearkim.aicodewalkthrough.service

import com.github.nearkim.aicodewalkthrough.model.RepositoryFeature
import com.github.nearkim.aicodewalkthrough.model.RepositoryFingerprint
import com.github.nearkim.aicodewalkthrough.model.RepositoryReviewFileFeatureIndex
import com.github.nearkim.aicodewalkthrough.model.RepositoryReviewSnapshot
import com.github.nearkim.aicodewalkthrough.model.ReviewRunFeatureArtifact
import com.github.nearkim.aicodewalkthrough.model.ReviewRunMetadata
import com.github.nearkim.aicodewalkthrough.util.RepositoryReviewMarkdownExporter
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.Json
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Service(Service.Level.PROJECT)
class ReviewArtifactStore(private val project: Project) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    constructor(projectRoot: String) : this(projectProxy(projectRoot))

    fun reviewRoot(): Path = artifactRoot()
        ?: throw IllegalStateException("Project base path is not available")

    fun hasStoredReview(): Boolean = latestSnapshotPath()?.let(Files::exists) == true

    fun loadLatest(): RepositoryReviewSnapshot? = loadLatestSnapshot()

    fun loadLatestSnapshot(): RepositoryReviewSnapshot? {
        val latestPath = latestSnapshotPath() ?: legacyLatestJsonPath() ?: return null
        if (!Files.exists(latestPath)) return null
        return runCatching {
            json.decodeFromString<RepositoryReviewSnapshot>(Files.readString(latestPath))
        }.onFailure { error ->
            thisLogger().warn("Failed to read stored repository review", error)
        }.getOrNull()
    }

    fun write(snapshot: RepositoryReviewSnapshot) {
        save(snapshot)
    }

    fun save(snapshot: RepositoryReviewSnapshot): ReviewRunMetadata {
        val root = artifactRoot() ?: throw IllegalStateException("Project base path is not available")
        Files.createDirectories(root)
        Files.createDirectories(featuresDir(root))
        Files.createDirectories(snapshotsDir(root))

        writeGitignore(root)

        val fingerprint = snapshot.repoFingerprint ?: captureFingerprint()
        val metadata = buildMetadata(root, snapshot, fingerprint)

        val latestSnapshotPath = root.resolve("latest-snapshot.json")
        val summaryPath = root.resolve("summary.md")
        val manifestPath = root.resolve("manifest.json")
        val indexPath = root.resolve("file-to-feature-index.json")
        val snapshotRunPath = snapshotsDir(root).resolve("${metadata.runId}.json")

        Files.writeString(latestSnapshotPath, json.encodeToString(snapshot))
        Files.writeString(summaryPath, RepositoryReviewMarkdownExporter.build(snapshot))
        Files.writeString(snapshotRunPath, json.encodeToString(snapshot))
        Files.writeString(indexPath, json.encodeToString(buildFileIndex(snapshot.features)))

        val featureArtifacts = snapshot.features.map { feature ->
            writeFeatureArtifacts(root, feature)
        }

        val finalMetadata = metadata.copy(
            latestSnapshotPath = relativePath(root, latestSnapshotPath),
            summaryPath = relativePath(root, summaryPath),
            snapshotPath = relativePath(root, snapshotRunPath),
            fileToFeatureIndexPath = relativePath(root, indexPath),
            featureArtifacts = featureArtifacts,
            featureCount = snapshot.features.size,
            crossCuttingFindingCount = snapshot.crossCuttingFindings.size,
        )
        Files.writeString(manifestPath, json.encodeToString(finalMetadata))
        return finalMetadata
    }

    fun captureFingerprint(): RepositoryFingerprint {
        return RepositoryFingerprintService(project).capture()
    }

    fun isStale(snapshot: RepositoryReviewSnapshot): Boolean {
        val current = captureFingerprint()
        val saved = snapshot.repoFingerprint ?: snapshot.fingerprint ?: return true
        return current.repoRoot != saved.repoRoot ||
            current.gitHead != saved.gitHead ||
            current.gitBranch != saved.gitBranch ||
            current.gitDirty != saved.gitDirty ||
            current.trackedFileCount != saved.trackedFileCount ||
            current.fileDigest != saved.fileDigest
    }

    private fun buildMetadata(
        root: Path,
        snapshot: RepositoryReviewSnapshot,
        fingerprint: RepositoryFingerprint,
    ): ReviewRunMetadata {
        return ReviewRunMetadata(
            runId = snapshot.id.ifBlank { UUID.randomUUID().toString() },
            generatedAtMs = snapshot.generatedAtMs,
            providerId = snapshot.providerId,
            providerName = snapshot.providerName,
            repoFingerprint = fingerprint,
        )
    }

    private fun writeFeatureArtifacts(root: Path, feature: RepositoryFeature): ReviewRunFeatureArtifact {
        val safeId = sanitizeFileName(feature.id.ifBlank { feature.name })
        val featureJsonPath = featuresDir(root).resolve("$safeId.json")
        val featureMarkdownPath = featuresDir(root).resolve("$safeId.md")
        Files.writeString(featureJsonPath, json.encodeToString(feature))
        Files.writeString(featureMarkdownPath, RepositoryReviewMarkdownExporter.buildFeature(feature))
        return ReviewRunFeatureArtifact(
            id = safeId,
            title = feature.title.ifBlank { feature.name },
            jsonPath = relativePath(root, featureJsonPath),
            markdownPath = relativePath(root, featureMarkdownPath),
            filePaths = feature.filePaths,
            pathCount = feature.paths.size,
            findingCount = feature.findings.size,
        )
    }

    private fun buildFileIndex(features: List<RepositoryFeature>): RepositoryReviewFileFeatureIndex {
        val byFilePath = linkedMapOf<String, MutableSet<String>>()
        features.forEach { feature ->
            val featureId = sanitizeFileName(feature.id.ifBlank { feature.name })
            (feature.filePaths + feature.ownedPaths + feature.sharedPaths).distinct().forEach { filePath ->
                byFilePath.getOrPut(filePath) { linkedSetOf() }.add(featureId)
            }
            feature.paths.forEach { path ->
                path.filePaths.distinct().forEach { filePath ->
                    byFilePath.getOrPut(filePath) { linkedSetOf() }.add(featureId)
                }
            }
            feature.findings.forEach { finding ->
                finding.affectedFiles.distinct().forEach { filePath ->
                    byFilePath.getOrPut(filePath) { linkedSetOf() }.add(featureId)
                }
            }
        }
        return RepositoryReviewFileFeatureIndex(
            byFilePath = byFilePath.mapValues { (_, ids) -> ids.toList() },
        )
    }

    private fun writeGitignore(root: Path) {
        val gitignore = root.resolve(".gitignore")
        if (!Files.exists(gitignore)) {
            Files.writeString(
                gitignore,
                """
                *
                !.gitignore
                """.trimIndent(),
            )
        }
    }

    private fun artifactRoot(): Path? =
        project.basePath?.let { Path.of(it).resolve(".ai-code-walkthrough").resolve("review") }

    private fun latestSnapshotPath(): Path? = artifactRoot()?.resolve("latest-snapshot.json")

    private fun legacyLatestJsonPath(): Path? = artifactRoot()?.resolve("latest.json")

    private fun featuresDir(root: Path): Path = root.resolve("features")

    private fun snapshotsDir(root: Path): Path = root.resolve("snapshots")

    private fun relativePath(root: Path, target: Path): String = root.relativize(target).toString().replace('\\', '/')

    private fun sanitizeFileName(input: String): String {
        val slug = input.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return slug.ifBlank { "feature" }
    }
}

private fun projectProxy(basePath: String): Project {
    val handler = InvocationHandler { _: Any, method: Method, args: Array<Any?>? ->
        when (method.name) {
            "getBasePath" -> basePath
            "toString" -> "ReviewArtifactStoreProjectProxy($basePath)"
            "hashCode" -> basePath.hashCode()
            "equals" -> args?.firstOrNull()?.let { it === args.firstOrNull() } ?: false
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
