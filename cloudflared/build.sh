#!/bin/sh
# 2.0.0-beta11: builds Cloudflare's tunnel client (cloudflared) for the APK, as libcloudflared.so in nativeLibraryDir
# (the only place an app may execute files from), the same way as tsbridge. Pinned to CLOUDFLARED_VERSION; the source
# is unchanged, two files are added to package main by -overlay: avs_init.go and tsbridge/dns.go (Android DNS).
set -e
cd "$(dirname "$0")"
HERE="$(pwd)"
AVS_BUILD_DIR="${AVS_BUILD_DIR:-/srv/avs-build}"
[ -f "$AVS_BUILD_DIR/goenv.sh" ] && . "$AVS_BUILD_DIR/goenv.sh"
VERSION="$(tr -d '[:space:]' < VERSION)"
COMMIT="$(tr -d '[:space:]' < COMMIT)"
# cloudflared pins a newer Go patch release than tsbridge; let Go fetch it (CI sets GOTOOLCHAIN=local).
export GOTOOLCHAIN=auto
SRC="${CLOUDFLARED_SRC:-$AVS_BUILD_DIR/cloudflared-src/$VERSION}"
if [ ! -d "$SRC/.git" ]; then
  mkdir -p "$(dirname "$SRC")"
  git clone -q --depth 1 --branch "$VERSION" https://github.com/cloudflare/cloudflared "$SRC"
fi
GOT_COMMIT="$(git -C "$SRC" rev-parse HEAD)"
if [ "$GOT_COMMIT" != "$COMMIT" ]; then
  echo "ERROR: cloudflared commit mismatch for $VERSION: got $GOT_COMMIT, expected $COMMIT" >&2
  exit 1
fi
MAIN="$SRC/cmd/cloudflared"
OVERLAY="$(mktemp)"
trap 'rm -f "$OVERLAY"' EXIT
cat > "$OVERLAY" <<JSON
{"Replace": {"$MAIN/avs_init.go": "$HERE/avs_init.go", "$MAIN/avs_dns.go": "$HERE/../tsbridge/dns.go"}}
JSON
LDF="-s -w -buildid= -X main.Version=$VERSION"
OUT="$HERE/../app/src/main/jniLibs/arm64-v8a/libcloudflared.so"
OUT64="$HERE/../app/src/main/jniLibs/x86_64/libcloudflared.so"
mkdir -p "$(dirname "$OUT")" "$(dirname "$OUT64")"
cd "$SRC"
CGO_ENABLED=0 GOOS=android GOARCH=arm64 GOFLAGS=-mod=mod go build -trimpath -overlay "$OVERLAY" -ldflags "$LDF" -o "$OUT" ./cmd/cloudflared
ls -l "$OUT"
# The debug APK also targets the x86_64 CI emulator; as for tsbridge, a static linux/amd64 build stands in there.
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 GOFLAGS=-mod=mod go build -trimpath -overlay "$OVERLAY" -ldflags "$LDF" -o "$OUT64" ./cmd/cloudflared
ls -l "$OUT64"
