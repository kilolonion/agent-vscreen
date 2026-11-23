#!/bin/sh
# 2.0: the debug APK must carry x86_64 native code for every native library (the CI emulator is x86_64, and OCR
# through the arm64 translator takes 0.5-30 s a reading); the release APK must stay arm64 only.
# Usage: check_abis.sh <app-debug.apk> <app-release.apk>
set -e
fail=0
libs() { unzip -Z1 "$1" | sed -n 's|^lib/\([^/]*\)/\(.*\.so\)$|\1 \2|p' | sort; }
arm=$(libs "$1" | awk '$1=="arm64-v8a"{print $2}')
x64=$(libs "$1" | awk '$1=="x86_64"{print $2}')
for so in $arm; do
  echo "$x64" | grep -qx "$so" || { echo "::error::debug APK has no x86_64 $so"; fail=1; }
done
[ -n "$arm" ] || { echo "::error::debug APK has no native libraries"; fail=1; }
other=$(libs "$2" | awk '$1!="arm64-v8a"{print $1}' | sort -u)
[ -z "$other" ] || { echo "::error::release APK carries other ABIs: $other"; fail=1; }
echo "debug arm64: $(echo $arm)"
echo "debug x86_64: $(echo $x64)"
exit $fail
