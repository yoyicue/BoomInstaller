package moe.shizuku.manager.installer;

import android.content.Context;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.system.Os;
import android.util.Log;

import androidx.annotation.Keep;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import moe.shizuku.manager.R;

public final class BoomInstallerUserService extends IBoomInstallerService.Stub {

    private static final String TAG = "BoomInstallerService";
    private static final int BUFFER_SIZE = 128 * 1024;
    public static final int STATUS_REPAIR_PENDING = -76;
    private static final long CLI_TIMEOUT_SECONDS = 420;
    private static final String EMBEDDED_XPAD_INSTALL_ASSET = "xpad-install";
    private static final String REQUIRED_XPAD_INSTALL_VERSION = "0.2.10";
    private static final String EMBEDDED_XPAD_INSTALL_SHA256 =
            "dfe061e8105199b69771e2f9dc05d92ad85538910181dc006070dadfb0a0c15e";
    private static final long EMBEDDED_XPAD_INSTALL_SIZE = 93752;
    private static final File WORK_ROOT = new File("/data/local/tmp/.boominstaller");
    private static final File LOG_ROOT = new File(WORK_ROOT, "logs");
    private static final int MAX_LOG_FILES = 12;

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

    @Override
    public void checkInstallerStatus(IInstallCallback callback) {
        if (callback == null) return;
        if (!busy.compareAndSet(false, true)) {
            notifyFinished(callback, PackageInstaller.STATUS_FAILURE_CONFLICT,
                    "Another operation is already running", "");
            return;
        }
        new Thread(() -> {
            File cli = null;
            try {
                ensureDirectory(WORK_ROOT, 0700);
                ensureDirectory(LOG_ROOT, 0700);
                long operationId = System.currentTimeMillis();
                File logFile = new File(LOG_ROOT,
                        "status-" + operationId + "-" + Process.myPid() + ".log");
                writeLogHeader(logFile, "znxrun-status", 0);
                cli = stageEmbeddedXpadInstaller(operationId, logFile);
                notifyStatus(callback, context.getString(R.string.installer_rechecking));
                // This recovery action is deliberately read-only and must remain status-only.
                int exit = runLogged(logFile, 30,
                        cli.getAbsolutePath(), "znxrun", "status");
                String output = readTail(logFile, 32 * 1024);
                boolean healthy = exit == 0 && output.contains("ZNXRUN_STATUS status=healthy");
                appendLog(logFile, "status_check_exit=" + exit + " healthy=" + healthy);
                notifyFinished(callback,
                        healthy ? PackageInstaller.STATUS_SUCCESS : STATUS_REPAIR_PENDING,
                        healthy ? context.getString(R.string.installer_identity_ready)
                                : context.getString(R.string.installer_repair_pending), "");
            } catch (Throwable error) {
                Log.e(TAG, "checkInstallerStatus", error);
                notifyFinished(callback, PackageInstaller.STATUS_FAILURE,
                        error.getClass().getSimpleName() + ": " + safeMessage(error), "");
            } finally {
                if (cli != null && cli.exists()) cli.delete();
                busy.set(false);
            }
        }, "BoomInstallerStatusWorker").start();
    }

    private void installInternal(ParcelFileDescriptor apk, long size, String displayName,
            IInstallCallback callback) throws Exception {
        int uid = Process.myUid();
        if (!InstallerIdentity.isInstallerServiceUid(uid)) {
            throw new SecurityException("installer service requires adb-shell identity, current uid="
                    + uid);
        }

        installWithManagedXpadInstaller(apk, size, displayName, callback);
    }

