"""Measures and shows what changes from frame to frame in a run of consecutive frames saved by the fixture
(lod_fixture.py run --burst TICK:FRAMES --capture-prefix NAME).

    python scripts/frame_flicker.py NAME-bTICK [--crop X0,Y0,X1,Y1] [--dir run-voxy/screenshots] [--out DIR]

Flicker on a pixel is half the mean size of its second difference in time, |f[i-1] - 2 f[i] + f[i+1]| / 2, in
8-bit luma: a pixel that changes steadily (a moving camera, drifting smoke) scores near nothing, one that jumps
back and forth scores the size of its jump. Printed for the crop: the mean, the 99th percentile, and the share of
pixels above 2 and above 6. Written to --out (default build/flicker):

    NAME-bTICK-heat.png    the crop's flicker, 0 black to 16 and more white, beside the first frame
    NAME-bTICK-strip.png   the crop in the first frames, enlarged, to look at an edge frame by frame

A shader pack's clouds drift and change on their own, and that is in the score too: compare a crop with an effect
in it against one without, not one run against another.
"""
import argparse
import glob
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]


def load(pattern: str, crop):
    files = sorted(glob.glob(pattern))
    if len(files) < 3:
        raise SystemExit(f"Need at least three frames, found {len(files)} for {pattern}")
    frames = []
    for name in files:
        image = Image.open(name).convert("RGB")
        frames.append(np.asarray(image.crop(crop) if crop else image, dtype=np.float32))
    return np.stack(frames)


def flicker(frames: np.ndarray) -> np.ndarray:
    luma = frames @ np.array([0.299, 0.587, 0.114], dtype=np.float32)
    return np.abs(luma[:-2] - 2 * luma[1:-1] + luma[2:]).mean(axis=0) / 2


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("burst", help="NAME-bTICK, the part of the file names before -fNN.png")
    parser.add_argument("--crop", help="X0,Y0,X1,Y1 in pixels; the whole frame if left out")
    parser.add_argument("--dir", default=str(ROOT / "run-voxy" / "screenshots"))
    parser.add_argument("--out", default=str(ROOT / "build" / "flicker"))
    parser.add_argument("--strip-frames", type=int, default=6)
    parser.add_argument("--zoom", type=int, default=0, help="enlargement of the strip; chosen to fit if left out")
    args = parser.parse_args()
    crop = tuple(int(v) for v in args.crop.split(",")) if args.crop else None
    frames = load(str(Path(args.dir) / f"{args.burst}-f*.png"), crop)
    score = flicker(frames)
    print(f"{args.burst}: {len(frames)} frames, {score.shape[1]}x{score.shape[0]} px: mean {score.mean():.3f}, "
          f"p99 {np.percentile(score, 99):.2f}, max {score.max():.1f}, "
          f"over 2: {100 * (score > 2).mean():.2f}%, over 6: {100 * (score > 6).mean():.2f}%")

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    heat = np.clip(score / 16, 0, 1) * 255
    heat = np.stack([heat, heat, heat], axis=-1)
    side = np.concatenate([frames[0], heat], axis=1).astype(np.uint8)
    Image.fromarray(side).save(out / f"{args.burst}-heat.png")

    count = min(args.strip_frames, len(frames))
    zoom = args.zoom or max(1, min(6, 1800 // (score.shape[1] * count)))
    tiles = [np.repeat(np.repeat(frames[i], zoom, axis=0), zoom, axis=1) for i in range(count)]
    gap = np.full((tiles[0].shape[0], 4, 3), 255, dtype=np.float32)
    row = []
    for tile in tiles:
        row += [tile, gap]
    Image.fromarray(np.concatenate(row[:-1], axis=1).astype(np.uint8)).save(out / f"{args.burst}-strip.png")
    print(f"Wrote {out / (args.burst + '-heat.png')} and {out / (args.burst + '-strip.png')}")


if __name__ == "__main__":
    main()
