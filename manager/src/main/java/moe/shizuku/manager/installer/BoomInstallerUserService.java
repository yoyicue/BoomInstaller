package moe.shizuku.manager.installer;

import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.system.Os;
import android.util.Log;

import androidx.annotation.Keep;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class BoomInstallerUserService extends IBoomInstallerService.Stub {

    private static final String TAG = "BoomInstallerService";
    private static final int BUFFER_SIZE = 128 * 1024;
    private static final int INSTALL_REPLACE_EXISTING = 0x00000002;
    private static final int INSTALL_ALLOW_TEST = 0x00000004;
    private static final long COMMIT_TIMEOUT_SECONDS = 180;

    private final Context context;
    private final AtomicBoolean busy = new AtomicBoolean(false);

    @Keep
    public BoomInstallerUserService(Context context) {
        this.context = context;
        Log.i(TAG, "created pid=" + Process.myPid() + " uid=" + Process.myUid());
    }

    @Override
    public void destroy() {
        Log.i(TAG, "destroy");
        System.exit(0);
    }

    @Override
    public int getUid() {
        return Process.myUid();
    }

    @Override
    public boolean isBusy() {
        return busy.get();
    }

    @Override
    public void install(ParcelFileDescriptor apk, long size, String displayName,
            IInstallCallback callback) {
        if (apk == null || callback == null) {
            closeQuietly(apk);
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            closeQuietly(apk);
            notifyFinished(callback, PackageInstaller.STATUS_FAILURE_CONFLICT,
                    "Another installation is already running", "");
            return;
        }

        Thread worker = new Thread(() -> {
            try {
                installInternal(apk, resolveSize(apk, size), displayName, callback);
            } catch (Throwable error) {
                Log.e(TAG, "install", error);
                notifyFinished(callback, PackageInstaller.STATUS_FAILURE,
                        error.getClass().getSimpleName() + ": " + safeMessage(error), "");
            } finally {
                closeQuietly(apk);
                busy.set(false);
            }
        }, "BoomInstallerWorker");
        worker.start();
    }

    private void installInternal(ParcelFileDescriptor apk, long size, String displayName,
            IInstallCallback callback) throws Exception {
        if (!InstallerIdentity.isInstallerServiceUid(Process.myUid())) {
            throw new SecurityException("installer service requires system identity, current uid="
                    + Process.myUid());
        }

        PackageInstaller installer = PackageInstallerFactory.create(context);
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (size > 0) params.setSize(size);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            params.setInstallReason(PackageManager.INSTALL_REASON_USER);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        PackageInstallerFactory.addInstallFlags(params,
                INSTALL_REPLACE_EXISTING | INSTALL_ALLOW_TEST);

        int sessionId = installer.createSession(params);
        boolean committed = false;
        try (PackageInstaller.Session session = installer.openSession(sessionId);
             InputStream input = new BufferedInputStream(
                     new ParcelFileDescriptor.AutoCloseInputStream(apk), BUFFER_SIZE)) {
            OutputStream sessionOutput = session.openWrite(
                    "base.apk", 0, size > 0 ? size : -1);
            try (BufferedOutputStream output = new BufferedOutputStream(
                    sessionOutput, BUFFER_SIZE)) {
                copy(input, output, size, callback);
                output.flush();
                session.fsync(sessionOutput);
            }

            Intent result = commitAndWait(session, callback);
            committed = true;
            int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            String message = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            String packageName = result.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
            notifyFinished(callback, status, message == null ? "" : message,
                    packageName == null ? "" : packageName);
        } finally {
            if (!committed) {
                try {
                    installer.abandonSession(sessionId);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void copy(InputStream input, OutputStream output, long total,
            IInstallCallback callback) throws Exception {
        byte[] buffer = new byte[BUFFER_SIZE];
        long written = 0;
        long lastReported = -1;
        notifyProgress(callback, 0, total);
        for (;;) {
            int count = input.read(buffer);
            if (count < 0) break;
            if (count == 0) continue;
            output.write(buffer, 0, count);
            written += count;
            if (written - lastReported >= 512 * 1024 || written == total) {
                notifyProgress(callback, written, total);
                lastReported = written;
            }
        }
        notifyProgress(callback, written, total > 0 ? total : written);
        if (total > 0 && written != total) {
            throw new IllegalStateException("APK size changed while reading: expected="
                    + total + " actual=" + written);
        }
    }

    private static Intent commitAndWait(PackageInstaller.Session session,
            IInstallCallback callback) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Intent> result = new AtomicReference<>();
        IntentSender sender = IntentSenderFactory.create(intent -> {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirmation != null) {
                    try {
                        callback.onUserActionRequired(confirmation);
                    } catch (RemoteException ignored) {
                    }
                }
                return;
            }
            result.set(intent);
            finished.countDown();
        });
        session.commit(sender);
        if (!finished.await(COMMIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("PackageInstaller commit timed out");
        }
        Intent value = result.get();
        if (value == null) throw new IllegalStateException("PackageInstaller returned no result");
        return value;
    }

    private static long resolveSize(ParcelFileDescriptor descriptor, long requested) {
        if (requested > 0) return requested;
        try {
            long size = Os.fstat(descriptor.getFileDescriptor()).st_size;
            return size > 0 ? size : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static void notifyProgress(IInstallCallback callback, long written, long total) {
        try {
            callback.onProgress(written, total);
        } catch (RemoteException ignored) {
        }
    }

    private static void notifyFinished(IInstallCallback callback, int status, String message,
            String packageName) {
        try {
            callback.onFinished(status, message == null ? "" : message,
                    packageName == null ? "" : packageName);
        } catch (RemoteException ignored) {
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null ? "no message" : message;
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try {
            descriptor.close();
        } catch (Throwable ignored) {
        }
    }
}
