# BoomInstaller (魔改Shizuku支持学而思)

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
service model. Service activation and APK installation are separate planes:

```text
control plane: root or paired local ADB shell -> libshizuku.so -> ShizukuService
install plane: BoomInstaller shell broker -> xpad-install -> 0044 first
                                                    `-> guarded 31317 repair if absent
```

The BoomInstaller product surface is intentionally small: application identity,
launcher/about UI, the XPad activation endpoints, and the APK installer entry.
Service status, permissions, application authorization, help, rish, Sui, Binder
descriptor, transaction IDs, and the public
`moe.shizuku.manager.permission.API_V23` contract retain their Shizuku identity.
Standard applications built with Shizuku-API can therefore use the embedded
service without recompilation. BoomInstaller is a replacement Shizuku manager
for this device and must not be installed alongside another manager that owns
the same standard dangerous permission.

The authorization file records which manager package last wrote it. When an
older or differently managed file is first opened, existing client grants are
discarded and applications must request access again. This prevents a newly
enabled root Shizuku service from silently inheriting another manager's grants.

The Manager executes `libshizuku.so` directly from its installed native library
directory, matching Shizuku's native starter model. The starter preserves the
identity that launched it: UID 0 from root or UID 2000 from local ADB. BoomInstaller
contains no CVE-2024-31317 payload and never turns UID 10072/0044 or UID 1000 into
a persistent Shizuku runtime. On TALIH_PD2 firmware, UID 10072 remains valuable as
the preferred OEM installer identity even though it cannot deliver the Shizuku
Binder. The companion `xpad-install` CLI owns that installer identity and the
hardened, bounded 31317 repair transaction. Target APK bytes are never submitted
through 31317.

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
Wi-Fi ADB transport, and starts the service as the current root or ADB-shell
identity. Activation never enters an installer exploit path.

On later ordinary boots, `BootCompleteReceiver` schedules a network-constrained
job instead of running inside the short boot-broadcast window. The job waits up
to 20 seconds for a configured root service, then (when needed) spends up to 60
seconds discovering the random TLS port, authenticating with the paired key, and
starting the installed `libshizuku.so` as ADB shell. It accepts success only after
the Binder arrives with UID 0 or 2000. No computer, installer exploit, copied DEX,
or `/data/local/tmp` file is used by that boot path. Pair again only after
uninstalling BoomInstaller, clearing its app data, revoking the paired device, or
manually disabling the required wireless-debugging settings.

To provision persistence without restarting the service in the current boot:

```shell
adb shell /data/local/tmp/xpad-install autostart enable
```

The last boot-start result is persisted in device-protected storage and exposed
read-only to root/ADB shell for remote diagnosis:

```shell
adb shell content call \
  --uri content://com.yoyicue.boominstaller.shizuku \
  --method getAutoStartStatus
```

## APK installer

When the home page reports that Shizuku is running as `root` or `adb`,
open **Install APK**, select an APK with Android's document picker, and tap
**Install**. The Manager passes the selected file descriptor to a private shell
broker, which checks for `xpad-install` v0.2.3 or newer, copies the APK to a
mode-0600 staging file, and invokes `install --backend auto`. That command repairs
and re-verifies managed 0044 when needed, then installs every target APK only
through 0044. Its guarded 31317 transaction is used solely to repair a missing or
broken 0044 identity before target installation starts.
The staging file is removed in `finally`; the latest 12 bounded operation logs are
kept under `/data/local/tmp/.boominstaller/logs` for `xpad2log` diagnostics.

The UI tells users to expect 10–30 seconds normally and up to about 60 seconds
when repair is required. Exit 75 is reported as “ordinary reboot required” rather
than encouraging another attempt in an unsafe boot.

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
