#!/usr/bin/env python3
"""Collect labelled screens from any Android device or emulator over adb: screenshot + `uiautomator dump`.

  python collect.py <out dir> [--serial X] [--steps 300] [--packages a,b] [--seed 1] [--settle 1.2]

Each step saves <out>/<n>.png and <n>.xml (the pair prepare.py's `collected` mode reads), then moves on by tapping
a random actionable node of the current screen (never an input box), pressing Back now and then, and relaunching
a random app from the launcher list when it lands outside the chosen packages or on the home screen. Nothing here
knows any app: the crawl is random over whatever the device has. Screens whose dump fails or that are identical to
the previous one are skipped.

Privacy: this captures whatever is on the screen. Run it on an emulator or a test phone, never on a phone with
personal accounts signed in, and look through the pictures before sharing them.

Stdlib only (plus adb on PATH). Put the resulting folder in Google Drive under vscreen-detector/extra/<name>/ and the
Colab notebook trains on it (repeated x5) and reports it as its own validation set.
"""
import argparse, hashlib, os, random, re, subprocess, time
import xml.etree.ElementTree as ET

B = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
COMP = re.compile(r"^[\w.]+/[\w.$]+$")


def adb(serial, *args, binary=False, timeout=30):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    out = subprocess.run(cmd, capture_output=True, timeout=timeout)
    return out.stdout if binary else out.stdout.decode("utf-8", "replace")


def launchable(serial):
    out = adb(serial, "shell", "cmd", "package", "query-activities", "--brief", "-a", "android.intent.action.MAIN",
              "-c", "android.intent.category.LAUNCHER")
    return sorted({l.strip() for l in out.splitlines() if COMP.match(l.strip())})


def dump(serial):
    adb(serial, "shell", "uiautomator", "dump", "/data/local/tmp/avs_dump.xml", timeout=40)
    x = adb(serial, "shell", "cat", "/data/local/tmp/avs_dump.xml")
    return x if x.strip().startswith("<?xml") or "<hierarchy" in x else None


def actionable(xml_text):
    out = []
    for n in ET.fromstring(xml_text).iter("node"):
        if n.get("clickable") != "true" or "EditText" in (n.get("class") or ""):
            continue
        m = B.match(n.get("bounds", ""))
        if not m:
            continue
        l, t, r, b = map(int, m.groups())
        if r - l > 8 and b - t > 8:
            out.append(((l + r) // 2, (t + b) // 2, n.get("package", "")))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out"); ap.add_argument("--serial", default="")
    ap.add_argument("--steps", type=int, default=300); ap.add_argument("--packages", default="")
    ap.add_argument("--seed", type=int, default=1); ap.add_argument("--settle", type=float, default=1.2)
    a = ap.parse_args()
    random.seed(a.seed)
    os.makedirs(a.out, exist_ok=True)
    acts = launchable(a.serial)
    pk = [p for p in a.packages.split(",") if p]
    if pk:
        acts = [x for x in acts if x.split("/")[0] in pk]
    if not acts:
        raise SystemExit("no launchable activities found")
    home = {l.strip().split("/")[0] for l in adb(a.serial, "shell", "cmd", "package", "resolve-activity", "--brief", "-c",
            "android.intent.category.HOME", "-a", "android.intent.action.MAIN").splitlines() if COMP.match(l.strip())}
    print(f"{len(acts)} apps to wander through")
    saved, last_hash, n = 0, None, len([f for f in os.listdir(a.out) if f.endswith(".png")])

    def relaunch():
        adb(a.serial, "shell", "am", "start", "-n", random.choice(acts))

    relaunch()
    for step in range(a.steps):
        time.sleep(a.settle)
        x = dump(a.serial)
        png = adb(a.serial, "exec-out", "screencap", "-p", binary=True)
        if x and png[:4] == b"\x89PNG":
            h = hashlib.md5(png).hexdigest()
            if h != last_hash:
                last_hash = h
                with open(os.path.join(a.out, f"{n:06d}.png"), "wb") as f:
                    f.write(png)
                with open(os.path.join(a.out, f"{n:06d}.xml"), "w", encoding="utf-8") as f:
                    f.write(x)
                n += 1; saved += 1
        nodes = actionable(x) if x else []
        pkgs = {p for _, _, p in nodes}
        off = (pk and not (pkgs & set(pk))) or bool(pkgs & home) or not nodes
        r = random.random()
        if off or r < 0.04:
            relaunch()
        elif r < 0.18:
            adb(a.serial, "shell", "input", "keyevent", "4")
        else:
            cx, cy, _ = random.choice(nodes)
            adb(a.serial, "shell", "input", "tap", str(cx), str(cy))
        if (step + 1) % 25 == 0:
            print(f"step {step + 1}/{a.steps}: {saved} screens saved", flush=True)
    print(f"done: {saved} screens in {a.out}")


if __name__ == "__main__":
    main()
