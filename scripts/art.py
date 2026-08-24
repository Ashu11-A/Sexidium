#!/usr/bin/env python3
"""Sexidium art toolchain — every Python art/asset script unified behind one CLI.

This single file replaces the former scripts/*.py set. Each old script is now a
subcommand; run `python3 scripts/art.py <command> --help` for its options.

  icon sheet pipeline (needs numpy + scipy + Pillow, e.g. the .venv-icons venv):
    extract        detect + de-key + cut icons/input.png  -> /tmp/icons_raw/*.png
    name-zip       name + classify the raw icons          -> icons/sexidium-icons.zip
    montage        preview sheets (idx|pos|proof)          -> /tmp/{sec,pos,proof}_*.png
    debug          detection diagnostics + overlays        -> /tmp/debug_boxes.png, q_*.png

  icon sheet pipeline (cont.):
    cut-icons      flood-key + cut UI/icons_minigames_experiences.png
                   -> assets/icons/{minigames,experiences,ui}/* (+ experiences/*_disabled)

  menu / chest art (needs only Pillow):
    gen-menu-art     import assets/icons/<section>/* verbatim   -> assets/item/<section>/*
    bake-medieval    import UltimateGUI medieval generic_<slots> -> assets/ui/chest/chest_<rows>.png
    tile-backgrounds split 768px backgrounds into <=256px row strips (font-atlas ceiling)
    slice-typography slice medieval font sheets -> per-char     -> assets/item/font_{title,button}/*
    bake-overhang    ornate overhang chest frames (legacy)      -> assets/ui/chest/chest_<rows>.png
    cut-chest        cut UI/slots/* flat slot frames            -> assets/ui/chest/chest_<rows>.png
    extend-chest     extend cut frames over player inventory    -> assets/ui/chest/chest_<rows>.png
    align-chest      warp cut frames onto the real slot grid    -> assets/ui/chest/chest_<rows>.png

  neural super-resolution (needs onnxruntime + numpy; run in .venv-rembg):
    upscale-sources  Real-ESRGAN x4 the 256px medieval sources -> Medieval/upscaled/* + card.png

bake-medieval is the live chest-background generator (it prefers the Real-ESRGAN
upscaled sources from `upscale-sources`, falling back to a LANCZOS upscale of the
256px masters; supersedes the purple bake-overhang and cut/extend/align lineage).

Heavy deps are imported lazily per command: only `extract`/`debug` touch
numpy/scipy and `upscale-sources` touches numpy+onnxruntime, while the Pillow-only
commands never import them — so a Pillow-only host (e.g. init-paper.sh, which probes
just `import PIL`) can still bake art.

Pure Pillow/numpy/scipy + stdlib; deterministic. Re-run after editing sources.
"""

import argparse
import glob
import json
import os
import shutil
import zipfile
from collections import Counter

# ---------------------------------------------------------------------------
# Lazy heavy-dependency binders. Each command pulls in exactly what it needs so
# the Pillow-only commands never require numpy/scipy (and vice versa). The
# detection helpers below reference the module globals these binders populate.
# ---------------------------------------------------------------------------
np = None
ndimage = None
Image = ImageDraw = ImageFont = None


def _need_numpy():
    """Bind numpy + scipy.ndimage as module globals (extract / debug only)."""
    global np, ndimage
    if np is None:
        import numpy as _np
        from scipy import ndimage as _nd
        np, ndimage = _np, _nd


def _need_numpy_only():
    """Bind numpy alone (no scipy) — for the ONNX upscaler, run in the onnxruntime venv (.venv-rembg)."""
    global np
    if np is None:
        import numpy as _np
        np = _np


def _need_pil():
    """Bind Pillow's Image/ImageDraw/ImageFont as module globals."""
    global Image, ImageDraw, ImageFont
    if Image is None:
        from PIL import Image as _I, ImageDraw as _D, ImageFont as _F
        Image, ImageDraw, ImageFont = _I, _D, _F


ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

# Shared chest geometry / colour, mirrored from MenuArt.CHEST_* (kept in sync across
# the cut/extend/align/bake commands so frames and screens hide the inventory uniformly).
CHEST_DIR = os.path.join(ROOT, "assets", "ui", "chest")
WIDTH = 176          # chest GUI width (GUI px)
# Components.OPAQUE_BG = 0xFF2D1F4D — the solid base that hides the chest/player slot grid (same colour
# the SceneBaker pads screens with). Keep in sync with the Java constant.
OPAQUE_BG = (0x2D, 0x1F, 0x4D, 0xFF)


# ===========================================================================
# Shared icon-sheet helpers (section boundaries + reading order)
# ---------------------------------------------------------------------------
# Kept in one place so the section boundaries and reading order can never drift
# between the extractor, the montage previews and the namer.
# ===========================================================================

# section boundaries proven to split the 2048x2048 sheet into the documented
# 35 / 15 / 44 / 60 / 37 / 39 groups (asymmetric: cursors are only 3 rows tall,
# elo ranks are 5, so the left/right columns use different y splits).
X_SPLIT = 970
Y_ELO = 720      # left column: elo_ranks above, system below
Y_CURSOR = 360   # right column: cursors above, currency below
Y_BOTTOM = 1360  # gui_buttons / font start


def section(box):
    x = (box[0] + box[2]) / 2
    y = (box[1] + box[3]) / 2
    if x < X_SPLIT:
        return "elo_ranks" if y < Y_ELO else ("system" if y < Y_BOTTOM else "gui_buttons")
    return "cursors" if y < Y_CURSOR else ("currency" if y < Y_BOTTOM else "font")


def reading_order(items):
    """Sort boxes top->bottom, then left->right within a ~row band (55px)."""
    items = sorted(items, key=lambda b: (b["box"][1] + b["box"][3]) / 2)
    rows, cur, last = [], [], None
    for b in items:
        cy = (b["box"][1] + b["box"][3]) / 2
        if last is None or cy - last < 55:
            cur.append(b)
        else:
            rows.append(cur); cur = [b]
        last = cy
    if cur:
        rows.append(cur)
    out = []
    for r in rows:
        out += sorted(r, key=lambda b: b["box"][0])
    return out


# ===========================================================================
# extract — detect + de-key + cut icons/input.png into per-icon PNGs
# ---------------------------------------------------------------------------
# Extract individual icons from a magenta-keyed pixel-art sprite sheet.
#
# Pipeline (all locality-aware, no hard-coded grid):
#   1. Background key   magenta bg + magenta-*blended* pixels (anti-alias fringe
#                       and the semi-transparent Gemini watermark) -> alpha 0.
#   2. Despill          remaining edge pixels get their magenta tint neutralised.
#   3. Detect           connected components (area filtered to kill specks).
#   4. Local cell size  each component looks at its K nearest neighbours; the
#                       median neighbour w/h is the "expected cell" there. Because
#                       glued icons are always the local minority, the median stays
#                       the true single-icon size.
#   5. Split            a component whose bbox ~= n*cell is sliced into an
#                       round(w/cell_w) x round(h/cell_h) grid, each cut snapped to
#                       the deepest projection valley (exact count, never slivers).
#   6. Merge            a stray sub-cell-size fragment is unioned into its nearest
#                       neighbour (re-joins a split letter / detached highlight).
#   7. Drop             boxes that are still mostly magenta-tinted (a leftover
#                       watermark / non-icon smudge) are discarded.
# Each surviving box is tight-cropped from the despilled RGBA and written out.
# ===========================================================================

AREA_MIN = 60          # px; drops noise specks (false positives)
K_NEIGH = 15           # neighbours used to estimate the local cell size
PAD = 6                # transparent padding around each cropped icon
TINT_DROP = 0.18       # box dropped if this fraction of pixels is magenta-tinted
FRAG_FRAC = 0.5        # box is a "fragment" below this fraction of local cell
PEEL = 2               # boundary-despill iterations (rim pixels shaved per pass)


def build_masks(im):
    """Return (keep_mask, despilled_rgba). keep=True where a real icon pixel is.

    Magenta chroma-key removal in three stages so no pink rim survives:
      1. key out pure magenta + magenta-blended (fringe/watermark) pixels;
      2. boundary peel: a kept pixel touching the background that is *magenta-
         hued* (R and B both well above G, and R~=B) is contamination, not icon,
         so drop it. Repeated PEEL times to eat a 1-2px anti-alias ring. The
         |R-B|<70 + brightness gates protect real red (R>>B), blue (B>>R) and
         pink (R>>B) icon edges; only true magenta/purple-grey spill is shaved.
      3. despill any residual leaning pixel by lifting G to min(R,B) (neutralise).
    """
    R, G, B = im[:, :, 0].astype(int), im[:, :, 1].astype(int), im[:, :, 2].astype(int)
    base = (R > 200) & (B > 200) & (G < 70) & (abs(R - B) < 70)        # pure magenta
    tint = (R > 175) & (B > 175) & (abs(R - B) < 48) & (G < np.minimum(R, B) - 12)
    keep = ~(base | tint)

    # bright magenta blend (light anti-alias fringe). abs(R-B)<55 spares red (R>>B),
    # blue (B>>R) and pink (R>>B) icon edges; only true magenta spill is shaved.
    bright = (R > 150) & (B > 150) & (abs(R - B) < 55) & (G < np.minimum(R, B) - 25)
    for _ in range(PEEL):
        boundary = keep & ndimage.binary_dilation(~keep)
        keep = keep & ~(boundary & bright)

    # residual *dark* magenta fringe (between a black outline and the key) is too dim
    # for the bright test. Neutralise only the edge ring to gray=min(R,G,B): it reads
    # as a natural dark outline, never pink, and icon interiors stay their true colour.
    out = im.copy()
    hued = (R > G + 25) & (B > G + 25) & (abs(R - B) < 60)
    boundary = keep & ndimage.binary_dilation(~keep)
    neut = boundary & hued
    gray = np.minimum(np.minimum(R, G), B)
    for ch in range(3):
        out[:, :, ch] = np.where(neut, gray, out[:, :, ch])
    out[:, :, 3] = np.where(keep, 255, 0).astype(np.uint8)
    return keep, out


def tint_fraction(keep_sub, R, G, B):
    if keep_sub.sum() == 0:
        return 1.0
    rr, gg, bb = R[keep_sub], G[keep_sub], B[keep_sub]
    return float(((rr > 180) & (bb > 180) & (gg < np.minimum(rr, bb) - 15)).mean())


def snap_cuts(cov, k, full):
    """k cut positions: equal-spaced guesses snapped to the nearest coverage valley."""
    if k <= 0:
        return []
    seg = full / (k + 1)
    cuts = []
    for i in range(1, k + 1):
        c = int(round(seg * i))
        win = max(4, int(seg * 0.45))
        lo, hi = max(1, c - win), min(full - 1, c + win)
        cuts.append(lo + int(np.argmin(cov[lo:hi])) if hi > lo else c)
    return sorted(set(cuts))


