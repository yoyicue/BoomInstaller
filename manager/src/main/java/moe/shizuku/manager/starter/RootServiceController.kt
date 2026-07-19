package moe.shizuku.manager.starter

import android.os.SystemClock
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku

/**
 * Owns the complete root-service startup transaction.
 *
 * A successful su command only means the native starter forked. The transaction is complete
 * only after this Manager receives a Shizuku Binder whose runtime UID is root.
 */
object RootServiceController {

    enum class State {
        ALREADY_RUNNING,
        STARTED,
        ROOT_UNAVAILABLE,
        SHELL_ERROR,
        SELINUX_BINDER_DENIED,
        STARTER_FAILED,
        SERVER_TIMEOUT,
        WRONG_RUNTIME_UID,
        CANCELLED
    }

    data class Result(
        val state: State,
        val detail: String,
        val output: String = ""
    ) {
        val succeeded: Boolean
            get() = state == State.ALREADY_RUNNING || state == State.STARTED

        val retryable: Boolean
            get() = state == State.ROOT_UNAVAILABLE
                || state == State.SHELL_ERROR
                || state == State.SERVER_TIMEOUT

        val statusName: String
            get() = when (state) {
                State.ALREADY_RUNNING -> "already-running"
                State.STARTED -> "started"
                State.ROOT_UNAVAILABLE -> "root-unavailable"
                State.SHELL_ERROR -> "root-shell-error"
                State.SELINUX_BINDER_DENIED -> "root-selinux-binder-denied"
                State.STARTER_FAILED -> "root-starter-failed"
                State.SERVER_TIMEOUT -> "root-service-timeout"
                State.WRONG_RUNTIME_UID -> "wrong-runtime-identity"
                State.CANCELLED -> "stopped"
            }

        fun diagnosticDetail(maxOutputChars: Int = 1_000): String {
            if (output.isBlank()) return detail
            val tail = output.takeLast(maxOutputChars)
                .replace('\n', ' ')
                .replace('\r', ' ')
            return "$detail; starter-output=$tail"
        }
    }

    private const val ROOT_UID = 0
    private const val SELINUX_BINDER_DENIED_EXIT = 10
    private const val DEFAULT_TIMEOUT_MILLIS = 10_000L
    private const val POLL_MILLIS = 250L
    private const val MAX_OUTPUT_CHARS = 8_192
    private val startLock = Any()

    fun start(
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        restartRunningServer: Boolean = false,
        cancelled: () -> Boolean = { false },
        onOutput: (String) -> Unit = {}
    ): Result = synchronized(startLock) {
        if (cancelled()) {
            return@synchronized Result(State.CANCELLED, "startup cancelled")
        }

        val existingUid = currentServerUid()
        if (existingUid == ROOT_UID && !restartRunningServer) {
            return@synchronized Result(State.ALREADY_RUNNING, "root Binder already connected")
        }
        if (existingUid != null && !restartRunningServer) {
            return@synchronized Result(
                State.WRONG_RUNTIME_UID,
                "expected root Binder uid=0, actual uid=$existingUid"
            )
        }

        onOutput("Opening root shell…")
        val shell = try {
            openRootShell()
        } catch (error: Throwable) {
            return@synchronized Result(
                State.SHELL_ERROR,
                error.javaClass.simpleName + ": " + (error.message ?: "cannot open root shell")
            )
        }
        if (shell == null) {
            return@synchronized Result(
                State.ROOT_UNAVAILABLE,
                "root permission was denied or the su service is not ready"
            )
        }
        if (cancelled()) {
            return@synchronized Result(State.CANCELLED, "startup cancelled")
        }

        onOutput("Starting BoomInstaller root service…")
        val commandResult = try {
            Shell.cmd(Starter.internalCommand).exec()
        } catch (error: Throwable) {
            Shell.getCachedShell()?.close()
            return@synchronized Result(
                State.SHELL_ERROR,
                error.javaClass.simpleName + ": " + (error.message ?: "starter execution failed")
            )
        }
        val output = commandResult.out.joinToString("\n").takeLast(MAX_OUTPUT_CHARS)
        commandResult.out.forEach(onOutput)
        if (commandResult.code != 0) {
            val state = if (commandResult.code == SELINUX_BINDER_DENIED_EXIT) {
                State.SELINUX_BINDER_DENIED
            } else {
                State.STARTER_FAILED
            }
            return@synchronized Result(
                state,
                "native starter exited with ${commandResult.code}" + outputSuffix(output),
                output
            )
        }

        onOutput("Waiting for root Binder…")
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var lastWrongUid: Int? = null
        do {
            if (cancelled()) {
                return@synchronized Result(State.CANCELLED, "startup cancelled", output)
            }
            val uid = currentServerUid()
            if (uid == ROOT_UID) {
                return@synchronized Result(State.STARTED, "root Binder connected", output)
            }
            if (uid != null) {
                lastWrongUid = uid
                if (!restartRunningServer) {
                    return@synchronized Result(
                        State.WRONG_RUNTIME_UID,
                        "expected root Binder uid=0, actual uid=$uid",
                        output
                    )
                }
            }
            SystemClock.sleep(POLL_MILLIS)
        } while (SystemClock.elapsedRealtime() < deadline)

        if (lastWrongUid != null) {
            Result(
                State.WRONG_RUNTIME_UID,
                "root starter completed but Binder uid remained $lastWrongUid",
                output
            )
        } else {
            Result(
                State.SERVER_TIMEOUT,
                "native starter completed but root Binder did not appear within ${timeoutMillis / 1000} seconds",
                output
            )
        }
    }

    private fun openRootShell(): Shell? {
        repeat(2) {
            val shell = Shell.getShell()
            if (shell.isRoot) return shell
            Shell.getCachedShell()?.close()
        }
        return null
    }

    private fun currentServerUid(): Int? {
        if (!Shizuku.pingBinder()) return null
        return runCatching { Shizuku.getUid() }.getOrNull()
    }

    private fun outputSuffix(output: String): String {
        val lastLine = output.lineSequence().lastOrNull { it.isNotBlank() } ?: return ""
        return ": ${lastLine.take(800)}"
    }
}
