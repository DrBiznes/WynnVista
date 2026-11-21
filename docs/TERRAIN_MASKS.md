# Terrain masks

Date: 2026-10-07. Branch `feature/sky-islands-effects`.

A terrain mask is a record of which blocks of one area of the Wynncraft map are filled. World effects that have to know where the ground is in three dimensions (the underside of a floating island, the gap between two islands, an overhang) cannot get that from the depth buffer, which only knows the first surface along each view ray. The mask is recorded once from the live server with a development build, kept in the repository, and reduced to whatever an effect needs.

Masks so far:

| Area | File | Box (blocks) | Recorded |
| --- | --- | --- | --- |
| Sky Islands | `masks/sky_islands.wvmask` (2.0 MB) | x 704..1535, y 0..255, z -5008..-4369 | 2026-10-07, 2,080 of 2,080 chunks |
| Mistwoods | `masks/mistwoods.wvmask` (1.9 MB) | x -1728..-913, y 0..255, z -1040..-465 | 2026-11-20, 1,761 of 1,836 chunks. The 75 missing are the east edge (x -1008..-913, z -768..-465), in the province's mountains. The south stops at z -465, at the wall to the wastelands, which could not be passed |

## What is recorded

For every block position in a chunk-aligned box, over the world's whole height, at one block resolution:

- **Visible**: any block that is drawn. Air, barriers, light blocks and structure voids are left out (`BlockRenderType.INVISIBLE`).
- **Fluid**: any block holding water or lava, including waterlogged blocks. A waterlogged stair is in both.
- **Leaves** (from 2026-11-20, for the Mistwoods): any block in the tag `minecraft:leaves`. What is left of a tree without them is its trunk and boughs, so a canopy can be told from the wood that carries it.

It comes from the chunks the client has loaded, so it is the real server terrain, not LOD data. Not recorded: entities (Wynncraft builds some decoration from display entities), block types, and anything the server changes later.

## Recording a mask

The recorder is `scripts/maskdump/MaskDump.java`. It is kept outside the mod's sources and is not in any released jar.

1. Set the area in `MaskDump.java`: `NAME`, and the chunk bounds `MIN_CHUNK_X`, `MAX_CHUNK_X`, `MIN_CHUNK_Z`, `MAX_CHUNK_Z` (block coordinate divided by 16, rounded down). Leave a margin of a few chunks around the area the effect covers.
2. Copy the file to `src/main/java/me/jamino/wynndhrangelimiter/debug/MaskDump.java`.
3. In `WynndhrangelimiterClient.onInitializeClient`, after `MOD.initialize();`, add `me.jamino.wynndhrangelimiter.debug.MaskDump.register();`.
4. Set `mod_version=2.1.0-dev` (the current version with `-dev`) in `gradle.properties`, so the jar cannot be mistaken for a release.
5. `gradlew build`, and put `build/libs/WynnVista-<version>-dev.jar` into the `mods` folder of a Modrinth profile in place of the released WynnVista.
6. Join Wynncraft and fly over the area. Recording runs by itself on a Wynncraft server while the player is within 600 blocks of the box; nothing is sent to the server.
   - A chat line reports every 5% of the chunks.
   - `/wvmask` shows the progress and the block coordinates of the nearest chunk still missing.
   - `/wvmask save` writes now. It is also written every minute, on leaving the server, and when the last chunk is recorded.
   - `/wvmask reset` starts again.
   - Progress is kept between sessions: the file is read back on the next start.
7. The output is in the profile folder under `wynnvista-mask/`: `<name>.wvmask`, and `<name>_preview.png`, a top-down picture with the height of the highest block as brightness, black for void and red for chunks not yet recorded.
8. Copy `<name>.wvmask` to `masks/`, check it with `scripts/mask_tool.py` (below), and add it to the table above.
9. Undo steps 2 to 4 (`git checkout -- gradle.properties src/main/java/.../WynndhrangelimiterClient.java` and delete the copied class). Commit any change to the recorder itself in `scripts/maskdump/`.

