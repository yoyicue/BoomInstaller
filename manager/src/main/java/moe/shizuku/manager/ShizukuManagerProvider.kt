package moe.shizuku.manager

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Binder
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.os.bundleOf
import moe.shizuku.api.BinderContainer
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.receiver.BootCompleteReceiver
import moe.shizuku.manager.receiver.BootStarterJobService
import moe.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TOKEN
import rikka.shizuku.ShizukuProvider
import rikka.shizuku.server.ktx.workerHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ShizukuManagerProvider : ShizukuProvider() {

    companion object {
        private const val EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"
        private const val METHOD_SEND_USER_SERVICE = "sendUserService"
        private const val METHOD_PREPARE_AUTO_START = "prepareAutoStart"
        private const val METHOD_PAIR_AUTO_START = "pairAutoStart"
        private const val METHOD_GET_AUTO_START_STATUS = "getAutoStartStatus"
        private const val EXTRA_PAIRING_CODE = "pairingCode"
        private const val EXTRA_PAIRED = "paired"
        private const val AUTO_START_KEY_NAME = "shizuku"
    }

    override fun onCreate(): Boolean {
        ShizukuSettings.initialize(requireNotNull(context))
        disableAutomaticSuiInitialization()
        return super.onCreate()
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == METHOD_PREPARE_AUTO_START || method == METHOD_PAIR_AUTO_START
            || method == METHOD_GET_AUTO_START_STATUS) {
            val caller = Binder.getCallingUid()
            if (caller != Process.SHELL_UID && caller != Process.ROOT_UID) {
                throw SecurityException("auto-start provisioning requires shell or root")
            }
        }

        if (method == METHOD_PREPARE_AUTO_START) {
            val packageManager = requireNotNull(context).packageManager
            for (component in arrayOf(
                ComponentName(requireNotNull(context), BootCompleteReceiver::class.java),
                ComponentName(requireNotNull(context), BootStarterJobService::class.java)
            )) {
                packageManager.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
            BootStarterJobService.recordStatus(
                requireNotNull(context),
                "waiting-network-trust",
                "waiting for stable wireless ADB and network authorization",
                false
            )
            return Bundle.EMPTY
        }

        if (method == METHOD_PAIR_AUTO_START) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                throw UnsupportedOperationException("wireless ADB pairing requires Android 11")
            }
            return pairAutoStart(extras)
        }

        if (method == METHOD_GET_AUTO_START_STATUS) {
            val preferences = requireNotNull(context).createDeviceProtectedStorageContext()
                .getSharedPreferences(BootStarterJobService.STATUS_PREFERENCES, 0)
            val keyStore = PreferenceAdbKeyStore(ShizukuSettings.getPreferences())
            val keyPresent = keyStore.hasStoredKey()
            val keyValid = keyPresent && runCatching {
                AdbKey(keyStore, AUTO_START_KEY_NAME, false)
            }.isSuccess
            val serverUid = if (Shizuku.pingBinder()) {
                runCatching { Shizuku.getUid() }.getOrDefault(-1)
            } else {
                -1
            }
            return bundleOf(
                "providerReady" to true,
                "state" to preferences.getString("state", "never"),
                "detail" to preferences.getString("detail", ""),
                "timestamp" to preferences.getLong("timestamp", 0),
                "mode" to ShizukuSettings.getLastLaunchMode(),
                "serverUid" to serverUid,
                "pairingKeyPresent" to keyPresent,
                "pairingKeyValid" to keyValid,
                "paired" to (preferences.getBoolean("paired", false) && keyValid),
                "wirelessAdbEnabled" to (Settings.Global.getInt(
                    requireNotNull(context).contentResolver, "adb_wifi_enabled", 0
                ) == 1)
            )
        }

        if (extras == null) return null

        return if (method == METHOD_SEND_USER_SERVICE) {
            try {
                extras.classLoader = BinderContainer::class.java.classLoader

                val token = extras.getString(USER_SERVICE_ARG_TOKEN) ?: return null
                val binder = extras.getParcelable<BinderContainer>(EXTRA_BINDER)?.binder ?: return null

                val countDownLatch = CountDownLatch(1)
                var reply: Bundle? = Bundle()

                val listener = object : Shizuku.OnBinderReceivedListener {

                    override fun onBinderReceived() {
                        try {
                            Shizuku.attachUserService(binder, bundleOf(
                                USER_SERVICE_ARG_TOKEN to token
                            ))
                            reply!!.putParcelable(EXTRA_BINDER, BinderContainer(Shizuku.getBinder()))
                        } catch (e: Throwable) {
                            LOGGER.e(e, "attachUserService $token")
                            reply = null
                        }

                        Shizuku.removeBinderReceivedListener(this)

                        countDownLatch.countDown()
                    }
                }

                Shizuku.addBinderReceivedListenerSticky(listener, workerHandler)

                return try {
                    countDownLatch.await(5, TimeUnit.SECONDS)
                    reply
                } catch (e: TimeoutException) {
                    LOGGER.e(e, "Binder not received in 5s")
                    null
                }
            } catch (e: Throwable) {
                LOGGER.e(e, "sendUserService")
                null
            }
        } else {
            super.call(method, arg, extras)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun pairAutoStart(extras: Bundle?): Bundle {
        val pairingCode = extras?.getString(EXTRA_PAIRING_CODE)
            ?: throw IllegalArgumentException("pairing code is required")
        if (pairingCode.length !in 8..64 || pairingCode.any { it.code !in 33..126 }) {
            throw IllegalArgumentException("invalid pairing code")
        }

        val key = AdbKey(
            PreferenceAdbKeyStore(ShizukuSettings.getPreferences()),
            AUTO_START_KEY_NAME
        )
        BootStarterJobService.recordStatus(
            requireNotNull(context), "pairing", "waiting for wireless ADB pairing service", false
        )
        val claimed = AtomicBoolean(false)
        val paired = AtomicBoolean(false)
        val error = AtomicReference<Throwable?>()
        val completed = CountDownLatch(1)
        val adbMdns = AdbMdns(requireNotNull(context), AdbMdns.TLS_PAIRING) { port ->
            if (port <= 0 || !claimed.compareAndSet(false, true)) return@AdbMdns
            Thread({
                try {
                    AdbPairingClient("127.0.0.1", port, pairingCode, key).use {
                        paired.set(it.start())
                    }
                } catch (t: Throwable) {
                    error.set(t)
                } finally {
                    completed.countDown()
                }
            }, "BoomInstaller-pairing").start()
        }
        adbMdns.start()
        try {
            if (!completed.await(15, TimeUnit.SECONDS)) {
                error.set(TimeoutException("ADB pairing service was not discovered"))
            }
        } finally {
            adbMdns.stop()
        }
        error.get()?.let { LOGGER.e(it, "pairAutoStart") }
        if (!paired.get()) {
            val state = if (error.get() is TimeoutException) {
                "wireless-adb-not-started"
            } else {
                "pairing-failed"
            }
            val detail = error.get()?.let {
                it.javaClass.simpleName + ": " + (it.message ?: "no message")
            } ?: "wireless ADB rejected the pairing request"
            BootStarterJobService.recordStatus(requireNotNull(context), state, detail, false)
            return bundleOf(EXTRA_PAIRED to false, "state" to state)
        }
        if (!ShizukuSettings.setLastLaunchMode(ShizukuSettings.LaunchMethod.ADB)) {
            throw IllegalStateException("ADB pairing succeeded but auto-start mode was not persisted")
        }
        var stable = 0
        repeat(5) {
            if (Settings.Global.getInt(
                    requireNotNull(context).contentResolver, "adb_wifi_enabled", 0
                ) == 1) {
                stable++
            } else {
                stable = 0
            }
            if (stable < 3) SystemClock.sleep(1000)
        }
        if (stable < 3) {
            BootStarterJobService.recordStatus(
                requireNotNull(context),
                "network-untrusted",
                "wireless ADB was disabled before pairing became stable",
                false
            )
            return bundleOf(EXTRA_PAIRED to false, "state" to "network-untrusted")
        }
        BootStarterJobService.recordStatus(
            requireNotNull(context),
            "pending-reboot",
            "paired; ordinary reboot required to verify automatic start",
            true
        )
        return bundleOf(
            EXTRA_PAIRED to true,
            "state" to "pending-reboot",
            "pairingKeyPresent" to true,
            "pairingKeyValid" to true
        )
    }
}
