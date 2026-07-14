package moe.shizuku.manager

import android.os.Bundle
import android.os.Binder
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import androidx.core.os.bundleOf
import moe.shizuku.api.BinderContainer
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
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
        private const val EXTRA_PAIRING_CODE = "pairingCode"
        private const val EXTRA_PAIRED = "paired"
        private const val AUTO_START_KEY_NAME = "boominstaller"
    }

    override fun onCreate(): Boolean {
        ShizukuSettings.initialize(requireNotNull(context))
        disableAutomaticSuiInitialization()
        return super.onCreate()
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == METHOD_PREPARE_AUTO_START || method == METHOD_PAIR_AUTO_START) {
            val caller = Binder.getCallingUid()
            if (caller != Process.SHELL_UID && caller != Process.ROOT_UID) {
                throw SecurityException("auto-start provisioning requires shell or root")
            }
        }

        if (method == METHOD_PREPARE_AUTO_START) {
            ShizukuSettings.setLastLaunchMode(ShizukuSettings.LaunchMethod.ADB)
            return Bundle.EMPTY
        }

        if (method == METHOD_PAIR_AUTO_START) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                throw UnsupportedOperationException("wireless ADB pairing requires Android 11")
            }
            return pairAutoStart(extras)
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
        return bundleOf(EXTRA_PAIRED to paired.get())
    }
}
