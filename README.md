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
