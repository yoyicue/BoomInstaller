package moe.shizuku.starter;

import android.app.ActivityThread;
import android.content.Context;
import android.content.ContextHidden;
import android.ddm.DdmHandleAppName;
import android.os.Build;
import android.os.IBinder;
import android.os.UserHandle;
import android.os.UserHandleHidden;
import android.util.Log;
import android.util.Pair;

import java.lang.reflect.Constructor;

import dev.rikka.tools.refine.Refine;

/**
 * Loads a UserService without creating the package's Application object.
 *
 * <p>Some XPad builds crash in {@code LoadedApk.makeApplication()} when it is called from a
 * standalone app_process. The package Context already owns the correct code ClassLoader and is
 * sufficient for BoomInstaller's service, so this is a safe fallback for that platform bug.</p>
 */
final class UserServiceFallback {

    private static final String TAG = "BoomInstallerServiceStarter";

    static Pair<IBinder, String> create(String[] args) {
        String name = null;
        String token = null;
        String packageName = null;
        String className = null;
        int uid = -1;

        for (String arg : args) {
            if (arg.startsWith("--debug-name=")) {
                name = arg.substring(13);
            } else if (arg.startsWith("--token=")) {
                token = arg.substring(8);
            } else if (arg.startsWith("--package=")) {
                packageName = arg.substring(10);
            } else if (arg.startsWith("--class=")) {
                className = arg.substring(8);
            } else if (arg.startsWith("--uid=")) {
                uid = Integer.parseInt(arg.substring(6));
            }
        }

        if (packageName == null || className == null || uid < 0) return null;

        int userId = uid / 100000;
        try {
            ActivityThread activityThread = ActivityThread.currentActivityThread();
            if (activityThread == null) activityThread = ActivityThread.systemMain();
            Context systemContext = activityThread.getSystemContext();

            DdmHandleAppName.setAppName(
                    name != null ? name : packageName + ":user_service", userId);

            //noinspection InstantiationOfUtilityClass
            UserHandle userHandle = Refine.unsafeCast(
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                            ? UserHandleHidden.of(userId)
                            : new UserHandleHidden(userId));
            Context packageContext = Refine.<ContextHidden>unsafeCast(systemContext)
                    .createPackageContextAsUser(packageName,
                            Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY,
                            userHandle);

            Class<?> serviceClass = packageContext.getClassLoader().loadClass(className);
            Constructor<?> contextConstructor = null;
            try {
                contextConstructor = serviceClass.getConstructor(Context.class);
            } catch (NoSuchMethodException | SecurityException ignored) {
            }

            IBinder service = contextConstructor != null
                    ? (IBinder) contextConstructor.newInstance(packageContext)
                    : (IBinder) serviceClass.newInstance();
            Log.i(TAG, "started service with package Context fallback");
            return new Pair<>(service, token);
        } catch (Throwable error) {
            Log.e(TAG, "package Context fallback failed", error);
            return null;
        }
    }

    private UserServiceFallback() {
    }
}
