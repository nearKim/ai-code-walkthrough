package com.github.nearkim.aicodewalkthrough.service

import java.io.InputStream
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object CliProcessRunner {

    fun run(
        process: Process,
        timeout: Duration,
        onStdoutLine: (String) -> Unit = {},
        onStderrLine: (String) -> Unit = {},
    ): Boolean {
        val readerFailure = AtomicReference<Throwable?>()
        val stdoutThread = pumpLines(process.inputStream, onStdoutLine, readerFailure)
        val stderrThread = pumpLines(process.errorStream, onStderrLine, readerFailure)

        val finished = try {
            process.waitFor(timeout.toMillis().coerceAtLeast(1), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            throw e
        }

        if (!finished) {
            process.destroyForcibly()
        }

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
