# BoomInstaller

BoomInstaller is a device-specific Shizuku fork for XPad. Its Android package is
`com.yoyicue.boominstaller`.

The project deliberately keeps the upstream Shizuku server, AIDL, API, and user
service model. The only device-specific boundary is service activation:

```text
BoomInstaller APK
  -> packaged libshizuku.so starter (shell/root)
  -> XPad identity selection inside the same native executable
  -> the same starter again (system/znxrun/root)
  -> rikka.shizuku.server.ShizukuService
```

The Manager executes `libshizuku.so` directly from its installed native library
directory, matching Shizuku's native starter model. The starter selects an
available identity:

- UID 10072 through the 0044 `run-as znxrun` path, only when that UID has the
  framework permission required to deliver Binder;
- UID 0 when the starter is launched from a root shell;
- UID 1000 through the CVE-2024-31317 system runner.

On TALIH_PD2 firmware, UID 10072 can install APKs but cannot call
`getContentProviderExternal`, so BoomInstaller activation falls back to UID 1000.
For the 31317 path, the payload is passed to Android's settings command directly
from memory. It then executes the packaged starter and installed APK in place.
There is no external helper, copied DEX, or temporary-directory dependency in the
BoomInstaller runtime. The hidden setting and short-lived system runner are removed
before activation returns.

## One-time activation

Install the APK through the XPad OEM installer path, then place the current
`xpad-install` ELF on the device. One ADB command both activates the current
boot and provisions later boots:

```shell
adb shell '
APK=$(pm path com.yoyicue.boominstaller)
APK=${APK#package:}
STARTER=${APK%/base.apk}/lib/arm64/libshizuku.so
/data/local/tmp/xpad-install activate --starter="$STARTER" --apk="$APK"
'
```

During this first run, BoomInstaller creates its own ADB key and pairs it with
Android wireless debugging over loopback. The private key never leaves the APK.
The command waits for this firmware's pairing service to settle, refreshes the
Wi-Fi ADB transport, selects the available XPad identity, and starts the service.

On later ordinary boots, `BootCompleteReceiver` enables wireless debugging,
discovers the device's random TLS port with mDNS, authenticates with the paired
key, and executes the installed `libshizuku.so`. No computer, exploit, copied
DEX, or `/data/local/tmp` file is used by that boot path. Pair again only after
uninstalling BoomInstaller, clearing its app data, revoking the paired device, or
manually disabling the required wireless-debugging settings.

To provision persistence without restarting the service in the current boot:

```shell
adb shell /data/local/tmp/xpad-install autostart enable
```

## APK installer

When the home page reports that BoomInstaller is running as `system`, open
**Install APK**, select an APK with Android's document picker, and tap
**Install**. The Manager passes the selected file descriptor to a private UID
1000 user service. That service streams it directly into a PackageInstaller
session, attributes the session to the firmware-approved
`com.tal.pad.znxxservice` installer, and sends progress and the final result back
to the activity. It does not copy the APK to a temporary path.

## Build

Requirements: JDK 21, Android SDK/Build Tools 35, and NDK 27.2.12479018.

```shell
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :manager:assembleDebug
```

The APK is written under `manager/build/outputs/apk/debug/`.

## Upstream and license

BoomInstaller is based on [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku)
and its Shizuku-API submodule. Source code remains under the Apache License 2.0;
see [LICENSE](LICENSE).
