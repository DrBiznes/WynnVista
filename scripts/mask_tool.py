#!/usr/bin/env python3
"""Read a block mask recorded by the development-only MaskDump (see docs/TERRAIN_MASKS.md).

  info     layout, coverage, and how the filled blocks are spread over height
  preview  top-down pictures: highest block, lowest block, and number of separate layers per column
  reduce   the 2D texture the world effects read (see "Reduced map" in docs/TERRAIN_MASKS.md)

Needs numpy and Pillow.
"""

import argparse
import gzip
from pathlib import Path
import struct

import numpy as np
from PIL import Image

MAGIC = b"WVMASK1\n"
DISTANCE_SCALE = 4  # the reduced map stores the distance into the void in quarter blocks
FALL_FOOT = 2       # a waterfall into the void reaches down to this height or lower
FALL_HEIGHT = 8     # and is at least this many blocks of water high
FALL_REACH = 16     # blocks around a waterfall over which the reduced map's alpha falls from 255 to 0


class Mask:
    def __init__(self, path: Path):
        with gzip.open(path, "rb") as f:
            if f.read(len(MAGIC)) != MAGIC:
                raise SystemExit(f"{path} is not a WVMASK1 file")
            self.min_x, self.min_y, self.min_z, self.size_x, self.size_y, self.size_z = struct.unpack(">6i", f.read(24))
            chunks = (self.size_x // 16) * (self.size_z // 16)
            self.recorded = np.frombuffer(f.read(chunks), np.uint8).reshape(self.size_z // 16, self.size_x // 16)
            count = self.size_x * self.size_y * self.size_z
            size = (count + 7) // 8
            shape = (self.size_y, self.size_z, self.size_x)
            # [y, z, x], True where a block is filled.
            self.solid = np.unpackbits(np.frombuffer(f.read(size), np.uint8), bitorder="little")[:count].reshape(shape).astype(bool)
            self.fluid = np.unpackbits(np.frombuffer(f.read(size), np.uint8), bitorder="little")[:count].reshape(shape).astype(bool)


def cmd_info(mask: Mask, args) -> None:
    print(f"x {mask.min_x}..{mask.min_x + mask.size_x - 1}, y {mask.min_y}..{mask.min_y + mask.size_y - 1}, "
          f"z {mask.min_z}..{mask.min_z + mask.size_z - 1}")
    print(f"chunks recorded: {int(mask.recorded.sum())} / {mask.recorded.size}")
    missing = np.argwhere(mask.recorded == 0)
    for cz, cx in missing[:20]:
        print(f"  missing chunk at block {mask.min_x + cx * 16 + 8}, {mask.min_z + cz * 16 + 8}")
    print(f"visible blocks: {int(mask.solid.sum()):,}; fluid blocks: {int(mask.fluid.sum()):,}")
    any_solid = mask.solid.any(axis=0)
    print(f"columns with no block at all (void): {100 * (1 - any_solid.mean()):.1f}%")
    per_level = mask.solid.sum(axis=(1, 2))
    levels = np.nonzero(per_level)[0]
    print(f"lowest block y {mask.min_y + levels[0]}, highest y {mask.min_y + levels[-1]}")
    cumulative = np.cumsum(per_level) / per_level.sum()
    for share in (0.001, 0.01, 0.05, 0.25, 0.5, 0.75, 0.95, 0.99):
        print(f"  {share * 100:5.1f}% of blocks are at or below y {mask.min_y + int(np.searchsorted(cumulative, share))}")
    print("blocks per 16 levels:")
    for start in range(0, mask.size_y, 16):
        total = int(per_level[start:start + 16].sum())
        if total:
            print(f"  y {mask.min_y + start:4d}..{mask.min_y + start + 15:4d}  {total:>11,}")


def cmd_preview(mask: Mask, args) -> None:
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    any_solid = mask.solid.any(axis=0)
    top = mask.size_y - 1 - np.argmax(mask.solid[::-1], axis=0)
    bottom = np.argmax(mask.solid, axis=0)
    # A layer starts wherever a filled block has an empty one beneath it.
    starts = mask.solid.copy()
    starts[1:] &= ~mask.solid[:-1]
    layers = starts.sum(axis=0)
    span = max(1, mask.size_y - 1)
    for name, values in (("top", top), ("bottom", bottom)):
        grey = np.where(any_solid, 40 + values * 215 // span, 0).astype(np.uint8)
        Image.fromarray(grey).save(out / f"{args.name}_{name}.png")
    Image.fromarray(np.minimum(layers * 40, 255).astype(np.uint8)).save(out / f"{args.name}_layers.png")
    print(f"wrote {args.name}_top.png, {args.name}_bottom.png, {args.name}_layers.png to {out}")
    print(f"columns with 2 or more layers: {100 * (layers >= 2).mean():.1f}%, 4 or more: {100 * (layers >= 4).mean():.1f}%")


def distance_to(target: np.ndarray, limit: float) -> np.ndarray:
    """Blocks from each column to the nearest column of `target` (3-4 chamfer), 0 on those, at most `limit`."""
    distance = np.where(target, np.float32(0), np.float32(limit * 3))
    while True:
        padded = np.pad(distance, 1, constant_values=limit * 3)
        best = distance
        for dz in (-1, 0, 1):
            for dx in (-1, 0, 1):
                if dz or dx:
                    step = 4 if dz and dx else 3
                    best = np.minimum(best, padded[1 + dz:1 + dz + distance.shape[0], 1 + dx:1 + dx + distance.shape[1]] + step)
        if np.array_equal(best, distance):
            return np.minimum(distance / 3, limit)
        distance = best


def cmd_reduce(mask: Mask, args) -> None:
    solid = mask.solid
    height = mask.size_y
    void = ~solid.any(axis=0)
    lowest = np.argmax(solid, axis=0)
    # Top of the lowest unbroken run of blocks in each column: the first empty block above the lowest one.
    above = np.arange(height)[:, None, None] > lowest[None]
    run_top = np.where((~solid & above).any(axis=0), np.argmax(~solid & above, axis=0), height) - 1
    # A block of that run has a face towards the void where a neighbouring column is still empty at its
    # height, that is, below the neighbour's lowest block. Columns outside the box count as filled.
    open_below = np.pad(np.where(void, height, lowest), 1, constant_values=0)
    reach = np.zeros_like(lowest)
    for dz in (-1, 0, 1):
        for dx in (-1, 0, 1):
            reach = np.maximum(reach, open_below[1 + dz:1 + dz + mask.size_z, 1 + dx:1 + dx + mask.size_x])
    exposed_to = np.minimum(run_top, np.maximum(reach - 1, lowest)) + 1
    distance = distance_to(~void, 255 / DISTANCE_SCALE)
    # A waterfall into the void: water standing at least FALL_HEIGHT blocks high on the bottom of the world.
    fluid = mask.fluid
    foot = np.argmax(fluid, axis=0)
    over = np.arange(height)[:, None, None] > foot[None]
    fall_top = np.where((~fluid & over).any(axis=0), np.argmax(~fluid & over, axis=0), height)
    falls = fluid.any(axis=0) & (foot + mask.min_y <= FALL_FOOT) & (fall_top - foot >= FALL_HEIGHT)
    near_fall = 1 - distance_to(falls, FALL_REACH) / FALL_REACH

    out = np.zeros((mask.size_z, mask.size_x, 4), np.uint8)
    out[..., 0] = np.where(void, 0, np.clip(exposed_to + mask.min_y, 1, 255))
    out[..., 1] = np.where(void, 255, np.clip(lowest + mask.min_y, 0, 254))
    out[..., 2] = np.round(distance * DISTANCE_SCALE)
    out[..., 3] = np.round(near_fall * 255)
    path = Path(args.out)
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(out, "RGBA").save(path, optimize=True)
    print(f"wrote {path}: {mask.size_x} x {mask.size_z}, {path.stat().st_size:,} bytes")
    print(f"origin x {mask.min_x}, z {mask.min_z}; void columns {100 * void.mean():.1f}%, "
          f"widest gap {2 * distance.max():.0f} blocks, waterfall columns {int(falls.sum())}; "
          f"water outside any block column {int((fluid & ~solid).any(axis=0).sum())}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("mask", type=Path)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("info").set_defaults(run=cmd_info)
    p = sub.add_parser("preview")
    p.add_argument("--out", default="build/mask-preview")
    p.add_argument("--name", default="mask")
    p.set_defaults(run=cmd_preview)
    p = sub.add_parser("reduce")
    p.add_argument("--out", required=True, help="PNG to write, under src/main/resources/assets/wynnvista/textures/effects/")
    p.set_defaults(run=cmd_reduce)
    args = parser.parse_args()
    args.run(Mask(args.mask), args)


if __name__ == "__main__":
    main()
