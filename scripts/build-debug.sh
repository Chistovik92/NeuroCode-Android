#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]; then
  echo "Android SDK не найден. Откройте проект в Android Studio или задайте ANDROID_HOME." >&2
  exit 1
fi

./gradlew --no-daemon testDebugUnitTest assembleDebug
"$project_dir/scripts/make-compact-apk.sh"
echo "APK: $project_dir/dist/NeuroCode-arm64-v8a-debug.apk"
