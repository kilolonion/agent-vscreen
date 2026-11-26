"""Score an exported YOLO detector (ONNX, raw head output [1, 5, N]) with the agent's question: tapping the centre
of a detection, does it land? Same metric as train.py's evaluate (hit_rate on textless targets, lands_on_tappable),
so the OmniParser original, our fine-tune, the small student and the MIT baseline are compared on the same screens.

  python yolo_eval.py <model.onnx> <prepared dir> [--limit 1500] [--out metrics.json] [--src rico|emulator|...]

The prepared images (any canvas size) are resized to the model's input; boxes are scaled the same way.
Also prints size and single-thread CPU latency on this machine (a phone is slower; see the notebook).
"""
import argparse, json, os, random, sys, time
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


def nms(boxes, scores, iou=0.5):
    order = np.argsort(-scores); keep = []
    while len(order):
        i = order[0]; keep.append(i)
        if len(order) == 1:
            break
        b = boxes[order[1:]]
        ix = np.clip(np.minimum(boxes[i, 2], b[:, 2]) - np.maximum(boxes[i, 0], b[:, 0]), 0, None)
        iy = np.clip(np.minimum(boxes[i, 3], b[:, 3]) - np.maximum(boxes[i, 1], b[:, 1]), 0, None)
        inter = ix * iy
        a0 = (boxes[i, 2] - boxes[i, 0]) * (boxes[i, 3] - boxes[i, 1]); a1 = (b[:, 2] - b[:, 0]) * (b[:, 3] - b[:, 1])
        order = order[1:][inter / (a0 + a1 - inter + 1e-9) < iou]
    return keep


def detections(out, thr):
    p = out[0][0]  # [5, N]: cx, cy, w, h, score (input pixels)
    sc = p[4]; m = sc >= thr
    if not m.any():
        return []
    cx, cy, w, h = p[0][m], p[1][m], p[2][m], p[3][m]
    boxes = np.stack([cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2], 1); s = sc[m]
    return [(float(s[i]), *map(float, boxes[i])) for i in nms(boxes, s)][:300]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("onnx"); ap.add_argument("data")
    ap.add_argument("--limit", type=int, default=1500); ap.add_argument("--out", default="")
    ap.add_argument("--src", default=""); ap.add_argument("--split", default="val")
    a = ap.parse_args()
    import onnxruntime as ort
    from train import score_screen
    so = ort.SessionOptions(); so.intra_op_num_threads = 1
    sess = ort.InferenceSession(a.onnx, so, providers=["CPUExecutionProvider"])
    inp = sess.get_inputs()[0]; IH, IW = inp.shape[2], inp.shape[3]
    S = []
    with open(os.path.join(a.data, "samples.jsonl"), encoding="utf-8") as f:
        for line in f:
            s = json.loads(line)
            if s["split"] == a.split and (not a.src or s["src"] == a.src):
                S.append(s)
    random.seed(0); S = random.sample(S, min(len(S), a.limit)) if a.limit else S
    thrs = (0.1, 0.2, 0.3, 0.4, 0.5)
    agg = {t: [0, 0, 0, 0] for t in thrs}; times = []
    for s in S:
        img = Image.open(os.path.join(a.data, "images", s["id"] + ".jpg")).convert("RGB")
        kx, ky = IW / img.width, IH / img.height
        x = (np.asarray(img.resize((IW, IH), Image.BILINEAR), np.float32) / 255.0).transpose(2, 0, 1)[None]
        t0 = time.time(); out = sess.run(None, {inp.name: x}); times.append(time.time() - t0)
        sc = lambda bs: [[b[0] * kx, b[1] * ky, b[2] * kx, b[3] * ky] for b in bs]
        ss = {"targets": sc(s["targets"]), "controls": sc(s.get("controls", [])), "ignore": sc(s["ignore"])}
        for t in thrs:
            agg[t] = [u + v for u, v in zip(agg[t], score_screen(detections(out, t), ss))]
    res = {}
    for t, (h, n, g, d) in agg.items():
        rec, prec = h / max(n, 1), g / max(d, 1)
        res[str(t)] = {"hit_rate": round(rec, 4), "lands_on_tappable": round(prec, 4), "f1": round(2 * rec * prec / max(rec + prec, 1e-9), 4),
                       "dets_per_screen": round(d / max(len(S), 1), 2), "targets": n}
    best = max(res, key=lambda k: res[k]["f1"])
    m = {"model": os.path.basename(a.onnx), "input": [IH, IW], "screens": len(S), "mb": round(os.path.getsize(a.onnx) / 1e6, 2),
         "ms_1thread_here": round(1000 * float(np.median(times[1:] or times)), 1), "best_threshold": float(best), "by_threshold": res}
    print(json.dumps({k: v for k, v in m.items() if k != "by_threshold"} | {"at_best": res[best]}, ensure_ascii=False))
    if a.out:
        with open(a.out, "w") as f:
            json.dump(m, f, indent=1)


if __name__ == "__main__":
    main()