def detect(keep):
    lbl, n = ndimage.label(keep)
    sizes = np.bincount(lbl.ravel())
    slices = ndimage.find_objects(lbl)

    comps = []  # (x0,y0,x1,y1, submask)
    for i, s in enumerate(slices, 1):
        if s is None or sizes[i] < AREA_MIN:
            continue
        comps.append((s[1].start, s[0].start, s[1].stop, s[0].stop, lbl[s] == i))

    cx = np.array([(c[0] + c[2]) / 2 for c in comps])
    cy = np.array([(c[1] + c[3]) / 2 for c in comps])
    W = np.array([c[2] - c[0] for c in comps])
    H = np.array([c[3] - c[1] for c in comps])

    def local_cell(i):
        d = (cx - cx[i]) ** 2 + (cy - cy[i]) ** 2
        idx = np.argsort(d)[:K_NEIGH]
        return np.median(W[idx]), np.median(H[idx])

    boxes = []
    for i, (x0, y0, x1, y1, m) in enumerate(comps):
        h, w = m.shape
        lw, lh = local_cell(i)
        nc, nr = max(1, round(w / lw)), max(1, round(h / lh))
        if nc * nr <= 1:
            boxes.append((x0, y0, x1, y1))
            continue
        rb = [0] + snap_cuts(m.sum(1), nr - 1, h) + [h]
        cb = [0] + snap_cuts(m.sum(0), nc - 1, w) + [w]
        for a in range(len(rb) - 1):
            for c in range(len(cb) - 1):
                cell = m[rb[a]:rb[a + 1], cb[c]:cb[c + 1]]
                if cell.sum() < AREA_MIN:
                    continue
                ys, xs = np.where(cell)
                boxes.append((x0 + cb[c] + xs.min(), y0 + rb[a] + ys.min(),
                              x0 + cb[c] + xs.max() + 1, y0 + rb[a] + ys.max() + 1))
    return dedupe_contained(merge_fragments(boxes))


def dedupe_contained(boxes):
    """Drop a box that sits inside a bigger one (a peel-carved interior island of a
    solid magenta-hued button), independent of the global median size test."""
    order = sorted(range(len(boxes)), key=lambda i: -(
        (boxes[i][2] - boxes[i][0]) * (boxes[i][3] - boxes[i][1])))
    kept = []
    for i in order:
        x0, y0, x1, y1 = boxes[i]
        ix, iy = (x0 + x1) / 2, (y0 + y1) / 2
        area = (x1 - x0) * (y1 - y0)
        inside = False
        for kx0, ky0, kx1, ky1 in kept:
            if kx0 <= ix <= kx1 and ky0 <= iy <= ky1:
                ox = max(0, min(x1, kx1) - max(x0, kx0))
                oy = max(0, min(y1, ky1) - max(y0, ky0))
                if ox * oy > 0.6 * area:
                    inside = True
                    break
        if not inside:
            kept.append(boxes[i])
    return kept


def merge_fragments(boxes):
    """Union sub-cell fragments into their nearest neighbour."""
    boxes = list(boxes)
    cx = np.array([(b[0] + b[2]) / 2 for b in boxes])
    cy = np.array([(b[1] + b[3]) / 2 for b in boxes])
    W = np.array([b[2] - b[0] for b in boxes])
    H = np.array([b[3] - b[1] for b in boxes])
    med_w, med_h = np.median(W), np.median(H)

    alive = [True] * len(boxes)
    for i in range(len(boxes)):
        if not alive[i]:
            continue
        if W[i] >= FRAG_FRAC * med_w and H[i] >= FRAG_FRAC * med_h:
            continue
        d = (cx - cx[i]) ** 2 + (cy - cy[i]) ** 2
        d[i] = 1e18
        for j in (np.argsort(d)):
            if alive[j] and j != i:
                gap = (d[j]) ** 0.5
                if gap < max(med_w, med_h):
                    a, b = boxes[i], boxes[j]
                    boxes[j] = (min(a[0], b[0]), min(a[1], b[1]),
                                max(a[2], b[2]), max(a[3], b[3]))
                    alive[i] = False
                break
    return [b for b, ok in zip(boxes, alive) if ok]


