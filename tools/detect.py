#!/usr/bin/env python3
"""
Local iteration harness for ShotScorer's CV pipeline.

Two selectable strategies, both mirror-able to Kotlin:

  --strategy card    : original two-stage — find bright card blob, bulls inside.
  --strategy cluster : find all bull candidates, cluster them, largest cluster =
                       the aim card. Better on range scenes where the whole
                       wall is bright and multiple cards / bullet holes coexist.

Usage:
    python detect.py <video.mp4>                      # preview window
    python detect.py <video.mp4> --strategy cluster --frame 60 --out x.png
    python detect.py <video.mp4> --out annotated.mp4  # export annotated video

Interactive keys: space=pause, n=next, p=prev, q=quit.
"""
from __future__ import annotations

import argparse
import sys
import time
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np


# ---------- Parameters (tune these against the sample MP4s) ----------

PROC_WIDTH = 960
CLAHE_CLIP = 2.5
CLAHE_TILE = 8

# "card" strategy — largest bright rectangle
CARD_MIN_AREA_FRAC = 0.03
CARD_MAX_AREA_FRAC = 0.75
CARD_MORPH_FRAC    = 0.08
CARD_ASPECT_MIN    = 0.3
CARD_ASPECT_MAX    = 3.5


# Bull detection (Hough) — used by both strategies
BULL_MIN_R         = 4
BULL_MAX_R_ABS     = 18    # hard cap in DOWNSCALED pixels. Real bulls are 5-15 px here.
BULL_MAX_R_FRAC    = 0.10  # of min(cardW,cardH) inside the ROI. Was 0.25 (too loose).
BULL_MIN_DIST_MUL  = 3
HOUGH_PARAM1       = 100
HOUGH_PARAM2       = 13    # even more permissive so faint 50m bulls survive
HOUGH_DP           = 1.2
CONTRAST_THRESHOLD = 40.0  # real bulls score 55-90; scene shadows 15-40. Filters clutter.

# Non-max suppression: drop lower-scoring circles that overlap a higher one
NMS_OVERLAP_R_MUL  = 1.0   # threshold: dist < this × max(r1,r2) → suppress worse

# "cluster" strategy — group bulls by spatial proximity
CLUSTER_MIN_BULLS  = 3      # a cluster needs at least this many
CLUSTER_EPS_R_MUL  = 4.0    # link bulls if centre distance ≤ this × mean radius

MAX_BULLS_KEPT = 200  # generous — cluster/NMS do the real filtering


@dataclass
class DetectResult:
    card: tuple[int, int, int, int] | None      # x,y,w,h full-res
    bulls: list[tuple[float, float, float, float]]  # cx,cy,r,score full-res
    active_index: int


# ---------- Contrast metric ----------

def bull_contrast(gray: np.ndarray, cx: int, cy: int, r: int) -> float:
    if r < 3:
        return 0.0
    h, w = gray.shape[:2]
    inner_r = max(2, int(r * 0.7))
    ix, iy = max(0, cx - inner_r), max(0, cy - inner_r)
    iw = min(2 * inner_r, w - ix); ih = min(2 * inner_r, h - iy)
    if iw <= 0 or ih <= 0: return 0.0
    outer_r = int(r * 1.6)
    ox, oy = max(0, cx - outer_r), max(0, cy - outer_r)
    ow = min(2 * outer_r, w - ox); oh = min(2 * outer_r, h - oy)
    if ow <= 0 or oh <= 0: return 0.0
    inner_mean = float(gray[iy:iy+ih, ix:ix+iw].mean())
    outer_mean = float(gray[oy:oy+oh, ox:ox+ow].mean())
    inner_area = iw * ih; outer_area = ow * oh
    a = inner_area / outer_area if outer_area > 0 else 0.0
    surround = (outer_mean - a * inner_mean) / (1.0 - a) if a < 1.0 else outer_mean
    return surround - inner_mean


# ---------- Card via brightness (original strategy) ----------

