package moe.shizuku.manager.installer;

/** Identities that can host BoomInstaller's managed xpad-install broker. */
public final class InstallerIdentity {

    private static final int ROOT_UID = 0;
    private static final int SHELL_UID = 2000;

    /** Whether this BoomInstaller server can provide the APK installer feature. */
    public static boolean canHostInstaller(int uid) {
        return uid == ROOT_UID || uid == SHELL_UID;
    }

    /** Root uses the OEM Provider path; ADB shell uses the managed 0044 path. */
    public static boolean isInstallerServiceUid(int uid) {
        return canHostInstaller(uid);
    }

    private InstallerIdentity() {
    }
}
