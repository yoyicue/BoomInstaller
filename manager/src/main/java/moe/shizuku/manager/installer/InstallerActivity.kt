package moe.shizuku.manager.installer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.InstallerActivityBinding
import rikka.shizuku.Shizuku
import java.text.NumberFormat

class InstallerActivity : AppBarActivity() {

    private data class ApkSelection(val uri: Uri, val name: String, val size: Long)

    private lateinit var binding: InstallerActivityBinding
    private var selection: ApkSelection? = null
    private var service: IBoomInstallerService? = null
    private var serviceBound = false
    private var installing = false

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(packageName, BoomInstallerUserService::class.java.name)
        )
            .daemon(false)
            .tag("boom-installer")
            .processNameSuffix("installer")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val candidate = IBoomInstallerService.Stub.asInterface(binder)
            runCatching { candidate.uid }.onSuccess { uid ->
                if (InstallerIdentity.isInstallerServiceUid(uid)) {
                    service = candidate
                    binding.status.text = getString(R.string.installer_service_ready, uid)
                } else {
                    service = null
                    binding.status.text = getString(R.string.installer_service_wrong_uid, uid)
                }
                updateActions()
            }.onFailure { showError(it) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            if (!installing) binding.status.setText(R.string.installer_service_disconnected)
            updateActions()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { bindInstallerService() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        service = null
        runOnUiThread {
            binding.status.setText(R.string.installer_boom_service_required)
            updateActions()
        }
    }

    private val installCallback = object : IInstallCallback.Stub() {
        override fun onStatus(message: String) {
            runOnUiThread { binding.status.text = message }
        }

        override fun onProgress(writtenBytes: Long, totalBytes: Long) {
            runOnUiThread {
                binding.progress.visibility = View.VISIBLE
                if (totalBytes > 0) {
                    binding.progress.isIndeterminate = false
                    binding.progress.progress = ((writtenBytes * 100L / totalBytes)
                        .coerceIn(0, 100)).toInt()
                    binding.status.text = getString(
                        R.string.installer_copying,
                        formatSize(writtenBytes),
                        formatSize(totalBytes)
                    )
                } else {
                    binding.progress.isIndeterminate = true
                    binding.status.text = getString(
                        R.string.installer_copying_unknown,
                        formatSize(writtenBytes)
                    )
                }
            }
        }

        override fun onFinished(status: Int, message: String, packageName: String) {
            runOnUiThread {
                installing = false
                binding.progress.isIndeterminate = false
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    binding.progress.progress = 100
                    binding.status.text = if (packageName.isBlank()) {
                        getString(R.string.installer_success)
                    } else {
                        getString(R.string.installer_success_package, packageName)
                    }
                } else {
                    binding.progress.visibility = View.GONE
                    binding.status.text = getString(
                        R.string.installer_failed,
                        status,
                        message.ifBlank { getString(R.string.installer_unknown_error) }
                    )
                }
                updateActions()
            }
        }
    }

    private val openApk = registerForActivityResult(
        object : ActivityResultContracts.OpenDocument() {
            override fun createIntent(context: Context, input: Array<String>): Intent {
                return super.createIntent(context, input).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        putExtra(
                            DocumentsContract.EXTRA_INITIAL_URI,
                            "content://com.android.externalstorage.documents/document/primary%3ADownload".toUri()
                        )
                    }
                }
            }
        }
    ) { uri ->
        if (uri != null) selectUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = InstallerActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.selectButton.setOnClickListener {
            openApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
        }
        binding.installButton.setOnClickListener { installSelected() }

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        intent?.data?.let(::selectUri)
        if (!Shizuku.pingBinder()) {
            binding.status.setText(R.string.installer_boom_service_required)
        }
        updateActions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let(::selectUri)
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        if (serviceBound) {
            runCatching {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, false)
            }
            serviceBound = false
            service = null
        }
        super.onDestroy()
    }

    private fun bindInstallerService() {
        runOnUiThread {
            if (service != null || serviceBound) return@runOnUiThread
            binding.status.setText(R.string.installer_service_connecting)
            runCatching {
                Shizuku.bindUserService(userServiceArgs, serviceConnection)
                serviceBound = true
            }.onFailure {
                serviceBound = false
                showError(it)
            }
        }
    }

    private fun selectUri(uri: Uri) {
        lifecycleScope.launch {
            val selected = withContext(Dispatchers.IO) { querySelection(uri) }
            if (selected == null) {
                binding.status.setText(R.string.installer_apk_unreadable)
                return@launch
            }
            selection = selected
            binding.selectedFile.text = if (selected.size > 0) {
                getString(R.string.installer_selected_file_size, selected.name, formatSize(selected.size))
            } else {
                selected.name
            }
            updateActions()
        }
    }

    private fun querySelection(uri: Uri): ApkSelection? {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "base.apk"
        var size = -1L
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let {
                        if (!cursor.isNull(it)) name = cursor.getString(it)
                    }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let {
                        if (!cursor.isNull(it)) size = cursor.getLong(it)
                    }
                }
            }
        }
        return runCatching {
            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                if (size <= 0) size = descriptor.statSize
            } ?: return null
            ApkSelection(uri, name, size)
        }.getOrNull()
    }

    private fun installSelected() {
        val selected = selection ?: return
        val remote = service ?: return
        installing = true
        binding.progress.visibility = View.VISIBLE
        binding.progress.isIndeterminate = true
        binding.status.setText(R.string.installer_opening_apk)
        updateActions()

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val descriptor = contentResolver.openFileDescriptor(selected.uri, "r")
                    ?: error(getString(R.string.installer_apk_unreadable))
                descriptor.use {
                    remote.install(it, selected.size, selected.name, installCallback)
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    installing = false
                    binding.progress.visibility = View.GONE
                    showError(it)
                    updateActions()
                }
            }
        }
    }

    private fun updateActions() {
        binding.selectButton.isEnabled = !installing
        binding.installButton.isEnabled = !installing && selection != null && service != null
    }

    private fun showError(error: Throwable) {
        binding.status.text = getString(
            R.string.installer_error,
            error.message ?: error.javaClass.simpleName
        )
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 0) return getString(R.string.installer_size_unknown)
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return "${NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(value)} ${units[unit]}"
    }
}