def find_card_bright(gray_small: np.ndarray) -> tuple[int, int, int, int] | None:
    h, w = gray_small.shape[:2]
    _, thresh = cv2.threshold(gray_small, 0, 255,
                              cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    k = max(9, int(min(w, h) * CARD_MORPH_FRAC))
    if k % 2 == 0: k += 1
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (k, k))
    closed = cv2.morphologyEx(thresh, cv2.MORPH_CLOSE, kernel)
    contours, _ = cv2.findContours(closed, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    frame_area = w * h
    min_a = frame_area * CARD_MIN_AREA_FRAC
    max_a = frame_area * CARD_MAX_AREA_FRAC
    best_area = 0.0
    best_rect = None
    for c in contours:
        area = cv2.contourArea(c)
        if area < min_a or area > max_a: continue
        x, y, cw, ch = cv2.boundingRect(c)
        ar = cw / ch if ch > 0 else 0.0
        if not (CARD_ASPECT_MIN <= ar <= CARD_ASPECT_MAX): continue
        if area > best_area:
            best_area = area
            best_rect = (x, y, cw, ch)
    return best_rect


# ---------- Bull scan ----------

def hough_bulls(gray_small: np.ndarray, roi: tuple[int, int, int, int] | None):
    """Return list of (cx, cy, r, score) in SMALL-frame coords."""
    if roi is None:
        work = gray_small
        offset = (0, 0)
    else:
        x, y, w, h = roi
        work = gray_small[y:y+h, x:x+w]
        offset = (x, y)
    clahe = cv2.createCLAHE(clipLimit=CLAHE_CLIP,
                            tileGridSize=(CLAHE_TILE, CLAHE_TILE))
    enh = clahe.apply(work)
    enh = cv2.medianBlur(enh, 3)
    h, w = work.shape[:2]
    # Two ceilings: absolute (whole-frame scan) and relative-to-ROI.
    max_r = min(BULL_MAX_R_ABS, max(20, int(min(w, h) * BULL_MAX_R_FRAC)))
    circles = cv2.HoughCircles(
        enh, cv2.HOUGH_GRADIENT,
        dp=HOUGH_DP,
        minDist=BULL_MIN_R * BULL_MIN_DIST_MUL,
        param1=HOUGH_PARAM1, param2=HOUGH_PARAM2,
        minRadius=BULL_MIN_R, maxRadius=max_r,
    )
    out = []
    if circles is not None:
        for cx, cy, r in circles[0]:
            score = bull_contrast(work, int(cx), int(cy), int(r))
            if score >= CONTRAST_THRESHOLD:
                out.append((float(cx + offset[0]), float(cy + offset[1]),
                            float(r), float(score)))
    out.sort(key=lambda b: -b[3])
    # Non-max suppression on remaining candidates
    kept: list[tuple[float, float, float, float]] = []
    for b in out:
        cx, cy, r, _ = b
        conflict = False
        for k in kept:
            kx, ky, kr, _ = k
            dist2 = (cx - kx) ** 2 + (cy - ky) ** 2
            thresh = max(r, kr) * NMS_OVERLAP_R_MUL
            if dist2 < thresh * thresh:
                conflict = True
                break
        if not conflict:
            kept.append(b)
    return kept[:MAX_BULLS_KEPT]


# ---------- Cluster-based card ----------

def cluster_bulls(bulls):
    """Union-find grouping. Two bulls in same cluster if their centres are
    within CLUSTER_EPS_R_MUL × mean radius of each other. Returns list of
    (indices_in_cluster) lists, largest first."""
    n = len(bulls)
    if n == 0: return []
    parent = list(range(n))
    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a
    def union(a, b):
        ra, rb = find(a), find(b)
        if ra != rb: parent[ra] = rb
    for i in range(n):
        for j in range(i + 1, n):
            cxi, cyi, ri, _ = bulls[i]
            cxj, cyj, rj, _ = bulls[j]
            eps = ((ri + rj) / 2.0) * CLUSTER_EPS_R_MUL
            if (cxi - cxj) ** 2 + (cyi - cyj) ** 2 <= eps * eps:
                union(i, j)
    groups: dict[int, list[int]] = {}
    for i in range(n):
        groups.setdefault(find(i), []).append(i)
    return sorted(groups.values(), key=len, reverse=True)


# ---------- Full-frame pipelines ----------

def detect_card_first(bgr: np.ndarray) -> DetectResult:
    h, w = bgr.shape[:2]
    scale = PROC_WIDTH / w
    small = cv2.resize(cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY),
                       (PROC_WIDTH, int(h * scale)))
    card_small = find_card_bright(small)
    if card_small is None:
        return DetectResult(None, [], -1)
    bulls_s = hough_bulls(small, card_small)
    bulls_full = [(bx / scale, by / scale, br / scale, sc) for bx, by, br, sc in bulls_s]
    cx0, cy0, cw, ch = card_small
    card_full = (int(cx0/scale), int(cy0/scale), int(cw/scale), int(ch/scale))
    active = _active(bulls_full, w, h)
    return DetectResult(card_full, bulls_full, active)


