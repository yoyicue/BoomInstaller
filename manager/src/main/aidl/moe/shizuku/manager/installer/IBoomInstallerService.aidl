package moe.shizuku.manager.installer;

import android.os.ParcelFileDescriptor;
import moe.shizuku.manager.installer.IInstallCallback;

interface IBoomInstallerService {
    void destroy() = 16777114;
    int getUid() = 1;
    boolean isBusy() = 2;
    void install(in ParcelFileDescriptor apk, long size, String displayName,
            IInstallCallback callback) = 3;
    void checkInstallerStatus(IInstallCallback callback) = 4;
}
