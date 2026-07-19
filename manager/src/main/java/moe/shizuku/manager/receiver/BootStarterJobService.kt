package moe.shizuku.manager.receiver

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.pm.PackageManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbAuthenticationException
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.starter.RootServiceController
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Starts BoomInstaller after networking is ready, outside BroadcastReceiver's short timeout.
 * The service runtime is root or standard ADB shell; installer identities are never selected here.
 */
class BootStarterJobService : JobService() {

    private val cancelled = AtomicBoolean(false)

    override fun onStartJob(params: JobParameters): Boolean {
        cancelled.set(false)
        Thread({
            val retry = try {
                !startForCurrentBoot()
            } catch (error: Throwable) {
                record("failed", error.javaClass.simpleName + ": " + (error.message ?: "no message"))
                true
            }
            jobFinished(params, retry && !cancelled.get())
        }, "BoomInstallerBootStarter").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        cancelled.set(true)
        record("stopped", "job constraints changed")
        return true
    }

    private fun startForCurrentBoot(): Boolean {
        ShizukuSettings.initialize(this)
        if (UserHandleCompat.myUserId() > 0) {
            record("skipped", "secondary user")
            return true
        }
        if (acceptedServerIsRunning()) {
            resetRootAttempts()
            record("already-running", "uid=${Shizuku.getUid()}")
            return true
        }

        val mode = ShizukuSettings.getLastLaunchMode()
        val canStartViaAdb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        if (mode != LaunchMethod.ROOT && (mode != LaunchMethod.ADB || !canStartViaAdb)) {
            record("unsupported", "mode=$mode adb=$canStartViaAdb")
            return true
        }

        if (mode == LaunchMethod.ROOT) {
            val attempt = nextRootAttempt()
            val rootResult = RootServiceController.start(
                timeoutMillis = SERVER_VERIFY_MILLIS,
                cancelled = { cancelled.get() }
            )
            if (rootResult.succeeded) {
                resetRootAttempts()
                record("started", "root Binder verified on attempt $attempt")
                return true
            }
            if (!canStartViaAdb) {
                val detail = "attempt $attempt/$ROOT_MAX_ATTEMPTS: " +
                    "${rootResult.diagnosticDetail()}; " +
                    "local ADB not provisioned"
                if (rootResult.retryable && attempt < ROOT_MAX_ATTEMPTS) {
                    record("retrying-${rootResult.statusName}", detail)
                    return false
                }
                record(rootResult.statusName, detail)
                return true
            }
            record(
                "fallback",
                "${rootResult.statusName}: ${rootResult.diagnosticDetail()}; " +
                    "trying local ADB shell"
            )
        }

        if (!canStartViaAdb) {
            record("failed", "local ADB not provisioned")
            return false
        }
        val result = adbStart()
        record(result.state, result.detail, result.paired)
        return result.started
    }

    private data class AdbStartResult(
        val started: Boolean,
        val state: String,
        val detail: String,
        val paired: Boolean? = null
    )

    private fun adbStart(): AdbStartResult {
        val resolver = contentResolver
        val keyStore = PreferenceAdbKeyStore(ShizukuSettings.getPreferences())
        if (!keyStore.hasStoredKey()) {
            return AdbStartResult(false, "not-paired", "wireless ADB pairing key is missing", false)
        }
        val key = try {
            AdbKey(keyStore, "shizuku", false)
        } catch (error: Throwable) {
            Log.w(AppConstants.TAG, "Stored local ADB key is invalid", error)
            return AdbStartResult(false, "key-invalid", "stored wireless ADB key is invalid", false)
        }
        if (!Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)
            || !Settings.Global.putInt(resolver, "adb_wifi_enabled", 1)
            || !Settings.Global.putLong(resolver, "adb_allowed_connection_time", 0L)) {
            return AdbStartResult(
                false, "wireless-adb-not-started", "wireless ADB settings write failed"
            )
        }
        var stable = 0
        repeat(WIRELESS_SETTINGS_SAMPLES) {
            if (Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 1
                && Settings.Global.getInt(resolver, "adb_wifi_enabled", 0) == 1) {
                stable++
            } else {
                stable = 0
            }
            if (stable < WIRELESS_SETTINGS_STABLE_SAMPLES) {
                SystemClock.sleep(WIRELESS_SETTINGS_POLL_MILLIS)
            }
        }
        if (stable < WIRELESS_SETTINGS_STABLE_SAMPLES) {
            return AdbStartResult(
                false,
                "network-untrusted",
                "wireless debugging was disabled; confirm 'Always allow on this network'",
                true
            )
        }

