#!/usr/bin/env bash
# Agent-VScreen build: reuses the Minis VScreen toolchain and Gradle cache; outputs go to /srv/avs-build.
set -euo pipefail
BASE=/data/workspace/minis-vscreen
export GRADLE_USER_HOME="$BASE/cache/gradle" TMPDIR=/srv/avs-build/tmp
export ANDROID_HOME="$BASE/toolchains/android-sdk" ANDROID_SDK_ROOT="$BASE/toolchains/android-sdk"
mkdir -p "$TMPDIR" /srv/avs-build/logs
cd "$(dirname "$0")"
TASK="${1:-:app:assembleRelease}"
# Rebuild the built-in Tailscale node when it is missing or older than its sources (it is not in git).
SO=app/src/main/jniLibs/arm64-v8a/libtsbridge.so
if [ ! -f "$SO" ] || [ -n "$(find tsbridge -newer "$SO" -type f \( -name '*.go' -o -name 'go.mod' -o -name 'go.sum' \) | head -1)" ]; then
  ./tsbridge/build.sh >> /srv/avs-build/logs/tsb.log 2>&1
fi
./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs='-Xmx3g -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process $TASK > /srv/avs-build/logs/gradle.log 2>&1
echo AVS_BUILD_SUCCESS
