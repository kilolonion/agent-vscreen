#!/bin/sh
# 2.0.0-beta7 (7.6, 7.7): verify release APK contains ZERO debug/CI test hooks (classes, methods, or manifest
# entries), while sanity-checking that the debug APK does carry them so the check cannot pass vacuously.
# Usage: check_release_hooks.sh <app-debug.apk> <app-release.apk>
set -e
if [ "$#" -ne 2 ]; then
  echo "Usage: $0 <app-debug.apk> <app-release.apk>" >&2
  exit 2
fi
python3 - "$1" "$2" <<'PY'
import struct
import sys
import zipfile

debug_apk, release_apk = sys.argv[1], sys.argv[2]

FORBIDDEN = [
    "Lcom/kilon/agentvscreen/CiHook;",
    "Lcom/kilon/agentvscreen/PublicTestHook;",
    "com.kilon.agentvscreen.CiHook",
    "com.kilon.agentvscreen.PublicTestHook",
    "com.kilon.agentvscreen.CI_SEED",
    "completePairingForDebugHook",
    "denyPendingPairingForDebugHook",
]

REQUIRED_IN_DEBUG = [
    "Lcom/kilon/agentvscreen/CiHook;",
    "Lcom/kilon/agentvscreen/PublicTestHook;",
    "com.kilon.agentvscreen.CI_SEED",
]


def read_uleb128(buf, off):
    result = 0
    shift = 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        if (b & 0x80) == 0:
            break
        shift += 7
    return result, off


def dex_strings(data):
    if len(data) < 0x70 or data[:4] != b"dex\n":
        return set()
    str_size, str_off = struct.unpack_from("<II", data, 0x38)
    out = set()
    for i in range(str_size):
        (s_off,) = struct.unpack_from("<I", data, str_off + i * 4)
        _, p = read_uleb128(data, s_off)
        end = data.find(b"\x00", p)
        if end < 0:
            continue
        out.add(data[p:end].decode("utf-8", errors="replace"))
    return out


def scan_apk(path, needles):
    hits = {}
    with zipfile.ZipFile(path) as zf:
        for name in zf.namelist():
            if name.endswith(".dex"):
                strings = dex_strings(zf.read(name))
                for needle in needles:
                    if needle in strings or any(needle in s for s in strings if len(s) < 256):
                        hits.setdefault(needle, []).append(name)
            elif name == "AndroidManifest.xml":
                raw = zf.read(name)
                for needle in needles:
                    if needle.encode("utf-8") in raw or needle.encode("utf-16le") in raw:
                        hits.setdefault(needle, []).append(name)
    return hits


debug_hits = scan_apk(debug_apk, REQUIRED_IN_DEBUG)
fail = 0
for req in REQUIRED_IN_DEBUG:
    if req not in debug_hits:
        print(f"::error::sanity check failed: debug APK missing expected hook {req}", file=sys.stderr)
        fail = 1

release_hits = scan_apk(release_apk, FORBIDDEN)
for sym, where in sorted(release_hits.items()):
    print(f"::error::release APK contains forbidden debug hook {sym} in {', '.join(where)}", file=sys.stderr)
    fail = 1

if fail == 0:
    print(f"OK release hooks check: 0/{len(FORBIDDEN)} forbidden symbols in {release_apk}; {len(debug_hits)}/{len(REQUIRED_IN_DEBUG)} verified in {debug_apk}")
sys.exit(fail)
PY
