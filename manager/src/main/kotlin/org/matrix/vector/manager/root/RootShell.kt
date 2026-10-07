package org.matrix.vector.manager.root

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs commands through `su`, which is the only door into a framework this manager's signature
 * cannot reach by binder.
 *
 * Every command has a hard timeout, because a su prompt the user never answers must not leave a
 * repository call hanging for the lifetime of the process — a screen waiting on a dead `su` looks
 * identical to a dead manager.
 */
internal object RootShell {

    data class Result(val exitCode: Int, val out: String, val err: String) {
        val ok: Boolean get() = exitCode == 0
    }

    @Volatile
    var probed: Boolean = false
        private set

    @Volatile
    var granted: Boolean = false
        private set

    /** Probes once per process: spawns `su` and checks the answer is root's. */
    fun ensureGranted(): Boolean {
        if (probed) return granted
        synchronized(this) {
            if (probed) return granted
            // The probe itself goes through su: the caller's uid says nothing about whether su
            // exists and is granted, which is the only question here.
            val r = run("su", "-c", "id", timeoutMs = 8_000)
            granted = r.exitCode == 0 && r.out.contains("uid=0")
            probed = true
            return granted
        }
    }

    /**
     * Runs one command directly, capturing stdout and stderr concurrently so a chatty child can
     * never fill a pipe and block both of us.
     */
    fun run(vararg command: String, timeoutMs: Long = 15_000): Result =
        try {
            val process = ProcessBuilder(*command).start()

            val outSink = drain(process)
            val errSink = drain(process, stdout = false)

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            val out = outSink.await(timeoutMs).orEmpty()
            val err = errSink.await(timeoutMs).orEmpty()
            if (finished) Result(process.exitValue(), out, err)
            else {
                process.destroyForcibly()
                Result(-1, out, "$err\n(command timed out after ${timeoutMs}ms)")
            }
        } catch (e: Exception) {
            Result(-1, "", e.message ?: "spawn failed")
        }

    /** Runs one shell string as root. [shell] is a full command line, quoted by the caller. */
    fun su(shell: String, timeoutMs: Long = 15_000): Result =
        run("su", "-c", shell, timeoutMs = timeoutMs)

    private fun drain(process: Process, stdout: Boolean = true): Sink {
        val stream = if (stdout) process.inputStream else process.errorStream
        val sink = Sink()
        Thread {
            try {
                sink.text = stream.bufferedReader().readText()
            } catch (_: Exception) {
            } finally {
                sink.latch.countDown()
            }
        }
            .start()
        return sink
    }

    private class Sink {
        @Volatile var text: String? = null
        val latch = CountDownLatch(1)

        fun await(timeoutMs: Long): String? {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return text
        }
    }
}
