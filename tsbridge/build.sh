#!/bin/sh
# Builds the built-in Tailscale node for the APK: a static android/arm64 executable named like a native library, so
# the installer unpacks it into nativeLibraryDir (the only place an app may execute files from).
set -e
cd "$(dirname "$0")"
AVS_BUILD_DIR="${AVS_BUILD_DIR:-/srv/avs-build}"
[ -f "$AVS_BUILD_DIR/goenv.sh" ] && . "$AVS_BUILD_DIR/goenv.sh"
OUT=../app/src/main/jniLibs/arm64-v8a/libtsbridge.so
mkdir -p "$(dirname "$OUT")"
TAGS="ts_omit_aws,ts_omit_bird,ts_omit_tap,ts_omit_kube,ts_omit_completion,ts_omit_ssh,ts_omit_wakeonlan,ts_omit_capture,ts_omit_relayserver,ts_omit_taildrop,ts_omit_tpm,ts_omit_syspolicy,ts_omit_debugeventbus,ts_omit_webclient,ts_omit_drive,ts_omit_systray,ts_omit_portmapper,ts_omit_appconnectors,ts_omit_clientupdate,ts_omit_advertiseroutes,ts_omit_useroutes,ts_omit_outboundproxy,ts_omit_netstack_stats"
CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -trimpath -tags "$TAGS" -ldflags "-s -w -buildid=" -o "$OUT" .
ls -l "$OUT"
# 2.0: the debug APK also targets the x86_64 CI emulator (app/build.gradle.kts); release keeps arm64 only.
OUT64=../app/src/main/jniLibs/x86_64/libtsbridge.so
mkdir -p "$(dirname "$OUT64")"
CGO_ENABLED=0 GOOS=android GOARCH=amd64 go build -trimpath -tags "$TAGS" -ldflags "-s -w -buildid=" -o "$OUT64" .
ls -l "$OUT64"
