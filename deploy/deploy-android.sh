#!/usr/bin/env bash
#
# Build the Saigon Social Android app pointing at the production API and install it on every
# connected Android device.
#
#   API   : https://api.saigon.social
#   Module: :app:androidApp (release variant)
#
# IMPORTANT — how this deploys:
#   The backend URL is chosen by the Gradle build type, not by editing source. The release build
#   bakes BuildConfig.API_BASE_URL=https://api.saigon.social (see app/androidApp/build.gradle.kts),
#   which MainActivity passes into the shared client. We therefore just build+install the *release*
#   variant. Release is signed with the debug keystore so it installs straight onto a device via
#   adb; debug builds instead point at a local dev server (http://10.0.2.2:9090).
#
#   Override the baked URL for either variant with the Gradle property `apiBaseUrl`, surfaced here
#   as DEFAULT_BASE_URL.
#
# Requirements:
#   - A working Android SDK (the Gradle build resolves it via local.properties / ANDROID_HOME).
#   - One or more devices/emulators connected and authorised (`adb devices`).
#
# Usage:
#   ./deploy/deploy-android.sh                                  # build prod release + install on all devices
#   DEFAULT_BASE_URL=https://staging.saigon.social ./deploy/deploy-android.sh
#   INSTALL_TASK=installDebug ./deploy/deploy-android.sh        # install the local-dev debug build instead
#
set -euo pipefail

# ---- Configuration ------------------------------------------------------------------------------
# Optional override for the API URL baked into the build (Gradle property `apiBaseUrl`). Empty means
# "use the build type's default" (release -> https://api.saigon.social, debug -> local dev server).
DEFAULT_BASE_URL="${DEFAULT_BASE_URL:-}"

# Which Gradle install task to run. `installRelease` builds the production-pointing, debug-signed
# release and installs it on every connected device; use installDebug for the local-dev build.
INSTALL_TASK="${INSTALL_TASK:-installRelease}"

# Resolve the repository root so the script works from any directory.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

# ---- Check for connected devices ----------------------------------------------------------------
# Bail out early with a clear message if there's nothing to install onto, rather than letting Gradle
# fail deep in the build. `adb` lives under the SDK's platform-tools; use it if it's on PATH.
if command -v adb >/dev/null 2>&1; then
  DEVICE_COUNT="$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')"
  if [ "${DEVICE_COUNT}" = "0" ]; then
    echo "ERROR: no connected Android devices found (adb devices)." >&2
    echo "       Connect a device or start an emulator, then re-run." >&2
    exit 1
  fi
  echo ">> Found ${DEVICE_COUNT} connected Android device(s); they will receive the app."
else
  echo ">> 'adb' not on PATH; relying on Gradle to locate the SDK and connected devices."
fi

# ---- Build & install on connected devices -------------------------------------------------------
GRADLE_ARGS=("--no-daemon" ":app:androidApp:${INSTALL_TASK}")
if [ -n "${DEFAULT_BASE_URL}" ]; then
  echo ">> Overriding baked API URL with apiBaseUrl=${DEFAULT_BASE_URL}"
  GRADLE_ARGS+=("-PapiBaseUrl=${DEFAULT_BASE_URL}")
fi

echo ">> Building and installing the Android app with Gradle (:app:androidApp:${INSTALL_TASK})"
chmod +x ./gradlew
./gradlew "${GRADLE_ARGS[@]}"

echo ">> Android app deployed to connected device(s) (task: ${INSTALL_TASK})."
