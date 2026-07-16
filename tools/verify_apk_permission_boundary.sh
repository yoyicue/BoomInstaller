#!/usr/bin/env bash
set -euo pipefail

APK=${1:?usage: verify_apk_permission_boundary.sh APK}
BOOM_PACKAGE=com.yoyicue.boominstaller
BOOM_API_PERMISSION=$BOOM_PACKAGE.permission.API_V23
BOOM_MANAGER_PERMISSION=$BOOM_PACKAGE.permission.MANAGER
BOOM_PERMISSION_GROUP=$BOOM_PACKAGE.permission-group.API

die() {
  printf 'BOOM_APK_PERMISSION_BOUNDARY_ERROR reason=%s\n' "$1" >&2
  exit 1
}

find_apkanalyzer() {
  local root candidate
  if command -v apkanalyzer >/dev/null 2>&1; then
    command -v apkanalyzer
    return
  fi
  for root in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" \
    /opt/homebrew/share/android-commandlinetools "$HOME/Library/Android/sdk"; do
    [[ -n "$root" && -x "$root/cmdline-tools/latest/bin/apkanalyzer" ]] && {
      printf '%s\n' "$root/cmdline-tools/latest/bin/apkanalyzer"
      return
    }
  done
  candidate=$(find /opt/homebrew/share/android-commandlinetools \
    "$HOME/Library/Android/sdk" -type f -name apkanalyzer 2>/dev/null |
    sort -V | tail -1)
  [[ -n "$candidate" && -x "$candidate" ]] || die apkanalyzer-missing
  printf '%s\n' "$candidate"
}

[[ -f "$APK" ]] || die apk-missing
ANALYZER=$(find_apkanalyzer)
manifest=$($ANALYZER manifest print "$APK") || die manifest-unreadable

for required in "$BOOM_PERMISSION_GROUP" "$BOOM_API_PERMISSION" \
  "$BOOM_MANAGER_PERMISSION"; do
  grep -Fq "android:name=\"$required\"" <<<"$manifest" ||
    die "owned-permission-missing-$required"
done

for forbidden in moe.shizuku.manager.permission-group.API \
  moe.shizuku.manager.permission.MANAGER \
  moe.shizuku.manager.permission.API_V23; do
  if grep -Fq "$forbidden" <<<"$manifest"; then
    die "official-shizuku-permission-redeclared-$forbidden"
  fi
done

grep -Fq 'android:name="moe.shizuku.manager.ShizukuApplication"' \
  <<<"$manifest" || die shizuku-application-class-renamed
grep -Fq 'android:name="rikka.shizuku.intent.action.REQUEST_BINDER"' \
  <<<"$manifest" || die shizuku-binder-action-renamed

printf 'BOOM_APK_PERMISSION_BOUNDARY_OK package=%s api_permission=%s apk=%s\n' \
  "$BOOM_PACKAGE" "$BOOM_API_PERMISSION" "$APK"
