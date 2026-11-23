#!/usr/bin/env bash
# Agent-VScreen build: reuses the Minis VScreen toolchain and Gradle cache; outputs go to $AVS_BUILD_DIR.
set -euo pipefail
set -f
AVS_BUILD_DIR="${AVS_BUILD_DIR:-/srv/avs-build}"
BASE="${AVS_TOOLCHAIN_BASE:-/data/workspace/minis-vscreen}"
export AVS_BUILD_DIR
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$BASE/cache/gradle}" TMPDIR="${TMPDIR:-$AVS_BUILD_DIR/tmp}"
export ANDROID_HOME="${ANDROID_HOME:-$BASE/toolchains/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
mkdir -p "$TMPDIR" "$AVS_BUILD_DIR/logs"
cd "$(dirname "$0")"
TASK="${1:-:app:assembleRelease}"
# 1.4.0-beta2: `./build.sh regen` rewrites the files generated from the tables (README ability blocks, REFERENCE.md,
# Operit advice), then runs the tests. Tests never write the source tree otherwise.
if [ "$TASK" = "regen" ]; then export AVS_REGEN=1; TASK=":app:testDebugUnitTest"; fi
# A release runs Android lint and the full Android unit suite.
if [ "$TASK" = ":app:assembleRelease" ]; then TASK=":app:lintDebug :app:testDebugUnitTest :app:assembleRelease"; fi
# Rebuild the built-in Tailscale node when it is missing or older than its sources (it is not in git).
SO=app/src/main/jniLibs/arm64-v8a/libtsbridge.so
if [ ! -f "$SO" ] || [ ! -f app/src/main/jniLibs/x86_64/libtsbridge.so ] || [ -n "$(find tsbridge -newer "$SO" -type f \( -name '*.go' ! -name '*_test.go' -o -name 'go.mod' -o -name 'go.sum' -o -name 'build.sh' \) -print -quit)" ]; then
  ./tsbridge/build.sh >> "$AVS_BUILD_DIR/logs/tsb.log" 2>&1
fi
# 1.4.0-beta2: the OCR models must build and read on the app's own onnxruntime version (beta1 did not on the phone).
ORT=$(grep -o 'onnxruntime-android:[0-9.]*' app/build.gradle.kts | cut -d: -f2)
OCR_PYTHON="${AVS_OCR_PYTHON:-$BASE/cache/ocrbench/venv-$ORT/bin/python}"
if [ -z "${AVS_OCR_PYTHON:-}" ] && [ ! -x "$OCR_PYTHON" ]; then OCR_PYTHON=python3; fi
"$OCR_PYTHON" tools/ocr-bench/check_assets.py app/src/main/assets/vscreen-ocr "$ORT" > "$AVS_BUILD_DIR/logs/ocrcheck.log" 2>&1 || { cat "$AVS_BUILD_DIR/logs/ocrcheck.log"; echo AVS_BUILD_FAILED_OCR; exit 1; }
./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs='-Xmx3g -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process $TASK > "$AVS_BUILD_DIR/logs/gradle.log" 2>&1
echo AVS_BUILD_SUCCESS
