"""Prepared samples -> a YOLO-format dataset (images/{train,val} symlinks, labels/{train,val}/*.txt, data.yaml).

  python to_yolo.py <prepared dir>[,<more>...] <out dir> [--extra <dir>,... --extra-repeat 5]

One class, "tappable": textless targets AND text-bearing controls (same as OmniParser's "icon" class, so its
detector head is reused as is). The phone tells them apart afterwards: a detection OCR can read is a control,
the rest are the textless targets this model exists for. `ignore` boxes are simply not written (YOLO has no
ignore regions). Plain file conversion; no YOLO library is imported here.
"""
import argparse, json, os


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src"); ap.add_argument("out")
    ap.add_argument("--extra", default=""); ap.add_argument("--extra-repeat", type=int, default=5)
    a = ap.parse_args()
    counts = {"train": 0, "val": 0}
    for sp in counts:
        os.makedirs(os.path.join(a.out, "images", sp), exist_ok=True); os.makedirs(os.path.join(a.out, "labels", sp), exist_ok=True)
    groups = [(d, 1) for d in a.src.split(",") if d] + [(d, a.extra_repeat) for d in a.extra.split(",") if d]
    for d, rep in groups:
        with open(os.path.join(d, "samples.jsonl"), encoding="utf-8") as f:
            for line in f:
                s = json.loads(line)
                sp = s["split"]; W, H = s["w"], s["h"]
                rows = []
                for l, t, r, b in s["targets"] + s.get("controls", []):
                    w, h = r - l, b - t
                    if w >= 2 and h >= 2:
                        rows.append(f"0 {(l + r) / 2 / W:.5f} {(t + b) / 2 / H:.5f} {w / W:.5f} {h / H:.5f}")
                for k in range(rep if sp == "train" else 1):
                    name = s["id"] + (f"__r{k}" if k else "")
                    dst = os.path.join(a.out, "images", sp, name + ".jpg")
                    if not os.path.exists(dst):
                        os.symlink(os.path.abspath(os.path.join(d, "images", s["id"] + ".jpg")), dst)
                    with open(os.path.join(a.out, "labels", sp, name + ".txt"), "w") as g:
                        g.write("\n".join(rows))
                    counts[sp] += 1
    with open(os.path.join(a.out, "data.yaml"), "w") as f:
        f.write(f"path: {os.path.abspath(a.out)}\ntrain: images/train\nval: images/val\nnames:\n  0: icon\n")
    print(f"yolo dataset: {counts['train']} train, {counts['val']} val -> {a.out}/data.yaml")


if __name__ == "__main__":
    main()
