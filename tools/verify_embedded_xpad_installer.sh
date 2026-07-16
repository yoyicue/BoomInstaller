#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
LOCK="$ROOT/third_party/xpad-installer.lock"
APK=${1:?usage: verify_embedded_xpad_installer.sh APK}

die() {
  printf 'BOOM_EMBEDDED_XPAD_VERIFY_ERROR reason=%s\n' "$1" >&2
  exit 1
}

property() {
  awk -F= -v key="$1" '$1 == key {sub(/^[^=]*=/, ""); print; exit}' "$LOCK"
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

[[ -f "$APK" ]] || die apk-missing
command -v unzip >/dev/null 2>&1 || die unzip-missing
unzip -tq "$APK" >/dev/null || die apk-zip-invalid
entries=$(unzip -Z1 "$APK")
grep -Fxq assets/xpad-install <<<"$entries" || die asset-missing
grep -Fxq assets/licenses/xpad-installer-GPL-3.0.txt <<<"$entries" || \
  die license-missing

TMP=$(mktemp -d "${TMPDIR:-/tmp}/boom-xpad-verify.XXXXXX")
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
unzip -p "$APK" assets/xpad-install > "$TMP/xpad-install"
unzip -p "$APK" assets/licenses/xpad-installer-GPL-3.0.txt > "$TMP/LICENSE"

ASSET_SIZE=$(property asset_size)
ASSET_SHA=$(property asset_sha256)
LICENSE_SIZE=$(property license_size)
LICENSE_SHA=$(property license_sha256)
[[ $(wc -c < "$TMP/xpad-install" | tr -d ' ') == "$ASSET_SIZE" ]] || die asset-size
[[ $(sha256_file "$TMP/xpad-install") == "$ASSET_SHA" ]] || die asset-sha256
[[ $(wc -c < "$TMP/LICENSE" | tr -d ' ') == "$LICENSE_SIZE" ]] || die license-size
[[ $(sha256_file "$TMP/LICENSE") == "$LICENSE_SHA" ]] || die license-sha256
if command -v file >/dev/null 2>&1; then
  file "$TMP/xpad-install" | grep -Eq 'ELF 64-bit.*ARM aarch64' || die asset-not-arm64-elf
fi

printf 'BOOM_EMBEDDED_XPAD_VERIFY_OK version=%s sha256=%s apk=%s\n' \
  "$(property version)" "$ASSET_SHA" "$APK"
