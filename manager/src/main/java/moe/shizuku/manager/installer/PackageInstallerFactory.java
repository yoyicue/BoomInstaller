package moe.shizuku.manager.installer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.IPackageInstaller;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.ServiceManager;

import java.lang.reflect.InvocationTargetException;

final class PackageInstallerFactory {

    private static final String OEM_INSTALLER_PACKAGE = "com.tal.pad.znxxservice";

    static PackageInstaller create(Context context) throws Exception {
        IPackageManager packageManager = IPackageManager.Stub.asInterface(
                ServiceManager.getService("package"));
        if (packageManager == null) {
            throw new IllegalStateException("package service is unavailable");
        }
        IPackageInstaller installer = packageManager.getPackageInstaller();
        if (installer == null) {
            throw new IllegalStateException("package installer is unavailable");
        }

        String attributionTag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? context.getAttributionTag() : null;
        return create(context, installer, OEM_INSTALLER_PACKAGE, attributionTag, 0);
    }

    private static PackageInstaller create(Context context, IPackageInstaller installer,
            String installerPackageName, String installerAttributionTag, int userId)
            throws NoSuchMethodException,
            InvocationTargetException, InstantiationException, IllegalAccessException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return PackageInstaller.class
                    .getConstructor(IPackageInstaller.class, String.class, String.class, int.class)
                    .newInstance(installer, installerPackageName, installerAttributionTag, userId);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PackageInstaller.class
                    .getConstructor(IPackageInstaller.class, String.class, int.class)
                    .newInstance(installer, installerPackageName, userId);
        } else {
            return PackageInstaller.class
                    .getConstructor(Context.class, android.content.pm.PackageManager.class,
                            IPackageInstaller.class, String.class, int.class)
                    .newInstance(context, context.getPackageManager(), installer,
                            installerPackageName, userId);
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    static void addInstallFlags(PackageInstaller.SessionParams params, int flags)
            throws NoSuchFieldException, IllegalAccessException {
        var field = PackageInstaller.SessionParams.class.getDeclaredField("installFlags");
        field.setAccessible(true);
        field.setInt(params, field.getInt(params) | flags);
    }

    private PackageInstallerFactory() {
    }
}
