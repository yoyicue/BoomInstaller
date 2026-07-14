#!/usr/bin/env bash
set -euo pipefail

umask 077

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -z ${JAVA_HOME:-} && \
  -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
BACKUP=${BOOM_RELEASE_SIGNING_BACKUP:-}
KEY_ALIAS=boom-xpad2-release
EXPECTED_PACKAGE=com.yoyicue.boominstaller
EXPECTED_CERT_SHA256=3cb5b69579d23197ced8100818a85a46b821383a504b394a44cfe3e98ade78a2
EXPECTED_RSA_FINGERPRINT=SHA256:cOVa4bIB0vgNbqR5Vi95Q0QFDLY7lJX79sHEHTm1Q2U

die() {
  printf 'BOOM_RELEASE_SIGN_REFUSED reason=%s\n' "$1" >&2
  exit 1
}

[[ -n "$BACKUP" ]] || die signing-backup-not-set
KEYSTORE="$BACKUP/xpad2-boom-release.p12"
SECRET_FILE="$BACKUP/xpad2-boom-release-password.rsa-oaep-sha256"
RECOVERY_KEY="$BACKUP/recovery-rsa/id_rsa"

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

find_build_tool() {
  local name=$1
  local candidate root
  local roots=()

  if command -v "$name" >/dev/null 2>&1; then
    command -v "$name"
    return
  fi

  for root in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" \
    /opt/homebrew/share/android-commandlinetools \
    "$HOME/Library/Android/sdk"; do
    [[ -n "$root" && -d "$root" ]] && roots+=("$root")
  done
  ((${#roots[@]})) || die android-sdk-missing
  candidate=$(find "${roots[@]}" -type f -name "$name" 2>/dev/null |
    sort -V | tail -1)
  [[ -n "$candidate" && -x "$candidate" ]] || die "$name-missing"
  printf '%s\n' "$candidate"
}

if (($# > 0)); then
  INPUT=$1
else
  shopt -s nullglob
  candidates=("$ROOT"/manager/build/outputs/apk/release/*.apk)
  shopt -u nullglob
  ((${#candidates[@]} == 1)) || die release-input-count
  INPUT=${candidates[0]}
fi

if (($# > 1)); then
  OUTPUT=$2
else
  input_name=$(basename "$INPUT")
  OUTPUT="$ROOT/out/apk/${input_name%-release.apk}-production.apk"
fi

[[ -f "$INPUT" ]] || die input-missing
[[ "$INPUT" != "$OUTPUT" ]] || die output-matches-input
[[ -f "$BACKUP/SHA256SUMS" ]] || die backup-manifest-missing
[[ -f "$KEYSTORE" ]] || die keystore-missing
[[ -f "$SECRET_FILE" ]] || die encrypted-secret-missing
[[ -f "$RECOVERY_KEY" ]] || die recovery-key-missing

(
  cd "$BACKUP"
  shasum -a 256 -c SHA256SUMS >/dev/null
) || die backup-checksum

cert_sha=$(openssl x509 -in "$BACKUP/xpad2-boom-release-cert.pem" \
  -outform DER | shasum -a 256 | awk '{print $1}')
[[ "$cert_sha" == "$EXPECTED_CERT_SHA256" ]] ||
  die certificate-backup-mismatch

rsa_fingerprint=$(ssh-keygen -lf "$BACKUP/recovery-rsa/id_rsa.pub" |
  awk '{print $2}')
[[ "$rsa_fingerprint" == "$EXPECTED_RSA_FINGERPRINT" ]] ||
  die recovery-key-fingerprint

APKSIGNER_BIN=$(find_build_tool apksigner)
AAPT2_BIN=$(find_build_tool aapt2)
input_badging=$("$AAPT2_BIN" dump badging "$INPUT")
[[ "$input_badging" == *"package: name='$EXPECTED_PACKAGE'"* ]] ||
  die package-mismatch

tmp_dir=$(mktemp -d /tmp/boominstaller-release-sign.XXXXXX)
trap 'rm -rf "$tmp_dir"; unset BOOM_RELEASE_STORE_PASSWORD \
  BOOM_RELEASE_KEY_PASSWORD' EXIT

cp "$RECOVERY_KEY" "$tmp_dir/recovery-key.pem"
chmod 600 "$tmp_dir/recovery-key.pem"
ssh-keygen -p -m PEM -P '' -N '' \
  -f "$tmp_dir/recovery-key.pem" >/dev/null
BOOM_RELEASE_STORE_PASSWORD=$(openssl pkeyutl -decrypt \
  -inkey "$tmp_dir/recovery-key.pem" \
  -pkeyopt rsa_padding_mode:oaep \
  -pkeyopt rsa_oaep_md:sha256 \
  -in "$SECRET_FILE")
BOOM_RELEASE_KEY_PASSWORD=$BOOM_RELEASE_STORE_PASSWORD
export BOOM_RELEASE_STORE_PASSWORD BOOM_RELEASE_KEY_PASSWORD

mkdir -p "$(dirname "$OUTPUT")"
rm -f "$OUTPUT"
"$APKSIGNER_BIN" sign \
  --ks "$KEYSTORE" \
  --ks-type PKCS12 \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:BOOM_RELEASE_STORE_PASSWORD \
  --key-pass env:BOOM_RELEASE_KEY_PASSWORD \
  --v1-signing-enabled false \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --v4-signing-enabled false \
  --out "$OUTPUT" \
  "$INPUT"

verification=$("$APKSIGNER_BIN" verify --verbose --print-certs "$OUTPUT")
grep -Fq 'Verified using v1 scheme (JAR signing): false' <<<"$verification" ||
  die unexpected-v1-signature
grep -Fq 'Verified using v2 scheme (APK Signature Scheme v2): true' \
  <<<"$verification" || die v2-signature
grep -Fq 'Verified using v3 scheme (APK Signature Scheme v3): true' \
  <<<"$verification" || die v3-signature
grep -Fq 'Number of signers: 1' <<<"$verification" || die signer-count
actual_cert=$(sed -n \
  's/^Signer #1 certificate SHA-256 digest: //p' <<<"$verification")
[[ "$actual_cert" == "$EXPECTED_CERT_SHA256" ]] || die certificate-mismatch

output_badging=$("$AAPT2_BIN" dump badging "$OUTPUT")
[[ "$output_badging" == *"package: name='$EXPECTED_PACKAGE'"* ]] ||
  die signed-package-mismatch

unset BOOM_RELEASE_STORE_PASSWORD BOOM_RELEASE_KEY_PASSWORD
printf 'BOOM_RELEASE_SIGN_OK input_sha256=%s output_sha256=%s cert_sha256=%s output=%s\n' \
  "$(sha256_file "$INPUT")" "$(sha256_file "$OUTPUT")" \
  "$actual_cert" "$OUTPUT"
