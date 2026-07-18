# BoomInstaller attribution and modifications

BoomInstaller is derived from
[RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) commit
`b844bc491f1790c72328e1a8e5b2349f8978f0ea` and uses the pinned
[Shizuku-API](https://github.com/RikkaApps/Shizuku-API) submodule recorded by
this repository.

The upstream project and this fork are licensed under the Apache License 2.0.
The complete license text is in [LICENSE](LICENSE). Upstream copyright notices
in source files are retained.

Material modifications in this fork include:

- the BoomInstaller application identity and branding;
- root/local-ADB Shizuku activation with verified ordinary-boot persistence;
- separation of Shizuku control-plane identity from the XPad installer identities;
- a standalone XPad 0044-first APK installation front end that verifies,
  temporarily extracts, executes, and removes a locked `xpad-install` engine;
- protected production signing and release verification tooling.

The canonical source repository is
<https://github.com/yoyicue/BoomInstaller>. `xpad-installer` is a separate
program and is not another name for this Android application.

The distributed APK contains `xpad-installer` v0.2.9 as a separately executed
ARM64 program. Its exact source is tag `v0.2.9`, commit
`c42acd7491d9f6e4dcd720963d3c066631c67706`, at
<https://github.com/yoyicue/xpad-installer>. That component is licensed
GPL-3.0-only; the complete license is packaged as
`assets/licenses/xpad-installer-GPL-3.0.txt`. The pinned artifact identity and
source location are recorded in `third_party/xpad-installer.lock`.