def cmd_extract(args):
    _need_numpy()
    _need_pil()
    im = np.asarray(Image.open(args.inp).convert("RGBA"))
    R, G, B = im[:, :, 0].astype(int), im[:, :, 1].astype(int), im[:, :, 2].astype(int)
    keep, rgba = build_masks(im)
    boxes = detect(keep)

    # tint drop + reading order sort (row bands top->bottom, left->right)
    kept = []
    for x0, y0, x1, y1 in boxes:
        sub = keep[y0:y1, x0:x1]
        if tint_fraction(sub, R[y0:y1, x0:x1], G[y0:y1, x0:x1], B[y0:y1, x0:x1]) > TINT_DROP:
            continue
        kept.append((x0, y0, x1, y1))
    kept.sort(key=lambda b: (round((b[1] + b[3]) / 2 / 40), (b[0] + b[2]) / 2))

    os.makedirs(args.out, exist_ok=True)
    manifest = []
    src = Image.fromarray(rgba, "RGBA")
    for idx, (x0, y0, x1, y1) in enumerate(kept):
        crop = src.crop((x0, y0, x1, y1))
        canvas = Image.new("RGBA", (crop.width + 2 * PAD, crop.height + 2 * PAD), (0, 0, 0, 0))
        canvas.paste(crop, (PAD, PAD))
        fn = f"idx_{idx:03d}.png"
        canvas.save(os.path.join(args.out, fn))
        box = [int(x0), int(y0), int(x1), int(y1)]
        manifest.append({"idx": idx, "file": fn, "box": box,
                         "w": int(x1 - x0), "h": int(y1 - y0)})
    with open(os.path.join(args.out, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=1)
    print("icons extracted:", len(kept), "->", args.out)


# ===========================================================================
# montage — preview sheets of the extracted icons (sec_*/pos_*/proof_*)
# ---------------------------------------------------------------------------
# Reads the per-icon PNGs + manifest.json produced by `extract` and lays them out
# on a checkerboard (so transparency is visible) with labels:
#   idx   -> sec_<section>.png    cells labelled by GLOBAL idx
#   pos   -> pos_<section>.png    labelled by within-section reading-order POSITION
#   proof -> proof_<section>.png  labelled by descriptive NAME (from `name-zip`)
# Throwaway previews to eyeball detection / pick names; not shipped.
# ===========================================================================

COLS = {"elo_ranks": 7, "cursors": 5, "system": 8, "currency": 10,
        "gui_buttons": 8, "font": 8}
CELL = 160
LABEL = 28

_FONTS = ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
          "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]


def load_font(size):
    for p in _FONTS:
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def checker(w, h):
    im = Image.new("RGB", (w, h), (120, 120, 126))
    d = ImageDraw.Draw(im)
    for y in range(0, h, 16):
        for x in range(0, w, 16):
            if (x // 16 + y // 16) % 2:
                d.rectangle([x, y, x + 15, y + 15], fill=(100, 100, 106))
    return im


def montage(cells, cols, cell=CELL, lab=LABEL):
    """cells: list of (icon_path, label_text)."""
    rows = (len(cells) + cols - 1) // cols
    W, H = cols * cell, rows * (cell + lab)
    cv = checker(W, H)
    d = ImageDraw.Draw(cv)
    font = load_font(20)
    for k, (path, label) in enumerate(cells):
        c, r = k % cols, k // cols
        x, y = c * cell, r * (cell + lab)
        ic = Image.open(path).convert("RGBA")
        s = min((cell - 14) / ic.width, (cell - 14) / ic.height, 1.0)
        ic = ic.resize((max(1, int(ic.width * s)), max(1, int(ic.height * s))), Image.NEAREST)
        cv.paste(ic, (x + (cell - ic.width) // 2, y + (cell - ic.height) // 2), ic)
        d.rectangle([x, y, x + cell - 1, y + cell + lab - 1], outline=(40, 40, 46), width=1)
        if len(label) > 20:
            label = label[:19] + "…"
        d.text((x + 5, y + cell + 2), label, fill=(255, 255, 0), font=font)
    return cv


def cmd_montage(args):
    _need_pil()
    manifest = json.load(open(os.path.join(args.raw, "manifest.json")))
    by_sec = {}
    for b in manifest:
        by_sec.setdefault(section(b["box"]), []).append(b)

    names = {}
    if args.mode == "proof":
        for r in json.load(open(os.path.join(args.named, "index.json"))):
            names[(r["section"], r["pos"])] = r["name"]

    for sec, items in by_sec.items():
        ordered = reading_order(items)
        cells = []
        for pos, b in enumerate(ordered):
            if args.mode == "idx":
                cells.append((os.path.join(args.raw, b["file"]), str(b["idx"])))
            elif args.mode == "pos":
                cells.append((os.path.join(args.raw, b["file"]), str(pos)))
            else:
                nm = names[(sec, pos)]
                cells.append((os.path.join(args.named, sec, nm + ".png"), nm))
        cv = montage(cells, COLS.get(sec, 8))
        cv.save(os.path.join(args.out, f"{args.mode if args.mode != 'idx' else 'sec'}_{sec}.png"))
        print(f"{args.mode}_{sec}: {len(ordered)} icons")


# ===========================================================================
# debug — detection diagnostics for the icon sheet (overlays + stats)
# ---------------------------------------------------------------------------
# Re-runs the `extract` detector and renders what it found so split/merge errors
# are easy to spot:
#   debug_boxes.png      whole sheet (downscaled) with every detected bbox in green
#   q_TL/TR/BL/BR.png    full-resolution quadrant overlays
#   + printed size histogram and per-section counts vs the documented targets.
# ===========================================================================

EXPECTED = {"elo_ranks": 35, "cursors": 15, "system": 44,
            "currency": 60, "gui_buttons": 37, "font": 39}


def cmd_debug(args):
    _need_numpy()
    _need_pil()
    im = np.asarray(Image.open(args.inp).convert("RGBA"))
    keep, _ = build_masks(im)
    boxes = detect(keep)

    ws = np.array([b[2] - b[0] for b in boxes])
    hs = np.array([b[3] - b[1] for b in boxes])
    print(f"detected: {len(boxes)}")
    print(f"w  min/median/max: {ws.min()}/{int(np.median(ws))}/{ws.max()}")
    print(f"h  min/median/max: {hs.min()}/{int(np.median(hs))}/{hs.max()}")
    print("size buckets (w*h):")
    areas = ws * hs
    for lo, hi in [(0, 3000), (3000, 6000), (6000, 10000), (10000, 20000), (20000, 1 << 30)]:
        print(f"  {lo:>6}-{hi:<8}: {int(((areas >= lo) & (areas < hi)).sum())}")

    counts = Counter(section(b) for b in boxes)
    print("\nsection            got  want  diff")
    for k in EXPECTED:
        g = counts[k]
        print(f"  {k:15} {g:4} {EXPECTED[k]:5} {g - EXPECTED[k]:+5}")

    over = Image.open(args.inp).convert("RGB")
    d = ImageDraw.Draw(over)
    for x0, y0, x1, y1 in boxes:
        d.rectangle([x0, y0, x1 - 1, y1 - 1], outline=(0, 255, 0), width=2)
    over.resize((1100, 1100)).save(os.path.join(args.out, "debug_boxes.png"))
    half = over.width // 2
    for qx, qy, name in [(0, 0, "TL"), (half, 0, "TR"), (0, half, "BL"), (half, half, "BR")]:
        over.crop((qx, qy, qx + half, qy + half)).save(os.path.join(args.out, f"q_{name}.png"))
    print(f"\nwrote debug_boxes.png + q_TL/TR/BL/BR.png to {args.out}")


# ===========================================================================
# name-zip — apply descriptive names to the extracted icons + bundle one zip
# ---------------------------------------------------------------------------
# Reads /tmp/icons_raw (output of `extract`), re-derives each icon's section +
# reading-order position (deterministic, so names bind to *what the icon is*, not
# to a fragile global index), renames every PNG and writes one classified zip:
#     <out>/<section>/<descriptive-name>.png   (inside the archive)
# Names per (section, position) were assigned by visually inspecting each icon.
# ===========================================================================

NAME_RAW = "/tmp/icons_raw"
NAMED_DIR = "/tmp/icons_named"
ZIP_PATH = os.path.join(ROOT, "icons", "sexidium-icons.zip")

# --- descriptive names, in reading-order position ---------------------------------
ELO_COLORS = ["onyx", "bronze", "silver", "gold", "emerald", "amethyst", "ruby"]
NAMES = {}
NAMES["elo_ranks"] = [f"rank_gem_{ELO_COLORS[i % 7]}_tier{i // 7 + 1}" for i in range(35)]

NAMES["cursors"] = [
    "cursor_arrow", "cursor_hand_point_gold", "cursor_cross_red", "cursor_target_green",
    "cursor_wheel_x", "cursor_hourglass_full", "cursor_hourglass_empty",
    "cursor_hand_point_white", "cursor_move_4way", "cursor_magnifier", "cursor_pencil",
    "cursor_x_red", "cursor_triangle_color", "cursor_leaf", "cursor_leaf_flat",
]

NAMES["system"] = [
    "mouse_left_click", "mouse_right_click", "dpad_keys_gold", "dpad_keys_silver",
    "dpad_keys", "key_ls_dark", "key_rs", "key_esc", "key_ls", "key_enter",
    "hourglass", "move_compass_green", "pencil_edit", "triangle_alert_green",
    "gear_settings", "windows_grid", "home", "undo", "redo", "delete_file_x",
    "square_empty", "shield", "check_green", "question_help", "wrench", "trash",
    "shield_blank", "shield_round", "shield_pointed", "shield_wide", "shield_dark",
    "plus_gold", "hand_point_gold", "hand_point", "hand_open", "hand_flat",
    "hand_grab", "hand_back", "hand_pinch", "hand_drag", "arrow_up",
    "arrow_right_gold", "arrow_left", "arrow_right",
]

NAMES["currency"] = [
    "coins_burst_gold", "pouch_brown", "pouch_coins_brown", "pouch_brown_full",
    "sack_spill_gold", "sack_spill_silver", "sack_spill_silver_2", "ore_nugget_grey",
    "coin_gold_blank", "coin_gold_blank_2", "coin_gold_100", "coin_gold_50",
    "coin_gold_10", "coin_gold_50_2", "coin_gold_100_2", "coin_silver_blank",
    "coin_silver_1", "coin_silver_10", "coin_silver_50", "coin_silver_100",
    "coin_silver_50_2", "coin_silver_100_2", "coin_bronze_100", "coin_gold_0",
    "coin_gold_2", "coin_gold_3", "coin_gold_4", "coin_gold_5", "coin_gold_6",
    "coin_gold_8", "coin_gold_9", "coin_single_gold", "coins_few_gold",
    "coins_cluster_gold", "coins_pile_gold", "coins_heap_gold", "coin_single_silver",
    "coins_few_silver", "coins_cluster_silver", "coins_pile_mixed", "coins_pile_silver",
    "gem_diamond_blue", "gem_emerald_green", "pouch_leather", "nuggets_gold",
    "symbol_dollar_gold", "symbol_yen_gold", "symbol_euro_gold", "symbol_bitcoin_gold",
    "symbol_cent_gold", "cash_note_green", "cash_note_dollar_green", "cash_stack_green",
    "coin_gold_blank_3", "coin_bitcoin_gold", "coin_yuan_gold", "coin_star_gold",
    "cash_note_green_2", "cash_note_dollar_green_2", "cash_stack_green_2",
]

NAMES["gui_buttons"] = [
    "button_bar_red", "button_bar_teal", "button_bar_orange", "button_bar_green",
    "button_bar_purple", "button_bar_red_2", "button_menu_list_red", "button_mail_red",
    "button_map_red", "button_clock_red", "badge_star_gold", "badge_emblem_gold",
    "badge_crown_gold", "button_info_red", "button_check_red", "button_close_x_red",
    "button_arrow_blue", "badge_emblem_blue", "badge_dots_blue", "badge_crossed_blue",
    "badge_snowflake_blue", "badge_silver", "button_alert_red", "button_back_green",
    "button_shield_green", "badge_dots_green", "badge_emblem_green", "badge_leaf_green",
    "badge_emblem_green_2", "button_cap_brown", "button_arrow_up_orange",
    "button_arrow_down_orange", "button_trophy_brown", "button_blank_tan",
    "button_blank_brown", "badge_chevron_brown", "badge_chevrons_brown",
]

FONT_CHARS = ["A", "B", "C", "D", "E", "F", "G", "H", "H_alt", "I", "J", "K", "L",
              "M", "N", "O", "O_alt", "P", "Q", "R", "S", "S_alt", "T", "U", "V",
              "V_alt", "W", "X", "Y", "Z", "0", "1", "2", "4", "5", "6", "7", "8", "9"]
NAMES["font"] = [f"char_{c}" for c in FONT_CHARS]


def cmd_name_zip(args):
    manifest = json.load(open(os.path.join(NAME_RAW, "manifest.json")))
    by_sec = {}
    for b in manifest:
        by_sec.setdefault(section(b["box"]), []).append(b)

    if os.path.isdir(NAMED_DIR):
        shutil.rmtree(NAMED_DIR)
    os.makedirs(NAMED_DIR)

    mapping = []
    for sec, items in by_sec.items():
        ordered = reading_order(items)
        names = NAMES[sec]
        assert len(ordered) == len(names), f"{sec}: {len(ordered)} icons vs {len(names)} names"
        os.makedirs(os.path.join(NAMED_DIR, sec), exist_ok=True)
        seen = {}
        for pos, b in enumerate(ordered):
            name = names[pos]
            seen[name] = seen.get(name, 0) + 1
            if seen[name] > 1:
                name = f"{name}_{seen[name]}"
            dst = os.path.join(NAMED_DIR, sec, name + ".png")
            shutil.copyfile(os.path.join(NAME_RAW, b["file"]), dst)
            mapping.append({"section": sec, "pos": pos, "name": name,
                            "box": b["box"], "w": b["w"], "h": b["h"]})

    json.dump(mapping, open(os.path.join(NAMED_DIR, "index.json"), "w"), indent=1)

    os.makedirs(os.path.dirname(ZIP_PATH), exist_ok=True)
    zp = os.path.normpath(ZIP_PATH)
    with zipfile.ZipFile(zp, "w", zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(NAMED_DIR):
            for fn in sorted(files):
                full = os.path.join(root, fn)
                z.write(full, os.path.relpath(full, NAMED_DIR))
    print("named icons:", len(mapping))
    print("zip:", zp, f"({os.path.getsize(zp) // 1024} KB)")
    for sec in NAMES:
        print(f"  {sec}: {len(by_sec.get(sec, []))}")


# ===========================================================================
# cut-icons — cut the minigame/experience/ui sheet with a border-flood chroma key
# ---------------------------------------------------------------------------
# Cuts UI/icons_minigames_experiences.png into the named assets/icons/<section>/* set.
#
# Why NOT a salient-object AI matte (rembg isnet/birefnet): these are full-frame,
# magenta-keyed pixel-art icons where the WHOLE sprite is the subject and the only
# true background is the flat magenta. A salient model keeps the bright focal element
# and ERASES the dim non-salient parts (e.g. the portal's grey stone frame) — exactly
# the "erased visual elements" failure. The precise, lossless method for a chroma-keyed
# sheet is a BORDER FLOOD FILL: remove only magenta that is connected to the crop border,
# so every interior pixel (incl. magenta/purple sprite parts not touching the border) is
# kept. Edge anti-alias fringe is despilled (lift G to gray on the rim).
#
# Icons are grouped from the sheet by dilating the colour mask (parts of one icon are
# close; icons are far apart), in the same reading order the original name map was built
# in, then each box is flood-cut, square-padded (item_model needs square), and written by
# ICON_MAP name. Experience icons additionally get a greyscale "_disabled" variant (the
# GUI shows it for an un-selected twist).
# ===========================================================================

ICON_SHEET = os.path.join(ROOT, "UI", "icons_minigames_experiences.png")
ICONS_OUT = os.path.join(ROOT, "assets", "icons")
CUT_PAD = 6
CUT_PEEL = 2           # edge-peel passes that shave the magenta anti-alias fringe (kills the pink rim)
CUT_FRINGE_GREEN = 35  # green gate: rim pixels below this green are magenta fringe; solid purple sprite
                       # edges (portal/droplet/evolvingmobs) carry more green and are spared
DILATE_ITERS = 8       # connects the parts of one icon without bridging neighbours
ROW_BAND = 130         # reading-order row-band height (px) for the deterministic sort

# Deterministic idx -> (section, name) map. idx is the reading-order position of the box
# (top->bottom by ROW_BAND, then left->right) — kept verbatim so a re-cut re-binds names to
# the SAME sprite. 42 icons: 5 minigames, 16 experiences (no shrinkingachievements art on
# the sheet), 21 generic ui.
ICON_MAP = {
    7: ("minigames", "combat"), 9: ("minigames", "gather"), 10: ("minigames", "race"),
    11: ("minigames", "tntwar"), 8: ("minigames", "fugitive"),
    14: ("experiences", "randomizer"), 16: ("experiences", "sharedlife"),
    17: ("experiences", "sharedinventory"), 18: ("experiences", "xphealth"),
    21: ("experiences", "tntmobs"), 23: ("experiences", "evolvingmobs"),
    24: ("experiences", "breakonebreakall"), 25: ("experiences", "blockdeleter"),
    26: ("experiences", "randomchunks"), 27: ("experiences", "walkingblocks"),
    28: ("experiences", "chained"), 30: ("experiences", "cleave"),
    31: ("experiences", "growing"), 20: ("experiences", "jumpenchants"),
    32: ("experiences", "mobduplication"), 12: ("experiences", "doubledrops"),
    0: ("ui", "swords_duel"), 1: ("ui", "blueprint"), 2: ("ui", "book"),
    3: ("ui", "compass"), 4: ("ui", "banner_crest"), 5: ("ui", "players"),
    6: ("ui", "gear"), 13: ("ui", "droplet_purple"), 15: ("ui", "block_walking"),
    19: ("ui", "no_green"), 22: ("ui", "trophy"), 29: ("ui", "mobs_aoe"),
    33: ("ui", "sword_dash"), 34: ("ui", "banner_heal"), 35: ("ui", "friends_add"),
    36: ("ui", "mail"), 37: ("ui", "friend_heart"), 38: ("ui", "portal"),
    39: ("ui", "spectate"), 40: ("ui", "lock"), 41: ("ui", "banner"),
}


def _icon_boxes(im):
    """Reading-order bounding boxes of each icon on the magenta sheet (build_masks + dilation group)."""
    keep, _ = build_masks(im)
    dil = ndimage.binary_dilation(keep, iterations=DILATE_ITERS)
    lbl, n = ndimage.label(dil)
    boxes = []
    for i in range(1, n + 1):
        m = (lbl == i) & keep
        if m.sum() < 400:
            continue
        ys, xs = np.where(m)
        boxes.append((int(xs.min()), int(ys.min()), int(xs.max() + 1), int(ys.max() + 1)))
    boxes.sort(key=lambda b: (round((b[1] + b[3]) / 2 / ROW_BAND), (b[0] + b[2]) / 2))
    return boxes


def _flood_cut(rgb, box):
    """Cut one icon by MERGING two background-removal methods, so no pink rim survives:

      1. BORDER FLOOD (lossless silhouette): drop only magenta connected to the crop border, so every
         interior pixel — incl. magenta/purple sprite parts — is kept (a salient-object AI matte fails
         here: it erases dim non-salient parts like a grey frame).
      2. EDGE PEEL + DESPILL (the build_masks chroma cleanup, restricted to the kept silhouette's rim):
         the flood leaves an anti-alias fringe that is half sprite / half magenta and too weak to flood.
         Peel it: for CUT_PEEL passes, drop the boundary pixels that are bright TRUE magenta
         (R,B high, G low, |R-B|<55 so red/blue/purple sprite edges are spared), then neutralise any
         milder hued rim pixel by lifting G to gray. This is what kills the residual pink.

    Returns a square-padded RGBA Image.
    """
    R, G, B = rgb[:, :, 0], rgb[:, :, 1], rgb[:, :, 2]
    # magenta-ish: red+blue high, green clearly lower (covers flat bg + anti-alias fringe + the key's
    # purple-leaning blend) — but only the BORDER-connected run of it is treated as background.
    magenta = (R > 110) & (B > 110) & (G < R - 35) & (G < B - 35)
    x0, y0, x1, y1 = box
    pad = 2
    sx0, sy0 = max(0, x0 - pad), max(0, y0 - pad)
    sx1, sy1 = min(rgb.shape[1], x1 + pad), min(rgb.shape[0], y1 + pad)
    sub = magenta[sy0:sy1, sx0:sx1]
    h, w = sub.shape
    border = np.zeros_like(sub)
    border[0, :] = border[-1, :] = border[:, 0] = border[:, -1] = True
    lab, _ = ndimage.label(sub)
    bg_labels = set(int(v) for v in np.unique(lab[sub & border]) if v > 0)
    bg = np.isin(lab, list(bg_labels)) if bg_labels else np.zeros_like(sub)
    keep = ~bg

    out = np.zeros((h, w, 4), np.uint8)
    out[:, :, :3] = rgb[sy0:sy1, sx0:sx1].astype(np.uint8)
    Rs = out[:, :, 0].astype(int); Gs = out[:, :, 1].astype(int); Bs = out[:, :, 2].astype(int)
    # Peel the magenta anti-alias fringe off the kept edge (method 2). The fringe is a blend of sprite +
    # magenta key, so it leans magenta (R,B above G, R≈B) AND inherits the key's very low green. The
    # GREEN GATE (G < CUT_FRINGE_GREEN) is what separates fringe from a SOLID purple sprite edge (portal /
    # droplet / evolving-mobs): real sprite purple carries more green than the near-black-green key blend,
    # so it survives. |R-B|<70 keeps red (R>>B) and blue (B>>R) edges either way. Earlier the fringe was
    # too DIM for the old bright-only test (R,B>150) — this catches it.
    fringe = (Rs > Gs + 15) & (Bs > Gs + 15) & (abs(Rs - Bs) < 70) & (Gs < CUT_FRINGE_GREEN)
    for _ in range(CUT_PEEL):
        boundary = keep & ndimage.binary_dilation(~keep)
        keep = keep & ~(boundary & fringe)
    # Despill any remaining faint hued rim: lift G to gray so it reads as a natural dark outline, not pink.
    boundary = keep & ndimage.binary_dilation(~keep)
    hued = (Rs > Gs + 12) & (Bs > Gs + 12) & (abs(Rs - Bs) < 70) & (Gs < CUT_FRINGE_GREEN)
    gray = np.minimum(np.minimum(Rs, Gs), Bs)
    neut = boundary & hued
    for c in range(3):
        out[:, :, c] = np.where(neut, gray, out[:, :, c])
    out[:, :, 3] = np.where(keep, 255, 0).astype(np.uint8)

    ys, xs = np.where(keep)
    a0, a1, b0, b1 = int(xs.min()), int(xs.max() + 1), int(ys.min()), int(ys.max() + 1)
    img = Image.fromarray(out[b0:b1, a0:a1], "RGBA")
    side = max(img.width, img.height) + 2 * CUT_PAD
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(img, ((side - img.width) // 2, (side - img.height) // 2), img)
    return canvas


def _disabled_variant(img):
    """Greyscale + slightly dimmed copy (alpha preserved) — the 'twist disabled' icon."""
    rgba = np.asarray(img).astype(int)
    lum = (0.299 * rgba[:, :, 0] + 0.587 * rgba[:, :, 1] + 0.114 * rgba[:, :, 2])
    g = np.clip(lum * 0.72, 0, 255).astype(np.uint8)  # dim so disabled reads as "off"
    out = np.zeros_like(rgba, np.uint8)
    for c in range(3):
        out[:, :, c] = g
    out[:, :, 3] = rgba[:, :, 3].astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def cmd_cut_icons(args):
    _need_numpy()
    _need_pil()
    im = np.asarray(Image.open(args.inp).convert("RGBA"))
    rgb = np.asarray(Image.open(args.inp).convert("RGB")).astype(int)
    boxes = _icon_boxes(im)
    if len(boxes) != len(ICON_MAP):
        raise SystemExit("expected %d icon boxes but grouped %d — sheet/params drifted"
                         % (len(ICON_MAP), len(boxes)))
    for sect in ("minigames", "experiences", "ui"):
        d = os.path.join(ICONS_OUT, sect)
        if os.path.isdir(d):
            shutil.rmtree(d)
        os.makedirs(d)
    n_disabled = 0
    for idx, (sect, name) in ICON_MAP.items():
        icon = _flood_cut(rgb, boxes[idx])
        icon.save(os.path.join(ICONS_OUT, sect, name + ".png"))
        if sect == "experiences":
            _disabled_variant(icon).save(os.path.join(ICONS_OUT, sect, name + "_disabled.png"))
            n_disabled += 1
    print("cut icons:", len(ICON_MAP), "(+%d disabled variants)" % n_disabled, "->", ICONS_OUT)


# ===========================================================================
# gen-menu-art — import assets/icons/<section>/* verbatim into the menu pack
# ---------------------------------------------------------------------------
# (Legacy: the minigame/experience/ui sets now ship straight from assets/icons via the
# init-paper.sh pack assembly, so they are NOT listed in SECTIONS below. This command only
# still imports the older sheet sections that live as processed outputs in assets/item.)
# Output lands at the pack texture path SexidiumResourcePack expects:
#     assets/item/<section>/<name>.png            # button icons (item_model textures)
# Each sprite is padded to a transparent square (so the item_model never stretches
# it) and resized to a uniform ICON px; folder organisation is preserved 1:1.
# Chest-GUI BACKGROUNDS are separate (see bake-overhang / cut-chest).
# ===========================================================================

ICONS_SRC = os.path.join(ROOT, "assets", "icons")
ITEM_DIR = os.path.join(ROOT, "assets", "item")

# Legacy sheet sections only (processed outputs already committed under assets/item). The
# minigames/experiences/ui sets are shipped directly from assets/icons (see cut-icons).
SECTIONS = ("currency", "cursors", "elo_ranks", "font", "gui_buttons", "system")

ICON = 128         # uniform final item-icon size (square; sprites are padded, never stretched)


def square_icon(src_path):
    """Load an RGBA sprite, centre it on a transparent square, resize to ICON px."""
    img = Image.open(src_path).convert("RGBA")
    w, h = img.size
    side = max(w, h)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(img, ((side - w) // 2, (side - h) // 2), img)
    if side != ICON:
        canvas = canvas.resize((ICON, ICON), Image.LANCZOS)
    return canvas


def cmd_gen_menu_art(args):
    _need_pil()
    os.makedirs(ITEM_DIR, exist_ok=True)
    count = 0
    for sect in SECTIONS:
        src_dir = os.path.join(ICONS_SRC, sect)
        if not os.path.isdir(src_dir):
            print("  (skip missing section:", sect + ")")
            continue
        out_dir = os.path.join(ITEM_DIR, sect)
        os.makedirs(out_dir, exist_ok=True)
        for name in sorted(os.listdir(src_dir)):
            if not name.endswith(".png"):
                continue
            square_icon(os.path.join(src_dir, name)).save(os.path.join(out_dir, name))
            count += 1
    print("imported icons:", count)
    print("item ->", ITEM_DIR)
    print("(chest backgrounds: run `art.py bake-overhang`)")


# ===========================================================================
# bake-overhang — ornate chest-menu backgrounds that OVERHANG the chest window
# ---------------------------------------------------------------------------
# Supersedes the flat "Beyond GUI" slot frames. Each chest size (1..6 rows) gets a
# background glyph built from the ornate UI/frame border (same SEXIDIUM frame the
# baked screens use), 9-sliced so it encloses the whole chest window and spills
# OUTSIDE it on every side (plaque/sword/lantern overhang into the screen). Inside
# the border is ONE flat panel (no painted slot cells), so item icons render
# directly on it, aligned to Minecraft's slot grid, and the player-inventory rows
# are hidden under the same panel.
#
# KEY CONSTRAINT: a bitmap-font glyph must fit Minecraft's 256x256 font-atlas page,
# so the SOURCE PNG is capped at 256 on each axis. On-screen size is set by the font
# `height` field (NOT the source px), so the frame still renders LARGER than the
# 176x222 window (overhang) while the source stays <=256.
#
# The printed table must match MenuArt:
#   CHEST_GLYPH_HEIGHTS = 148 + rows*18 ; CHEST_GLYPH_ASCENT = 27 ; frame render width = 212.
# ===========================================================================

# Ornate border insets in the source frame (transparent hollow centre); the plaque makes the top deeper.
SRC_BORDER = (102, 178, 104, 104)  # left, top, right, bottom (measured in the 1024^2 frame)
# Flat interior panel colour (Components.PANEL, opaque so it hides the player-inventory grid).
PANEL = (0x3B, 0x2B, 0x60, 0xFF)

# Overhang (GUI px the border extends beyond the flat panel) and the panel's window-relative box.
OHX, OHT, OHB = 22, 29, 20
HX0, HY0, HX1 = 4, 15, 172  # panel left/top/right; panel bottom = the window bottom (per row count)


def nine_slice(img, border, out_w, out_h, ol, ot, orr, ob):
    """Resize img to out_w x out_h keeping corners, stretching edges, dropping the centre (transparent)."""
    w, h = img.size
    bl, bt, br, bb = border
    cw, ch = out_w - ol - orr, out_h - ot - ob
    out = Image.new("RGBA", (out_w, out_h), (0, 0, 0, 0))
    crop = lambda a, b, c, d: img.crop((a, b, c, d))

    def put(piece, x, y, pw, ph):
        out.alpha_composite(piece.resize((max(1, pw), max(1, ph)), Image.LANCZOS), (x, y))
    put(crop(0, 0, bl, bt), 0, 0, ol, ot)
    put(crop(w - br, 0, w, bt), out_w - orr, 0, orr, ot)
    put(crop(0, h - bb, bl, h), 0, out_h - ob, ol, ob)
    put(crop(w - br, h - bb, w, h), out_w - orr, out_h - ob, orr, ob)
    put(crop(bl, 0, w - br, bt), ol, 0, cw, ot)
    put(crop(bl, h - bb, w - br, h), ol, out_h - ob, cw, ob)
    put(crop(0, bt, bl, h - bb), 0, ot, ol, ch)
    put(crop(w - br, bt, w, h - bb), out_w - orr, ot, orr, ch)
    return out


def _bake_one(frame, rows):
    win_h = 114 + rows * 18
    hy1 = win_h  # panel bottom = window bottom (covers slot grid + the hidden player-inventory rows)
    tex_x0, tex_y0 = HX0 - OHX, HY0 - OHT
    tex_w, tex_h = (HX1 + OHX) - tex_x0, (hy1 + OHB) - tex_y0
    local = lambda cx, cy: (cx - tex_x0, cy - tex_y0)
    out = Image.new("RGBA", (tex_w, tex_h), (0, 0, 0, 0))
    # One flat interior panel over the whole window interior.
    panel = Image.new("RGBA", (HX1 - HX0, hy1 - HY0), PANEL)
    out.alpha_composite(panel, local(HX0, HY0))
    # Ornate border 9-sliced around the panel, overhanging on every side.
    frame_w, frame_h = (HX1 + OHX) - (HX0 - OHX), (hy1 + OHB) - (HY0 - OHT)
    border = nine_slice(frame, SRC_BORDER, frame_w, frame_h, OHX, OHT, OHX, OHB)
    out.alpha_composite(border, local(HX0 - OHX, HY0 - OHT))
    out.save(os.path.join(CHEST_DIR, "chest_%d.png" % rows))
    return tex_w, tex_h


def cmd_bake_overhang(args):
    _need_pil()
    frame_src = glob.glob(os.path.join(ROOT, "UI", "frame", "*.png"))
    if not frame_src:
        raise SystemExit("no ornate frame found under UI/frame/*.png")
    frame = Image.open(sorted(frame_src)[0]).convert("RGBA")
    os.makedirs(CHEST_DIR, exist_ok=True)
    heights = {}
    for rows in range(1, 7):
        w, h = _bake_one(frame, rows)
        heights[rows] = h
        print("chest_%d.png -> %dx%d  (<=256: %s)" % (rows, w, h, max(w, h) <= 256))
    print("CHEST_GLYPH_HEIGHTS (rows 1..6):", [heights[r] for r in range(1, 7)])
    print("CHEST_GLYPH_ASCENT = %d ; frame left x = %d ; frame render width = %d"
          % (13 - (HY0 - OHT), HX0 - OHX, (HX1 + OHX) - (HX0 - OHX)))


# ===========================================================================
# upscale-sources — Real-ESRGAN (neural) super-resolution of the medieval sources
# ---------------------------------------------------------------------------
# WHY: the chest frames bake from 256px medieval art LANCZOS-upscaled to the 768px font cell — a plain
# resize adds NO detail, so the 768px frames look soft. This 4x-upscales the SOURCES with Real-ESRGAN
# x4plus (a state-of-the-art GAN super-resolution net), which reconstructs plausible edges/texture; the
# committed 1024px results then downscale cleanly to 768 in `bake-medieval`. Run ONCE and commit the
# upscaled sources so the normal build stays GPU/model-free (only this command needs the model).
#
# Model: Real-ESRGAN x4plus, ONNX (dynamic NCHW float32, RGB), run through onnxruntime — auto-downloaded
# to build/upscale/ (gitignored) on first run. Alpha is upscaled separately (LANCZOS) and recombined,
# since the RGB GAN is 3-channel (the same split realesrgan-ncnn-vulkan does internally).
#
# REQUIRES onnxruntime + numpy + Pillow, i.e. run with the rembg venv:
#     .venv-rembg/bin/python scripts/art.py upscale-sources
# CUDA is used if onnxruntime can load it, else CPU (~5s per 256px image — fine for the 7 sources).
# ===========================================================================

# Public Real-ESRGAN x4plus ONNX mirror (dynamic-shape float32 I/O). Only `upscale-sources` fetches it; the
# committed upscaled sources mean `bake-medieval` and the build never need it.
ESRGAN_MODEL_URL = "https://huggingface.co/OwlMaster/AllFilesRope/resolve/main/RealESRGAN_x4plus.fp16.onnx"
ESRGAN_MODEL_PATH = os.path.join(ROOT, "build", "upscale", "realesrgan-x4plus.onnx")
ESRGAN_SCALE = 4  # the x4plus model's fixed magnification


def _esrgan_session():
    """An onnxruntime session for the Real-ESRGAN model (downloaded on first use). CUDA if available, else CPU."""
    import onnxruntime as ort  # lazy: only this command needs it
    if not os.path.isfile(ESRGAN_MODEL_PATH):
        os.makedirs(os.path.dirname(ESRGAN_MODEL_PATH), exist_ok=True)
        import urllib.request
        print("downloading Real-ESRGAN x4plus model ->", os.path.relpath(ESRGAN_MODEL_PATH, ROOT))
        req = urllib.request.Request(ESRGAN_MODEL_URL, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=300) as r, open(ESRGAN_MODEL_PATH, "wb") as f:
            f.write(r.read())
    avail = set(ort.get_available_providers())
    providers = [p for p in ("CUDAExecutionProvider", "CPUExecutionProvider") if p in avail] \
        or ["CPUExecutionProvider"]
    return ort.InferenceSession(ESRGAN_MODEL_PATH, providers=providers)


def _alpha_bleed(img, iters=24):
    """Edge-extend: fill transparent-area RGB with the nearest opaque RGB (alpha unchanged). The medieval art
    is WHITE where alpha=0, which bleeds into the anti-aliased edges on any resize → a white outline/halo.
    Bleeding the opaque colour outward first means the only colour near an edge is the art's own, so the
    halo is gone. numpy-only 4-neighbour propagation (no scipy)."""
    arr = np.asarray(img.convert("RGBA")).astype(np.float32)
    rgb = arr[:, :, :3]
    alpha = arr[:, :, 3]
    known = alpha > 0
    rgb = np.where(known[:, :, None], rgb, 0.0)
    for _ in range(iters):
        if known.all():
            break
        ksum = np.zeros_like(rgb)
        kcnt = np.zeros_like(alpha)
        ksum[1:, :, :] += rgb[:-1, :, :];  kcnt[1:, :] += known[:-1, :]
        ksum[:-1, :, :] += rgb[1:, :, :];  kcnt[:-1, :] += known[1:, :]
        ksum[:, 1:, :] += rgb[:, :-1, :];  kcnt[:, 1:] += known[:, :-1]
        ksum[:, :-1, :] += rgb[:, 1:, :];  kcnt[:, :-1] += known[:, 1:]
        fill = (~known) & (kcnt > 0)
        rgb[fill] = ksum[fill] / kcnt[fill][:, None]
        known = known | fill
    return Image.fromarray(np.dstack([rgb, alpha]).astype(np.uint8), "RGBA")


def _esrgan_upscale_rgba(sess, img):
    """4x-upscale an RGBA image: Real-ESRGAN on the RGB planes, LANCZOS on alpha, recombined to RGBA. The RGB
    is alpha-bled before AND after the GAN so the white transparent background never reaches an edge (no halo)."""
    img = img.convert("RGBA")
    rgb = np.asarray(_alpha_bleed(img).convert("RGB"), np.float32) / 255.0   # bled -> no white at edges
    x = np.ascontiguousarray(np.transpose(rgb, (2, 0, 1))[None])       # 1,3,H,W
    y = sess.run(None, {sess.get_inputs()[0].name: x})[0]              # 1,3,4H,4W
    out_rgb = (np.clip(np.transpose(y[0], (1, 2, 0)), 0, 1) * 255.0 + 0.5).astype(np.uint8)
    alpha = img.getchannel("A").resize((img.width * ESRGAN_SCALE, img.height * ESRGAN_SCALE), Image.LANCZOS)
    return _alpha_bleed(Image.fromarray(np.dstack([out_rgb, np.asarray(alpha)]), "RGBA"))


def cmd_upscale_sources(args):
    _need_numpy_only()
    _need_pil()
    sess = _esrgan_session()
    print("onnxruntime providers:", sess.get_providers())
    # Chest frames: generic_<slots>.png (256²) -> upscaled/generic_<slots>.png (1024²), consumed by bake-medieval.
    up_dir = os.path.join(MEDIEVAL_DIR, "upscaled")
    os.makedirs(up_dir, exist_ok=True)
    for rows in range(1, 7):
        slots = rows * 9
        src = os.path.join(MEDIEVAL_DIR, "generic_%d.png" % slots)
        if not os.path.isfile(src):
            raise SystemExit("missing medieval source: " + src)
        out = _esrgan_upscale_rgba(sess, Image.open(src))
        out.save(os.path.join(up_dir, "generic_%d.png" % slots))
        print("upscaled generic_%d.png  256² -> %dx%d" % (slots, out.width, out.height))
    # Card sprite: the 56x36 medieval-pack source -> the committed assets/ui/cards/card.png (drawn into screens).
    card_src = os.path.join(MEDIEVAL_DIR, "card.png")
    if os.path.isfile(card_src):
        out = _esrgan_upscale_rgba(sess, Image.open(card_src))
        card_dst = os.path.join(ROOT, "assets", "ui", "cards", "card.png")
        os.makedirs(os.path.dirname(card_dst), exist_ok=True)
        out.save(card_dst)
        print("upscaled card.png      56x36 -> %dx%d" % (out.width, out.height))
    print("upscale-sources: done. Re-run `bake-medieval` + `tile-backgrounds` + bakeMenuScreens to refresh assets.")


# ===========================================================================
# bake-medieval — import the UltimateGUI "Medieval" pack as the live chest art
# ---------------------------------------------------------------------------
# The medieval pack ships one full-window GUI background per chest size,
# generic_<slots>.png (slots = rows*9, EXCLUDING the player inventory), each a
# 256x256 canvas with the wooden frame centred on a transparent margin. The art
# is authored to vanilla GUI proportions: 18 source px = 1 slot row (1:1 scale),
# the 9-column slot grid centred so column 0 lands on the vanilla slot at GUI
# (8,18), and the player-inventory rows drawn under the same frame so a baked
# background hides them. Minecraft bitmap fonts derive advance from opaque pixels, so transparent
# left/top margins are not a reliable anchor. We move the opaque frame to (0,0); placement is then
# controlled by left_x/ascent in backgrounds.yml.
#
# RESOLUTION: the committed font cell is baked at SCALE× the 256px GUI cell (768px) so the frame carries
# more detail on high-DPI / high-GUI-scale clients. The on-screen render height stays 256 (the bitmap-font
# `height` in backgrounds.yml), so the glyph renders at scale 256/768 = 1/3 and lands pixel-identically to
# the old 256 art — just sharper. The 256px source has no extra detail of its own, so the upscale is a
# high-quality LANCZOS resample (matches the SceneBaker screen pipeline, which renders fresh at SCALE×).
# (The Minecraft per-glyph font-atlas page is historically 256px; verify the target build renders >256
# glyphs — see SceneBaker.SOURCE_SCALE / backgrounds.yml.)
#
# The printed table must match MenuArt (the yml registry mirrors these — all in ON-SCREEN GUI px,
# unchanged by the resolution bump):
#   height = 256 ; ascent = 23 ; left_x = -10 ; render_width = 198.
# Slot grid measured at normalised source-(x=18,y=28) in GUI px; at SCALE× that is (18*SCALE, 28*SCALE)
# source px, which the 1/SCALE render scale maps back to GUI (8,18). The PNG canvas is 256*SCALE px.
# ===========================================================================

MEDIEVAL_DIR = os.path.join(ROOT, "UI", "UltimateGUI_medieval_pack", "Medieval")
# Resolution multiplier for the baked chest font cell (SceneBaker.SOURCE_SCALE mirror). On-screen size is
# unchanged (backgrounds.yml `height` stays 256); only the source texture grows for sharpness.
MEDIEVAL_SCALE = 3
MEDIEVAL_GUI_CELL = 256
MEDIEVAL_OUT_CELL = MEDIEVAL_GUI_CELL * MEDIEVAL_SCALE  # 768


def cmd_bake_medieval(args):
    _need_pil()
    os.makedirs(CHEST_DIR, exist_ok=True)
    cell = MEDIEVAL_OUT_CELL
    up_dir = os.path.join(MEDIEVAL_DIR, "upscaled")
    for rows in range(1, 7):
        slots = rows * 9
        upscaled = os.path.join(up_dir, "generic_%d.png" % slots)
        base = os.path.join(MEDIEVAL_DIR, "generic_%d.png" % slots)
        # Prefer the Real-ESRGAN-upscaled source (scripts/art.py upscale-sources): a 1024px AI render that
        # downscales to 768 keeps real detail. Fall back to the 256px source (a plain LANCZOS upscale, soft).
        if os.path.isfile(upscaled):
            src = upscaled
        elif os.path.isfile(base):
            src = base
        else:
            raise SystemExit("missing medieval source: " + base)
        frame = Image.open(src).convert("RGBA")
        w, h = frame.size
        if w != h or w % MEDIEVAL_GUI_CELL != 0:
            raise SystemExit("medieval source %s must be a square multiple of %d GUI px, got %dx%d"
                             % (src, MEDIEVAL_GUI_CELL, w, h))
        bbox = frame.getchannel("A").getbbox()
        if bbox is None:
            raise SystemExit("empty (fully transparent) image: " + src)
        # The frame fills the OUT_CELL identically regardless of source resolution: scale the cropped bbox by
        # cell/source-canvas (256px → ×3 upscale; 1024px AI → ×0.75 downscale), pin top-left at (0,0). The
        # AI source is a uniform 4× of the 256px one, so the baked geometry is unchanged — just sharper.
        cropped = frame.crop(bbox)
        scale = cell / float(w)
        scaled = cropped.resize((max(1, round(cropped.width * scale)), max(1, round(cropped.height * scale))),
                                Image.LANCZOS)
        out = Image.new("RGBA", (cell, cell), (0, 0, 0, 0))
        out.alpha_composite(scaled, (0, 0))
        out.save(os.path.join(CHEST_DIR, "chest_%d.png" % rows))
        print("chest_%d.png <- %s  ->  %dx%d  (source %dpx, scale %.3g)"
              % (rows, os.path.relpath(src, ROOT), cell, cell, w, scale))
    print("CHEST_GLYPH_HEIGHTS (rows 1..6, ON-SCREEN GUI px):", [MEDIEVAL_GUI_CELL for _ in range(1, 7)])
    print("CHEST_GLYPH_ASCENT = 23 ; CHEST_FRAME_LEFT_X = -10 ; CHEST_FRAME_RENDER_WIDTH = 198"
          " ; SOURCE CELL = %dpx" % cell)


# ===========================================================================
# slice-typography — cut the medieval bitmap-font sheets into per-char PNGs
# ---------------------------------------------------------------------------
# The pack ships two proportional font sheets, typography_title (the chunky
# plaque caps) and typography_button (the small button caps). Each sheet has a
# framed PLAQUE PREVIEW on top (decorative, ignored) and below it the CLEAN
# glyph set on a transparent background, laid out in two rows: A-O then P-Z.
# There are NO digits and NO lowercase (uppercase Latin only). We slice the two
# clean rows into per-character PNGs under assets/item/font_title/ and
# font_button/ using the same char_<X>.png convention BitmapFont.fromCharPngDir
# and the resource-pack generator already consume, so the medieval caps become
# the sexidium:title / sexidium:button fonts with no new loader. Glyphs are
# split by transparent column gutters (proportional widths preserved).
# ===========================================================================

# The two clean rows below the plaque preview, in reading order (uppercase only).
TYPO_ROWS = ("ABCDEFGHIJKLMNO", "PQRSTUVWXYZ")
TYPO_FONTS = {
    "typography_title": "font_title",
    "typography_button": "font_button",
}


def _ink_runs(flags):
    """Index ranges [start, end) of consecutive truthy entries (ink, vs transparent gutters)."""
    runs = []
    start = None
    for i, v in enumerate(flags):
        if v and start is None:
            start = i
        elif not v and start is not None:
            runs.append((start, i))
            start = None
    if start is not None:
        runs.append((start, len(flags)))
    return runs


def _slice_row(img, y0, y1, chars):
    """Split the row band [y0,y1) into per-char glyphs by transparent column gutters."""
    px = img.load()
    col_ink = [any(px[x, y][3] > 20 for y in range(y0, y1)) for x in range(img.width)]
    cols = _ink_runs(col_ink)
    if len(cols) != len(chars):
        raise SystemExit("sliced %d glyph columns but expected %d (%s) — check the sheet"
                         % (len(cols), len(chars), chars))
    out = {}
    for (x0, x1), ch in zip(cols, chars):
        out[ch] = img.crop((x0, y0, x1, y1))  # tight per-glyph cell (row band height kept uniform)
    return out


def cmd_slice_typography(args):
    _need_pil()
    for sheet, out_name in TYPO_FONTS.items():
        src = os.path.join(MEDIEVAL_DIR, sheet + ".png")
        if not os.path.isfile(src):
            raise SystemExit("missing typography sheet: " + src)
        img = Image.open(src).convert("RGBA")
        px = img.load()
        row_ink = [any(px[x, y][3] > 20 for x in range(img.width)) for y in range(img.height)]
        bands = _ink_runs(row_ink)
        # bands[0] = framed plaque preview (ignored); bands[1]/[2] = the two clean glyph rows.
        if len(bands) < 3:
            raise SystemExit("expected plaque + 2 glyph rows in " + sheet + ", got bands " + str(bands))
        glyphs = {}
        for band, chars in zip(bands[1:3], TYPO_ROWS):
            glyphs.update(_slice_row(img, band[0], band[1], chars))
        out_dir = os.path.join(ITEM_DIR, out_name)
        os.makedirs(out_dir, exist_ok=True)
        # Drop any stale glyphs from a previous slice so a removed char never lingers.
        for old in glob.glob(os.path.join(out_dir, "char_*.png")):
            os.remove(old)
        for ch, glyph in glyphs.items():
            glyph.save(os.path.join(out_dir, "char_%s.png" % ch))
        print("%s -> %s/  (%d glyphs A-Z)" % (sheet, out_name, len(glyphs)))


# ===========================================================================
# cut-chest — cut the "Beyond GUI" slot frames into chest backgrounds
# ---------------------------------------------------------------------------
# Source: UI/slots/Beyond GUI - slot 1.png … slots 6.png — hand-drawn purple chest
# panels, one per row count, each a 1254² RGB image with the frame centred on a
# light checkerboard margin. The margin is keyed out, the frame cropped tight and
# uniformly scaled to the chest width (aspect PRESERVED, never stretched), then an
# opaque skirt fills everything below the menu's slot grid so the player inventory
# stays hidden. Output: assets/ui/chest/chest_<rows>.png.
#
# The per-row GLYPH HEIGHT printed below must match MenuArt.CHEST_GLYPH_HEIGHTS.
# Superseded by bake-overhang for the live look; kept for history.
# ===========================================================================

SLOTS_SRC = os.path.join(ROOT, "UI", "slots")
# 1 source px = 1 GUI px. A Minecraft bitmap-font glyph must fit a 256x256 font-atlas page or it
# silently fails to stitch and renders as a degenerate box, so a glyph texture may not supersample
# past 256 on its widest axis. At WIDTH=176 that caps SCALE at 1 (176*2=352 > 256).
SCALE = 1            # texture width = WIDTH*SCALE, kept <=256 for the font atlas

SOURCE = {
    1: "Beyond GUI - slot 1.png",
    2: "Beyond GUI - slots 2.png",
    3: "Beyond GUI - slots 3.png",
    4: "Beyond GUI - slots 4.png",
    5: "Beyond GUI - slots 5.png",
    6: "Beyond GUI - slots 6.png",
}


def keyed_alpha(img):
    """RGBA copy with the light checkerboard margin keyed out (alpha 0).

    The margin is a near-white/light-grey checkerboard (~241–254, neutral); the frame
    is dark/saturated purple + navy cells. A pixel is background iff it is both very
    light and near-neutral — which the coloured frame and the dark cells never are.
    """
    rgba = img.convert("RGBA")
    px = rgba.load()
    w, h = rgba.size
    for y in range(h):
        for x in range(w):
            r, g, b, _ = px[x, y]
            if (max(r, g, b) - min(r, g, b)) < 14 and min(r, g, b) > 232:
                px[x, y] = (r, g, b, 0)
    return rgba


def _cut_one(rows):
    img = Image.open(os.path.join(SLOTS_SRC, SOURCE[rows]))
    rgba = keyed_alpha(img)
    bbox = rgba.getbbox()
    if bbox is None:
        raise SystemExit("empty frame after keying: " + SOURCE[rows])
    frame = rgba.crop(bbox)
    fw, fh = frame.size
    out_w = WIDTH * SCALE
    out_h = round(fh * out_w / fw)               # preserve aspect — NO horizontal stretch
    out = frame.resize((out_w, out_h), Image.LANCZOS)
    # Extend down over the player-inventory rows: the glyph spans the FULL chest window (114 + rows*18 GUI
    # px) with an opaque skirt filling everything below the menu's slot grid, so a lobby menu hides the
    # player inventory like a baked screen. The frame keeps its top origin and decorative overhang; its
    # slot cells (all above skirt_top) are untouched.
    win_h = (114 + rows * 18) * SCALE
    skirt_top = (18 + rows * 18) * SCALE
    canvas = Image.new("RGBA", (out_w, win_h), (0, 0, 0, 0))
    canvas.paste(Image.new("RGBA", (out_w, win_h - skirt_top), OPAQUE_BG), (0, skirt_top))
    canvas.alpha_composite(out, (0, 0))
    os.makedirs(CHEST_DIR, exist_ok=True)
    canvas.save(os.path.join(CHEST_DIR, "chest_%d.png" % rows))
    glyph_height = round(win_h / SCALE)          # GUI px height MenuArt renders it at (width -> 176)
    return (out_w, win_h), glyph_height


def cmd_cut_chest(args):
    _need_pil()
    heights = {}
    for rows in range(1, 7):
        size, gh = _cut_one(rows)
        heights[rows] = gh
        print("chest_%d.png" % rows, "->", size, "| MenuArt glyph height(%d) = %d GUI px" % (rows, gh))
    print("out ->", CHEST_DIR)
    print("CHEST_GLYPH_HEIGHTS (rows 1..6):", [heights[r] for r in range(1, 7)])


# ===========================================================================
# extend-chest — extend the committed cut frames down over the player inventory
# ---------------------------------------------------------------------------
# The row-count frames cut by `cut-chest` stop just past the chest slot grid, so the
# vanilla PLAYER inventory still shows below the menu. This re-canvasses each frame to
# the FULL chest window height (114 + rows*18 GUI px) with an OPAQUE skirt filling
# everything below the menu's own slot grid; the frame art (incl. its TRANSPARENT slot
# windows) is drawn back on top. Operates in place on assets/ui/chest/chest_<rows>.png.
# Idempotent: a frame already at its window height is skipped.
# ===========================================================================

# The committed (pre-extension) cut frame heights, as cut by cut-chest. Used both as the
# extend idempotency guard and to recover the pristine frame in align-chest.
CUT_FRAME_HEIGHTS = {1: 61, 2: 94, 3: 99, 4: 118, 5: 132, 6: 164}


def _chest_window_height(rows):
    """Full vanilla chest-window height in GUI px (top chrome + slot grid + player inventory)."""
    return 114 + rows * 18


def _extend_skirt_top(rows):
    """Y of the menu's slot-grid bottom — the opaque skirt starts here and runs to the window bottom."""
    return 18 + rows * 18


def _extend_one(rows):
    path = os.path.join(CHEST_DIR, "chest_%d.png" % rows)
    frame = Image.open(path).convert("RGBA")
    win_h = _chest_window_height(rows)
    if frame.height >= win_h:
        return win_h, True  # already extended — idempotent no-op
    canvas = Image.new("RGBA", (WIDTH, win_h), (0, 0, 0, 0))
    # Opaque skirt over the gap + player-inventory band, BEHIND the frame so the frame's decorative
    # bottom still shows; the frame's transparent slot windows all sit above skirt_top, so they are
    # untouched and the menu's item icons keep showing through.
    skirt = Image.new("RGBA", (WIDTH, win_h - _extend_skirt_top(rows)), OPAQUE_BG)
    canvas.paste(skirt, (0, _extend_skirt_top(rows)))
    canvas.alpha_composite(frame, (0, 0))
    canvas.save(path)
    return win_h, False


def cmd_extend_chest(args):
    _need_pil()
    heights = {}
    for rows in range(1, 7):
        h, skipped = _extend_one(rows)
        heights[rows] = h
        print("chest_%d.png -> %dx%d %s" % (rows, WIDTH, h, "(already extended)" if skipped else "(extended)"))
    print("CHEST_GLYPH_HEIGHTS (rows 1..6):", [heights[r] for r in range(1, 7)])


# ===========================================================================
# align-chest — warp each cut frame's painted slot grid onto the real slot rows
# ---------------------------------------------------------------------------
# The hand-drawn "Beyond GUI" frames were scaled preserving their own aspect, so their
# painted grid overshoots the vanilla chest slot grid (slots at GUI y = 18 + row*18,
# pitch 18). A piecewise vertical warp maps three bands onto the exact window geometry:
#     [0, gtop)        title bar / top border -> [0, 18)
#     [gtop, gbot]     painted slot grid      -> [18, 18 + rows*18)   (aligned)
#     (gbot, natH)     bottom border          -> just below the grid; OPAQUE skirt fills
#                                                down to the window bottom (inventory hidden).
# gtop/gbot are detected from the full-width navy slot-cell rows (the centred title plaque
# is sub-threshold). Idempotent: the first run recovers each pristine frame into
# assets/ui/chest/_src/ and every run warps from there (re-running never compounds).
# ===========================================================================

CHEST_SRC_DIR = os.path.join(CHEST_DIR, "_src")
TITLE_BAR = 18     # vanilla title-bar height; the real slot grid starts here
SLOT_PITCH = 18    # GUI px per slot row


def _align_is_navy(p):
    r, g, b, a = p
    return a > 40 and (r + g + b) < 150 and b >= r


def _align_detect_grid_band(frame):
    """First/last full-width slot-cell row (the centred title plaque is sub-threshold)."""
    w, h = frame.size
    px = frame.load()
    counts = [sum(1 for x in range(w) if _align_is_navy(px[x, y])) for y in range(h)]
    thr = 100  # >= ~8 cells wide => a real grid row, not the centred plaque (~50 px)
    rows = [y for y, c in enumerate(counts) if c >= thr]
    if not rows:
        return TITLE_BAR, h - 1
    return rows[0], rows[-1]


def _align_pristine_frame(rows):
    """The original cut frame, recovered once into _src/ and reused thereafter."""
    src = os.path.join(CHEST_SRC_DIR, "chest_%d.png" % rows)
    if os.path.isfile(src):
        return Image.open(src).convert("RGBA")
    cur = Image.open(os.path.join(CHEST_DIR, "chest_%d.png" % rows)).convert("RGBA")
    frame = cur.crop((0, 0, WIDTH, CUT_FRAME_HEIGHTS[rows]))  # original frame sits above any skirt
    os.makedirs(CHEST_SRC_DIR, exist_ok=True)
    frame.save(src)
    return frame


def _align_band(frame, y0, y1, out_h):
    """Vertically resample frame[y0:y1] to out_h px (full width), preserving alpha."""
    y0 = max(0, min(y0, frame.height))
    y1 = max(y0 + 1, min(y1, frame.height))
    return frame.crop((0, y0, WIDTH, y1)).resize((WIDTH, max(1, out_h)), Image.LANCZOS)


def _align_one(rows):
    frame = _align_pristine_frame(rows)
    gtop, gbot = _align_detect_grid_band(frame)
    win_h = _chest_window_height(rows)
    grid_h = rows * SLOT_PITCH
    grid_bottom = TITLE_BAR + grid_h            # = 18 + rows*18, the real grid bottom

    out = Image.new("RGBA", (WIDTH, win_h), (0, 0, 0, 0))
    # Opaque skirt covering everything below the grid (gap + player inventory).
    out.paste(Image.new("RGBA", (WIDTH, win_h - grid_bottom), OPAQUE_BG), (0, grid_bottom))
    # Title bar / top border -> [0, 18); aligned grid -> [18, grid_bottom).
    out.alpha_composite(_align_band(frame, 0, gtop, TITLE_BAR), (0, 0))
    out.alpha_composite(_align_band(frame, gtop, gbot + 1, grid_h), (0, TITLE_BAR))
    # Bottom border (frame rows below the grid) drawn just under the grid, over the skirt.
    bb_src_h = frame.height - (gbot + 1)
    if bb_src_h > 1:
        bb_h = min(bb_src_h, win_h - grid_bottom)
        out.alpha_composite(_align_band(frame, gbot + 1, frame.height, bb_h), (0, grid_bottom))

    out.save(os.path.join(CHEST_DIR, "chest_%d.png" % rows))
    return win_h, (gtop, gbot)


def cmd_align_chest(args):
    _need_pil()
    heights = {}
    for rows in range(1, 7):
        h, (gtop, gbot) = _align_one(rows)
        heights[rows] = h
        print("chest_%d.png -> %dx%d  (grid band [%d,%d] -> [18,%d])"
              % (rows, WIDTH, h, gtop, gbot, 18 + rows * SLOT_PITCH))
    print("CHEST_GLYPH_HEIGHTS (rows 1..6):", [heights[r] for r in range(1, 7)])


# ===========================================================================
# depink — kill residual magenta the border-flood kept inside already-cut icons
# ---------------------------------------------------------------------------
# `cut-icons` removes background by a BORDER FLOOD (drop only magenta connected to the crop
# border) so it can preserve interior magenta/purple sprite parts (portal, droplet_purple,
# evolving-mobs). The cost: when an icon has a magenta-keyed POCKET that the sprite encloses
# — the gap inside a lock's shackle, between chain links, behind a trophy's handles, the ring
# around no_green's leaf — that pocket never touches the border, so the flood keeps it and a
# bright pink blob survives. The rim-peel (method 2) only ever shaved the OUTER silhouette.
#
# depink MERGES THE TWO METHODS on the finished PNG: it re-applies build_masks' strict bright-
# magenta chroma key (method 1's colour test) CONNECTIVITY-INDEPENDENTLY, so an interior pocket
# is dropped just like a border one. The strict green gate (G < DEPINK_GREEN) is what lets this
# stay safe: real sprite purple carries far more green (droplet/portal measure G~110) than the
# near-black-green background key (G~5-30), so only true key residue is removed — purple sprites
# are spared. Then it runs method 2 (peel the bright magenta anti-alias fringe ringing each newly
# opened hole, then despill any faint hued rim to gray) so no pink ring is left behind.
#
# Operates IN PLACE on the icon PNGs passed on the command line (a directory expands to every
# *.png it holds, recursively). Relative paths resolve under assets/icons.
# ===========================================================================

DEPINK_GREEN = 70      # green gate: residue is near-black-green (G<70); real purple sprite (G~110) is spared
DEPINK_PEEL = 2        # fringe-peel passes around each newly opened hole (matches CUT_PEEL)


def _depink(rgba):
    """Remove bright-magenta key residue anywhere in an already-cut RGBA icon (+peel/despill the rim)."""
    a = np.asarray(rgba).astype(int)
    R, G, B, A = a[:, :, 0], a[:, :, 1], a[:, :, 2], a[:, :, 3]
    op = A > 0
    # Strict bright magenta KEY colour (method 1), tested everywhere — not just border-connected runs.
    # R,B high + green near-black + R≈B. The G<DEPINK_GREEN gate spares real purple sprite parts.
    residue = (R > 175) & (B > 175) & (G < DEPINK_GREEN) & (np.abs(R - B) < 60) & op
    keep = op & ~residue

    out = a.astype(np.uint8).copy()
    Rs, Gs, Bs = out[:, :, 0].astype(int), out[:, :, 1].astype(int), out[:, :, 2].astype(int)
    # Method 2: peel the magenta anti-alias fringe ringing each opened hole (a sprite/key blend that
    # leans magenta but inherits the key's very low green), then neutralise any milder hued rim to gray.
    fringe = (Rs > Gs + 15) & (Bs > Gs + 15) & (np.abs(Rs - Bs) < 70) & (Gs < DEPINK_GREEN)
    for _ in range(DEPINK_PEEL):
        boundary = keep & ndimage.binary_dilation(~keep)
        keep = keep & ~(boundary & fringe)
    boundary = keep & ndimage.binary_dilation(~keep)
    hued = (Rs > Gs + 12) & (Bs > Gs + 12) & (np.abs(Rs - Bs) < 70) & (Gs < DEPINK_GREEN)
    gray = np.minimum(np.minimum(Rs, Gs), Bs)
    neut = boundary & hued
    for c in range(3):
        out[:, :, c] = np.where(neut, gray, out[:, :, c])
    out[:, :, 3] = np.where(keep, A, 0).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def cmd_depink(args):
    _need_numpy()
    _need_pil()
    targets = []
    for rel in args.files:
        path = rel if os.path.isabs(rel) else os.path.join(ICONS_OUT, rel)
        if os.path.isdir(path):
            targets += sorted(glob.glob(os.path.join(path, "**", "*.png"), recursive=True))
        else:
            targets.append(path)
    for path in targets:
        img = Image.open(path).convert("RGBA")
        before = int((np.asarray(img)[:, :, 3] > 0).sum())
        out = _depink(img)
        after = int((np.asarray(out)[:, :, 3] > 0).sum())
        out.save(path)
        print("depink %-34s -%d px" % (os.path.relpath(path, ICONS_OUT), before - after))


# ===========================================================================
# overlay-hub — validate the baked hub overlays sit on chest_6's item slots
# ---------------------------------------------------------------------------
# The hub baked screens (assets/ui/screens/main-hub.png + main-hub-op.png) are TRANSPARENT
# medieval-card overlays meant to render on top of the chest_6 wood frame in-game. This:
#   1. asserts each overlay matches chest_6.png's size (the SOURCE_SCALE× font cell, 768) and is actually
#      transparent (has alpha-0 pixels — not an opaque board);
#   2. composites each overlay over chest_6.png (what the player sees) and also draws the
#      vanilla 9x6 item-slot grid (cyan) so you can confirm each card covers its slots.
# Writes the composites to build/hub-previews/ for review. Exits non-zero if a check fails.
# ===========================================================================

SCREENS_DIR = os.path.join(ROOT, "assets", "ui", "screens")
HUB_OVERLAYS = ("main-hub", "main-hub-op")


def cmd_overlay_hub(args):
    _need_pil()
    chest_path = os.path.join(CHEST_DIR, "chest_6.png")
    chest = Image.open(chest_path).convert("RGBA")
    cw, ch = chest.size
    # The font cell is GUI_CELL (256) GUI px scaled up by an integer SOURCE_SCALE for sharpness (768 = 3x).
    # All chest geometry below is in GUI px, multiplied by `scale` to land on the larger source canvas.
    if cw != ch or cw % 256 != 0:
        raise SystemExit("chest_6.png expected a square multiple of 256, got %dx%d" % (cw, ch))
    scale = cw // 256
    out_dir = os.path.join(ROOT, "build", "hub-previews")
    os.makedirs(out_dir, exist_ok=True)
    ok = True
    for name in HUB_OVERLAYS:
        path = os.path.join(SCREENS_DIR, name + ".png")
        if not os.path.isfile(path):
            print("MISSING %s" % path)
            ok = False
            continue
        overlay = Image.open(path).convert("RGBA")
        size_match = overlay.size == chest.size
        amin, amax = overlay.getchannel("A").getextrema()
        transparent = amin == 0      # has fully see-through pixels => a real overlay, not an opaque board
        has_art = amax > 0           # has opaque card pixels => not an empty image
        # Composite over the frame (the in-game stack: overlay shares chest_6's geometry, so a 1:1 paste IS
        # what the player sees) and a slot-grid copy to check card-on-slot fit. The cyan grid marks chest_6's
        # painted slot cells (first cell at GUI (19,30), pitch 18, cell 16), scaled onto the source canvas.
        comp = chest.copy()
        comp.alpha_composite(overlay)
        grid = comp.copy()
        draw = ImageDraw.Draw(grid)
        for r in range(6):
            for c in range(9):
                x, y = (19 + c * 18) * scale, (30 + r * 18) * scale
                draw.rectangle([x, y, x + 16 * scale - 1, y + 16 * scale - 1], outline=(0, 255, 255, 255))
        comp.convert("RGB").save(os.path.join(out_dir, name + "_on_frame.png"))
        grid.convert("RGB").save(os.path.join(out_dir, name + "_on_frame_slots.png"))
        comp.resize((cw * 2, ch * 2), Image.NEAREST).convert("RGB").save(os.path.join(out_dir, name + "_on_frame_zoom2x.png"))
        passed = size_match and transparent and has_art
        ok = ok and passed
        print("%-12s %s  size_match=%s transparent=%s art=%s (alpha %d..%d)"
              % (name, "PASS" if passed else "FAIL", size_match, transparent, has_art, amin, amax))
    print("overlay-hub:", "PASS" if ok else "FAIL", "->", os.path.relpath(out_dir, ROOT))
    if not ok:
        raise SystemExit(1)


# ===========================================================================
# tile-backgrounds — split each 768px background into ≤256px font-glyph tiles
# ---------------------------------------------------------------------------
# WHY: Minecraft stitches every bitmap-font glyph into a fixed 256x256 GlyphAtlasTexture at SOURCE
# resolution; a single glyph cell larger than 256px on either axis cannot be placed and renders BLANK
# (verified: minecraft.wiki/w/Font — "Glyphs themselves must not be larger than 256x256 pixels"). Our
# backgrounds bake at 768px (3x) for sharpness, so a one-codepoint-per-file glyph (cell == whole 768px
# file) is invisible in-game. The texture FILE may be any size, only each GLYPH CELL is capped — so we
# split the 768px image into a grid of ≤256px cells, each its own glyph, reassembled on screen by the
# title-trick (per-tile horizontal shift + per-row font ascent). See MenuArt.TILE_* / PaperMenuArt.
#
# GRID: 4x4. The on-screen cell stays 256px (so the slot grid still maps 1:1 to vanilla slots), and 256
# is NOT divisible by 3 — a 3x3 grid would need 85.33px on-screen tiles (non-integer ascent/height ->
# seams). 4x4 keeps the exact 3x resolution (768/4 = 192px source tile <= 256, shown at 256/4 = 64px)
# with integer geometry and no seams. Output: 4 horizontal STRIP files per background (768x192), one per
# tile-row, named <stem>.row<r>.png next to the source. Each strip is a bitmap provider with a 1x4 char
# grid -> four 192px cells; the provider's single `ascent` is that row's vertical placement.
#
# PER-TILE ADVANCE (the seam/drift fix): Minecraft bakes its bitmap glyph advance from the glyph's rightmost
# OPAQUE column (advance = round((rightmost+1) * height/cellHeight) + 1). The title-trick positions each tile
# with a cursor-neutral shift triple whose RETURN must undo exactly that advance — so it must know the real
# advance of every tile. The chest frames are solid (every tile advances a full cell), but a SCREEN overlay
# has partial/transparent tiles whose advance is smaller and VARIES, so a single uniform value drifts them
# (each row slid right + 1px seams). We therefore compute each tile's exact advance here and write them to
# menu/tile-advances.txt (read by MenuArt); fully-transparent tiles get 0 and are skipped by the title-trick.
# (Earlier an alpha=1 "anchor" pixel tried to force a uniform advance, but the client did not count it for
# the transparent screen tiles — the per-tile advance is the robust fix and needs no in-image marker.)
# ===========================================================================

TILE_GRID = 4                          # NxN tiles per background (mirror of MenuArt.TILE_GRID)
TILE_SOURCE = 768 // TILE_GRID         # 192px source tile (<=256 font-atlas ceiling)
TILE_SCREEN = 256 // TILE_GRID         # 64px on-screen tile (mirror of MenuArt.TILE_SCREEN)
# Where MenuArt reads the per-tile advances from (a generated resource, sibling to backgrounds.yml).
TILE_MANIFEST = os.path.join(ROOT, "packages", "core", "src", "main", "resources", "menu", "tile-advances.txt")


def _tiled_background_sources():
    """The committed 768px backgrounds that need tiling (chest frames + baked screens)."""
    srcs = sorted(glob.glob(os.path.join(CHEST_DIR, "chest_*.png")))
    srcs += sorted(glob.glob(os.path.join(SCREENS_DIR, "main-hub*.png")))
    # Skip already-emitted strips (chest_6.row0.png) so a re-run is idempotent.
    return [p for p in srcs if ".row" not in os.path.basename(p)]


def _strip_path(src, row):
    stem, _ = os.path.splitext(src)
    return "%s.row%d.png" % (stem, row)


def _tile_advance(strip_px, cell, col, grid):
    """Minecraft's bitmap advance for one tile cell of a strip: round((rightmost_opaque+1) * 64/cell) + 1.
    Returns 0 for a fully-transparent cell (the title-trick skips it)."""
    x0 = col * cell
    rightmost = -1
    for x in range(cell - 1, -1, -1):
        if any(strip_px[x0 + x, y][3] > 0 for y in range(cell)):
            rightmost = x
            break
    if rightmost < 0:
        return 0
    return int(round((rightmost + 1) * TILE_SCREEN / float(cell))) + 1


def cmd_tile_backgrounds(args):
    _need_pil()
    grid = TILE_GRID
    assets_root = os.path.join(ROOT, "assets")
    n = 0
    manifest = {}  # stem (e.g. "ui/chest/chest_6") -> [grid*grid advances, row-major]
    for src in _tiled_background_sources():
        img = Image.open(src).convert("RGBA")
        w, h = img.size
        cell = w // grid
        if w != h or w % grid != 0 or cell > 256:
            raise SystemExit("background %s is %dx%d; need a square multiple of %d with cell<=256"
                             % (src, w, h, grid))
        advances = []
        for r in range(grid):
            strip = img.crop((0, r * cell, w, (r + 1) * cell))  # w x cell
            strip.save(_strip_path(src, r))
            n += 1
            px = strip.load()
            advances += [_tile_advance(px, cell, c, grid) for c in range(grid)]
        stem = os.path.splitext(os.path.relpath(src, assets_root))[0].replace(os.sep, "/")
        manifest[stem] = advances
        empties = sum(1 for a in advances if a == 0)
        print("tiled %-30s -> %d strips %dx%d  (advances %s, %d empty)"
              % (stem, grid, w, cell, "/".join(str(a) for a in sorted(set(advances))), empties))
    # Write the per-tile advance manifest MenuArt reads (each line: stem=a0,a1,...). Sorted for a stable diff.
    os.makedirs(os.path.dirname(TILE_MANIFEST), exist_ok=True)
    lines = ["# Per-tile horizontal advances (GUI px) for the tiled menu backgrounds, row-major over the\n",
             "# TILE_GRID x TILE_GRID grid. Generated by `scripts/art.py tile-backgrounds`; read by MenuArt.\n",
             "# 0 = fully-transparent tile (the title-trick skips it). Do not hand-edit.\n"]
    for stem in sorted(manifest):
        lines.append("%s=%s\n" % (stem, ",".join(str(a) for a in manifest[stem])))
    with open(TILE_MANIFEST, "w") as f:
        f.writelines(lines)
    print("tile-backgrounds: wrote %d strips (%dx%d grid, %dpx source tiles) + %s"
          % (n, grid, grid, TILE_SOURCE, os.path.relpath(TILE_MANIFEST, ROOT)))


# ===========================================================================
# CLI
# ===========================================================================

def main():
    ap = argparse.ArgumentParser(
        prog="art.py",
        description="Sexidium art toolchain (icon sheet + menu/chest textures).",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True, metavar="<command>")

    p = sub.add_parser("extract", help="detect + de-key + cut icons/input.png into per-icon PNGs")
    p.add_argument("--in", dest="inp", default="icons/input.png")
    p.add_argument("--out", dest="out", default="/tmp/icons_raw")
    p.set_defaults(func=cmd_extract)

    p = sub.add_parser("montage", help="preview sheets of the extracted icons")
    p.add_argument("--raw", default="/tmp/icons_raw")
    p.add_argument("--named", default="/tmp/icons_named")
    p.add_argument("--out", default="/tmp")
    p.add_argument("--mode", choices=["idx", "pos", "proof"], default="pos")
    p.set_defaults(func=cmd_montage)

    p = sub.add_parser("debug", help="detection diagnostics + green-bbox overlays")
    p.add_argument("--in", dest="inp", default="icons/input.png")
    p.add_argument("--out", default="/tmp")
    p.set_defaults(func=cmd_debug)

    p = sub.add_parser("name-zip", help="name + classify the raw icons -> sexidium-icons.zip")
    p.set_defaults(func=cmd_name_zip)

    p = sub.add_parser("cut-icons", help="flood-key cut the minigame/experience/ui sheet -> assets/icons/*")
    p.add_argument("--in", dest="inp", default=ICON_SHEET)
    p.set_defaults(func=cmd_cut_icons)

    p = sub.add_parser("depink", help="kill residual magenta pockets inside already-cut icons")
    p.add_argument("files", nargs="+", help="icon PNG paths or dirs (relative paths resolve under assets/icons)")
    p.set_defaults(func=cmd_depink)

    p = sub.add_parser("gen-menu-art", help="import assets/icons/<section>/* into the menu pack")
    p.set_defaults(func=cmd_gen_menu_art)

    p = sub.add_parser("bake-overhang", help="ornate overhang chest frames (legacy generator)")
    p.set_defaults(func=cmd_bake_overhang)

    p = sub.add_parser("upscale-sources",
                       help="Real-ESRGAN 4x super-resolution of the 256px medieval sources (run in .venv-rembg)")
    p.set_defaults(func=cmd_upscale_sources)

    p = sub.add_parser("bake-medieval", help="import UltimateGUI medieval generic_<slots> -> chest frames (live)")
    p.set_defaults(func=cmd_bake_medieval)

    p = sub.add_parser("overlay-hub", help="validate the baked hub overlays sit on chest_6's item slots")
    p.set_defaults(func=cmd_overlay_hub)

    p = sub.add_parser("tile-backgrounds",
                       help="split 768px chest/screen backgrounds into <=256px font-glyph row strips")
    p.set_defaults(func=cmd_tile_backgrounds)

    p = sub.add_parser("slice-typography", help="slice medieval font sheets -> item/font_title|font_button per-char")
    p.set_defaults(func=cmd_slice_typography)

    p = sub.add_parser("cut-chest", help="cut UI/slots/* flat slot frames into chest backgrounds")
    p.set_defaults(func=cmd_cut_chest)

    p = sub.add_parser("extend-chest", help="extend cut frames over the player inventory")
    p.set_defaults(func=cmd_extend_chest)

    p = sub.add_parser("align-chest", help="warp cut frames onto the real slot grid")
    p.set_defaults(func=cmd_align_chest)

    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
