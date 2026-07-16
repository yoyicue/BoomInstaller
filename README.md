# BoomInstaller (魔改Shizuku支持学而思)

BoomInstaller is a device-specific Shizuku fork for XPad. Its Android package is
`com.yoyicue.boominstaller`.

Canonical project identity:

- source repository: `https://github.com/yoyicue/BoomInstaller`;
- Android product and APK: **BoomInstaller**;
- embedded installation engine: [`xpad-installer`](https://github.com/yoyicue/xpad-installer)
  v0.2.5, hash-locked inside the APK and extracted only for one broker operation;
- integrated offline installer: [`xpad2-cli`](https://github.com/yoyicue/xpad2-cli).

The former private repository name `xpad2_installer` is retired. It referred to
this Android app and must not be confused with the separate `xpad-installer` CLI.

The project deliberately keeps the upstream Shizuku server, AIDL, API, and user
service model. Service activation and APK installation are separate planes:

```text
control plane: root or paired local ADB shell -> libshizuku.so -> ShizukuService
install plane: BoomInstaller shell broker -> embedded xpad-install -> 0044 first
                                                             `-> guarded 31317 repair if absent
```

The BoomInstaller product surface is intentionally small: application identity,
launcher/about UI, the XPad activation endpoints, and the APK installer entry.
Service status, application authorization, help, rish, Sui, Binder descriptors,
transaction IDs, Java packages, AIDL, and internal protocol keys retain their
Shizuku identity. Android Manifest permission ownership is deliberately scoped
to `com.yoyicue.boominstaller.permission.*`: permission and permission-group
names are process-global PackageManager resources, not private Java class names.
This lets BoomInstaller coexist with the official `moe.shizuku.privileged.api`
package instead of trying to redeclare permissions owned by it. When both are
installed, the official manager remains responsible for ordinary third-party
Shizuku clients; BoomInstaller independently serves its XPad installer surface.

The authorization file records which manager package last wrote it. When an
older or differently managed file is first opened, existing client grants are
discarded and applications must request access again. This prevents a newly
enabled root Shizuku service from silently inheriting another manager's grants.

The Manager executes `libshizuku.so` directly from its installed native library
directory, matching Shizuku's native starter model. The starter preserves the
identity that launched it: UID 0 from root or UID 2000 from local ADB. The
Shizuku control-plane source contains no installer exploit and never turns an
0044 or UID 1000 identity into a persistent Shizuku runtime.

For standalone APK installation, the BoomInstaller APK carries the exact
`xpad-install` v0.2.5 ELF recorded in `third_party/xpad-installer.lock`. The
UID-2000 broker verifies the embedded SHA-256, extracts it into its mode-0700
private work directory, executes one operation, and removes it in `finally`.
The engine derives the OEM installer UID per device, uses 0044 for every target
APK, and permits bounded 31317 only to repair a missing or broken 0044 identity.
No external `/data/local/tmp/xpad-install` or xpad2 installation is required.

## One-time activation

Install and open the APK, then start its Shizuku service using either standard
in-app method:

- on an already rooted device, tap the Root start entry;
- otherwise, use the Wireless debugging entry to pair and start through local
  ADB shell.

BoomInstaller stores the standard in-app `shizuku` pairing identity in
device-protected storage. Its boot job reuses that identity. The underlying
private-key storage is unchanged from r12, so upgrades retain existing pairing.
This does not require xpad2 or an external CLI.

For a current-boot desktop ADB start, the standard native starter can also be
invoked directly:

```shell
adb shell '
APK=$(pm path com.yoyicue.boominstaller)
APK=${APK#package:}
STARTER=${APK%/base.apk}/lib/arm64/libshizuku.so
"$STARTER" --apk="$APK"
'
```

The direct ADB command starts the current boot as UID 2000; use the in-app
wireless pairing flow once if ordinary-boot local ADB recovery is required.
Activation never enters an installer exploit path.

On later ordinary boots, `BootCompleteReceiver` schedules a network-constrained
job instead of running inside the short boot-broadcast window. The job waits up
to 20 seconds for a configured root service, then (when needed) spends up to 60
seconds discovering the random TLS port, authenticating with the paired key, and
starting the installed `libshizuku.so` as ADB shell. It accepts success only after
the Binder arrives with UID 0 or 2000. No computer, installer exploit, copied DEX,
or external `/data/local/tmp` file is used by that boot path. Pair again only after
uninstalling BoomInstaller, clearing its app data, revoking the paired device, or
manually disabling the required wireless-debugging settings.

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
broker, which extracts and verifies its embedded `xpad-install` v0.2.5, copies
the APK to a mode-0600 staging file, and invokes `install --backend auto`. That command repairs
and re-verifies managed 0044 when needed, then installs every target APK only
through 0044. Its guarded 31317 transaction is used solely to repair a missing or
broken 0044 identity before target installation starts. Version 0.2.5 also waits
for the repaired alias to become fully healthy before deciding whether the repair
succeeded, avoiding a false failure while `packages.list` is still converging.
The staging APK and extracted engine are removed in `finally`; the latest 12 bounded operation logs are
kept under `/data/local/tmp/.boominstaller/logs` for `xpad2log` diagnostics.

The UI tells users to expect 10–30 seconds normally and up to about 60 seconds
when repair is required. Exit 75 is reported as “ordinary reboot required” rather
than encouraging another attempt in an unsafe boot.

## Build

Requirements: JDK 21, Android SDK/Build Tools 35, NDK 27.2.12479018, `curl`,
and `unzip`.

```shell
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :manager:assembleDebug
```

The APK is written under `manager/build/outputs/apk/debug/`.
The build first resolves the public xpad-installer v0.2.5 Release, verifies its
locked size/SHA-256 and embeds both the ARM64 ELF and GPLv3 license. Set
`BOOM_XPAD_INSTALLER=/path/to/xpad-install` for an offline build; the supplied
file must match the lock exactly. `tools/verify_apk_permission_boundary.sh`
checks the merged APK Manifest, rejects every official Shizuku permission
declaration, and separately confirms that upstream Shizuku application and
Binder action identities remain present.

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
submodule. BoomInstaller source remains under the Apache License 2.0; see
[LICENSE](LICENSE) and [NOTICE.md](NOTICE.md). The separately executed embedded
xpad-installer v0.2.5 is GPL-3.0-only; its full license is included in the APK
and its corresponding source is the exact public tag linked above.
