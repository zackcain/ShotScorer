# Local CV iteration harness

Reproduces the Kotlin bull-detection pipeline in Python so you can tune it
against recorded MP4s without building an APK. All logic mirrors
`app/src/main/java/com/shotscorer/app/MainActivity.kt`'s `detectFrame(...)`.

## One-time setup

Requires Python 3.10+ (Windows: from the Microsoft Store is fine).

```
python -m pip install opencv-python numpy
```

## Usage

Interactive preview — a resizable window with keyboard controls:

```
python detect.py path/to/your.mp4
```

Keys inside the window: `space` pause/resume, `n` next frame while paused,
`p` previous, `q` quit.

Dump one annotated still (fast way to test parameter changes):

```
python detect.py path/to/your.mp4 --frame 60 --out annotated.png
```

Export a whole annotated video (slower but shows temporal consistency):

```
python detect.py path/to/your.mp4 --out annotated.mp4 --every 2
```

`--every N` processes every N-th frame (halve the framerate at N=2, etc) to
speed up long recordings.

## What the overlay means

- **Blue rectangle**: detected card boundary (may not appear on some scenes)
- **Amber circles**: candidate bulls that passed the contrast filter
- **Green crosshair**: the "active" bull, defined as the passed candidate
  closest to frame centre (the rifle-mounted camera aims at whichever bull
  is in the middle)
- **Number next to each circle**: contrast score (0-255 scale). Real bulls
  typically score 40-100; scores under ~25 are usually junk.

## Tuning workflow

Parameters live at the top of `detect.py`. Change one, re-run against the
same MP4, compare results. When you find values that work well across all
the test clips, port them into `MainActivity.kt` — the parameter names
match exactly.

Suggested test clips to keep on disk:

- `10m_clean.mp4` — single bull, easy case
- `25yd_wall.mp4` — three cards on range wall, medium case
- `50m_far.mp4` — small card at distance + bullet-hole clutter, hardest case

A parameter set that catches ≥90% of bulls on the first two and never
false-positives on the third is the goalpost.
