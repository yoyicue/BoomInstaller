# BoomInstaller

BoomInstaller is a device-specific Shizuku fork for XPad. Its Android package is
`com.yoyicue.boominstaller`.

Canonical project identity:

- source repository: `https://github.com/yoyicue/BoomInstaller`;
- Android product and APK: **BoomInstaller**;
- companion device CLI: [`xpad-installer`](https://github.com/yoyicue/xpad-installer),
  installed as `/data/local/tmp/xpad-install`;
- integrated offline installer: [`xpad2-cli`](https://github.com/yoyicue/xpad2-cli).

The former private repository name `xpad2_installer` is retired. It referred to
this Android app and must not be confused with the separate `xpad-installer` CLI.

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

On later ordinary boots, `BootCompleteReceiver` first restores the last working
mode. If that mode is root but KernelSU is not ready yet, it automatically falls
back to the paired local wireless-debugging path: it discovers the device's
random TLS port with mDNS, authenticates with the paired key, and executes the
installed `libshizuku.so`. No computer, exploit, copied DEX, or
`/data/local/tmp` file is used by that boot path. Pair again only after
uninstalling BoomInstaller, clearing its app data, revoking the paired device, or
manually disabling the required wireless-debugging settings.

To provision persistence without restarting the service in the current boot:

```shell
adb shell /data/local/tmp/xpad-install autostart enable
```

## APK installer

When the home page reports that BoomInstaller is running as `root` or `system`,
open **Install APK**, select an APK with Android's document picker, and tap
**Install**. The Manager passes the selected file descriptor to a private UID
1000 installer broker. In root mode, only this broker is launched under UID
1000; the main BoomInstaller service remains root. The broker streams the APK
directly into a PackageInstaller session, attributes the session to the
firmware-approved `com.tal.pad.znxxservice` installer, and sends progress and the
final result back to the activity. It does not copy the APK to a temporary path.

## Build

Requirements: JDK 21, Android SDK/Build Tools 35, and NDK 27.2.12479018.

```shell
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :manager:assembleDebug
```

The APK is written under `manager/build/outputs/apk/debug/`.

### Release signing

Build the minimized release content, then sign it with the dedicated BOOM
production certificate from the protected signing backup:

```shell
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :manager:assembleRelease
export BOOM_RELEASE_SIGNING_BACKUP=/path/to/protected/signing-backup
tools/sign_release.sh
```

The signing script validates the complete backup checksum manifest, pins the
recovery-key fingerprint and production certificate, decrypts the PKCS12
password only in process memory, and requires APK Signature Schemes v2 and v3.
The final artifact is written to `out/apk/*-production.apk`; the intermediate
APK under `manager/build/outputs/apk/release/` is not a production artifact.
Override the default backup path with `BOOM_RELEASE_SIGNING_BACKUP` when needed.

Production and debug certificates are intentionally different. Android cannot
install a production-signed build over a previously installed debug-signed
build without an explicit signing-key migration or removing the debug package.

## Upstream and license

BoomInstaller is based on [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku)
commit `b844bc491f1790c72328e1a8e5b2349f8978f0ea` and its pinned Shizuku-API
submodule. Source code remains under the Apache License 2.0; see [LICENSE](LICENSE)
and [NOTICE.md](NOTICE.md).