A chunk is recorded once, the first time it is seen loaded. A chunk the server sent incompletely would stay that way until `/wvmask reset`; this has not been observed.

## File format

`.wvmask` is gzip. Inside, big-endian:

| Bytes | Content |
| --- | --- |
| 8 | `WVMASK2\n` (`WVMASK1\n` in `sky_islands.wvmask`, which has no leaves) |
| 6 x 4 | ints `minX`, `minY`, `minZ`, `sizeX`, `sizeY`, `sizeZ` |
| `sizeX/16 * sizeZ/16` | one byte per chunk, 1 when recorded; x runs first, then z |
| `ceil(sizeX * sizeY * sizeZ / 8)` | visible blocks, one bit each |
| the same again | fluid blocks, one bit each |
| the same again, `WVMASK2` only | leaves (the block tag `minecraft:leaves`), one bit each; every one is also a visible block |

A block's bit index is `(y * sizeZ + z) * sizeX + x`, with x, y and z counted from the minimum corner; the lowest bit of each byte comes first.

## Reading a mask

`scripts/mask_tool.py` (needs numpy and Pillow):

```
python scripts/mask_tool.py masks/sky_islands.wvmask info
python scripts/mask_tool.py masks/sky_islands.wvmask preview --name sky_islands
```

`info` prints the box, the chunks missing, the block counts and how the blocks are spread over height. `preview` writes three top-down pictures (four for a mask with leaves: the fourth is the highest block that is not a leaf) to `build/mask-preview/`: the highest block of each column, the lowest, and the number of separate layers (a layer starts wherever a filled block has an empty one beneath it). `Mask` in the same file loads the two bit sets as numpy arrays indexed `[y, z, x]`.

## Reduced map

Effects do not read the mask. `mask_tool.py reduce` turns it into a picture with one texel per block column, shipped as a mod asset and bound as `uTerrainMap` to an effect that names it in `WorldEffect.terrainMap()`. Both Sky Islands effects do, with the map below; the Mistwoods have a map of their own kind (see Mistwoods map).

```
python scripts/mask_tool.py masks/sky_islands.wvmask reduce --out src/main/resources/assets/wynnvista/textures/effects/sky_islands.png
```

| Channel | Content |
| --- | --- |
| Red | Height below which the column's lowest run of blocks faces the void: the top of that run, or the lowest block of a neighbouring column if that is lower (of the eight neighbours, the highest). 0 for a column with no block. Terrain between green and red is an underside or a cliff over the void. Not read by an effect at present |
| Green | y of the column's lowest block; 255 for a column with no block |
| Blue | For a column with no block, the distance to the nearest column with one, in quarter blocks (up to 63.75); 0 elsewhere. The updrafts rise only where it is above 0 |
| Alpha | Nearness of a waterfall into the void: 255 on a column of water at least 8 blocks high that reaches down to y 2 or lower, falling to 0 over 16 blocks. Spray rises around it and the updrafts keep away |

The texel of block column `x, z` is `x - minX, z - minZ`, with the box of the mask. `sky_islands.png` is 832 x 640 and 419 KB. When the mask is recorded again the map has to be regenerated; `SkyIslandsVoidTest` checks its size and two known columns.

A 2D map was chosen over a 3D texture or distance field because the effects it is meant for are not marched: they ask whether a terrain pixel is on an underside, or how far a point is from the nearest island. Half of the area's columns have more than one layer, but what matters to light and wind coming from below is the lowest one.

## Mistwoods map

The canopy mist of the Mistwoods (`MistwoodsMist`) needs other things from its mask than the Sky Islands effects: what stands in the mist's top and how the mist flows around it, where the woods end, and which ledges mist can fall from. `mask_tool.py reduce-mistwoods` writes them as two tiles of 816 x 576, one under the other, in one picture:

```
python scripts/mask_tool.py masks/mistwoods.wvmask reduce-mistwoods --out src/main/resources/assets/wynnvista/textures/effects/mistwoods.png --preview build/mask-preview
```

| Tile | Channel | Content |
| --- | --- | --- |
| First | Red, green | x and z of the flow of the mist, as `128 + 127 * flow / 2`, in steps of 4: 1 is the speed of the even wind. 128 in both inside an obstacle |
| First | Blue | Distance to the nearest obstacle in quarter blocks (up to 63.75); 0 on one |
| First | Alpha | The woods: 255 inside their outline, falling to 0 over 48 blocks outside it |
| Second | Red | y of the mist on the nearest ledge that spills, for columns within 5 blocks of its lip; 0 elsewhere |
| Second | Green | Distance to the nearest lip in half blocks (up to 127.5); 0 on a lip's column |
| Second | Blue | y at which that ledge's fall ends: the first block under the lip's open air |
| Second | Alpha | y the deck's top is held at by a wall at the woods' edge that is lower than the deck; 255 where nothing holds it |

The texel of block column `x, z` is `x - minX, z - minZ` in the first tile and 576 rows further down in the second. `mistwoods.png` is 816 x 1152 and 191 KB. `MistwoodsMistTest` checks its size and known columns.

- **Obstacles** are what stands in the deck's top (y 102): columns with wood, that is blocks that are not leaves, on at least 10 of the 15 levels y 96 to 110, in groups of at least 40 columns. Smaller groups are the tips of gathering trees, which change with every server restart. The unrecorded chunks count as obstacles. That leaves the giant trunks, a few buildings, the citadel's plateau and the mountains.
- **The flow** is the even wind (towards `-0.95, 0.32`, down the valley) as it goes around the obstacles: a potential flow, found as the stream function that satisfies Laplace's equation, is the even wind's at the sides of the box and is constant on each obstacle, with the constant that lets nothing through it. It is solved by relaxation on four grids, coarse first (4,000 passes at 8 blocks down to 500 at 1). The valley narrows towards the south-west and the true flow is four times as fast there as in the north-east; the map keeps the directions and the still places and raises the speed to the power 0.5, which leaves 0.67 to 1.4 times the even wind over 90% of the woods.
- **The woods** are where leaves below the deck's top cover more than 12% of the columns, averaged twice over 41 blocks: the largest such piece, with its clearings filled. Only leaves the deck reaches count, because the mask does not tell a cherry tree from a Mistwoods tree and Hyloch's cherry trees stand on higher ground beside the woods. Two borders are lines drawn by hand through the buildings that make them, because the mist ends there whatever grows behind them. `MIST_NORTH` runs through the wall and castles towards Hyloch and the highlands: the two towers beside the citadel (`-1408 -858`, `-1385 -858`), the castle north of Aldwell (`-1295 -885`), the wall and the castle at its east end (`-1150 -965`), then north along that castle's east side, because east of it the woods run on behind the wall. `MIST_WEST` runs through the industrial wall towards Timasca, south from the citadel's gate at about x -1636. Past either line the alpha is 0, and it reaches its full value 3 blocks inside it: the mist comes hard up to the wall and the cut is inside the masonry.
- **Ledges**: a surface block (wood with three open blocks above it) between y 112 and y 176 with at least 14 surface blocks within 3 blocks of it on its own level or the next, beside a column that is open from 12 blocks below it to one above. Runs of fewer than 4 such columns are dropped, as is everything within 24 blocks of the land around the woods, whose walls hold no mist. 879 columns are left, around the giant trees and Aldwell.

- **Held down by a wall.** The industrial wall stands to y 73 to 88 and the deck's top is at y 102, so mist filled to the deck's height lay over the wall and into Timasca. Along both border lines the deck's top is held at the height of what the line runs through, taken as the highest block that is not a leaf within 4 blocks of the line and then the middle value of 21 blocks of line, so a tree beside the wall or a gap in its battlements does not count. From there it may rise by 0.75 blocks per block into the woods. A castle of 140 blocks or more holds the deck at any height. Where the line crosses bare ground the deck comes down to the ground. 3.6% of the map's columns are held below the deck's height, the lowest at y 68.

