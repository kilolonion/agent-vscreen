"""One labelling rule for the textless-element detector, shared by every data source.

A "target" is something a finger can tap that OCR cannot name: an actionable accessibility node (clickable,
long-clickable or checkable), visible, of tap size, whose whole subtree carries no visible text. Actionable nodes
that DO carry text become "ignore" boxes: OCR already finds them, and an icon inside one (a bottom-nav icon above
its label) is neither a positive nor a negative example, so training leaves those pixels out of the loss.

Sources are parsed into the same plain node tree first, then labelled by `extract`, so RICO, `uiautomator dump`
from an emulator or a phone, and later the app's own records all mean the same thing by "target":
  - parse_rico(json dict)        RICO view hierarchies (unique_uis/combined/<id>.json)
  - parse_uiautomator(xml text)  `uiautomator dump` output
Nothing here knows any particular app.

Sample format written by the converters (one JSON per screen, next to its image):
  {"id": str, "src": str, "w": int, "h": int, "targets": [[l,t,r,b], ...], "controls": [...], "ignore": [...]}
Boxes are in the image's own pixels.
"""
import re
import xml.etree.ElementTree as ET

# Tap-size and shape limits, as fractions of the screen so they hold on any resolution.
MIN_SIDE = 0.018      # of screen width: ~19 px on a 1080 px screen (a 7 dp glyph is not a target)
MAX_AREA = 0.12       # of screen area: bigger actionable nodes are rows, cards, whole panes
MAX_ASPECT = 8.0      # long thin bars are rows/dividers, not icons
SAME_BOX_IOU = 0.7    # a clickable wrapper around one clickable icon is one target (keep the inner box)


class Node:
    __slots__ = ("box", "text", "actionable", "visible", "cls", "children")

    def __init__(self, box, text, actionable, visible, cls):
        self.box, self.text, self.actionable, self.visible, self.cls = box, text, actionable, visible, cls
        self.children = []


def _truthy(v):
    return v is True or v == "true" or v == 1


# ---------- parsers ----------

def parse_rico(doc):
    """RICO view hierarchy JSON -> (root Node, (screen_w, screen_h) in hierarchy coordinates)."""
    root = (doc.get("activity") or {}).get("root") or doc.get("root")
    if not root:
        return None, None

    def walk(d):
        if not isinstance(d, dict):
            return None
        b = d.get("bounds") or [0, 0, 0, 0]
        vis = d.get("visible-to-user")
        visible = (vis is None or _truthy(vis)) and d.get("visibility", "visible") == "visible"
        text = d.get("text") or ""
        if not isinstance(text, str):
            text = str(text)
        act = _truthy(d.get("clickable")) or _truthy(d.get("long-clickable")) or _truthy(d.get("checkable"))
        n = Node([int(b[0]), int(b[1]), int(b[2]), int(b[3])], text, act, visible, str(d.get("class") or ""))
        for c in d.get("children") or []:
            k = walk(c)
            if k is not None:
                n.children.append(k)
        return n

    r = walk(root)
    return r, (r.box[2] - r.box[0], r.box[3] - r.box[1])


_B = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def parse_uiautomator(xml_text):
    """`uiautomator dump` XML -> (root Node, None). Several windows (a dialog over an app) become siblings."""
    top = ET.fromstring(xml_text)

    def walk(e):
        m = _B.match(e.get("bounds", "") or "")
        box = [int(m.group(i)) for i in range(1, 5)] if m else [0, 0, 0, 0]
        act = any(e.get(k) == "true" for k in ("clickable", "long-clickable", "checkable"))
        visible = e.get("visible-to-user", "true") != "false"
        n = Node(box, e.get("text", "") or "", act, visible, e.get("class", "") or "")
        for c in e.findall("node"):
            n.children.append(walk(c))
        return n

    root = Node([0, 0, 0, 0], "", False, True, "hierarchy")
    for c in top.findall("node"):
        root.children.append(walk(c))
    return root, None


# ---------- the rule ----------

def _has_text(n):
    if n.text.strip():
        return True
    return any(_has_text(c) for c in n.children if c.visible)


def _iou(a, b):
    ix = max(0, min(a[2], b[2]) - max(a[0], b[0])); iy = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    u = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / u if u > 0 else 0.0


def extract(root, screen_w, screen_h, scale=1.0):
    """Label one screen. `scale` maps hierarchy coordinates to image pixels. Returns (targets, controls, ignore):
    targets = textless actionable elements; controls = tap-size actionable elements that carry text (OCR names
    them; the YOLO models learn them as the same "tappable" class); ignore = everything left out of the loss."""
    W, H = screen_w, screen_h
    found, controls, ignore = [], [], []  # found: (box, depth)

    def clip(b):
        return [max(0, b[0]), max(0, b[1]), min(W, b[2]), min(H, b[3])]

    def walk(n, depth):
        if not n.visible:
            return
        if n.actionable and "EditText" not in n.cls:
            b = clip([round(v * scale) for v in n.box])
            w, h = b[2] - b[0], b[3] - b[1]
            if w > 0 and h > 0:
                tap_size = min(w, h) >= MIN_SIDE * W and w * h <= MAX_AREA * W * H and max(w / h, h / w) <= MAX_ASPECT
                if _has_text(n):
                    (controls if tap_size else ignore).append(b)
                elif tap_size:
                    found.append((b, depth))
        elif "Image" in n.cls and not n.text.strip():
            # A picture nobody marked actionable may still be a tappable icon (its listener sits on a parent
            # that also holds other things, or the app never set the flags). Not a positive, but not a
            # negative either.
            b = clip([round(v * scale) for v in n.box])
            w, h = b[2] - b[0], b[3] - b[1]
            if w > 0 and h > 0 and w * h <= MAX_AREA * W * H:
                ignore.append(b)
        for c in n.children:
            walk(c, depth + 1)

    walk(root, 0)
    # Nested near-identical targets are one element: keep the deepest (tightest) box.
    found.sort(key=lambda t: -t[1])
    targets = []
    for b, _ in found:
        if all(_iou(b, k) < SAME_BOX_IOU for k in targets):
            targets.append(b)
    ignore = [b for b in ignore if all(_iou(b, k) < SAME_BOX_IOU for k in targets)]
    return targets, controls, ignore


def rico_sample(doc, img_w, img_h, sid):
    """RICO screen -> sample dict, or None when the hierarchy does not describe this screenshot.

    RICO hierarchies are in 1440-wide coordinates while screenshots are 1080 or 540 wide; the scale comes from the
    root width. The root may stop above the navigation bar (1440x2392 over a full 2560 screenshot), which is fine.
    Dropped: landscape, and roots that are not the full-width app window (a dialog's own hierarchy would leave
    the screen under it unlabelled, teaching the model that visible buttons there are background)."""
    root, size = parse_rico(doc)
    if root is None or not size[0] or not size[1] or img_w >= img_h:
        return None
    if root.box[0] != 0 or root.box[1] != 0:
        return None
    s = img_w / size[0]
    if not (0.85 * img_h <= size[1] * s <= 1.02 * img_h) or size[0] < 1000:
        return None
    t, c, ig = extract(root, img_w, img_h, s)
    return {"id": sid, "src": "rico", "w": img_w, "h": img_h, "targets": t, "controls": c, "ignore": ig}


def uiautomator_sample(xml_text, img_w, img_h, sid, src):
    root, _ = parse_uiautomator(xml_text)
    t, c, ig = extract(root, img_w, img_h, 1.0)
    return {"id": sid, "src": src, "w": img_w, "h": img_h, "targets": t, "controls": c, "ignore": ig}
