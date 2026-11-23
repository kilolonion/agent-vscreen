"""Textless-element detector: train, evaluate, export. Runs on a Colab GPU (or CPU for a smoke test).

Model: CenterNet-style, one class. ImageNet-pretrained MobileNetV3 (timm) -> small top-down FPN to stride 4 ->
heads "heat" (is a target centred here) and "reg" (sub-cell offset + log size). Exported ONNX takes a 0..1 RGB
image [1,3,704,320] and returns
  score [1,1,176,80]  peak-suppressed confidence (a cell is non-zero only if it is a local 3x3 maximum)
  box   [1,4,176,80]  cx, cy, w, h in input pixels
so the phone only scans `score > threshold` and reads four numbers per hit (no NMS, no anchors).

  python train.py train  --data D --ckpt C [--epochs 40 --batch 64 --max-hours 4 --backbone mobilenetv3_large_100]
  python train.py eval   --data D --ckpt C/best.pt [--onnx m.onnx]
  python train.py export --ckpt C/best.pt --out O        (writes O/detector.onnx fp32 + O/detector.fp16.onnx: fp16 storage, fp32 compute)

Training resumes from C/last.pt automatically (Colab runtimes get recycled), and stops cleanly at --max-hours.
"""
import argparse, json, math, os, random, time
import numpy as np
import torch, torch.nn as nn, torch.nn.functional as F
from PIL import Image, ImageOps

IN_W, IN_H, R = 320, 704, 4
OUT_W, OUT_H = IN_W // R, IN_H // R
MEAN = (0.485, 0.456, 0.406); STD = (0.229, 0.224, 0.225)


# ---------------- data ----------------

def load_samples(data_dirs, split):
    out = []
    for d in data_dirs:
        with open(os.path.join(d, "samples.jsonl"), encoding="utf-8") as f:
            for line in f:
                s = json.loads(line)
                if s["split"] == split:
                    s["img"] = os.path.join(d, "images", s["id"] + ".jpg"); out.append(s)
    return out


def _gauss(heat, cx, cy, sx, sy):
    x0, x1 = int(max(0, math.floor(cx - 3 * sx))), int(min(OUT_W, math.ceil(cx + 3 * sx) + 1))
    y0, y1 = int(max(0, math.floor(cy - 3 * sy))), int(min(OUT_H, math.ceil(cy + 3 * sy) + 1))
    if x0 >= x1 or y0 >= y1:
        return
    xs = np.arange(x0, x1, dtype=np.float32) - cx; ys = np.arange(y0, y1, dtype=np.float32) - cy
    g = np.exp(-(xs[None, :] ** 2) / (2 * sx * sx) - (ys[:, None] ** 2) / (2 * sy * sy))
    np.maximum(heat[y0:y1, x0:x1], g, out=heat[y0:y1, x0:x1])


def encode(targets, ignore):
    heat = np.zeros((OUT_H, OUT_W), np.float32)
    weight = np.ones((OUT_H, OUT_W), np.float32)
    reg = np.zeros((4, OUT_H, OUT_W), np.float32)
    mask = np.zeros((OUT_H, OUT_W), np.float32)
    for l, t, r, b in ignore:
        weight[int(t / R):int(math.ceil(b / R)), int(l / R):int(math.ceil(r / R))] = 0
    for l, t, r, b in targets:
        w, h = r - l, b - t
        if w < 2 or h < 2:
            continue
        cx, cy = (l + r) / 2 / R, (b + t) / 2 / R
        ix, iy = int(cx), int(cy)
        if not (0 <= ix < OUT_W and 0 <= iy < OUT_H):
            continue
        _gauss(heat, ix, iy, max(0.4, w / R / 6), max(0.4, h / R / 6))
        heat[iy, ix] = 1.0
        reg[:, iy, ix] = (cx - ix, cy - iy, math.log(w / R), math.log(h / R)); mask[iy, ix] = 1
    weight[heat > 0.05] = 1  # an ignore box never hides a real target's neighbourhood
    return heat, weight, reg, mask