The numbers are constants at the head of that part of `mask_tool.py` (`MIST_*`, `POUR_*`). `MIST_DECK` and `MIST_WIND` have to agree with `DECK` and `WIND` in `mistwoods_mist.fsh`.

## Mistwoods: what the mask shows

- 1,761 of 1,836 chunks. The strip that is missing is in the mountains east of the woods; the south stops at z -465, some 40 blocks short of the wall to the wastelands, which cannot be passed.
- 44.1 million visible blocks, 68,377 fluid blocks, 508,909 leaves over 19% of the columns, nearly all of them in the woods.
- The forest floor is mostly at y 40 to 63, most often about y 50. At y 60 to 100 the land around it is already solid on two sides: the citadel's plateau in the north-west and the mountains in the south-east.
- The leaves are in two tiers. 55% of them are below y 96: the gathering trees and the lower boughs of the giant trees. Between y 104 and y 128 there are few, and above that are the giant trees' crowns, up to y 215.
- The giant trunks are 16 to 25 blocks across at y 110 and their wood reaches y 180 to 252. The clearest are at about `-1389 -563`, `-1536 -538`, `-1170 -725`, `-1238 -820` and `-1405 -713`, with the Aldwell group around `-1286 -670`, `-1273 -724`, `-1313 -711` and `-1323 -679`.
- Aldwell has its ground at y 40 to 55 and platforms on every level from y 72 to about y 150.

## Sky Islands: what the mask shows

- The world there is 256 blocks high, y 0 to 255. Blocks were found from y 0 to y 254.
- 12.8% of the columns in the box hold no block at all: the void between the islands.
- The mainland around the islands is filled down to y 0.
- The islands' undersides are spikes. Half of the island columns have their lowest block at or below y 63, a quarter at or below y 32, and the longest spikes end at y 1 to 3. An effect lying on the void therefore has to sit at about y 0, with the spikes dipping into it.
- 48% of all columns have two or more layers and 12% have four or more, so a single height per column (top and underside only) would lose much of the area.
- 73,570 fluid blocks. Water is not a visible block in the mask's sense, so a waterfall through the void leaves its columns empty. 128 columns hold water at least 8 blocks high that reaches y 2 or lower: 49 waterfalls into the void, the largest group at Sky Falls (around `1416 -4578` to `1452 -4576`), where the water is over 120 blocks high.

## Future plans

- **A 3D form of the mask.** The reduced map (above) knows only the lowest layer of each column. An effect that is marched between the islands, or that has to stop under an overhang, would need a coarse 3D texture or a distance field (at 4 blocks per texel the Sky Islands box is 208 x 64 x 160, 2.1 MB at one byte per texel). Not built.
- **More areas.** Other parts of the map with vertical terrain or a void under them will need the same treatment. Each is one more row in the table above and one more `.wvmask`.
- **Choose the area in game.** The box is a set of constants, so each area needs a rebuild. `/wvmask start <name> <x1> <z1> <x2> <z2>` would let one development jar record any number of areas.
- **Build switch.** Steps 2 to 4 and 9 are done by hand. A Gradle property that adds the recorder's source folder and registers it would make a development jar one command and leave nothing to undo.
- **Recorded data to add if an effect needs it:** block light sources (for glows that follow lanterns and crystals), a few block classes (leaves, water, ice) instead of one bit, and display entities.
- **Repository size.** A `.wvmask` is about 2 MB per area. If there are many, they may be better kept outside git (a release asset), with only the reduced assets in the repository.
- **Staleness.** A mask is a snapshot. When Wynncraft changes an area, it has to be recorded again; nothing detects this.
