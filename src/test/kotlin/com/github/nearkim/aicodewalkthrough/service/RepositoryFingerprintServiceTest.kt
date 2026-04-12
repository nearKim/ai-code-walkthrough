package com.github.nearkim.aicodewalkthrough.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

class RepositoryFingerprintServiceTest {

    @Test
    fun `capture collects git metadata when available`() {
        val runner = RepositoryReviewCommandRunner { command, workingDirectory ->
            assertEquals(Path.of("/repo"), workingDirectory)
            when (command) {
                listOf("git", "rev-parse", "HEAD") -> "abc123"
                listOf("git", "rev-parse", "--abbrev-ref", "HEAD") -> "main"
                listOf("git", "status", "--porcelain", "--untracked-files=no") -> " M src/App.kt"
                listOf("git", "ls-files") -> "src/App.kt\nsrc/Other.kt"
                else -> error("Unexpected command: ${command.joinToString(" ")}")
            }
        }

        val fingerprint = RepositoryFingerprintService.capture("/repo", runner) { 1234L }

        assertEquals("/repo", fingerprint.repoRoot)
        assertEquals("abc123", fingerprint.gitHead)
        assertEquals("main", fingerprint.gitBranch)
        assertEquals(true, fingerprint.gitDirty)
        assertEquals(2, fingerprint.trackedFileCount)
        assertNull(fingerprint.fileDigest)
        assertEquals(1234L, fingerprint.generatedAtMs)
    }

    @Test
    fun `capture falls back gracefully when git is unavailable`() {
        val fingerprint = RepositoryFingerprintService.capture(
            projectRoot = "/repo",
            commandRunner = RepositoryReviewCommandRunner { _, _ -> throw IllegalStateException("missing git") },
            clock = { 99L },
        )

        assertEquals("/repo", fingerprint.repoRoot)
        assertNull(fingerprint.gitHead)
        assertNull(fingerprint.gitBranch)
        assertEquals(false, fingerprint.gitDirty)
        assertEquals(0, fingerprint.trackedFileCount)
        assertNull(fingerprint.fileDigest)
        assertEquals(99L, fingerprint.generatedAtMs)
    }
}