class ScreenSet(torch.utils.data.Dataset):
    def __init__(self, samples, train):
        self.s, self.train = samples, train

    def __len__(self):
        return len(self.s)

    def __getitem__(self, i):
        s = self.s[i]
        img = Image.open(s["img"]).convert("RGB")
        tg = [list(b) for b in s["targets"]]; ig = [list(b) for b in s["ignore"] + s.get("controls", [])]
        if self.train:
            img, tg, ig = self.augment(img, tg, ig, s.get("scale", 1.0), s.get("w", IN_W))
        heat, weight, reg, mask = encode(tg, ig)
        x = torch.from_numpy(np.asarray(img, np.float32) / 255.0).permute(2, 0, 1)
        return x, torch.from_numpy(heat)[None], torch.from_numpy(weight)[None], torch.from_numpy(reg), torch.from_numpy(mask)

    @staticmethod
    def augment(img, tg, ig, _scale, _w):
        # Content occupies the top-left of the letterboxed canvas; find it to rescale/shift without the padding.
        arr = np.asarray(img)
        rows = np.where((np.abs(arr.astype(np.int16) - 114).sum(2) > 6).any(1))[0]
        cols = np.where((np.abs(arr.astype(np.int16) - 114).sum(2) > 6).any(0))[0]
        cw = (cols[-1] + 1) if len(cols) else IN_W; ch = (rows[-1] + 1) if len(rows) else IN_H
        content = img.crop((0, 0, cw, ch))
        k = random.uniform(0.75, 1.3)
        nw, nh = max(8, int(cw * k)), max(8, int(ch * k))
        content = content.resize((nw, nh), Image.BILINEAR)
        ox = random.randint(min(0, IN_W - nw), max(0, IN_W - nw)); oy = random.randint(min(0, IN_H - nh), max(0, IN_H - nh))
        canvas = Image.new("RGB", (IN_W, IN_H), tuple(random.randint(0, 255) for _ in range(3)) if random.random() < 0.3 else (114, 114, 114))
        canvas.paste(content, (ox, oy))
        mv = lambda b: [b[0] * k + ox, b[1] * k + oy, b[2] * k + ox, b[3] * k + oy]

        def keep(bs, need):
            out = []
            for b in map(mv, bs):
                c = [max(0, b[0]), max(0, b[1]), min(IN_W, b[2]), min(IN_H, b[3])]
                a0 = (b[2] - b[0]) * (b[3] - b[1]); a1 = max(0, c[2] - c[0]) * max(0, c[3] - c[1])
                if a0 > 0 and a1 / a0 >= need:
                    out.append(c)
            return out
        tg, ig = keep(tg, 0.6), keep(ig, 0.01)
        img = canvas
        if random.random() < 0.3:
            img = ImageOps.mirror(img)
            tg = [[IN_W - b[2], b[1], IN_W - b[0], b[3]] for b in tg]; ig = [[IN_W - b[2], b[1], IN_W - b[0], b[3]] for b in ig]
        if random.random() < 0.2:
            img = ImageOps.invert(img)  # dark themes
        if random.random() < 0.1:
            img = ImageOps.grayscale(img).convert("RGB")
        a = np.asarray(img, np.float32)
        a = (a - 128) * random.uniform(0.7, 1.3) + 128 + random.uniform(-25, 25)
        img = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
        return img, tg, ig


# ---------------- model ----------------

class Detector(nn.Module):
    def __init__(self, backbone="mobilenetv3_large_100", pretrained=True, ch=64):
        super().__init__()
        import timm
        self.body = timm.create_model(backbone, pretrained=pretrained, features_only=True, out_indices=(1, 2, 3, 4))
        chs = self.body.feature_info.channels()
        self.lat = nn.ModuleList(nn.Conv2d(c, ch, 1) for c in chs)
        self.smooth = nn.Sequential(nn.Conv2d(ch, ch, 3, padding=1, bias=False), nn.BatchNorm2d(ch), nn.ReLU(inplace=True),
                                    nn.Conv2d(ch, ch, 3, padding=1, bias=False), nn.BatchNorm2d(ch), nn.ReLU(inplace=True))
        def head(n):
            return nn.Sequential(nn.Conv2d(ch, ch, 3, padding=1), nn.ReLU(inplace=True), nn.Conv2d(ch, n, 1))
        self.heat, self.reg = head(1), head(4)
        nn.init.constant_(self.heat[-1].bias, -2.19)
        self.register_buffer("mean", torch.tensor(MEAN).view(1, 3, 1, 1)); self.register_buffer("std", torch.tensor(STD).view(1, 3, 1, 1))

    def forward(self, x):  # x: 0..1 RGB
        fs = self.body((x - self.mean) / self.std)
        y = self.lat[-1](fs[-1])
        for f, lat in zip(reversed(fs[:-1]), reversed(self.lat[:-1])):
            y = F.interpolate(y, size=f.shape[-2:], mode="nearest") + lat(f)
        y = self.smooth(y)
        return self.heat(y), self.reg(y)


