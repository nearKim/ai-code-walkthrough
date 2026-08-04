package com.github.nearkim.aicodewalkthrough.service

import java.io.InputStream
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object CliProcessRunner {

    fun runUntilExit(
        process: Process,
        stdin: String? = null,
        onStdoutLine: (String) -> Unit = {},
        onStderrLine: (String) -> Unit = {},
    ) {
        runProcess(
            process = process,
            timeout = null,
            stdin = stdin,
            onStdoutLine = onStdoutLine,
            onStderrLine = onStderrLine,
        )
    }

    fun run(
        process: Process,
        timeout: Duration,
        onStdoutLine: (String) -> Unit = {},
        onStderrLine: (String) -> Unit = {},
    ): Boolean = runProcess(
        process = process,
        timeout = timeout,
        stdin = null,
        onStdoutLine = onStdoutLine,
        onStderrLine = onStderrLine,
    )

    private fun runProcess(
        process: Process,
        timeout: Duration?,
        stdin: String?,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit,
    ): Boolean {
        val readerFailure = AtomicReference<Throwable?>()
        val stdoutThread = pumpLines(process.inputStream, onStdoutLine, readerFailure)
        val stderrThread = pumpLines(process.errorStream, onStderrLine, readerFailure)
        val stdinThread = stdin?.let { pumpStdin(process, it) }

        val finished = try {
            if (timeout == null) {
                process.waitFor()
                true
            } else {
                process.waitFor(timeout.toMillis().coerceAtLeast(1), TimeUnit.MILLISECONDS)
            }
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            throw e
        }

        if (!finished) {
            process.destroyForcibly()
        }

        stdinThread?.join(READER_SHUTDOWN_TIMEOUT_MILLIS)
        if (finished) {
            stdoutThread.join()
            stderrThread.join()
            readerFailure.get()?.let { throw IllegalStateException("Failed to read CLI process output", it) }
        } else {
            stdoutThread.join(READER_SHUTDOWN_TIMEOUT_MILLIS)
            stderrThread.join(READER_SHUTDOWN_TIMEOUT_MILLIS)
        }

        return finished
    }

    /**
     * Prompts can exceed the OS argument limit, so they travel on stdin. Writing from its own
     * thread keeps a full stdout pipe from deadlocking the write; a broken pipe only means the
     * CLI exited early, and its exit code already carries that failure.
     */
    private fun pumpStdin(process: Process, text: String): Thread = Thread.ofVirtual().start {
        runCatching { process.outputStream.bufferedWriter().use { it.write(text) } }
    }

    private fun pumpLines(
        stream: InputStream,
        onLine: (String) -> Unit,
        readerFailure: AtomicReference<Throwable?>,
    ): Thread = Thread.ofVirtual().start {
        try {
            stream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
        } catch (e: Exception) {
            readerFailure.compareAndSet(null, e)
        }
    }

    private const val READER_SHUTDOWN_TIMEOUT_MILLIS = 1_000L
}
