# World effects

Date: 2026-10-06. Branch `feature/world-effects`. Effects: the smoke plume rising from Mount Wynn (peak at `-183 205 -1964`) and the lava fog over the Roots of Corruption, around the Nether portal. Tested on Windows 11, RTX 4070, Minecraft 1.21.11, DH 3.3.3, Voxy 0.2.16-beta, WynnIris 1.2.2.

## How it works

World effects are drawn over the **finished world image**, after every world pass (LODs, translucents, a shader pack's final pass) and before the hand and HUD. Each effect is ray-marched per pixel in camera-relative world space and stops at the nearest terrain, so it needs no geometry and no far plane: the plume is visible from any distance, with or without a LOD mod.

| Piece | Location |
| --- | --- |
| Hook: wraps `WorldRenderer.render` inside `GameRenderer.renderWorld` | `mixin/client/MixinGameRendererEffects` |
| Effect contract: id, config label, shader, anchor block, bounds, uniforms | `effects/WorldEffect` |
| Registry and renderer: culling, per-effect programs, half-resolution path, exact GL state save/restore | `effects/WorldEffects` |
| Region rule shared with the LOD mask (pure, unit-tested) | `effects/EffectRegion` |
| Frustum rectangle, sample and octave reduction (pure, unit-tested) | `effects/EffectCulling` |
| Program wrapper with `#include` expansion | `effects/EffectProgram` |
| LOD depth registry (one optional layer per frame) | `effects/LodDepth` |
| DH depth and projection, through the public DH API (`DhApiBeforeRenderEvent`, `IDhApiRenderProxy`) | `compat/dh/DhEffectDepth` |
| Voxy depth and projection | `compat/voxy/VoxyEffectDepth`, `MixinVoxyRenderPipelineDepth` |
| Fog probe rules: where it looks, which distances it trusts, smoothing (pure, unit-tested) | `effects/EffectFog` |
| The plume: placement, shape, sun/moon lighting, rendering style (pure, unit-tested) | `effects/SmokePlume` |
| The Roots of Corruption lava fog: placement, shape, drift, glow, rendering style (pure, unit-tested) | `effects/NetherFog` |
| Shaders: shared scene code, the plume's shape and its two styles, the lava fog's shape and its two styles, the half-resolution composite, the fog probe | `assets/wynnvista/shaders/effects/{scene.glsl,plume_shape.glsl,smoke_plume.fsh,smoke_plume_blocky.fsh,nether_fog_shape.glsl,nether_fog.fsh,nether_fog_blocky.fsh,upsample.fsh,fog_probe.fsh}` |

Occlusion uses two depth sources, each unprojected with the projection it was rendered with, and takes the nearer hit:

- **Vanilla depth** (Minecraft's main framebuffer; under Iris this is the pack's `depthtex0`, so the hand is included). Values at the far plane are ignored, because Voxy clamps its LOD depth onto the vanilla far plane there.
- **LOD depth**, when a LOD mod drew this frame. DH never writes Minecraft's depth buffer, and Voxy's own depth holds the true distance of far terrain.

The DH side uses only the DH API, so it is not tied to the pinned 3.3.3 terrain shaders (it is registered whenever DH is loaded). The Voxy side is a mixin and follows the existing Voxy version gate.

## Fog (shader packs, WynnIris ambiance packs)

Effects are drawn after the shader pack's final pass, so the pack's fog is already in the image and does not reach them. That fog cannot be read as a value:

- A pack's fog is computed in the pack's own GLSL from the pack's own options. Iris only hands packs the vanilla `fogColor` / `fogStart` / `fogEnd`, which do not describe it.
- A WynnIris ambiance profile is a shader pack name plus a map of that pack's option overrides (`AmbienceProfile`), swapped in as a cached pipeline when the player crosses a region. It adds no fog of its own; the fog is still the pack's (Photon, for the one ambiance pack available now), with option names that differ per pack.
- WynnIris' own post-process passes (Mist Woods fog, skybox scene effects, transitions) also run inside `WorldRenderer.render`, before the effect pass.

So the fog is measured from the finished image instead (`fog_probe.fsh`, one 1x1 target per effect). Each frame, before anything is drawn, the probe samples a 32x24 grid over the effect's part of the view, keeps the terrain whose distance is within 0.5x–1.5x of the effect's (vanilla or LOD depth), and records two things: how much pixel-to-pixel detail that terrain still has, and its average colour. Terrain swallowed by fog is flat and fog-coloured. The result is smoothed over about 0.4 s and applied by `throughFog()` in `scene.glsl`: as the detail goes from `CONTRAST_CLEAR` (0.05) to `CONTRAST_GONE` (0.015), the effect's colour goes from its own to the colour of that terrain, keeping its opacity. The plume therefore always looks like the mountain under it: invisible where the fog has the sky's colour, a pale shape where the pack's fog is lighter than its sky.

This works the same for any pack, for stock Iris, and without a shader pack. Limits:

- It needs terrain at about the effect's distance in view. Without a LOD mod and with the effect beyond vanilla render distance there is nothing to measure and the effect is drawn unfogged; the same holds while looking only at sky (the last value is kept, and dropped after the effect has been out of view for a second).
- The thresholds were set from Complementary Reimagined in the Voxy fixture (below). Photon with the WynnIris ambiance pack was checked by eye on the live server, not measured.

## Smoke plume styles

"Smoke Plume Style" on the World Effects config page (`smokePlumeStyle` in the config file) chooses how the plume is drawn. Placement, shape, density (`plume_shape.glsl`) and the sun and moon lighting are the same in both; switching takes effect on the next frame.

- **Realistic** (`REALISTIC`, the default, `smoke_plume.fsh`): the soft ray-marched volume described in the rest of this document.
- **Blocky** (`BLOCKY`, `smoke_plume_blocky.fsh`): translucent, flat-shaded cubes whose overlap adds up to the opacity, after the look of the [Better Clouds](https://github.com/Qendolin/better-clouds) mod. Only the technique follows that mod; none of its code or assets are included.

Better Clouds draws instanced cube geometry. Here the cubes are found per pixel instead, so the style uses the same pass, depth sources, culling and fog probe as every other effect.

- **Lattices.** The smoke leaves the vent as many small cubes and ends as few large ones. Four lattices, with cells of 2, 4, 8 and 16 blocks, each own a band of heights above the vent: from 0, 24, 72 and 200 blocks. All of them rise with the smoke (4.8 blocks per second). Where two bands meet, over 1.5 cells of the coarser lattice each way, the small cubes shrink away while the large ones grow in, so nothing pops as cubes cross the boundary.
- **Cubes.** A cell holds a cube when the density at its centre is high enough; the cube's size follows the density, and a smaller cube sits at a random place inside its cell. Density uses two noise octaves: finer detail would only shuffle which cells are filled.
- **March.** For each lattice the ray is clipped to the box of that band, then walks it cell by cell (at most 80 cells) and intersects each cube exactly, front to back. The bands are stacked by height, so they are visited in the order of the ray's climb or descent. The march stops when the smoke is opaque.
- **Opacity.** A full-size 16-block cube is 28% opaque; smaller cubes are fainter (18% at 2 blocks), which keeps a given thickness of smoke about equally opaque at every level while single cubes stay visible.
- **Colour.** A cube has one colour: the plume's lighting evaluated at the cell centre, brighter on the side of the column facing the light, a per-cube variation of up to 20%, and block-style shading of the face the ray enters by (top 1.0, sides 0.9 and 0.82, underside 0.7).
- **Near and far.** Cubes fade out between three cells and one cell from the camera. A lattice whose cells are smaller than 1.5 pixels at the plume's distance (`uPixelSize`) is not used; the first one that is large enough takes over its band. That switch happens for the whole plume at once as the camera crosses a distance, with no blend.
- Each lattice's period divides the noise period, so cubes keep their look when the scroll wraps.

The blocky style is never drawn at half resolution (`WorldEffect.halfResolution`), as the upsample would blur the cube edges.

## Roots of Corruption lava fog

`nether_fog` is a layer of glowing fog over the corrupted ground around the Nether portal: an ellipse centred on `254 -1300`, from y 67, below ground level (about y 85), up to y 197. Inside radii of 140 blocks along x and 90 along z it has its full density and height; it then disperses over a further 160 blocks. The ellipse was fitted to a top-down fixture screenshot of the area, not to exact map data.

- **The pit stays clear.** The floor is a hard limit: the bounding box starts at y 67 and the density is zero below it, so the portal's pit (floor near y 50), where the world event is fought, is not fogged. From down there the fog is a glowing ceiling.
- **It thins around the player.** Within 48 blocks of the camera the density falls to 30%, so a player walking on the surface inside the layer can still see.
- **No visible border.** Outside the core the density falls with the cube of a smooth fade and the top sinks to 40% of its height, so from outside the fog is a haze that gathers towards the middle. Upward it thins with the square of the height. The coarsest noise octave shifts the fade and lifts the underside by up to 14 blocks in places.

The look follows the Nether of Complementary Reimagined (`shaders/lib/atmospherics/netherStorm.glsl`), used as a reference for the technique only; no code of that pack is included, as its licence does not allow redistribution. The density is the sum of an even haze, soft billows, and wisps made by sampling stretched noise per octave with the wind reversed and doubled and raising each octave to the eighth power. It is thickest just above the floor and thins linearly to nothing at the top. Columns of brighter fog (one extra 2D noise sample) read as light shafts between the spikes. Opacity is capped at 93%.

The fog glows ember red near the ground and a dark blood red higher up. Within 75 blocks of the Nether portal (`342 -1292`) the glow turns to the portal's purple, strongest low down; this is a colour mix in the same march and adds no samples.

Lighting uses the plume's sun and moon model (`SmokePlume.lighting`): the fog's own lava glow is full at night and 65% by day, with sky and sun light scattered on top by day. Distance haze and `throughFog()` are applied as for the plume. It is drawn up to 2,000 blocks away.

Cost is kept down inside the shader: at most 40 samples and 3 octaves whatever the quality setting, at most 380 blocks marched, the shaft sample skipped once little light gets through, and the march stops when the fog is opaque. Standing inside it covers the whole view and takes the half-resolution path.

### Blocky style

"Lava Fog Style" on the World Effects config page (`netherFogStyle` in the config file) is separate from the plume's setting. **Realistic** (`REALISTIC`, the default) is the fog described above. **Blocky** (`BLOCKY`, `nether_fog_blocky.fsh`) uses the plume's cube technique, with the layer's shape, drifting noise and glow shared through `nether_fog_shape.glsl`. What differs from the plume:

- **Slabs on a fixed lattice.** Cells are flat: 6 x 3 x 6 blocks up to 36 blocks above the floor, 12 x 6 x 12 above that. The lattice does not move; the noise drifts through it, so slabs swell and shrink in place. A slab's size follows the billow and wisp noise at the cell centre, scaled by the square root of how much fog the layer allows there.
- **Haze per cell.** The even haze is not made of slabs. Each cell the ray crosses adds haze for the length of ray inside it, at the density of the cell centre, with the same extinction as the realistic haze. It is therefore constant within a cell.
- **Clearing around the camera.** Slabs shrink towards the camera and are absent within 10 blocks; the haze thins to 30% as in the realistic style.
- **Colour.** One colour per slab from the shared glow (ember, blood red, the portal's purple), brighter where the fog is thick, with a per-slab variation and the block-style face shading of the plume. There are no light shafts.
- **Limits.** At most 144 cells per lattice and 380 blocks of fog are walked, with two noise octaves. Opacity is capped at 93%. The fine lattice is replaced by the coarse one when its cells are lower than 1.5 pixels. No half resolution.

## Where an effect is shown

Decided on the CPU, in this order, before any GL call. If nothing survives, the frame does no effect work at all.

1. **Config.** The master switch `effectsEnabled`, then the effect's own switch in `effects` (`{"smoke_plume": false}`; an effect that is not listed is on). The config screen has a "World Effects" page with the master switch, the quality slider and one toggle per registered effect.
2. **Region mask.** An effect is anchored to a block and is shown exactly where LOD terrain at that block is shown: the anchor must lie inside the rectangles of the frame's `VisibilitySnapshot`, the same snapshot the DH and Voxy masks use. The plume therefore disappears with the main map in the Realm of Light, the Void, unlisted areas (`NONE`) and under any fixture override. With "Enable LOD Masking" off there is no mask, so the region the mask *would* select from the player's position is used instead. Effects never appear outside Wynncraft or the local fixture.
3. **Distance.** Horizontal distance from the anchor against the effect's `maxViewDistance`.
4. **Frustum.** The effect's bounding box is projected; a box entirely outside the view is skipped. The far plane is deliberately not tested.

## Performance

- **Scissor.** Only the screen rectangle of the bounding box is shaded.
- **Empty-space skip.** Inside that rectangle a 32-sample pass without noise finds where the ray is actually inside the column; rays that only cross the box stop there, and the expensive samples are spent only where smoke can be.
- **Half resolution.** An effect covering more than 12% of the view is marched into a half-size buffer and composited by `upsample.fsh`, which compares each pixel's terrain distance with the distance its four low-resolution neighbours were marched to: interpolated where they agree, best match alone at terrain edges, so edges against nearer terrain stay sharp.
- **Fewer samples when small.** Sample count follows the on-screen size (never below 20, never above "World Effect Quality"); noise octaves drop from 4 to 3 to 2 as a pixel grows past the finest detail.
- **Explicit noise LOD.** `noise()` samples with `textureLod`. With plain `texture` the driver evaluated the noise for every lattice cell of the blocky plume, filled or not (8–12 ms instead of under 1 ms).
- **Cheaper lighting.** One self-shadow sample instead of two, reused once little of a sample reaches the eye.

GPU time of the whole effect pass (`GL_TIME_ELAPSED`, 100-frame averages, 1920x1080, Voxy, RTX 4070, quality 64). Run with `JAVA_TOOL_OPTIONS=-Dwynnvista.effects.profile=true` to log it.

| View | Before | After |
| --- | --- | --- |
| Ragni, plume about 560x600 px | 0.25–0.45 ms | 0.11–0.15 ms |
| 65 blocks from the vent looking up, plume fills the view | about 7.6 ms | about 0.9–1.0 ms |
| 160 blocks out and 100 above the vent, looking up through the smoke | about 23 ms | about 0.9–1.3 ms |
| Facing away, or region hidden | not measured | no GL work (`World effects: none visible`) |

"Before" already had the scissor, the empty-space skip and the sample reduction; it lacked the half-resolution path and the cheaper lighting. The original full-view pass was never timed, so the gain from the first three items is not in this table.

## Results

Fixture runs: `python scripts/lod_fixture.py --backend <dh|voxy> run --masking --override MAIN --commands '...' --timeline '...' --capture-prefix <name>`, optionally with `--iris --shaderpack ComplementaryReimagined`, `--no-effects` or `--disable-effects smoke_plume,nether_fog`. Screenshots are under the ignored `run-*/screenshots/`.

| Check | Result |
| --- | --- |
| `gradlew build`: 49 JUnit tests including `SmokePlumeTest`, `NetherFogTest`, `EffectCullingTest`, `EffectRegionTest`, `EffectFogTest` | PASS |
| Voxy, no shader pack: Ragni view, crater close-ups, far view from the east, noon / sunset / night | PASS: the plume rises out of the crater, the crater rim and nearer LOD terrain hide it, lava glow at night (`pw-*`, `final-*`, `half-*`) |
| Voxy + Complementary Reimagined | PASS (`iv-*`), run before the culling and half-resolution work |
| DH (OpenGL), no shader pack | PASS for drawing and LOD occlusion (`pv-*`), run before the culling and half-resolution work |
| DH + Complementary Reimagined, direct and half-resolution paths | PASS for drawing and LOD occlusion (`id2-*`) |
| Vanilla geometry in front of the plume (stone pillar 12 blocks from the camera) | PASS: `pillar-t179` shows only the plume top above the pillar; run before the half-resolution work |
| Mask `LIGHT` or `NONE` while standing at Ragni | PASS: `World effects: none visible`, the plume program is never compiled |
| `--disable-effects smoke_plume`; `--no-effects` | PASS: nothing drawn |
| Masking off at Ragni | PASS: plume drawn |
| Facing away from the mountain | PASS: `none visible`, then drawn again from the next viewpoint |
| Fog probe, Voxy + Complementary Reimagined, default options, clear weather: 520, 240 and 1,400 blocks from the peak, noon and midnight | PASS: probe reads 1.00 at all three, plume unchanged (`fogoff-*`). Raw terrain detail 0.155 / 0.30 / 0.064–0.070 |
| Same with `ATM_FOG_DISTANCE=10`, `ATM_FOG_ALTITUDE=300` and rain | PASS: probe reads 0.00 at 520 and 1,400 blocks (0.19 at night), 0.44 at 240 blocks where the mountain is still faintly visible; the plume has the mountain's fogged colour (`fogon-*`). Raw detail 0.014 / 0.030 / 0.012–0.021 |
| Photon with the WynnIris ambiance pack on the live server (manual, by the maintainer, 2026-10-07) | PASS by eye: the plume is hidden by the presets' fog. No screenshots or probe readings recorded |
| Lava fog, Voxy, no shader pack and Complementary Reimagined (2026-10-07), 854x480 and 1920x1080: on the surface at the rim, in the pit looking up, inside the layer, 300 blocks away from above, straight down from y 520; midnight, noon, dusk | PASS: drawn with vanilla + LOD depth in both, spikes and terrain hide it, nothing below the floor (y 85 in these runs; lowered to y 67 afterwards and re-checked from the pit, `nx-*`), covers the corrupted ground (`nv-*`, `nw-*`) |
| Lava fog GPU time, 1920x1080, Voxy, RTX 4070, plume switched off, fog covering the whole view at half resolution | 1.05 ms average with Complementary Reimagined after the rim was dispersed (30 logged 100-frame averages, highest 2.05 ms); 0.86 ms before |
| Blocky plume style (`--plume-style BLOCKY`), Voxy, no shader pack, 854x480 (2026-10-07): 250, 580 and 1,270 blocks from the peak, under the column looking up, 70 blocks from the vent, above the vent looking down, inside the smoke at y 450; noon, dusk, midnight | PASS by eye: small cubes leave the crater and grow to 16 blocks at the top, terrain hides them, lighting follows the time of day, cubes near the camera fade (`blocky-*`, `blocky2-*` with one 16-block lattice; `b5-*` with the four lattices) |
| Blocky plume GPU time, 854x480, Voxy, RTX 4070, lava fog switched off | 0.31–0.38 ms from 580 blocks (plume 302x396 px); 0.64–0.67 ms covering the whole view, 70 blocks from the vent and from above it, at full resolution. Not measured at 1920x1080 |
| Blocky lava fog (`--fog-style BLOCKY`), Voxy, no shader pack, 854x480 (2026-10-07): 350 blocks away from above, inside the layer at two places; midnight and noon | PASS by eye: slabs over the corrupted ground, spikes and terrain hide them, the view from inside stays open (`f1-*`). The realistic fog was run through the same views after its shape code moved to `nether_fog_shape.glsl` and looks as before (`r1-*`) |
| Blocky lava fog GPU time, 854x480, Voxy, RTX 4070, plume switched off, full resolution | 0.59–0.68 ms from 350 blocks (fog 854x331 px), 0.62–0.82 ms inside the layer. The realistic fog measured 0.65 ms in the first view. Not measured at 1920x1080 |
| LOD caches after all runs | PASS: `lod_fixture.py check` for both backends (not repeated after the fog runs) |

## Open

- **DH fixture height.** In the DH fixture the imported LOD terrain sits roughly 40–50 blocks lower than the same terrain in the Voxy fixture, so the plume floats above the cone there. In the Voxy fixture the supplied peak coordinates land exactly in the crater. This looks like a vertical shift of the copied DH database in the superflat save, not an effect error, but it must be confirmed with DH on the live server.
- **Up close in real chunks.** The fixture has no real Wynncraft blocks, so the view from inside vanilla render distance (standing on the mountain, in the crater, inside the smoke) is only covered by the pillar test. Half-resolution edges against real foliage have not been seen.
- **Shader packs.** The plume is composited after the pack's final pass with its own lighting (sun/moon direction from the time of day, sky colour from the fog colour). The pack's fog is matched by measurement (see Fog); it does not receive the pack's bloom or tonemapping, and pack clouds do not hide it. Only Complementary Reimagined was run.
- **Fog probe.** Not run with DH or without a shader pack. Photon with the WynnIris ambiance pack has only the manual check above; no probe readings were recorded for it. A view whose in-range terrain is all flat (open sea, snow) reads as fog.
- **Lava fog.** Run only in the Voxy fixture, which has LOD terrain and no real blocks; not run with DH, in rain, with Photon or on the live server. Its extent comes from a screenshot, so the rim may need adjusting against the real area. Density and colour were set by eye. Pack and vanilla clouds in front of the fog are drawn under it (see Translucents).
- **Blocky plume style.** Run only in the Voxy fixture without a shader pack; not run with DH, with a shader pack, at 1920x1080 or on the live server. Beyond about 3,000 blocks a cube is only a few pixels and may shimmer as it rises, and the change of lattice with distance is a visible switch; neither was looked at in motion. Band heights, cube sizes, opacity and shading were set by eye.
- **Blocky lava fog.** Run only in the Voxy fixture without a shader pack, at 854x480; not seen in motion, in rain, in the pit, near the portal's purple glow, or at the distance where the fine lattice is dropped. From inside it is brighter and more opaque than the realistic fog. Slab size, opacity and band height were set by eye.
- **Translucents.** Water, particles and clouds that do not write depth are not sorted against the plume.
- **Performance** was measured on one GPU with Voxy only; nothing was measured on integrated or older graphics. The switch between the direct and half-resolution paths at 12% coverage has no hysteresis.
- DH's Blaze3D renderer and vanilla with no LOD mod were not run. The fixture-only `FIXTURE_CUSTOM` mask rule is covered by unit tests, not by a run.

## Adding an effect

1. Implement `WorldEffect`: an id, a config label, an anchor block, world-space bounds, a view distance and an `upload` that sets its uniforms.
2. Write its fragment shader beside `smoke_plume.fsh`. Start with `#include "scene.glsl"` for the view ray, `boxSpan`, `sceneDistance`, noise and the `uSteps` / `uOctaves` budget. Write `fragColor` (premultiplied, colour passed through `throughFog`) and `fragDistance` for every pixel instead of discarding, so the half-resolution path works.
3. Add it to `EFFECTS` in `WorldEffects`. The config toggle, region masking, culling, scissor, fog probe and resolution choice then apply without further code.
