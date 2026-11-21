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

MAGICS = (b"WVMASK1\n", b"WVMASK2\n")  # the second adds the leaves layer
DISTANCE_SCALE = 4  # the reduced map stores the distance into the void in quarter blocks
FALL_FOOT = 2       # a waterfall into the void reaches down to this height or lower
FALL_HEIGHT = 8     # and is at least this many blocks of water high
FALL_REACH = 16     # blocks around a waterfall over which the reduced map's alpha falls from 255 to 0


class Mask:
    def __init__(self, path: Path):
        with gzip.open(path, "rb") as f:
            magic = f.read(len(MAGICS[0]))
            if magic not in MAGICS:
                raise SystemExit(f"{path} is not a WVMASK file")
            self.min_x, self.min_y, self.min_z, self.size_x, self.size_y, self.size_z = struct.unpack(">6i", f.read(24))
            chunks = (self.size_x // 16) * (self.size_z // 16)
            self.recorded = np.frombuffer(f.read(chunks), np.uint8).reshape(self.size_z // 16, self.size_x // 16)
            count = self.size_x * self.size_y * self.size_z
            size = (count + 7) // 8
            shape = (self.size_y, self.size_z, self.size_x)
            # [y, z, x], True where a block is filled.
            self.solid = np.unpackbits(np.frombuffer(f.read(size), np.uint8), bitorder="little")[:count].reshape(shape).astype(bool)
            self.fluid = np.unpackbits(np.frombuffer(f.read(size), np.uint8), bitorder="little")[:count].reshape(shape).astype(bool)
            # Leaves, a subset of solid; none in a WVMASK1 file, which did not record them.
            if magic == MAGICS[1]:
                self.leaves = np.unpackbits(np.frombuffer(f.read(size), np.uint8), bitorder="little")[:count].reshape(shape).astype(bool)
            else:
                self.leaves = np.zeros(shape, bool)


def cmd_info(mask: Mask, args) -> None:
    print(f"x {mask.min_x}..{mask.min_x + mask.size_x - 1}, y {mask.min_y}..{mask.min_y + mask.size_y - 1}, "
          f"z {mask.min_z}..{mask.min_z + mask.size_z - 1}")
    print(f"chunks recorded: {int(mask.recorded.sum())} / {mask.recorded.size}")
    missing = np.argwhere(mask.recorded == 0)
    for cz, cx in missing[:20]:
        print(f"  missing chunk at block {mask.min_x + cx * 16 + 8}, {mask.min_z + cz * 16 + 8}")
    print(f"visible blocks: {int(mask.solid.sum()):,}; fluid blocks: {int(mask.fluid.sum()):,}; "
          f"leaves: {int(mask.leaves.sum()):,}")
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
    written = f"{args.name}_top.png, {args.name}_bottom.png, {args.name}_layers.png"
    if mask.leaves.any():
        # The highest block that is not leaves: ground, buildings, and the trunks and boughs of trees.
        wood = mask.solid & ~mask.leaves
        wood_top = mask.size_y - 1 - np.argmax(wood[::-1], axis=0)
        grey = np.where(wood.any(axis=0), 40 + wood_top * 215 // span, 0).astype(np.uint8)
        Image.fromarray(grey).save(out / f"{args.name}_wood.png")
        written += f", {args.name}_wood.png"
    print(f"wrote {written} to {out}")
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


def components(mask: np.ndarray) -> tuple[np.ndarray, int]:
    """Labels the 8-connected groups of True columns from 1; 0 elsewhere. Returns the labels and their number."""
    labels = np.zeros(mask.shape, np.int32)
    size_z, size_x = mask.shape
    count = 0
    for z0, x0 in zip(*np.nonzero(mask)):
        if labels[z0, x0]:
            continue
        count += 1
        labels[z0, x0] = count
        stack = [(z0, x0)]
        while stack:
            z, x = stack.pop()
            for zz in range(max(z - 1, 0), min(z + 2, size_z)):
                for xx in range(max(x - 1, 0), min(x + 2, size_x)):
                    if mask[zz, xx] and not labels[zz, xx]:
                        labels[zz, xx] = count
                        stack.append((zz, xx))
    return labels, count


def box_blur(values: np.ndarray, radius: int) -> np.ndarray:
    """Mean over a square of side 2 * radius + 1 around each column; the edge columns are repeated outward."""
    out = values.astype(np.float32)
    for axis in (0, 1):
        pad = [(0, 0), (0, 0)]
        pad[axis] = (radius + 1, radius)
        total = np.cumsum(np.pad(out, pad, mode="edge"), axis=axis)
        span = 2 * radius + 1
        out = (np.take(total, np.arange(span, total.shape[axis]), axis=axis)
               - np.take(total, np.arange(0, total.shape[axis] - span), axis=axis)) / span
    return out


def potential_flow(obstacle: np.ndarray, wind: tuple[float, float]) -> np.ndarray:
    """The even wind `wind` (x, z) as it goes around the obstacle columns: [z, x, 2], x and z of the flow.

    The flow has no sources and does not turn on itself, so it is the gradient of a stream function that
    satisfies Laplace's equation, is the even wind's at the sides of the box, and is one constant on each
    obstacle, chosen so that nothing flows through it: the mean of the air around it. Solved by relaxation,
    coarse first.
    """
    def stream(z, x):
        return wind[0] * z - wind[1] * x

    psi = None
    for scale in (8, 4, 2, 1):
        size_z, size_x = obstacle.shape[0] // scale, obstacle.shape[1] // scale
        solid = obstacle[:size_z * scale, :size_x * scale].reshape(size_z, scale, size_x, scale).any(axis=(1, 3))
        z, x = np.mgrid[:size_z, :size_x].astype(np.float32) * scale + (scale - 1) / 2
        free = stream(z, x)
        if psi is None:
            psi = free.copy()
        else:
            psi = np.repeat(np.repeat(psi, 2, axis=0), 2, axis=1)[:size_z, :size_x]
            psi = np.pad(psi, ((0, size_z - psi.shape[0]), (0, size_x - psi.shape[1])), mode="edge")
        edge = np.zeros_like(solid)
        edge[0], edge[-1], edge[:, 0], edge[:, -1] = True, True, True, True
        labels, count = components(solid)
        # Every face between an obstacle column and an open one: the open column, and the obstacle it touches.
        open_cells, touching = [], []
        for dz, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            moved = np.roll(labels, (dz, dx), axis=(0, 1))
            if dz:
                moved[0 if dz > 0 else -1] = 0
            else:
                moved[:, 0 if dx > 0 else -1] = 0
            faces = (moved > 0) & ~solid
            open_cells.append(np.flatnonzero(faces))
            touching.append(moved[faces])
        open_cells, touching = np.concatenate(open_cells), np.concatenate(touching)
        faces = np.maximum(np.bincount(touching, minlength=count + 1), 1)
        # The coarse levels carry the flow across the box and are cheap; the fine ones only round its corners.
        for _ in range(500 * scale):
            padded = np.pad(psi, 1, mode="edge")
            mean = 0.25 * (padded[:-2, 1:-1] + padded[2:, 1:-1] + padded[1:-1, :-2] + padded[1:-1, 2:])
            psi = np.where(edge, free, mean)
            level = np.bincount(touching, weights=psi.ravel()[open_cells], minlength=count + 1) / faces
            psi = np.where(solid, level[labels], psi)
    padded = np.pad(psi, 1, mode="edge")
    flow = np.stack(((padded[2:, 1:-1] - padded[:-2, 1:-1]) / 2, -(padded[1:-1, 2:] - padded[1:-1, :-2]) / 2), axis=-1)
    flow[solid] = 0
    return flow


# The Mistwoods' canopy mist deck (MistwoodsMist): the levels its map is cut at.
MIST_DECK = 102             # the deck's top: above the lower canopy, below the crowns of the giant trees
MIST_BAND = (96, 110)       # what stands in the deck's top is in its way: wood on most of these levels
MIST_BAND_SHARE = 10
MIST_OBSTACLE = 40          # columns; smaller groups are the tips of gathering trees, which change
MIST_WIND = (-0.9487, 0.3162)   # x and z of the wind the mist drifts on, down the valley; WIND in mistwoods_mist.fsh
MIST_FLOW_SCALE = 2.0       # the map holds the flow as a share of this many times the even wind's speed
MIST_FLOW_STEP = 4          # steps of the flow's 256 levels that are kept; fewer compress better
MIST_FLOW_EVEN = 0.5        # power the flow's speed is raised to, which evens it out
MIST_LAND = 20000           # columns; an obstacle this large is the land around the woods, not a tree
POUR_OFF_LAND = 24          # blocks from that land within which no ledge spills
MIST_FOREST_BLUR = 20       # blocks over which the cover of leaves is averaged to find the woods
MIST_FOREST_COVER = 0.12    # share of columns under leaves above which a place is in the woods
MIST_FADE = 48              # blocks past the woods' edge over which the mist thins to nothing
# The wall and the castles that part the woods from Hyloch and the highlands, as a line through them from west
# to east, x and z: the two towers beside the citadel, the castle north of Aldwell, the wall and the castle at
# its east end, then north along that castle's east side: the woods east of it run on behind the wall there.
# The mist lies south of the line.
MIST_NORTH = ((-1728, -849), (-1375, -849), (-1325, -867), (-1268, -867), (-1257, -910), (-1243, -931),
              (-1190, -931), (-1165, -938), (-1124, -945), (-1118, -1040), (-913, -1040))
# The industrial wall between the woods and Timasca, which runs south from the citadel's gate, as a line
# through it from north to south, z and x. North of z -665 the citadel itself is the border. The mist lies
# east of the line.
MIST_WEST = ((-1040, -1728), (-666, -1728), (-664, -1637), (-520, -1636), (-508, -1624), (-465, -1624))
MIST_BORDER_FADE = 3        # blocks inside those lines over which the mist comes to its full density
MIST_BORDER_RISE = 0.75     # blocks the deck's top may rise per block away from them
MIST_BORDER_FREE = 140      # a wall this high holds the deck at any height
POUR_LEVELS = (112, 176)    # ledges between these heights can spill mist: clear of the deck, not up in the crowns
POUR_LEDGE = 14             # surface blocks a ledge has within 3 blocks, so a bough's end or a twig is none
POUR_DROP = 12              # blocks of open air under the ledge's lip
POUR_RUN = 4                # columns of lip in a row; shorter ones are left out
POUR_REACH = 5              # blocks around a lip that know its heights


def cmd_reduce_mistwoods(mask: Mask, args) -> None:
    """The Mistwoods' map: two tiles, one under the other (see "Mistwoods map" in docs/TERRAIN_MASKS.md)."""
    solid = mask.solid
    wood = solid & ~mask.leaves
    height, size_z, size_x = solid.shape
    unrecorded = np.kron(mask.recorded == 0, np.ones((16, 16), bool))

    # What stands in the deck's top: giant trunks, the citadel's wall, the rim of the basin.
    band = wood[MIST_BAND[0] - mask.min_y:MIST_BAND[1] + 1 - mask.min_y].sum(axis=0) >= MIST_BAND_SHARE
    labels, count = components(band)
    sizes = np.bincount(labels.ravel(), minlength=count + 1)
    obstacle = ((labels > 0) & (sizes[labels] >= MIST_OBSTACLE)) | unrecorded
    distance = distance_to(obstacle, 255 / DISTANCE_SCALE)

    flow = potential_flow(obstacle, MIST_WIND)
    speed = np.hypot(flow[..., 0], flow[..., 1])

    # The woods: where leaves cover the ground, as one piece with its clearings filled in. Only leaves the
    # deck reaches count: the cherry trees of Hyloch stand on higher ground beside the woods.
    canopy = mask.leaves[:MIST_DECK - mask.min_y].any(axis=0)
    cover = box_blur(box_blur(canopy, MIST_FOREST_BLUR), MIST_FOREST_BLUR)
    labels, count = components(cover > MIST_FOREST_COVER)
    sizes = np.bincount(labels.ravel(), minlength=count + 1)
    sizes[0] = 0
    woods = labels == np.argmax(sizes)
    outside, _ = components(np.pad(~woods, 1, constant_values=True))
    woods = outside[1:-1, 1:-1] != outside[0, 0]
    # Past the wall and castles towards Hyloch and past the industrial wall towards Timasca there is no mist,
    # trees or not: it comes hard up to them.
    xs, zs = mask.min_x + np.arange(size_x) + 0.5, mask.min_z + np.arange(size_z) + 0.5
    north = np.interp(xs, [x for x, _ in MIST_NORTH], [z for _, z in MIST_NORTH])
    west = np.interp(zs, [z for z, _ in MIST_WEST], [x for _, x in MIST_WEST])
    inside = np.minimum((zs[:, None] - north[None]) / np.sqrt(1 + np.gradient(north) ** 2)[None],
                        (xs[None] - west[:, None]) / np.sqrt(1 + np.gradient(west) ** 2)[:, None])
    woods &= inside > 0
    held = np.clip(inside / MIST_BORDER_FADE, 0, 1)
    area = np.minimum(1 - distance_to(woods, MIST_FADE) / MIST_FADE, held * held * (3 - 2 * held))
    # A wall holds the mist no higher than it stands. Along both lines the deck's top is held at the height
    # of what the line runs through (a castle, a wall, or bare ground where there is neither) and may rise
    # from there by MIST_BORDER_RISE a block. The height is the middle one of 21 blocks of line, so that a
    # tree beside the wall or a gap in its battlements does not count.
    wood_top = np.where(wood.any(axis=0), height - 1 - np.argmax(wood[::-1], axis=0), 0) + mask.min_y
    ceiling = np.full((size_z, size_x), 1.0e4, np.float32)
    for line in ([(x, z) for x, z in MIST_NORTH], [(x, z) for z, x in MIST_WEST]):
        cells = []
        for (x0, z0), (x1, z1) in zip(line, line[1:]):
            steps = int(max(abs(x1 - x0), abs(z1 - z0)))
            cells += [(round(z0 + (z1 - z0) * i / steps) - mask.min_z, round(x0 + (x1 - x0) * i / steps) - mask.min_x)
                      for i in range(steps)]
        cells = [(z, x) for z, x in cells if 4 < z < size_z - 5 and 4 < x < size_x - 5]
        tops = np.array([wood_top[z - 4:z + 5, x - 4:x + 5].max() for z, x in cells], np.float32)
        middle = np.median(np.lib.stride_tricks.sliding_window_view(np.pad(tops, 10, mode="edge"), 21), axis=1)
        for (z, x), top in zip(cells, middle):
            ceiling[z, x] = min(ceiling[z, x], top)
    while True:
        padded = np.pad(ceiling, 1, constant_values=1.0e4)
        best = ceiling
        for dz in (-1, 0, 1):
            for dx in (-1, 0, 1):
                if dz or dx:
                    step = MIST_BORDER_RISE * (1.4142 if dz and dx else 1.0)
                    best = np.minimum(best, padded[1 + dz:1 + dz + size_z, 1 + dx:1 + dx + size_x] + step)
        best = np.minimum(best, 255)
        if np.array_equal(best, ceiling):
            break
        ceiling = best
    ceiling = np.where(ceiling >= MIST_BORDER_FREE, 255, ceiling)
    # The valley narrows towards the south-west and the true flow is four times as fast there as in the
    # north-east. The mist keeps the flow's directions and its still places, with less of that range.
    even = np.median(speed[woods & ~obstacle])
    flow *= ((np.maximum(speed, 1e-6) / even) ** MIST_FLOW_EVEN / np.maximum(speed, 1e-6))[..., None]
    # The land the woods lie between; its walls hold no mist to spill.
    labels, count = components(obstacle)
    sizes = np.bincount(labels.ravel(), minlength=count + 1)
    land = (labels > 0) & (sizes[labels] >= MIST_LAND)
    off_land = distance_to(land, POUR_OFF_LAND) >= POUR_OFF_LAND

    # Ledges above the deck that mist can pour from: the open column beside a lip, the lip's height, and
    # where the fall ends.
    low, high = POUR_LEVELS[0] - mask.min_y, POUR_LEVELS[1] - mask.min_y
    air = ~solid
    surface = np.zeros_like(solid)
    surface[:-3] = wood[:-3] & air[1:-2] & air[2:-1] & air[3:]
    lip = np.zeros((size_z, size_x), np.int32)       # per open column, the level of the mist on the ledge beside it
    for y in range(low, high):
        ledge = surface[y] & (box_blur(surface[y - 1:y + 2].any(axis=0), 3) * 49 >= POUR_LEDGE)
        if not ledge.any():
            continue
        clear = air[y - POUR_DROP:y + 2].all(axis=0)
        beside = np.zeros_like(ledge)
        beside[1:] |= ledge[:-1]
        beside[:-1] |= ledge[1:]
        beside[:, 1:] |= ledge[:, :-1]
        beside[:, :-1] |= ledge[:, 1:]
        lip = np.where(beside & clear, y + 1, lip)
    lip = np.where(woods & off_land, lip, 0)
    labels, count = components(lip > 0)
    sizes = np.bincount(labels.ravel(), minlength=count + 1)
    lip = np.where(sizes[labels] >= POUR_RUN, lip, 0)
    pours = lip > 0
    # Where each fall ends: the first block under the lip's open air, or the bottom of the world.
    levels = np.arange(height)[:, None, None]
    below = solid & (levels < lip[None])
    foot = np.where(pours, np.where(below.any(axis=0), height - np.argmax(below[::-1], axis=0), 0), 0)
    reach = distance_to(pours, 255 / 2)
    # The columns around a lip take its heights, the highest lip's where two are as near.
    near_lip, near_foot = lip.copy(), foot.copy()
    for _ in range(POUR_REACH):
        padded_lip, padded_foot = np.pad(near_lip, 1), np.pad(near_foot, 1)
        best_lip, best_foot = near_lip.copy(), near_foot.copy()
        for dz in (-1, 0, 1):
            for dx in (-1, 0, 1):
                other = padded_lip[1 + dz:1 + dz + size_z, 1 + dx:1 + dx + size_x]
                take = (near_lip == 0) & (other > best_lip)
                best_lip = np.where(take, other, best_lip)
                best_foot = np.where(take, padded_foot[1 + dz:1 + dz + size_z, 1 + dx:1 + dx + size_x], best_foot)
        near_lip, near_foot = best_lip, best_foot

    out = np.zeros((2 * size_z, size_x, 4), np.uint8)
    coded = np.clip(np.round(flow / MIST_FLOW_SCALE * 127 / MIST_FLOW_STEP) * MIST_FLOW_STEP + 128, 0, 255)
    out[:size_z, :, 0:2] = coded
    out[:size_z, :, 2] = np.round(distance * DISTANCE_SCALE)
    out[:size_z, :, 3] = np.round(np.clip(area, 0, 1) * 255)
    out[size_z:, :, 0] = np.clip(near_lip + mask.min_y, 0, 255)
    out[size_z:, :, 1] = np.round(np.minimum(reach * 2, 255))
    out[size_z:, :, 2] = np.clip(near_foot + mask.min_y, 0, 255)
    out[size_z:, :, 3] = np.round(ceiling)
    path = Path(args.out)
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(out, "RGBA").save(path, optimize=True)
    print(f"wrote {path}: {size_x} x {2 * size_z}, {path.stat().st_size:,} bytes")
    print(f"origin x {mask.min_x}, z {mask.min_z}; obstacles {100 * obstacle.mean():.1f}% of columns, "
          f"woods {100 * woods.mean():.1f}%, flow in the woods "
          f"{np.percentile(np.hypot(flow[..., 0], flow[..., 1])[woods & ~obstacle], [5, 50, 95]).round(2)} "
          f"of the even wind, pour columns {int(pours.sum())} on {len(np.unique(lip[pours]))} levels, "
          f"deck held down over {100 * ((ceiling < MIST_DECK) & (area > 0)).mean():.1f}% of columns, lowest {ceiling[area > 0].min():.0f}")
    if args.preview:
        preview = Path(args.preview)
        preview.mkdir(parents=True, exist_ok=True)
        picture = np.zeros((size_z, size_x, 3), np.uint8)
        picture[..., 1] = np.round(np.clip(area, 0, 1) * 110)
        picture[..., 2] = np.round(np.clip(np.hypot(flow[..., 0], flow[..., 1]) / MIST_FLOW_SCALE, 0, 1) * 255)
        picture[obstacle] = (150, 150, 150)
        picture[pours] = (255, 60, 60)
        Image.fromarray(picture).save(preview / "mistwoods_map.png")
        print(f"wrote {preview / 'mistwoods_map.png'}: obstacles grey, the woods green, the flow's speed blue, lips red")


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
    p = sub.add_parser("reduce-mistwoods")
    p.add_argument("--out", required=True, help="PNG to write, under src/main/resources/assets/wynnvista/textures/effects/")
    p.add_argument("--preview", help="folder for a picture of what the map holds")
    p.set_defaults(run=cmd_reduce_mistwoods)
    args = parser.parse_args()
    args.run(Mask(args.mask), args)


if __name__ == "__main__":
    main()
