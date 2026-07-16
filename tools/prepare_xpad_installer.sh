#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
LOCK="$ROOT/third_party/xpad-installer.lock"
OUTPUT_DIR=${1:?usage: prepare_xpad_installer.sh OUTPUT_ASSETS_DIRECTORY}

die() {
  printf 'BOOM_EMBEDDED_XPAD_PREPARE_ERROR reason=%s\n' "$1" >&2
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

valid_file() {
  local path=$1 expected_size=$2 expected_sha=$3 actual_size actual_sha
  [[ -f "$path" ]] || return 1
  actual_size=$(wc -c < "$path" | tr -d ' ')
  [[ "$actual_size" == "$expected_size" ]] || return 1
  actual_sha=$(sha256_file "$path")
  [[ "$actual_sha" == "$expected_sha" ]]
}

download_locked() {
  local url=$1 destination=$2 expected_size=$3 expected_sha=$4 partial
  command -v curl >/dev/null 2>&1 || die curl-missing
  mkdir -p "$(dirname "$destination")"
  partial="$destination.partial.$$"
  rm -f "$partial"
  curl --fail --location --silent --show-error --retry 3 --retry-all-errors \
    --connect-timeout 20 --max-time 240 --proto '=https' --tlsv1.2 \
    --output "$partial" "$url" || {
      rm -f "$partial"
      die download-failed
    }
  valid_file "$partial" "$expected_size" "$expected_sha" || {
    rm -f "$partial"
    die downloaded-identity-mismatch
  }
  chmod 0600 "$partial"
  mv -f "$partial" "$destination"
}

[[ -f "$LOCK" ]] || die lock-missing
VERSION=$(property version)
ASSET_SIZE=$(property asset_size)
ASSET_SHA=$(property asset_sha256)
ASSET_URL=$(property asset_url)
LICENSE_SIZE=$(property license_size)
LICENSE_SHA=$(property license_sha256)
LICENSE_URL=$(property license_url)
[[ -n "$VERSION" && -n "$ASSET_SIZE" && -n "$ASSET_SHA" && -n "$ASSET_URL" ]] || \
  die lock-invalid

CACHE_DIR="$ROOT/.boom-deps"
ASSET_CACHE="$CACHE_DIR/xpad-install-v$VERSION"
LICENSE_CACHE="$CACHE_DIR/xpad-installer-GPL-3.0.txt"

if [[ -n ${BOOM_XPAD_INSTALLER:-} ]]; then
  valid_file "$BOOM_XPAD_INSTALLER" "$ASSET_SIZE" "$ASSET_SHA" || \
    die explicit-asset-identity-mismatch
  ASSET_SOURCE=$BOOM_XPAD_INSTALLER
elif valid_file "$ROOT/../xpad-installer/dist/xpad-install" "$ASSET_SIZE" "$ASSET_SHA"; then
  ASSET_SOURCE="$ROOT/../xpad-installer/dist/xpad-install"
elif valid_file "$ASSET_CACHE" "$ASSET_SIZE" "$ASSET_SHA"; then
  ASSET_SOURCE=$ASSET_CACHE
else
  download_locked "$ASSET_URL" "$ASSET_CACHE" "$ASSET_SIZE" "$ASSET_SHA"
  ASSET_SOURCE=$ASSET_CACHE
fi

if [[ -n ${BOOM_XPAD_INSTALLER_LICENSE:-} ]]; then
  valid_file "$BOOM_XPAD_INSTALLER_LICENSE" "$LICENSE_SIZE" "$LICENSE_SHA" || \
    die explicit-license-identity-mismatch
  LICENSE_SOURCE=$BOOM_XPAD_INSTALLER_LICENSE
elif valid_file "$ROOT/../xpad-installer/LICENSE" "$LICENSE_SIZE" "$LICENSE_SHA"; then
  LICENSE_SOURCE="$ROOT/../xpad-installer/LICENSE"
elif valid_file "$LICENSE_CACHE" "$LICENSE_SIZE" "$LICENSE_SHA"; then
  LICENSE_SOURCE=$LICENSE_CACHE
else
  download_locked "$LICENSE_URL" "$LICENSE_CACHE" "$LICENSE_SIZE" "$LICENSE_SHA"
  LICENSE_SOURCE=$LICENSE_CACHE
fi

if command -v file >/dev/null 2>&1; then
  file "$ASSET_SOURCE" | grep -Eq 'ELF 64-bit.*ARM aarch64' || die asset-not-arm64-elf
fi

mkdir -p "$OUTPUT_DIR/licenses"
install -m 0644 "$ASSET_SOURCE" "$OUTPUT_DIR/xpad-install"
install -m 0644 "$LICENSE_SOURCE" "$OUTPUT_DIR/licenses/xpad-installer-GPL-3.0.txt"
valid_file "$OUTPUT_DIR/xpad-install" "$ASSET_SIZE" "$ASSET_SHA" || die output-asset-mismatch
valid_file "$OUTPUT_DIR/licenses/xpad-installer-GPL-3.0.txt" \
  "$LICENSE_SIZE" "$LICENSE_SHA" || die output-license-mismatch

printf 'BOOM_EMBEDDED_XPAD_PREPARE_OK version=%s sha256=%s output=%s\n' \
  "$VERSION" "$ASSET_SHA" "$OUTPUT_DIR/xpad-install"