def detect_cluster(bgr: np.ndarray, keep_all_bulls: bool = False) -> DetectResult:
    h, w = bgr.shape[:2]
    scale = PROC_WIDTH / w
    small = cv2.resize(cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY),
                       (PROC_WIDTH, int(h * scale)))
    bulls_s = hough_bulls(small, roi=None)
    if not bulls_s:
        return DetectResult(None, [], -1)
    clusters = cluster_bulls(bulls_s)
    valid = [g for g in clusters if len(g) >= CLUSTER_MIN_BULLS]
    if not valid:
        # Show all as amber, no card
        bulls_full = [(bx / scale, by / scale, br / scale, sc)
                      for bx, by, br, sc in bulls_s]
        return DetectResult(None, bulls_full, _active(bulls_full, w, h))

    # Pick the LARGEST cluster (most bulls) — that's the real card.
    # Ties broken by proximity to frame centre.
    def centroid(g):
        xs = [bulls_s[i][0] for i in g]
        ys = [bulls_s[i][1] for i in g]
        return sum(xs) / len(xs), sum(ys) / len(ys)
    fcx, fcy = PROC_WIDTH / 2, small.shape[0] / 2
    aim_cluster = max(
        valid,
        key=lambda g: (len(g), -((centroid(g)[0] - fcx) ** 2
                                 + (centroid(g)[1] - fcy) ** 2)),
    )

    # Card = bounding box of aim cluster, padded 10% for context.
    xs = [bulls_s[i][0] for i in aim_cluster]
    ys = [bulls_s[i][1] for i in aim_cluster]
    rs = [bulls_s[i][2] for i in aim_cluster]
    x0 = int(min(xs) - max(rs) * 1.2); y0 = int(min(ys) - max(rs) * 1.2)
    x1 = int(max(xs) + max(rs) * 1.2); y1 = int(max(ys) + max(rs) * 1.2)
    x0 = max(0, x0); y0 = max(0, y0)
    x1 = min(small.shape[1], x1); y1 = min(small.shape[0], y1)
    card_full = (int(x0/scale), int(y0/scale),
                 int((x1-x0)/scale), int((y1-y0)/scale))

    if keep_all_bulls:
        keep = bulls_s
    else:
        keep = [bulls_s[i] for i in aim_cluster]
    bulls_full = [(bx / scale, by / scale, br / scale, sc) for bx, by, br, sc in keep]
    return DetectResult(card_full, bulls_full, _active(bulls_full, w, h))


def _active(bulls, w, h) -> int:
    if not bulls: return -1
    fcx, fcy = w / 2, h / 2
    return min(range(len(bulls)),
               key=lambda i: (bulls[i][0] - fcx) ** 2 + (bulls[i][1] - fcy) ** 2)


# ---------- Render ----------

def annotate(frame, res: DetectResult, hud: str = ""):
    out = frame.copy()
    if res.card is not None:
        x, y, w, h = res.card
        cv2.rectangle(out, (x, y), (x + w, y + h), (255, 180, 80), 3)
    for i, (cx, cy, r, score) in enumerate(res.bulls):
        colour = (0, 255, 0) if i == res.active_index else (0, 200, 255)
        thick = 4 if i == res.active_index else 2
        cv2.circle(out, (int(cx), int(cy)), int(r), colour, thick)
        if i == res.active_index:
            arm = max(30, int(r * 1.5))
            cv2.line(out, (int(cx - arm), int(cy)), (int(cx + arm), int(cy)), colour, 3)
            cv2.line(out, (int(cx), int(cy - arm)), (int(cx), int(cy + arm)), colour, 3)
        cv2.putText(out, f"{score:.0f}",
                    (int(cx) + int(r) + 4, int(cy) - int(r)),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.6, colour, 2)
    for i, line in enumerate(hud.splitlines()):
        cv2.putText(out, line, (12, 32 + 28 * i),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)
    return out


# ---------- Runners ----------

