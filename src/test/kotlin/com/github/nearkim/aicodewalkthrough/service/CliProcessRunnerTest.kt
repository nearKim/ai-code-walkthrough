package com.github.nearkim.aicodewalkthrough.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CliProcessRunnerTest {

    @Test
    fun `unbounded run waits for process exit without using timed wait`() {
        val process = CompletionOnlyProcess()

        CliProcessRunner.runUntilExit(process)

        assertTrue(process.waitedUntilExit)
        assertFalse(process.usedTimedWait)
        assertFalse(process.wasDestroyed)
    }

    @Test
    fun `completed process output is drained before returning`() {
        val process = CompletedOutputProcess(
            stdout = "first\nsecond\n",
            stderr = "warning\n",
        )
        val stdoutLines = mutableListOf<String>()
        val stderrLines = mutableListOf<String>()

        val finished = CliProcessRunner.run(
            process = process,
            timeout = Duration.ofSeconds(1),
            onStdoutLine = stdoutLines::add,
            onStderrLine = stderrLines::add,
        )

        assertTrue(finished)
        assertEquals(listOf("first", "second"), stdoutLines)
        assertEquals(listOf("warning"), stderrLines)
    }

    @Test
    fun `timeout is enforced while process output streams remain open`() {
        val process = BlockingOutputProcess()
        val startedAt = System.nanoTime()

        val finished = CliProcessRunner.run(
            process = process,
            timeout = Duration.ofMillis(50),
        )

        val elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis()
        assertFalse(finished)
        assertTrue(process.wasDestroyed)
        assertTrue("Timeout took ${elapsedMillis}ms", elapsedMillis < 1_000)
    }

    private class BlockingOutputProcess : Process() {
        private val destroyed = AtomicBoolean(false)
        private val stdout = PipedInputStream()
        private val stdoutWriter = PipedOutputStream(stdout)
        private val stderr = PipedInputStream()
        private val stderrWriter = PipedOutputStream(stderr)

        val wasDestroyed: Boolean
            get() = destroyed.get()

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = stdout

        override fun getErrorStream(): InputStream = stderr

        override fun waitFor(): Int {
            while (!destroyed.get()) {
                Thread.sleep(10)
            }
            return 137
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            Thread.sleep(unit.toMillis(timeout))
            return false
        }

        override fun exitValue(): Int {
            if (!destroyed.get()) throw IllegalThreadStateException("Process is still running")
            return 137
        }

        override fun destroy() {
            destroyForcibly()
        }

        override fun destroyForcibly(): Process {
            if (destroyed.compareAndSet(false, true)) {
                stdoutWriter.close()
                stderrWriter.close()
            }
            return this
        }

        override fun isAlive(): Boolean = !destroyed.get()
    }

    private class CompletedOutputProcess(stdout: String, stderr: String) : Process() {
        private val stdoutStream = ByteArrayInputStream(stdout.toByteArray())
        private val stderrStream = ByteArrayInputStream(stderr.toByteArray())

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = stdoutStream

        override fun getErrorStream(): InputStream = stderrStream

        override fun waitFor(): Int = 0

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true

        override fun exitValue(): Int = 0

        override fun destroy() = Unit

        override fun destroyForcibly(): Process = this

        override fun isAlive(): Boolean = false
    }

    private class CompletionOnlyProcess : Process() {
        var waitedUntilExit = false
        var usedTimedWait = false
        var wasDestroyed = false

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            waitedUntilExit = true
            return 0
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            usedTimedWait = true
            return false
        }

        override fun exitValue(): Int = 0

        override fun destroy() {
            wasDestroyed = true
        }

        override fun destroyForcibly(): Process {
            wasDestroyed = true
            return this
        }

        override fun isAlive(): Boolean = false
    }
}