    private void installWithManagedXpadInstaller(ParcelFileDescriptor apk, long size,
            String displayName, IInstallCallback callback) throws Exception {
        notifyStatus(callback, context.getString(R.string.installer_checking_cli));
        ensureDirectory(WORK_ROOT, 0700);
        ensureDirectory(LOG_ROOT, 0700);
        cleanOldWorkFiles();

        long operationId = System.currentTimeMillis();
        File logFile = new File(LOG_ROOT,
                "install-" + operationId + "-" + Process.myPid() + ".log");
        writeLogHeader(logFile, displayName, size);

        File cli = null;
        File staged = new File(WORK_ROOT,
                "stage-" + operationId + "-" + Process.myPid() + ".apk");
        String packageName = "";
        long expectedVersion = -1;
        try {
            cli = stageEmbeddedXpadInstaller(operationId, logFile);
            int selfTest = runLogged(logFile, CLI_TIMEOUT_SECONDS,
                    cli.getAbsolutePath(), "self-test");
            appendLog(logFile, "self_test_exit=" + selfTest);
            String selfTestOutput = readTail(logFile, 32 * 1024);
            if (selfTest != 0 || !selfTestOutput.contains("XPAD_INSTALL_SELF_TEST status=ok")
                    || !versionAtLeast(extractVersion(selfTestOutput),
                            REQUIRED_XPAD_INSTALL_VERSION)) {
                appendLog(logFile, "result=embedded-cli-unavailable");
                notifyFinished(callback, PackageInstaller.STATUS_FAILURE,
                        context.getString(R.string.installer_cli_missing), "");
                return;
            }

            try (InputStream input = new BufferedInputStream(
                        new ParcelFileDescriptor.AutoCloseInputStream(apk), BUFFER_SIZE);
                 FileOutputStream fileOutput = new FileOutputStream(staged);
                 BufferedOutputStream output = new BufferedOutputStream(fileOutput, BUFFER_SIZE)) {
                copy(input, output, size, callback);
                output.flush();
                fileOutput.getFD().sync();
            }
            Os.chmod(staged.getAbsolutePath(), 0600);

            PackageInfo archive = context.getPackageManager()
                    .getPackageArchiveInfo(staged.getAbsolutePath(), 0);
            if (archive == null || archive.packageName == null) {
                throw new IllegalArgumentException("Selected file is not a valid APK");
            }
            packageName = archive.packageName;
            expectedVersion = archive.getLongVersionCode();
            appendLog(logFile, "target_package=" + safeLogValue(packageName)
                    + " target_version_code=" + expectedVersion);

            notifyStatus(callback, context.getString(R.string.installer_installing_managed));
            appendLog(logFile, "phase=managed-0044-install");
            int exitCode = runLogged(logFile, CLI_TIMEOUT_SECONDS,
                    cli.getAbsolutePath(), "install", "--backend", "auto",
                    staged.getAbsolutePath());
            appendLog(logFile, "xpad_install_exit=" + exitCode);
            String tail = compactDiagnosticTail(readTail(logFile, 8 * 1024));
            if (exitCode == 75) {
                appendLog(logFile, "result=reboot-required");
                notifyFinished(callback, PackageInstaller.STATUS_FAILURE,
                        context.getString(R.string.installer_reboot_required,
                                logFile.getAbsolutePath()), packageName);
                return;
            }
            if (exitCode == 76) {
                appendLog(logFile, "result=repair-pending");
                notifyFinished(callback, STATUS_REPAIR_PENDING,
                        context.getString(R.string.installer_repair_pending), packageName);
                return;
            }
            if (exitCode != 0) {
                appendLog(logFile, "result=install-failed");
                notifyFinished(callback, PackageInstaller.STATUS_FAILURE,
                        context.getString(R.string.installer_cli_failed, exitCode,
                                logFile.getAbsolutePath(), tail), packageName);
                return;
            }
            if (!verifyInstalled(packageName, expectedVersion)) {
                appendLog(logFile, "verification=failed result=verification-failed");
                notifyFinished(callback, PackageInstaller.STATUS_FAILURE,
                        context.getString(R.string.installer_verification_failed,
                                logFile.getAbsolutePath()), packageName);
                return;
            }
            appendLog(logFile, "verification=passed result=success");
            notifyFinished(callback, PackageInstaller.STATUS_SUCCESS, "", packageName);
        } catch (Throwable error) {
            appendLog(logFile, "result=exception exception="
                    + safeLogValue(error.getClass().getSimpleName() + ": "
                    + safeMessage(error)));
            throw error;
        } finally {
            boolean removed = !staged.exists() || staged.delete();
            appendLog(logFile, "staging_removed=" + removed);
            if (!removed) {
                Log.w(TAG, "cannot remove staging file " + staged);
            }
            boolean cliRemoved = cli == null || !cli.exists() || cli.delete();
            appendLog(logFile, "embedded_cli_removed=" + cliRemoved);
            if (!cliRemoved) {
                Log.w(TAG, "cannot remove embedded installer engine " + cli);
            }
        }
    }

    private File stageEmbeddedXpadInstaller(long operationId, File logFile) throws Exception {
        File cli = new File(WORK_ROOT,
                "xpad-install-" + operationId + "-" + Process.myPid());
        File partial = new File(cli.getAbsolutePath() + ".partial");
        if (partial.exists() && !partial.delete()) {
            throw new IllegalStateException("Cannot clear embedded installer partial file");
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long written = 0;
        boolean activated = false;
        try {
            try (InputStream input = new BufferedInputStream(
                        context.getAssets().open(EMBEDDED_XPAD_INSTALL_ASSET), BUFFER_SIZE);
                 FileOutputStream fileOutput = new FileOutputStream(partial);
                 BufferedOutputStream output = new BufferedOutputStream(fileOutput, BUFFER_SIZE)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                for (;;) {
                    int count = input.read(buffer);
                    if (count < 0) break;
                    if (count == 0) continue;
                    written += count;
                    if (written > EMBEDDED_XPAD_INSTALL_SIZE) {
                        throw new SecurityException("Embedded installer engine is oversized");
                    }
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
                output.flush();
                fileOutput.getFD().sync();
            }

            String actualSha256 = toHex(digest.digest());
            if (written != EMBEDDED_XPAD_INSTALL_SIZE
                    || !EMBEDDED_XPAD_INSTALL_SHA256.equals(actualSha256)) {
                throw new SecurityException("Embedded installer engine identity mismatch");
            }
            Os.chmod(partial.getAbsolutePath(), 0700);
            if (cli.exists() && !cli.delete()) {
                throw new IllegalStateException("Cannot replace embedded installer engine");
            }
            if (!partial.renameTo(cli)) {
                throw new IllegalStateException("Cannot activate embedded installer engine");
            }
            Os.chmod(cli.getAbsolutePath(), 0700);
            appendLog(logFile, "installer_engine=embedded-xpad-install version="
                    + REQUIRED_XPAD_INSTALL_VERSION + " sha256=" + actualSha256);
            activated = true;
            return cli;
        } finally {
            if (partial.exists() && !partial.delete()) {
                Log.w(TAG, "cannot remove embedded installer partial " + partial);
            }
            if (!activated && cli.exists() && !cli.delete()) {
                Log.w(TAG, "cannot remove failed embedded installer engine " + cli);
            }
        }
    }

    private static String toHex(byte[] value) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] output = new char[value.length * 2];
        for (int index = 0; index < value.length; index++) {
            int item = value[index] & 0xff;
            output[index * 2] = alphabet[item >>> 4];
            output[index * 2 + 1] = alphabet[item & 0x0f];
        }
        return new String(output);
    }

