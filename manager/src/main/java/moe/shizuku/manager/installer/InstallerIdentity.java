package moe.shizuku.manager.installer;

import android.os.Process;

/** Identities that can host BoomInstaller's privileged PackageInstaller service. */
public final class InstallerIdentity {

    private static final int ROOT_UID = 0;

    /** Whether this BoomInstaller server can provide the APK installer feature. */
    public static boolean canHostInstaller(int uid) {
        return uid == ROOT_UID || uid == Process.SYSTEM_UID;
    }

    /** The isolated installer broker itself must call PackageInstaller as system. */
    public static boolean isInstallerServiceUid(int uid) {
        return uid == Process.SYSTEM_UID;
    }

    private InstallerIdentity() {
    }
}
