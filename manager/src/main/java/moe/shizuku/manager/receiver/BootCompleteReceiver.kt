package moe.shizuku.manager.receiver

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BootCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED != intent.action
            && Intent.ACTION_BOOT_COMPLETED != intent.action) {
            return
        }

        if (UserHandleCompat.myUserId() > 0 || Shizuku.pingBinder()) return

        val lastLaunchMode = ShizukuSettings.getLastLaunchMode()
        val canStartViaAdb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU // https://r.android.com/2128832
            && context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        if (lastLaunchMode != LaunchMethod.ROOT
            && (lastLaunchMode != LaunchMethod.ADB || !canStartViaAdb)) {
            Log.w(AppConstants.TAG, "No support start on boot")
            return
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                var started = lastLaunchMode == LaunchMethod.ROOT && rootStart()
                if (!started && canStartViaAdb) {
                    if (lastLaunchMode == LaunchMethod.ROOT) {
                        Log.w(AppConstants.TAG, "Root start on boot unavailable; falling back to local ADB")
                    }
                    started = adbStart(context)
                }
                if (!started) Log.e(AppConstants.TAG, "Start on boot failed")
            } catch (e: Throwable) {
                Log.e(AppConstants.TAG, "Start on boot failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun rootStart(): Boolean {
        return try {
            if (!Shell.getShell().isRoot) {
                //NotificationHelper.notify(context, AppConstants.NOTIFICATION_ID_STATUS, AppConstants.NOTIFICATION_CHANNEL_STATUS, R.string.notification_service_start_no_root)
                Shell.getCachedShell()?.close()
                return false
            }

            val result = Shell.cmd(Starter.internalCommand).exec()
            if (result.code != 0) {
                Log.e(AppConstants.TAG, "Root start on boot failed with exit code ${result.code}")
                return false
            }
            Log.i(AppConstants.TAG, "Root start on boot succeeded")
            true
        } catch (e: Throwable) {
            Shell.getCachedShell()?.close()
            Log.e(AppConstants.TAG, "Root start on boot failed", e)
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun adbStart(context: Context): Boolean {
        val cr = context.contentResolver
        Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
        Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
        Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
        return try {
            val key = AdbKey(
                PreferenceAdbKeyStore(ShizukuSettings.getPreferences()),
                "boominstaller"
            )
            val discoveredPort = AtomicInteger(-1)
            val latch = CountDownLatch(1)
            val adbMdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { port ->
                if (port > 0 && discoveredPort.compareAndSet(-1, port)) {
                    latch.countDown()
                }
            }
            var started = false
            if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1) {
                try {
                    adbMdns.start()
                    latch.await(3, TimeUnit.SECONDS)
                } finally {
                    adbMdns.stop()
                }
                val port = discoveredPort.get()
                if (port > 0) started = startViaAdb(port, key)
            }
            if (!started) Log.e(AppConstants.TAG, "ADB start on boot failed: mDNS found no usable local ADB port")
            started
        } catch (e: Throwable) {
            Log.e(AppConstants.TAG, "ADB start on boot failed", e)
            false
        }
    }

    private fun startViaAdb(port: Int, key: AdbKey): Boolean {
        return try {
            AdbClient("127.0.0.1", port, key).use { client ->
                client.connect()
                client.shellCommand(Starter.internalCommand, null)
            }
            Log.i(AppConstants.TAG, "ADB start on boot succeeded on local port $port")
            true
        } catch (e: Throwable) {
            Log.d(AppConstants.TAG, "Local port $port is not ADB", e)
            false
        }
    }

}