class Exported(nn.Module):
    """The graph the phone runs: decode included, NMS replaced by a 3x3 max-pool peak test."""
    def __init__(self, det):
        super().__init__()
        self.det = det
        gy, gx = torch.meshgrid(torch.arange(OUT_H, dtype=torch.float32), torch.arange(OUT_W, dtype=torch.float32), indexing="ij")
        self.register_buffer("gx", gx[None, None]); self.register_buffer("gy", gy[None, None])

    def forward(self, image):
        h, r = self.det(image)
        s = torch.sigmoid(h)
        peak = (F.max_pool2d(s, 3, 1, 1) == s).to(s.dtype)
        score = s * peak
        cx = (self.gx + r[:, 0:1]) * R; cy = (self.gy + r[:, 1:2]) * R
        w = torch.exp(r[:, 2:3].clamp(-4, 6)) * R; hh = torch.exp(r[:, 3:4].clamp(-4, 6)) * R
        return score, torch.cat([cx, cy, w, hh], 1)


def focal(logit, gt, weight):
    p = torch.sigmoid(logit.float()).clamp(1e-4, 1 - 1e-4)
    pos = gt.eq(1).float()
    pos_l = torch.log(p) * (1 - p) ** 2 * pos
    neg_l = torch.log(1 - p) * p ** 2 * (1 - gt) ** 4 * (1 - pos) * weight
    return -(pos_l.sum() + neg_l.sum()) / pos.sum().clamp(min=1)


def loss_fn(out, heat, weight, reg, mask):
    lh, lr = out
    l_heat = focal(lh, heat, weight)
    m = mask[:, None]
    l_reg = (F.l1_loss(lr.float() * m, reg * m, reduction="sum")) / m.sum().clamp(min=1)
    return l_heat + l_reg, l_heat.detach(), l_reg.detach()


# ---------------- evaluation ----------------

def decode(score, box, thr, topk=100):
    """score [1,H,W], box [4,H,W] (numpy) -> list of (score, l, t, r, b) in input pixels."""
    s = score.reshape(-1); idx = np.argsort(-s)[:topk]; idx = idx[s[idx] >= thr]
    b = box.reshape(4, -1)[:, idx]
    return [(float(s[i]), float(b[0, j] - b[2, j] / 2), float(b[1, j] - b[3, j] / 2), float(b[0, j] + b[2, j] / 2), float(b[1, j] + b[3, j] / 2))
            for j, i in enumerate(idx)]


def _inside(x, y, b):
    return b[0] <= x <= b[2] and b[1] <= y <= b[3]


def score_screen(dets, s):
    """The question that matters for an agent: tapping the centre of each detection, what do we hit?
    hit:  a ground-truth textless target has a detection whose centre is inside it ("found and tap lands")
    good: a detection's centre lands on a target or a text-bearing control (tappable either way)"""
    hit = sum(1 for g in s["targets"] if any(_inside((d[1] + d[3]) / 2, (d[2] + d[4]) / 2, g) for d in dets))
    good = sum(1 for d in dets if any(_inside((d[1] + d[3]) / 2, (d[2] + d[4]) / 2, g) for g in s["targets"] + s.get("controls", []) + s["ignore"]))
    return hit, len(s["targets"]), good, len(dets)


def evaluate(predict, samples, thrs=(0.2, 0.3, 0.4, 0.5, 0.6)):
    agg = {t: [0, 0, 0, 0] for t in thrs}
    for s in samples:
        x = np.asarray(Image.open(s["img"]).convert("RGB"), np.float32)[None].transpose(0, 3, 1, 2) / 255.0
        score, box = predict(x)
        for t in thrs:
            r = score_screen(decode(score[0], box[0], t), s)
            agg[t] = [a + b for a, b in zip(agg[t], r)]
    res = {}
    for t, (h, n, g, d) in agg.items():
        rec, prec = h / max(n, 1), g / max(d, 1)
        res[str(t)] = {"hit_rate": round(rec, 4), "lands_on_tappable": round(prec, 4),
                       "f1": round(2 * rec * prec / max(rec + prec, 1e-9), 4), "dets_per_screen": round(d / max(len(samples), 1), 2),
                       "targets": n}
    best = max(res, key=lambda k: res[k]["f1"])
    return {"screens": len(samples), "by_threshold": res, "best_threshold": float(best)}