        val ports = LinkedBlockingQueue<Int>()
        val seen = HashSet<Int>()
        val adbMdns = AdbMdns(this, AdbMdns.TLS_CONNECT) { port ->
            if (port > 0) ports.offer(port)
        }
        val deadline = SystemClock.elapsedRealtime() + ADB_WAIT_MILLIS
        var lastFailure: AdbStartResult? = null
        adbMdns.start()
        try {
            while (!cancelled.get() && SystemClock.elapsedRealtime() < deadline) {
                if (Settings.Global.getInt(resolver, "adb_wifi_enabled", 0) != 1) {
                    return AdbStartResult(
                        false,
                        "network-untrusted",
                        "wireless debugging authorization was revoked while starting",
                        true
                    )
                }
                val remaining = deadline - SystemClock.elapsedRealtime()
                val port = ports.poll(minOf(MDNS_POLL_MILLIS, remaining), TimeUnit.MILLISECONDS)
                    ?: continue
                if (!seen.add(port)) continue
                Log.i(AppConstants.TAG, "BoomInstaller local ADB candidate: port=$port")
                val result = startViaAdb(port, key)
                if (result.started || result.state == "key-invalid") return result
                lastFailure = result
            }
        } finally {
            adbMdns.stop()
        }
        if (cancelled.get()) {
            return AdbStartResult(false, "stopped", "job constraints changed", true)
        }
        return lastFailure ?: AdbStartResult(
            false,
            "wireless-adb-not-started",
            "wireless ADB TLS service was not discovered within ${ADB_WAIT_MILLIS / 1000} seconds",
            true
        )
    }

    private fun startViaAdb(port: Int, key: AdbKey): AdbStartResult {
        return try {
            val marker = "__BOOM_START_RC_${android.os.Process.myPid()}__"
            val output = StringBuilder()
            AdbClient("127.0.0.1", port, key).use { client ->
                client.connect()
                client.shellCommand("${Starter.internalCommand}; RC=\$?; echo $marker\$RC") { bytes ->
                    output.append(String(bytes, StandardCharsets.UTF_8))
                    if (output.length > MAX_START_OUTPUT) {
                        output.delete(0, output.length - MAX_START_OUTPUT)
                    }
                }
            }
            if (!output.contains(marker + "0")) {
                return AdbStartResult(
                    false,
                    "local-adb-command-failed",
                    output.toString().trim().takeLast(800),
                    true
                )
            }
            val accepted = awaitServer(android.os.Process.SHELL_UID, SERVER_VERIFY_MILLIS)
            if (accepted) {
                AdbStartResult(true, "started", "adb-shell", true)
            } else {
                AdbStartResult(
                    false,
                    "service-start-timeout",
                    "local ADB command completed but BoomInstaller service did not appear",
                    true
                )
            }
        } catch (error: AdbAuthenticationException) {
            Log.w(AppConstants.TAG, "Stored local ADB key was rejected", error)
            AdbStartResult(false, "key-invalid", "paired wireless ADB key was rejected", false)
        } catch (error: Throwable) {
            Log.w(AppConstants.TAG, "Local ADB candidate $port failed", error)
            AdbStartResult(
                false,
                "local-adb-connect-failed",
                error.javaClass.simpleName + ": " + (error.message ?: "no message"),
                true
            )
        }
    }

    private fun acceptedServerIsRunning(): Boolean {
        if (!Shizuku.pingBinder()) return false
        return runCatching { Shizuku.getUid() == 0 || Shizuku.getUid() == android.os.Process.SHELL_UID }
            .getOrDefault(false)
    }

    private fun awaitServer(expectedUid: Int, timeoutMillis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        do {
            if (cancelled.get()) return false
            if (Shizuku.pingBinder()) {
                val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
                if (uid == expectedUid) return true
                record("wrong-runtime-identity", "expected=$expectedUid actual=$uid")
                return false
            }
            SystemClock.sleep(250)
        } while (SystemClock.elapsedRealtime() < deadline)
        return false
    }

    private fun record(state: String, detail: String, paired: Boolean? = null) {
        recordStatus(this, state, detail, paired)
    }

    private fun nextRootAttempt(): Int {
        val preferences = statusPreferences(this)
        val next = preferences.getInt(KEY_ROOT_ATTEMPT, 0) + 1
        preferences.edit().putInt(KEY_ROOT_ATTEMPT, next).commit()
        return next
    }

    private fun resetRootAttempts() {
        statusPreferences(this).edit().putInt(KEY_ROOT_ATTEMPT, 0).commit()
    }

    companion object {
        const val JOB_ID = 0x424f4f4d
        const val STATUS_PREFERENCES = "boom_autostart_status"
        private const val WIRELESS_SETTINGS_SAMPLES = 5
        private const val WIRELESS_SETTINGS_STABLE_SAMPLES = 3
        private const val WIRELESS_SETTINGS_POLL_MILLIS = 1_000L
        const val ROOT_RETRY_INITIAL_MILLIS = 15_000L
        private const val ROOT_MAX_ATTEMPTS = 5
        private const val ADB_WAIT_MILLIS = 60_000L
        private const val MDNS_POLL_MILLIS = 5_000L
        private const val SERVER_VERIFY_MILLIS = 10_000L
        private const val MAX_START_OUTPUT = 8_192
        private const val KEY_BOOT_ID = "root_attempt_boot_id"
        private const val KEY_ROOT_ATTEMPT = "root_attempt"
        private const val KEY_UNLOCKED_PHASE = "root_attempt_unlocked_phase"

        private fun statusPreferences(context: Context) =
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(STATUS_PREFERENCES, Context.MODE_PRIVATE)

        fun prepareForBoot(context: Context, credentialUnlocked: Boolean) {
            val bootId = runCatching {
                java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()
            }.getOrDefault("")
            val preferences = statusPreferences(context)
            val newBoot = bootId.isNotEmpty() && preferences.getString(KEY_BOOT_ID, null) != bootId
            val enteredUnlockedPhase = credentialUnlocked &&
                !preferences.getBoolean(KEY_UNLOCKED_PHASE, false)
            if (newBoot || enteredUnlockedPhase) {
                val editor = preferences.edit().putInt(KEY_ROOT_ATTEMPT, 0)
                if (bootId.isNotEmpty()) editor.putString(KEY_BOOT_ID, bootId)
                editor.putBoolean(KEY_UNLOCKED_PHASE, credentialUnlocked)
                editor.commit()
            }
        }

        fun recordStatus(context: Context, state: String, detail: String, paired: Boolean? = null) {
            val safeDetail = detail.replace('\n', ' ').replace('\r', ' ').take(1600)
            val preferences = statusPreferences(context)
            val editor = preferences.edit()
                .putString("state", state)
                .putString("detail", safeDetail)
                .putLong("timestamp", System.currentTimeMillis())
            if (paired != null) editor.putBoolean("paired", paired)
            editor.commit()
            Log.i(AppConstants.TAG, "BoomInstaller boot start: state=$state detail=$safeDetail")
        }
    }
}
