#!/bin/sh
# Packs the Operit ToolPkg: a plain zip of manifest.json, dist/ and resources/.
set -e
cd "$(dirname "$0")"
OUT="${1:-agent-vscreen.toolpkg}"
rm -f "$OUT"
python3 - "$OUT" <<'PY'
import sys, zipfile, os
with zipfile.ZipFile(sys.argv[1], "w", zipfile.ZIP_DEFLATED) as z:
    for root in ["manifest.json", "dist", "resources"]:
        if os.path.isfile(root): z.write(root); continue
        for d, _, fs in os.walk(root):
            for f in sorted(fs): z.write(os.path.join(d, f))
PY
ls -l "$OUT"