def torch_predictor(model, device):
    exp = Exported(model).to(device).eval()

    @torch.no_grad()
    def f(x):
        s, b = exp(torch.from_numpy(x).to(device))
        return s.float().cpu().numpy(), b.float().cpu().numpy()
    return f


def onnx_predictor(path, threads=1):
    import onnxruntime as ort
    so = ort.SessionOptions(); so.intra_op_num_threads = threads
    sess = ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])
    return lambda x: sess.run(None, {"image": x.astype(np.float32)})


# ---------------- commands ----------------

def build_model(args, pretrained):
    return Detector(args.backbone, pretrained=pretrained)


def cmd_train(a):
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    data = a.data.split(",")
    tr, va = load_samples(data, "train"), load_samples(data, "val")
    if a.extra:  # our own modern screens (emulator / phone): repeat so a few hundred screens still count
        ex = a.extra.split(",")
        tr += load_samples(ex, "train") * a.extra_repeat; va += load_samples(ex, "val")
    print(f"train {len(tr)} screens, val {len(va)} screens, device {dev}", flush=True)
    os.makedirs(a.ckpt, exist_ok=True)
    model = build_model(a, pretrained=True).to(dev)
    from timm.utils import ModelEmaV3
    ema = ModelEmaV3(model, decay=0.9998, use_warmup=True)
    opt = torch.optim.AdamW(model.parameters(), lr=a.lr, weight_decay=0.01)
    dl = torch.utils.data.DataLoader(ScreenSet(tr, True), batch_size=a.batch, shuffle=True, num_workers=a.workers,
                                     pin_memory=dev == "cuda", drop_last=True, persistent_workers=a.workers > 0)
    steps = a.epochs * len(dl); warm = min(1000, steps // 20)
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda i: min(1, (i + 1) / max(warm, 1)) * 0.5 * (1 + math.cos(math.pi * min(i, steps) / steps)))
    bf16 = dev == "cuda" and torch.cuda.is_bf16_supported()
    scaler = torch.amp.GradScaler(enabled=dev == "cuda" and not bf16)
    start, best = 0, -1.0
    last = os.path.join(a.ckpt, "last.pt")
    if os.path.exists(last):
        ck = torch.load(last, map_location=dev, weights_only=False)
        model.load_state_dict(ck["model"]); ema.module.load_state_dict(ck["ema"]); opt.load_state_dict(ck["opt"])
        sched.load_state_dict(ck["sched"]); scaler.load_state_dict(ck["scaler"]); start, best = ck["epoch"] + 1, ck["best"]
        print(f"resumed at epoch {start}, best f1 {best:.4f}", flush=True)
    random.seed(0); va_sub = random.sample(va, min(len(va), a.val_screens))
    t0 = time.time()
    for ep in range(start, a.epochs):
        model.train(); te = time.time(); n = 0; acc = np.zeros(3)
        for x, heat, weight, reg, mask in dl:
            x, heat, weight, reg, mask = (t.to(dev, non_blocking=True) for t in (x, heat, weight, reg, mask))
            with torch.autocast(dev, dtype=torch.bfloat16 if bf16 else torch.float16, enabled=dev == "cuda"):
                out = model(x)
            loss, lh, lr = loss_fn(out, heat, weight, reg, mask)
            opt.zero_grad(set_to_none=True)
            scaler.scale(loss).backward(); scaler.unscale_(opt); nn.utils.clip_grad_norm_(model.parameters(), 10.0)
            scaler.step(opt); scaler.update(); sched.step(); ema.update(model)
            acc += (loss.item(), lh.item(), lr.item()); n += 1
            if n % 200 == 0:
                print(f"  ep {ep} step {n}/{len(dl)} loss {acc[0] / n:.3f} (heat {acc[1] / n:.3f} reg {acc[2] / n:.3f})", flush=True)
            if a.max_steps and n >= a.max_steps:
                break
        m = evaluate(torch_predictor(ema.module, dev), va_sub)
        f1 = m["by_threshold"][str(m["best_threshold"])]["f1"]
        print(f"epoch {ep}: loss {acc[0] / max(n, 1):.3f}, {time.time() - te:.0f}s, val {json.dumps(m['by_threshold'][str(m['best_threshold'])])} @ {m['best_threshold']}", flush=True)
        state = {"model": model.state_dict(), "ema": ema.module.state_dict(), "opt": opt.state_dict(), "sched": sched.state_dict(),
                 "scaler": scaler.state_dict(), "epoch": ep, "best": max(best, f1), "backbone": a.backbone, "val": m}
        torch.save(state, last + ".tmp"); os.replace(last + ".tmp", last)
        if f1 > best:
            best = f1
            torch.save({"ema": ema.module.state_dict(), "backbone": a.backbone, "epoch": ep, "val": m}, os.path.join(a.ckpt, "best.pt"))
            print(f"  new best f1 {f1:.4f} -> best.pt", flush=True)
        if a.max_hours and (time.time() - t0) / 3600 > a.max_hours:
            print(f"time budget {a.max_hours} h reached after epoch {ep}; run again to continue", flush=True)
            break


