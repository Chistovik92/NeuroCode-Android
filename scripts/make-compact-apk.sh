#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
input_apk="${1:-$project_dir/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk}"
output_apk="${2:-$project_dir/dist/NeuroCode-arm64-v8a-debug.apk}"
android_user_dir="${ANDROID_USER_HOME:-$HOME/.android}"
keystore="${3:-$android_user_dir/debug.keystore}"

if [[ -z "$sdk_dir" || ! -d "$sdk_dir" ]]; then
  echo "Android SDK не найден. Задайте ANDROID_HOME или ANDROID_SDK_ROOT." >&2
  exit 1
fi
if [[ ! -f "$input_apk" ]]; then
  echo "APK не найден: $input_apk. Сначала выполните ./gradlew assembleDebug." >&2
  exit 1
fi
if [[ ! -f "$keystore" ]]; then
  echo "Debug keystore не найден: $keystore. Сначала выполните ./gradlew assembleDebug." >&2
  exit 1
fi

build_tools="$(find "$sdk_dir/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
strip_tool="$(find "$sdk_dir/ndk" -path '*/toolchains/llvm/prebuilt/*/bin/llvm-strip' | sort -V | tail -n 1)"
zipalign_tool="$build_tools/zipalign"
apksigner_tool="$build_tools/apksigner"

for required_tool in "$strip_tool" "$zipalign_tool" "$apksigner_tool"; do
  if [[ ! -x "$required_tool" ]]; then
    echo "Не найден инструмент Android SDK: $required_tool" >&2
    exit 1
  fi
done

temp_dir="$(mktemp -d)"
cleanup() {
  if [[ -n "$temp_dir" && -d "$temp_dir" ]]; then
    rm -rf -- "$temp_dir"
  fi
}
trap cleanup EXIT

unpacked_dir="$temp_dir/unpacked"
unsigned_apk="$temp_dir/unsigned.apk"
aligned_apk="$temp_dir/aligned.apk"
mkdir -p "$unpacked_dir" "$(dirname "$output_apk")"
unzip -q "$input_apk" -d "$unpacked_dir"

if [[ -d "$unpacked_dir/META-INF" ]]; then
  find "$unpacked_dir/META-INF" -type f \
    \( -name '*.RSA' -o -name '*.DSA' -o -name '*.EC' -o -name '*.SF' -o -name 'MANIFEST.MF' \) \
    -delete
fi

while IFS= read -r -d '' library; do
  stripped_library="$library.stripped"
  if "$strip_tool" --strip-unneeded -o "$stripped_library" "$library"; then
    mv "$stripped_library" "$library"
  else
    rm -f -- "$stripped_library"
    echo "Предупреждение: не удалось убрать debug-символы из $library" >&2
  fi
done < <(find "$unpacked_dir/lib" -type f -name '*.so' -print0)

(
  cd "$unpacked_dir"
  zip -q -r -9 "$unsigned_apk" . -x 'lib/*'
  zip -q -r -0 "$unsigned_apk" lib
)

"$zipalign_tool" -P 16 -f 4 "$unsigned_apk" "$aligned_apk"
"$apksigner_tool" sign \
  --ks "$keystore" \
  --ks-key-alias androiddebugkey \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$output_apk" \
  "$aligned_apk"
"$apksigner_tool" verify --verbose --print-certs "$output_apk"
sha256sum "$output_apk"