def pick_strategy(name: str):
    if name == "card":
        return detect_card_first
    if name == "cluster":
        return detect_cluster
    if name == "cluster-all":
        return lambda f: detect_cluster(f, keep_all_bulls=True)
    sys.exit(f"unknown strategy {name}")


def run_preview(path: Path, strat, start_frame: int = 0):
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened(): sys.exit(f"could not open {path}")
    if start_frame: cap.set(cv2.CAP_PROP_POS_FRAMES, start_frame)
    win = "shotscorer detect (space=pause n=next p=prev q=quit)"
    cv2.namedWindow(win, cv2.WINDOW_NORMAL)
    cv2.resizeWindow(win, 1600, 900)
    paused = False
    frame_idx = start_frame
    frame = None
    while True:
        if not paused or frame is None:
            ok, frame = cap.read()
            if not ok: break
            frame_idx = int(cap.get(cv2.CAP_PROP_POS_FRAMES))
        t0 = time.time()
        res = strat(frame)
        dt = (time.time() - t0) * 1000
        hud = f"frame {frame_idx}  {dt:.0f}ms\ncard:{'yes' if res.card else 'no'}  bulls:{len(res.bulls)}"
        cv2.imshow(win, annotate(frame, res, hud))
        key = cv2.waitKey(0 if paused else 1) & 0xFF
        if key == ord('q'): break
        elif key == ord(' '): paused = not paused
        elif key == ord('n') and paused:
            ok, frame = cap.read()
            if ok: frame_idx = int(cap.get(cv2.CAP_PROP_POS_FRAMES))
        elif key == ord('p') and paused:
            cap.set(cv2.CAP_PROP_POS_FRAMES, max(0, frame_idx - 2))
            ok, frame = cap.read()
            if ok: frame_idx = int(cap.get(cv2.CAP_PROP_POS_FRAMES))
    cap.release(); cv2.destroyAllWindows()


def run_still(path: Path, strat, out_path: Path, frame_idx: int):
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened(): sys.exit(f"could not open {path}")
    cap.set(cv2.CAP_PROP_POS_FRAMES, frame_idx)
    ok, frame = cap.read()
    if not ok: sys.exit(f"could not read frame {frame_idx}")
    res = strat(frame)
    hud = f"frame {frame_idx}  card:{'yes' if res.card else 'no'}  bulls:{len(res.bulls)}"
    cv2.imwrite(str(out_path), annotate(frame, res, hud))
    print(f"wrote {out_path}  bulls:{len(res.bulls)} card:{'yes' if res.card else 'no'}")


def run_export(path: Path, strat, out_path: Path, every_n: int):
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened(): sys.exit(f"could not open {path}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 24.0
    w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    n = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    fourcc = cv2.VideoWriter_fourcc(*"mp4v")
    writer = cv2.VideoWriter(str(out_path), fourcc, fps / every_n, (w, h))
    idx = hits = 0
    t0 = time.time()
    while True:
        ok, frame = cap.read()
        if not ok: break
        if idx % every_n == 0:
            res = strat(frame)
            if res.card is not None: hits += 1
            hud = f"f{idx}/{n}  card:{'Y' if res.card else 'N'} bulls:{len(res.bulls)}"
            writer.write(annotate(frame, res, hud))
        idx += 1
        if idx % 60 == 0:
            elapsed = time.time() - t0
            print(f"  f {idx}/{n}   card-hit {hits}/{(idx+every_n-1)//every_n}   "
                  f"{idx/elapsed:.1f} in-fps")
    writer.release(); cap.release()
    print(f"wrote {out_path}")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("video", type=Path)
    ap.add_argument("--strategy", default="cluster",
                    choices=["card", "cluster", "cluster-all"])
    ap.add_argument("--out", type=Path)
    ap.add_argument("--every", type=int, default=1)
    ap.add_argument("--frame", type=int)
    ap.add_argument("--start", type=int, default=0)
    args = ap.parse_args()
    if not args.video.exists(): sys.exit(f"video not found: {args.video}")
    strat = pick_strategy(args.strategy)
    if args.frame is not None:
        run_still(args.video, strat, args.out or Path("annotated.png"), args.frame)
    elif args.out:
        run_export(args.video, strat, args.out, args.every)
    else:
        run_preview(args.video, strat, args.start)


if __name__ == "__main__":
    main()