def _load(path, dev="cpu"):
    ck = torch.load(path, map_location=dev, weights_only=False)
    m = Detector(ck.get("backbone", "mobilenetv3_large_100"), pretrained=False)
    m.load_state_dict(ck["ema"]); return m.eval()


def cmd_eval(a):
    data = a.data.split(",")
    va = load_samples(data, "val")
    if a.limit:
        random.seed(0); va = random.sample(va, min(len(va), a.limit))
    pred = onnx_predictor(a.onnx) if a.onnx else torch_predictor(_load(a.ckpt, "cuda" if torch.cuda.is_available() else "cpu"),
                                                                "cuda" if torch.cuda.is_available() else "cpu")
    m = evaluate(pred, va)
    print(json.dumps(m, indent=1))
    if a.out:
        with open(a.out, "w") as f:
            json.dump(m, f, indent=1)


def cmd_export(a):
    import onnx
    os.makedirs(a.out, exist_ok=True)
    exp = Exported(_load(a.ckpt)).eval()
    fp32 = os.path.join(a.out, "detector.onnx")
    torch.onnx.export(exp, torch.zeros(1, 3, IN_H, IN_W), fp32, input_names=["image"], output_names=["score", "box"],
                      opset_version=17, do_constant_folding=True, dynamo=False)
    onnx.checker.check_model(onnx.load(fp32))
    # fp16 STORAGE, fp32 compute, made by the same script as the OCR models: plain fp16 graphs broke on the phone's
    # arm64 onnxruntime (MaxPool kernel, 1.4.0-beta1), and this graph has a MaxPool.
    import subprocess, sys
    fp16 = os.path.join(a.out, "detector.fp16.onnx")
    subprocess.run([sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ocr-bench", "mkstore16.py"), fp32, fp16], check=True)
    # Parity + speed on this machine's onnxruntime (install the app's version, see the notebook).
    import onnxruntime as ort
    x = np.random.RandomState(0).rand(1, 3, IN_H, IN_W).astype(np.float32)
    with torch.no_grad():
        ref = exp(torch.from_numpy(x))[0].numpy()
    info = {"onnxruntime": ort.__version__}
    for name, p in (("fp32", fp32), ("fp16", fp16)):
        run = onnx_predictor(p, threads=1)
        s, _ = run(x)
        for _ in range(3):
            run(x)
        t = time.time()
        for _ in range(10):
            run(x)
        # Compare local maxima (3x3 pooled), not the peak-masked map: a tie that flips which cell is the peak is
        # not a different answer.
        pool = lambda v: F.max_pool2d(torch.from_numpy(np.asarray(v, np.float32)), 3, 1, 1).numpy()
        info[name] = {"mb": round(os.path.getsize(p) / 1e6, 2), "ms_1thread_here": round((time.time() - t) * 100, 1),
                      "max_peak_diff": float(np.abs(pool(s) - pool(ref)).max())}
    print(json.dumps(info, indent=1))
    with open(os.path.join(a.out, "export.json"), "w") as f:
        json.dump(info, f, indent=1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["train", "eval", "export"])
    ap.add_argument("--data", default=""); ap.add_argument("--extra", default=""); ap.add_argument("--extra-repeat", type=int, default=5)
    ap.add_argument("--ckpt", default="ckpt"); ap.add_argument("--out", default="")
    ap.add_argument("--onnx", default=""); ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--backbone", default="mobilenetv3_large_100")
    ap.add_argument("--epochs", type=int, default=40); ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--lr", type=float, default=2e-3); ap.add_argument("--workers", type=int, default=os.cpu_count() or 2)
    ap.add_argument("--max-hours", type=float, default=0); ap.add_argument("--max-steps", type=int, default=0)
    ap.add_argument("--val-screens", type=int, default=1500)
    a = ap.parse_args()
    {"train": cmd_train, "eval": cmd_eval, "export": cmd_export}[a.cmd](a)


if __name__ == "__main__":
    main()
