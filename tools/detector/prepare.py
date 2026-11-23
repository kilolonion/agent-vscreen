"""Turn raw sources into one training set: <out>/images/<id>.jpg (letterboxed to the model input) + <out>/samples.jsonl.

  python prepare.py rico <unique_uis/combined dir> <out> [--workers 8]
  python prepare.py collected <dir with *.png|*.jpg + same-name *.xml from uiautomator dump> <out> [--src emulator]

Images are resized once (aspect kept, scale = min(W/w, H/h), top-left anchored, padded) so training reads
small JPEGs; boxes in samples.jsonl are in the resized image's pixels. Splits are by app package where the
source knows it (RICO: one app never lands in both train and val), else by screen id hash.
"""
import argparse, hashlib, json, os, sys
from concurrent.futures import ProcessPoolExecutor
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import labels

IN_W, IN_H = 320, 704  # default canvas (9:19.8, the shape of most current phones); --size changes it


if os.environ.get("AVS_DET_SIZE"):
    IN_W, IN_H = map(int, os.environ["AVS_DET_SIZE"].split("x"))


def _split(key, val_pct=5):
    return "val" if int(hashlib.md5(key.encode()).hexdigest(), 16) % 100 < val_pct else "train"


def _save(img, sample, out, key):
    s = min(IN_W / img.width, IN_H / img.height)
    w, h = max(1, round(img.width * s)), max(1, round(img.height * s))
    canvas = Image.new("RGB", (IN_W, IN_H), (114, 114, 114))
    canvas.paste(img.convert("RGB").resize((w, h), Image.BILINEAR), (0, 0))
    canvas.save(os.path.join(out, "images", sample["id"] + ".jpg"), quality=90)
    sc = lambda b: [round(b[0] * s, 1), round(b[1] * s, 1), round(b[2] * s, 1), round(b[3] * s, 1)]
    sample = dict(sample, w=IN_W, h=IN_H, scale=s, targets=[sc(b) for b in sample["targets"]], controls=[sc(b) for b in sample["controls"]],
                  ignore=[sc(b) for b in sample["ignore"]], split=_split(key))
    return sample


def _rico_one(args):
    src, out, name = args
    sid = name[:-5]
    try:
        with open(os.path.join(src, name), encoding="utf-8") as f:
            doc = json.load(f)
        jpg = os.path.join(src, sid + ".jpg")
        if not os.path.exists(jpg):
            return None
        img = Image.open(jpg)
        smp = labels.rico_sample(doc, img.width, img.height, "rico_" + sid)
        if smp is None:
            return None
        pkg = (doc.get("activity_name") or sid).split("/")[0]
        return _save(img, smp, out, pkg)
    except Exception:
        return None


def _collected_one(args):
    src, out, name, tag = args
    stem = os.path.splitext(name)[0]
    xml = os.path.join(src, stem + ".xml")
    if not os.path.exists(xml):
        return None
    try:
        img = Image.open(os.path.join(src, name))
        with open(xml, encoding="utf-8") as f:
            smp = labels.uiautomator_sample(f.read(), img.width, img.height, f"{tag}_{stem}", tag)
        return _save(img, smp, out, stem)
    except Exception:
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("kind", choices=["rico", "collected"])
    ap.add_argument("src"); ap.add_argument("out")
    ap.add_argument("--workers", type=int, default=os.cpu_count() or 2)
    ap.add_argument("--src-tag", default="collected")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--size", default="320x704", help="canvas WxH; 448x992 for the YOLO models")
    a = ap.parse_args()
    global IN_W, IN_H
    IN_W, IN_H = map(int, a.size.split("x"))
    os.makedirs(os.path.join(a.out, "images"), exist_ok=True)
    names = sorted(os.listdir(a.src))
    os.environ["AVS_DET_SIZE"] = a.size  # child processes re-read it
    if a.kind == "rico":
        jobs = [(a.src, a.out, n) for n in names if n.endswith(".json")]
        fn = _rico_one
    else:
        jobs = [(a.src, a.out, n, a.src_tag) for n in names if n.lower().endswith((".png", ".jpg"))]
        fn = _collected_one
    if a.limit:
        jobs = jobs[:a.limit]
    kept = targets = 0
    with ProcessPoolExecutor(a.workers) as ex, open(os.path.join(a.out, "samples.jsonl"), "a", encoding="utf-8") as f:
        for i, s in enumerate(ex.map(fn, jobs, chunksize=64)):
            if s is not None:
                f.write(json.dumps(s, ensure_ascii=False) + "\n"); kept += 1; targets += len(s["targets"])
            if (i + 1) % 5000 == 0:
                print(f"{i + 1}/{len(jobs)} read, {kept} kept", flush=True)
    print(f"done: {kept}/{len(jobs)} screens kept, {targets} textless targets ({targets / max(kept, 1):.1f}/screen)")


if __name__ == "__main__":
    main()
