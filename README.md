# BoomInstaller

BoomInstaller is a device-specific Shizuku fork for XPad. Its Android package is
`com.yoyicue.boominstaller`.

The project deliberately keeps the upstream Shizuku server, AIDL, API, and user
service model. The only device-specific boundary is service activation:

```text
BoomInstaller APK
  -> /data/local/tmp/xpad-install activate
  -> packaged libshizuku.so starter
  -> rikka.shizuku.server.ShizukuService
```

The Manager passes its installed APK path and packaged native starter to
`xpad-install`. The native tool selects an available identity:

- UID 10072 through the 0044 `run-as znxrun` path, only when that UID has the
  framework permission required to deliver Binder;
- UID 0 through the existing temporary-root transport;
- UID 1000 through the CVE-2024-31317 system runner.

On TALIH_PD2 firmware, UID 10072 can install APKs but cannot call
`getContentProviderExternal`, so BoomInstaller activation falls back to UID 1000.
For the 31317 path, open BoomInstaller before activation so its provider is already
running. Service activation does not load `/data/local/tmp/installer.dex`; the DEX
embedded in `xpad-install` remains for its APK installation commands.

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
