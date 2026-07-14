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
- XPad-specific UID 10072, UID 0, and UID 1000 activation paths;
- self-contained native activation and ordinary-boot persistence;
- an XPad system-identity APK installation service;
- protected production signing and release verification tooling.

The canonical source repository is
<https://github.com/yoyicue/BoomInstaller>. `xpad-installer` is a separate
device CLI and is not another name for this Android application.
