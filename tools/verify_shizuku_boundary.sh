#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

fail() {
  echo "SHIZUKU_BOUNDARY_ERROR: $*" >&2
  exit 1
}

require_text() {
  file=$1
  value=$2
  grep -Fq "$value" "$file" || fail "$file is missing: $value"
}

reject_text() {
  file=$1
  value=$2
  if grep -Fq "$value" "$file"; then
    fail "$file must not contain: $value"
  fi
}

STANDARD_API_PERMISSION=moe.shizuku.manager.permission.API_V23
STANDARD_MANAGER_PERMISSION=moe.shizuku.manager.permission.MANAGER
BOOM_PACKAGE=com.yoyicue.boominstaller

require_text manager/src/main/AndroidManifest.xml "android:name=\"$STANDARD_API_PERMISSION\""
require_text manager/src/main/AndroidManifest.xml "android:name=\"$STANDARD_MANAGER_PERMISSION\""
require_text manager/src/main/java/moe/shizuku/manager/Manifest.java "$STANDARD_API_PERMISSION"
require_text server/src/main/java/rikka/shizuku/server/BinderSender.java "$STANDARD_API_PERMISSION"
require_text server/src/main/java/rikka/shizuku/server/BinderSender.java "$STANDARD_MANAGER_PERMISSION"
require_text server/src/main/java/rikka/shizuku/server/ServerConstants.java "$STANDARD_API_PERMISSION"
require_text api/provider/src/main/java/rikka/shizuku/ShizukuProvider.java "$STANDARD_API_PERMISSION"

if grep -R -Fq "com.yoyicue.boominstaller.permission" \
  manager/src/main/AndroidManifest.xml \
  manager/src/main/java/moe/shizuku/manager/Manifest.java \
  server/src/main/java/rikka/shizuku/server/BinderSender.java \
  server/src/main/java/rikka/shizuku/server/ServerConstants.java; then
  fail "Shizuku protocol permissions must not use the BoomInstaller namespace"
fi

require_text api/shared/src/main/java/rikka/shizuku/ShizukuApiConstants.java \
  'BINDER_DESCRIPTOR = "moe.shizuku.server.IShizukuService"'
require_text shell/src/main/java/rikka/shizuku/shell/ShizukuShellLoader.java \
  'new Intent("rikka.shizuku.intent.action.REQUEST_BINDER")'
require_text shell/src/main/java/rikka/shizuku/shell/ShizukuShellLoader.java \
  ".setPackage(\"$BOOM_PACKAGE\")"
require_text server/src/main/java/rikka/shizuku/server/ServerConstants.java \
  "MANAGER_APPLICATION_ID = \"$BOOM_PACKAGE\""

require_text manager/src/main/res/values/strings.xml \
  '<string name="app_name" translatable="false">BoomInstaller</string>'
require_text manager/src/main/res/values/strings.xml \
  '<string name="product_title" translatable="false">BoomInstaller (魔改Shizuku支持学而思)</string>'
require_text manager/src/main/res/values/strings.xml \
  '<string name="shizuku_name" translatable="false">Shizuku</string>'

boom_default_count=$(grep -o 'BoomInstaller' manager/src/main/res/values/strings.xml | wc -l | tr -d ' ')
[ "$boom_default_count" = 2 ] || fail "core resources expose BoomInstaller outside app_name/product_title"
reject_text manager/src/main/res/values-zh-rCN/strings.xml BoomInstaller
if grep -R -q '<string name="app_name"' manager/src/main/res/values-*; then
  fail "localized resources must not override the BoomInstaller application identity"
fi

require_text manager/src/main/java/moe/shizuku/manager/home/HomeActivity.kt \
  'supportActionBar?.setTitle(R.string.product_title)'
require_text manager/src/main/res/layout/about_dialog.xml \
  'android:text="@string/product_title"'

require_text manager/src/main/res/values-zh-rCN/strings_installer.xml \
  '通过学而思原生安装通道安装 APK'
require_text manager/src/main/java/moe/shizuku/manager/home/InstallerViewHolder.kt InstallerActivity
require_text manager/src/main/java/moe/shizuku/manager/home/HomeActivity.kt \
  'https://github.com/yoyicue/BoomInstaller'

# Service activation is standard Shizuku root/ADB-shell. Installer identities
# are owned exclusively by the companion xpad-install data plane.
reject_text manager/src/main/jni/CMakeLists.txt xpad_activation.cpp
reject_text manager/src/main/jni/starter.cpp xpad::activate
[ ! -e manager/src/main/jni/xpad_activation.cpp ] || \
  fail "BoomInstaller must not embed a private 31317 implementation"
[ ! -e manager/src/main/jni/xpad_activation.h ] || \
  fail "BoomInstaller must not expose installer identity activation in its starter"
if grep -R -Eq '31317|hidden_api_blacklist_exemptions|--setuid=1000' \
  manager/src/main/java manager/src/main/jni; then
  fail "BoomInstaller runtime source must not contain an installer exploit path"
fi
require_text manager/src/main/java/moe/shizuku/manager/receiver/BootCompleteReceiver.kt \
  'setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)'
require_text manager/src/main/java/moe/shizuku/manager/receiver/BootStarterJobService.kt \
  'Shizuku.getUid() == 0 || Shizuku.getUid() == android.os.Process.SHELL_UID'
require_text manager/src/main/java/moe/shizuku/manager/receiver/BootStarterJobService.kt \
  'awaitServer(android.os.Process.SHELL_UID, SERVER_VERIFY_MILLIS)'
require_text manager/src/main/java/moe/shizuku/manager/installer/BoomInstallerUserService.java \
  'REQUIRED_XPAD_INSTALL_VERSION = "0.2.3"'
require_text manager/src/main/java/moe/shizuku/manager/installer/BoomInstallerUserService.java \
  'XPAD_INSTALL, "install", "--backend", "auto"'
reject_text manager/src/main/java/moe/shizuku/manager/installer/InstallerIdentity.java \
  Process.SYSTEM_UID
[ ! -e manager/src/main/java/moe/shizuku/manager/installer/PackageInstallerFactory.java ] || \
  fail "BoomInstaller must not retain a parallel UID 1000 PackageInstaller backend"
[ ! -e manager/src/main/java/moe/shizuku/manager/installer/IntentSenderFactory.java ] || \
  fail "BoomInstaller must route all APK commits through xpad-install"
require_text server/src/main/java/rikka/shizuku/server/ShizukuUserServiceManager.java \
  'Starting BoomInstaller APK broker as shell for managed 0044 installation'

require_text server/src/main/java/rikka/shizuku/server/ShizukuConfig.java \
  '@SerializedName("manager")'
require_text server/src/main/java/rikka/shizuku/server/ShizukuConfigManager.java \
  'reset client grants'
require_text server/src/main/java/rikka/shizuku/server/ShizukuConfigManager.java \
  'revokeRuntimePermission(pi.packageName, PERMISSION, userId)'

echo "SHIZUKU_BOUNDARY_OK package=$BOOM_PACKAGE api_permission=$STANDARD_API_PERMISSION"