    private boolean verifyInstalled(String packageName, long expectedVersion)
            throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                PackageInfo installed = context.getPackageManager().getPackageInfo(packageName, 0);
                if (installed.getLongVersionCode() == expectedVersion) return true;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static void ensureDirectory(File directory, int mode) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create " + directory);
        }
        Os.chmod(directory.getAbsolutePath(), mode);
    }

    private static void cleanOldWorkFiles() {
        File[] staging = WORK_ROOT.listFiles(file -> file.isFile() && (
                file.getName().startsWith("stage-") && file.getName().endsWith(".apk")
                || file.getName().startsWith("xpad-install-")));
        if (staging != null) {
            for (File file : staging) file.delete();
        }
        File[] logs = LOG_ROOT.listFiles(file -> file.isFile()
                && file.getName().startsWith("install-") && file.getName().endsWith(".log"));
        if (logs == null || logs.length < MAX_LOG_FILES) return;
        Arrays.sort(logs, Comparator.comparingLong(File::lastModified).reversed());
        for (int i = MAX_LOG_FILES - 1; i < logs.length; i++) logs[i].delete();
    }

    private static void writeLogHeader(File logFile, String displayName, long size)
            throws Exception {
        String safeName = safeLogValue(displayName == null ? "unknown" : displayName);
        try (FileOutputStream output = new FileOutputStream(logFile)) {
            String header = "BoomInstaller managed install\n"
                    + "uid=" + Process.myUid() + " pid=" + Process.myPid() + "\n"
                    + "started_ms=" + System.currentTimeMillis() + "\n"
                    + "display_name=" + safeName + " size=" + size + "\n";
            output.write(header.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        Os.chmod(logFile.getAbsolutePath(), 0644);
    }

    private static void appendLog(File logFile, String line) {
        try (FileOutputStream output = new FileOutputStream(logFile, true)) {
            output.write((safeLogValue(line) + "\n").getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (Throwable error) {
            Log.w(TAG, "cannot append operation log", error);
        }
    }

    private static String safeLogValue(String value) {
        return value.replace('\n', '_').replace('\r', '_');
    }

    private static int runLogged(File logFile, long timeoutSeconds, String... command)
            throws Exception {
        java.lang.Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .start();
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            return 124;
        }
        return process.exitValue();
    }

    private static String readTail(File file, int maximumBytes) throws Exception {
        long length = file.length();
        int count = (int) Math.min(length, maximumBytes);
        byte[] data = new byte[count];
        try (FileInputStream input = new FileInputStream(file)) {
            long skip = length - count;
            while (skip > 0) {
                long skipped = input.skip(skip);
                if (skipped <= 0) break;
                skip -= skipped;
            }
            int offset = 0;
            while (offset < count) {
                int read = input.read(data, offset, count - offset);
                if (read < 0) break;
                offset += read;
            }
            return new String(data, 0, offset, StandardCharsets.UTF_8);
        }
    }

    private static String extractVersion(String output) {
        String marker = "version=";
        int start = output.lastIndexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = start;
        while (end < output.length()) {
            char value = output.charAt(end);
            if ((value < '0' || value > '9') && value != '.') break;
            end++;
        }
        return output.substring(start, end);
    }

    private static boolean versionAtLeast(String actual, String required) {
        String[] left = actual.split("\\.");
        String[] right = required.split("\\.");
        for (int index = 0; index < Math.max(left.length, right.length); index++) {
            int a = index < left.length ? parseVersionPart(left[index]) : 0;
            int b = index < right.length ? parseVersionPart(right[index]) : 0;
            if (a != b) return a > b;
        }
        return !actual.isEmpty();
    }

    private static int parseVersionPart(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String compactDiagnosticTail(String output) {
        String compact = output.trim();
        if (compact.length() > 1200) compact = compact.substring(compact.length() - 1200);
        return compact.isEmpty() ? "" : "\n" + compact;
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

    private static void notifyStatus(IInstallCallback callback, String message) {
        try {
            callback.onStatus(message);
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
