# World effects

Date: 2026-10-06. Branch `feature/world-effects`. Effects: the smoke plume rising from Mount Wynn (peak at `-183 205 -1964`) the lava fog over the Roots of Corruption, around the Nether portal, and, in the Sky Islands, the void under them and the updrafts and motes in their air (branch `feature/sky-islands-effects`, 2026-10-07). Tested on Windows 11, RTX 4070, Minecraft 1.21.11, DH 3.3.3, Voxy 0.2.16-beta, WynnIris 1.2.2.

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
| Cloud layer registry (one optional layer per frame) | `effects/CloudLayer` |
| Better Clouds layer: its clouds alone and the depth from before them | `compat/betterclouds/BetterCloudsLayer`, `BetterCloudsSupport`, `MixinBetterCloudsRenderer` |
| Fog rules: where the probe looks, which distances it trusts, smoothing, the LOD border fade (pure, unit-tested) | `effects/EffectFog` |
| The plume: placement, shape, sun/moon lighting, the pack sun path and the brightness reference for matching a pack's sky, rendering style (pure, unit-tested) | `effects/SmokePlume` |
| The Roots of Corruption lava fog: placement, shape, drift, glow, rendering style (pure, unit-tested) | `effects/NetherFog` |
| The void under the Sky Islands: placement, levels, pattern timing (pure, unit-tested) | `effects/SkyIslandsVoid` |
| Updrafts and motes in the Sky Islands: placement of the named places (pure, unit-tested) | `effects/SkyIslandsAir` |
| Terrain maps: a per-area picture of the ground an effect may read as `uTerrainMap` (see [TERRAIN_MASKS.md](TERRAIN_MASKS.md)) | `WorldEffect.terrainMap()`, `assets/wynnvista/textures/effects/` |
| Shaders: shared scene code, the plume's shape and its two styles, the lava fog's shape and its two styles, the Sky Islands void and air (`sky_islands_void.fsh`, `sky_islands_air.fsh`), the half-resolution composite, the fog probe | `assets/wynnvista/shaders/effects/{scene.glsl,plume_shape.glsl,smoke_plume.fsh,smoke_plume_blocky.fsh,nether_fog_shape.glsl,nether_fog.fsh,nether_fog_blocky.fsh,upsample.fsh,fog_probe.fsh}` |

Occlusion uses two depth sources, each unprojected with the projection it was rendered with, and takes the nearer hit:

- **Vanilla depth** (Minecraft's main framebuffer; under Iris this is the pack's `depthtex0`, so the hand is included). Values at the far plane are ignored, because Voxy clamps its LOD depth onto the vanilla far plane there.
- **LOD depth**, when a LOD mod drew this frame. DH never writes Minecraft's depth buffer, and Voxy's own depth holds the true distance of far terrain.

The DH side uses only the DH API, so it is not tied to the pinned 3.3.3 terrain shaders (it is registered whenever DH is loaded). The Voxy side is a mixin and follows the existing Voxy version gate.

## Fog

Effects are drawn after everything else, so whatever fog is in the image (a shader pack's, WynnIris' post-process fog, Distant Horizons' or Voxy's own) does not reach them. It is put on them afterwards, in one of two ways:

1. **Modelled**, when the fog's source is known: a supported shader pack, or DH / Voxy drawing their own fog without a pack. The amount of fog is worked out from that pack's or mod's own formula and settings. This is the default wherever it is available.
2. **Measured** by the fog probe, when nothing is known: an unrecognised pack, vanilla with no LOD mod, or "Follow Fog Settings" switched off.

| Piece | Location |
| --- | --- |
| Contract: fog between the camera and a point, as haze and fade; the frame's environment | `effects/FogModel` |
| One model per pack and per LOD mod, with the options each reads (pure, unit-tested) | `effects/FogModels` |
| Which model applies; border-fade curve; probe rules (pure, unit-tested) | `effects/EffectFog` |
| The model sampled into a 96x32 table over ground distance and height (pure, unit-tested) | `effects/FogTable` |
| The active pack's name and option values, from Iris | `compat/iris/IrisPackOptions`, `IrisSupport.packOptions()` |
| DH's fog settings of the frame, through `DhApiBeforeFogRenderEvent`; Voxy's from its viewport | `compat/dh/DhEffectDepth`, `MixinVoxyRenderPipelineDepth` |
| Where LOD terrain ends, as a pack is told it | `LodDepth.Layer.renderDistance`: DH `chunkRenderDistance` x 16 (`dhRenderDistance`), Voxy `sectionRenderDistance` x 512 (`vxRenderDistance` x 16) |
| Table lookup and its use | `tableFog()`, `underClouds()` in `scene.glsl`; uniforms `uFogTable`, `uFogTableRange`, `uFogTableColor` |
| The probe: horizon colour, or the measurement | `fog_probe.fsh` (`uProbeMode`) |

### How a model is applied

A model answers one question: for a point this far along the ground and at this world height, how much of an effect there is **haze** (replaced by the fog's colour) and how much is **fade** (gone into whatever is behind it, as LOD terrain goes into the sky at its border). All of that maths is Java. Each frame the model is sampled into a small float texture; the effect shaders look up the point where the effect begins on the pixel's ray and need no per-pack code. The table is rebuilt only when the model, the frame's environment (camera height to half a block, rain, sun position, LOD distance) or the range it covers changes.

The fog's **colour** is known for DH and Voxy (their fog colour is a setting or the game's own). For a shader pack it is the pack's sky, which is not modelled: the probe runs in a second mode and returns the average colour of the sky up to about 6 degrees above the horizon in the effect's direction, smoothed over 0.4 s. Until a sky pixel has been seen there, the game's fog colour is used.

A model replaces both the probe's measurement and the effects' own distance haze. "Follow Fog Settings" on the World Effects config page (`effectFogModels`, on by default) switches modelling off.

### Sources

Overworld and outdoors only: cave, underwater and blindness fog, and the packs' per-biome variations, are not modelled. Each pack model is that pack's formula written out again from the version named; no pack code is included.

| Source | Recognised by | Haze | Fade at the LOD border | Its settings that are followed |
| --- | --- | --- | --- | --- |
| **Complementary Reimagined / Unbound** r5.x (+ Euphoria Patches), `lib/atmospherics/fog/mainFog.glsl` | options `BORDER_FOG_DISTANCE_OVERWORLD` and `ATM_FOG_DISTANCE` | Atmospheric fog `1 - 2^(-(d - 40)(0.4 + 0.4 rain) / distance)`, scaled by density x multiplier less 0.25 (0.1 in rain); thins over 90 blocks above its altitude, but not for far terrain | `1 - exp(-K (d / R)^4)` x density, `d` = larger of ground distance and height difference, `R` less 256 blocks with Voxy | `ATMOSPHERIC_FOG`, `ATM_FOG_DISTANCE`, `ATM_FOG_ALTITUDE`, `ATM_FOG_MULT`, `ATMOSPHERIC_FOG_DENSITY`, `BORDER_FOG`, `BORDER_FOG_OVERWORLD`, `BORDER_FOG_DISTANCE_OVERWORLD`, `BORDER_FOG_DENSITY_OVERWORLD` |
| **BSL** v10.1.8, `lib/atmospherics/fog.glsl` | options `FOG_DENSITY_NIGHT` and `FAR_VANILLA_FOG`, or "bsl" in the name | Grows with distance (`d` x density / 1024, eased past 0.5), up to four times denser at night and 1.5 times in rain, halving every 128 blocks above y 62 | A linear ramp over the last 40% of the LOD distance, only when the pack's "far vanilla fog" covers the overworld, which it does not by default | `FOG_DENSITY`, `FOG_DENSITY_LOD`, `FOG_DENSITY_NIGHT`, `FOG_DENSITY_WEATHER`, `FOG_HEIGHT`, `FOG_HEIGHT_Y`, `FOG_HEIGHT_FALLOFF`, `FAR_VANILLA_FOG`, `FAR_VANILLA_FOG_STYLE`, `FOG_DENSITY_VANILLA` |
| **Photon** v1.3b, `include/fog/overworld/raymarched.glsl`, `include/weather/fog.glsl`, `include/fog/simple_fog.glsl` | option `AIR_FOG_MIE_DENSITY_NOON`, or "photon" in the name | Air integrated along the view ray in 8 steps: a blue haze halving every 30 blocks above y 93 and a mist halving every 7 blocks above y 70, thick at sunrise, sunset and night, almost absent at noon, thickest in rain | `1 - 2^(-2.4 (d / R)^2)` along the ground, three quarters of it lifted between the horizon and about 11.5 degrees above it | `OVERWORLD_FOG_INTENSITY`, `SEA_LEVEL`, the `AIR_FOG_RAYLEIGH_*` and `AIR_FOG_MIE_*` falloff, density and colour options (clear and rain), `BORDER_FOG` |
| **Distant Horizons** 3.3.3, no pack, `shaders/fog/gl/fog.frag` | DH drawing its fog this frame | Far fog over a share of the LOD distance (linear, exponential or exponential squared, with minimum and maximum thickness) and the height fog in each of DH's mix modes | none | Everything DH hands its fog shader, read each frame, including the fog colour |
| **Voxy** 0.2.16, no pack, `shaders/post/blit_texture_depth_cutout.frag` | Voxy's normal pipeline with "environmental fog" on | The game's own distance fog along the view ray, no thicker than at the far corner of the vanilla render distance | none | The game's fog start, end, colour and strength for the frame |
| Unrecognised shader pack | | measured by the probe | Complementary's default, as a guess | none |

Approximations worth knowing:

- **Photon** marches its fog with a cloud-like noise, per-colour extinction, biome densities and a slow random humidity and temperature. The model is one density in temperate air of average humidity, so it is the loosest of the three.
- **BSL** and **Complementary** vary their fog with biome weather and with how much sky light reaches the camera; the model assumes open sky and the neutral biome.
- A pack update can change a formula. Models are tied to the versions above.
- Option values are read once per loaded pack; changing an option reloads the pack in Iris. `Iris.getCurrentPack()`, `ShaderPack.getShaderPackOptions()`, `OptionValues` and `OptionSet` are Iris internals, not `IrisApi`; they are used from one class, and on a build without them the linkage error is caught once, logged (`Shader pack options are not readable`) and the pack treated as unrecognised.

**Adding a pack.** Install it into a fixture (`--shaderpack-source`), read how it fogs LOD terrain and from which uniform it takes the LOD distance, add a record to `FogModels` and a branch to `forPack` with unit tests of its numbers, then run the views in Results. A pack option is set for a run with a `<pack name>.txt` of `OPTION=value` lines beside the pack in `shaderpacks/`. `-Dwynnvista.effects.profile=true` logs the probe's colour and the modelled haze and fade at the foot and top of each effect.

### The probe's measurement

Used only without a model. Each frame, before anything is drawn, the probe samples a 32x24 grid over the effect's part of the view, keeps the terrain whose distance is within 0.5x–1.5x of the effect's (vanilla or LOD depth), and records how much pixel-to-pixel detail that terrain still has and its average colour. Terrain swallowed by fog is flat and fog-coloured. The result is smoothed over about 0.4 s and applied by `throughFog()` in `scene.glsl`: as the detail goes from `CONTRAST_CLEAR` (0.05) to `CONTRAST_GONE` (0.015), the effect's colour goes from its own to the colour of that terrain, keeping its opacity.

Its limits are why it is no longer the first choice: it needs terrain at about the effect's distance in view (none: the effect is drawn unfogged), a view whose in-range terrain is all flat (open sea, snow) reads as fog, and partial haze goes unseen while nearer terrain in the band keeps its detail. The thresholds were set from Complementary Reimagined in the Voxy fixture.

WynnIris' own post-process fog (Mist Woods, skybox scene effects) is not part of any pack's formula. With a recognised pack it is therefore no longer followed, where the probe used to catch it once it had swallowed the terrain.

## Lighting under a shader pack

An effect has its own lighting: a sun and moon on a fixed east-west path, a sky light, and colours for each time of day (`SmokePlume.lighting`). A shader pack lights, exposes and grades its scene differently, so an effect with only its own lighting was a dark shape at night in every pack and off-colour at sunrise. While a pack renders the world, two things are taken from it instead ("Match Shader Pack Lighting" on the World Effects config page, `effectPackLighting`, on by default):

- **Where the light comes from.** Iris knows the pack's `sunPathRotation` (`WorldRenderingPipeline.getSunPathRotation()`, read in `compat/iris/IrisPackOptions`). The light direction follows the path Iris gives the pack's sun, RotY(-90) RotZ(rotation) RotX(sky angle) of straight up, with the moon opposite: a negative rotation leans the noon sun to the south. Without a pack the fixed path, with its slight southward lean, is kept.
- **How bright and what colour.** The probe's second texel holds the colour of the sky just above the horizon in the effect's direction (see Fog). `skyLight()` in `scene.glsl` uses it three ways: the effect's sky light keeps its strength but takes 80% of that sky's hue; its sun or moon light takes 50% of it; and everything it is lit by is scaled so that a middling part of the effect (`SmokePlume.skyReference`: three quarters of its sky light plus 45% of its sun light) is as bright as that sky, within 0.35x to 2.5x. The lava glow of the plume and the lava fog's own emission are not scaled.
- **While no sky is in view.** The probe keeps the last sky it saw, and beside it (the texel's alpha) the effect's own brightness at that moment (`uReference`). The gain is the ratio of those two, so it is how much brighter or dimmer than the effect's light model the pack draws, and the effect goes on following the time of day through its own model. Under the Sky Islands the land around hides the horizon for as long as the player stays low; before this (2026-10-08) a sky last seen at noon was compared with the model at night, and the reverse, which left the cloud cubes at 0.35x by day or 2.5x at night.
- **Which faces are lit**, for the Sky Islands cloud cubes only. A pack lights a block from where its sun is, so under one the sun's or moon's share of a cube's light goes to its faces by how far they are turned to the pack's sun (`uLightDir`, 1.5 times the even share on a face turned straight to it, none on one turned away); the sky's share and the block shading of the faces stay. Without a pack every face keeps its even share, as vanilla blocks do.

The reasoning for the horizon as the reference: smoke and haze both scatter the same light, so by day a plume is about as bright as the haze at the horizon, and at night it is a moonlit shape of about the sky's brightness. The image is the pack's finished one, after its tonemapping, which is also where effects are drawn, so no knowledge of the pack's exposure is needed.

Limits:

- It needs sky at the horizon in view near the effect. Until one has been seen (looking straight down, or from inside a canyon) the effect keeps its own lighting, with the pack's sun direction. A sky that has gone out of view keeps its hue: after a sunset seen from above, the cubes under the islands stay faintly tinted by it until sky is seen again or the effect has been out of view for a second.
- One colour for the whole effect. A pack's sunset is far brighter towards the sun than away from it; the sample is an average over the effect's part of the view and half its width to either side.
- Pack clouds or a sun disc inside that strip of sky are averaged in.
- The effect still does not receive the pack's bloom, and is not in its water reflections.
- Not applied without a shader pack: there the game's fog colour tints the sky light, as before.

## Shader pack clouds

A shader pack marches its clouds straight into the image and writes no depth for them, so to the effect pass they were sky: effects were painted over them. Each supported pack does keep, for its own later passes, the distance to its cloud on every pixel. That is read and turned into a cloud layer, and the effects use the mechanism made for Better Clouds (below): an effect is split where its ray meets the cloud, and the part behind is weakened by the cloud's opacity. "Shader Pack Clouds Hide Effects" on the World Effects config page (`effectPackClouds`, on by default).

| Piece | Location |
| --- | --- |
| Which buffers a pack keeps its clouds in and how distances are stored (pure, unit-tested) | `effects/PackClouds`, `FogModel.clouds()` in each pack's model in `FogModels` |
| The pack's buffers to distance and opacity, one view-sized pass per frame | `pack_clouds.fsh`, `WorldEffects.readPackClouds` |
| GL names of a pack's colour buffers, by reflection on Iris's pipeline | `IrisPackOptions.colorTexture`, `IrisSupport.packCloudTextures` |
| Copy of a buffer taken right after the pack's deferred passes | `compat/iris/IrisCloudCapture`, `MixinIrisPipelineClouds` (applied when `IrisSupport.pipelineSupported()`) |
| Use in the effects | `cloudDistance()` in `scene.glsl`, `uCloudParams.x` = 2 |

| Pack | Buffer | What it holds | Opacity |
| --- | --- | --- | --- |
| **Photon** v1.3b | `colortex11`, `colortex12` (cloud history, kept between frames) | `colortex11.a`: how much shows through the cloud; `colortex12.x`: distance in blocks, the nearest of the four texels around the pixel as the pack reads it. Scaled by the pack's `TAAU_RENDER_SCALE` when `TAAU` is on | The pack's own |
| **BSL** v10.1.8 | `colortex4.r` | Distance to the first sample where the cloud is more than half opaque, as a share of twice the vanilla render distance (or of the LOD distance, for DH Iris's far plane, when larger); 1 for none | Not stored: 85%, softened by the cloud's share of a 3x3 neighbourhood two texels apart |
| **Complementary** r5.x | `colortex5.a` | Square root of the distance as a share of the pack's render distance; 1 for none. The pack's composite passes put something else there, so the buffer is copied right after `deferredRenderer.renderAll()` in `IrisRenderingPipeline.beginTranslucents` | As BSL |

- **Reading at the end of the frame.** After its final pass Iris copies every buffer that was written back into its main texture, so `RenderTarget.getMainTexture()` is the finished one. For the earlier copy the newest contents are in the alternate texture when the buffer is in `flippedAfterTranslucent`. Both were confirmed only by the result in the runs below.
- **Photon's clouds are near.** Photon scales its cloud space down, so its apparent cloud distances are a few hundred blocks and an overcast sky covers distant terrain. An effect 2,000 blocks away is then hidden completely, as its mountain is.
- **The cloud's colour is not known**, so the effect behind it is only weakened, which lets some of the cloud's contrast against the sky through where the effect is dense (as for Better Clouds under a shader pack).
- When Better Clouds' layer is present it is used instead; the two are not combined.
- Unrecognised packs, and a build of Iris without the fields used, leave effects drawn over pack clouds as before (`The shader pack's buffers are not readable` / `Iris pipeline hook unavailable` in the log).
- Cost: one view-sized pass of a few texture reads per frame while an effect is in view, plus one buffer copy for Complementary. Not measured.

## Better Clouds

[Better Clouds](https://github.com/Qendolin/better-clouds) is optional; nothing here runs without it. It blends its translucent clouds into the world image and writes their depth into Minecraft's depth buffer. To the effect pass a cloud was therefore terrain: the plume stopped at it, and the cloud showed the sky it had been blended with instead of the smoke behind it.

`MixinBetterCloudsRenderer` wraps the one draw that shades the clouds into the image (the first `glDrawArrays` of `Renderer.drawShading`), and only in frames after one in which an effect was in view:

1. Before the draw, the depth of the framebuffer being drawn to is copied (`glBlitFramebuffer`). This is the terrain behind the clouds.
2. The draw runs as Better Clouds intended.
3. The same draw is repeated into an empty buffer. Better Clouds' own blending leaves premultiplied colour and the opacity there, and its shader writes the clouds' depth.

In `scene.glsl`, `sceneDistance()` uses the copied depth wherever the vanilla depth is the cloud's, so anything drawn nearer after the clouds still counts. Each effect records what its march had gathered when it reached the cloud's distance (`cloudDistance()`), and `underClouds()` puts the cloud between that part and the rest: the part behind is weakened by the cloud's opacity, and the cloud's own colour, which blending the effect over the image would cover, is added back. Without a shader pack the result is exactly what drawing smoke, cloud, smoke in order would give.

- **Far clouds.** Better Clouds clamps clouds beyond the vanilla far plane onto it, so they have no usable depth. Their distance is taken as where the ray meets the dimension's cloud height.
- **Shader packs.** Effects are composited after the pack's final pass. Their fog is modelled or measured (see Fog), their lighting matched to the pack's sky and sun path (see Lighting under a shader pack) and the clouds of Complementary, BSL and Photon drawn in front of them (see Shader pack clouds); they do not receive the pack's bloom and are not in its reflections. Run on Voxy only.
- **Other versions.** `BetterCloudsSupport` reads the renderer's bytecode for that draw call before the mixin is applied. A Better Clouds build without it is left alone and logs `Better Clouds world effect integration unavailable`; its clouds then hide effects as before.
- **Cost.** One depth copy and one more full-view cloud shading draw per frame while an effect is in view. Not measured.
- No Better Clouds code or assets are included, and the mod is not compiled against it.

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

## Sky Islands void

`sky_islands_void` replaces the flat vanilla void under the Sky Islands. Its box is x 560..1808, z -5056..-4192, y -40..72, larger than the terrain mask (x 704..1535, z -5008..-4369) on every side: the void runs on south towards the Raiders' bases and east past the coast, and a separate piece of it lies under the Colossus to the south-west. It does not read the terrain map. Over the last 96 blocks before a side of the box everything fades out, so where no land hides the edge there is none. One shader, one pass.

- **Cloud clumps.** Separate clumps of whole cubes, modelled on the cloud models that launch the player between the islands, floating around the ends of the longest spikes with open void between them. The cubes sit on a lattice of 4-block cells, at most one per cell, the way the blocky smoke plume places its cubes: a cube is larger the further its column's noise is past what the cell needs, so a clump has 4-block cubes in its middle and 2-block cubes at its rim, and it sits at a random place inside its cell (a cube above another rests on the floor of its cell). The noise has peaks about 16 blocks apart, each a clump one to three cubes high, and a slow part with patches of about 64 blocks that gathers the clumps into groups; a third lookup starts a clump on the layer at y 0 or the one at y 4. A cell that falls just short of holding a cube holds **crumbs**: up to eight cubes of 1.1 to 2 blocks that rest on the cube below; under a clump they hang from it.
- **A cube's look.** White, or faintly rose or blue (about two in five), with a rose blush along the lowest 5/16 of its sides and over its underside, lighter texels an eighth of the cube across, and a block's face shading (top 1, sides 0.9 and 0.84, underside 0.74). At night the cubes keep a little light of their own so the faces can still be told apart. The lattice drifts along x at 1.28 blocks per second. Crumbs fade out between 110 and 170 blocks from the camera, cubes between 220 and 520, and both within 5 blocks of it.
- **How it is drawn.** The view ray is clipped to the slab of heights that can hold cloud (y -4 to 20) and walks the lattice cell by cell, at most 96 cells and no further than the 520 blocks at which the cubes are gone, with three noise lookups per cell; a cube is intersected exactly, and the eight crumbs of a cell each get a box test. A ray that needs more cells is nearly level and deep in the haze by then.
- **Haze.** A thin even mist, densest at y 8 and thinning exponentially over 8 blocks above and 10 below. Its amount along a view ray is the exact integral of that profile up to the first opaque cube or the terrain, so it is barely there straight down, softens the spikes where they enter the cloud, and closes into a pale deck towards the horizon, where it replaces the faded cubes. The part below its level is dim violet.
- **The void.** An opaque plane at y -40, below the world: almost black, and in some places a nebula. Where one is comes from a single slow noise value (patches of about 64 blocks) that must be above 0.64: the field changes with time, one noise cell in 50 seconds, so nebulae are uncommon and fade in and out over a minute or two. Nothing inside a nebula moves or changes: the whole pattern, with the grid of squares it is drawn in, drifts along z at 1.28 blocks per second, so a nebula slides as one piece without its squares crawling or flickering (a version whose clouds each drifted their own way over fixed squares jittered). Stars twinkle once in 25 seconds. A second field gives a place its shades out of purple, violet, indigo and two blues, all near each other after the End portal and sky, so nebulae differ a little and one shades from purple to blue across its width. Inside: two clouds of four noise octaves bent by more noise, each in its own colour and in five steps of brightness; thin bright filaments in a third, paler colour; and a star in 3% of the squares, brighter inside the clouds. All of it is in squares of 4 blocks. The technique follows the night nebula of Complementary Reimagined (`shaders/lib/atmospherics/nightNebula.glsl`), used as a reference only; no code of that pack is included. Everything is looked up 220 blocks below the plane, so it barely moves as the camera does, and is dimmed by the dark in front of it (to under a half straight down, less at a slant) and to 55% by day. Outside the nebulae about one 4-block square in 250 holds a crystal's glint that pulses slowly.
- **Fallen islands.** A few islands lie in the void, in front of the nebulae and fading into the dark, to give it depth. Three flat layers are looked up 60, 130 and 210 blocks below the plane, so they slide against each other as the camera moves. A layer is land only where its noise is above 0.70, which leaves an island here and there, in squares of 4, 4 and 8 blocks: dim stone, a shade darker at the rim, and darker again where the same noise read 30% further down the ray with a higher threshold gives the island a side that narrows downward. An island is mixed over what is behind it by how much of it shows: 100%, 70% and 45% for the three layers, times what the dark in front leaves (about 80%, 63% and 48% straight down, less at a slant), and daylight falls off with depth as well, so the deepest are barely there. They do not move.
- **Waterfalls.** The void does nothing at a waterfall: the water runs into the cloud like anything else, and the spray around it belongs to the updraft pass. Two attempts at more were removed on 2026-10-07 and 2026-10-08: a mound of cloud with rings of brighter cells running outwards, and a plain opaque patch of cloud that also hid the water's foot. The patch flickered against the drifting cells around it.

The cloud is lit by the sky and sun (the plume's light model; under a shader pack see Lighting under a shader pack). Nothing is drawn on the islands themselves. Everything is fixed to the world and repeats every 32,000 ticks without a jump. Distance haze, the modelled fog and clouds are applied as for the other effects. Always at full resolution, as the upsample would blur the cube edges.

Where it is drawn: from within 640 blocks of its box along the ground (`SkyIslandsVoid.REACH`, measured from the box and not from its middle, which was 1,500 blocks from the middle before), so from the Canyon of the Lost and Molten Heights beside it and not from further. The box ends at y 72, where less than half a percent of the haze is left along any ray, and not at the islands' tops (y 256 before): looking level from an island the pass covers the part of the view below the horizon, and looking up it is skipped.

Earlier versions, all replaced as too busy for the place: soft billowing mist with bright veins and violet light on the islands' undersides (2026-10-07); five flat sheets of square cells over a plane holding a three-colour nebula and stars (2026-10-08); a continuous terraced sea of cubes over four parallax layers of fallen islands with wisps of violet between them (2026-10-08, never committed).

## Sky Islands updrafts and motes

`sky_islands_air` is one pass for everything that rises through the air there. It is drawn only within 64 blocks of the camera and reads the terrain map. The pass is skipped when the camera is more than 64 blocks from its box in any direction (`inRange`); before, it ran anywhere within 589 blocks of the middle of the map.

All of it is one mechanism. The ground is divided into squares of 8 blocks, and a square holds at most one upright line, 0.28 blocks thick, at a place inside it fixed by a hash. Dashes move along the line. The view ray walks the squares it passes over (at most 20) and intersects each line exactly. For a streak, the height at which the ray enters the line decides whether a dash is there. A mote is a whole cube, as high as the line is thick: the ray's way through the line is tested against it, so the cube has its top or underside as well as its sides, and the face the ray enters by is shaded as a block's is (top 1, sides 0.8 and 0.62, underside 0.5). Testing only where the ray enters, as the first version did, drew two sides without a top, an angle bracket. Nothing is marched, and the map is only read for a line the ray actually meets. What a line is depends on where its square lies:

- **Spray**, within about 10 blocks of a waterfall (the map's alpha): a square there holds two more lines, thinner, of small white cubes that rise at 2.2 blocks per second from the cloud and thin out with height, up to 18 blocks right at the water and lower further out. These squares are found with one map lookup per square, made only where the ray is within 18 blocks above the cloud.
- **Updraft streaks**, over open void (the map's blue channel), except within about 11 blocks of a waterfall. White dashes of 6 to 11 blocks rising at 9 blocks per second from the top of the cloud (y 10) to between y 80 and y 130, brightest at the head and stepping down in three levels along the tail. They blow in gusts: a noise field with patches of about 96 blocks that moves across the area switches the lines of a patch on and off, and 60% of the squares in a gust hold a streak. Lit like the cloud.
- **Windwalker Temple** (`1358 -4745`): within 80 blocks of it the gusts are stronger and near it constant, every square holds a streak, and the streaks reach up to 40 blocks higher.
- **Levitation motes**, under an island (the map's green channel gives the underside): in 45% of the squares, small violet cubes rising at 0.8 blocks per second from the cloud to the rock, each with a slow blink. They shine by themselves, 80% at night and 45% by day.
- **Astraulus' Tower** (`1206 126 -4921`): within 40 blocks, from 30 below to 55 above that height, motes of pale gold and pale blue that drift up slowly and blink.
- **Wybel Island** (`1310 66 -4684`): within 45 blocks, from 8 below to 40 above, sparkles in four pastels that blink faster.

Lines fade in from 1.5 to 5 blocks from the camera and out from 38 to 64. Terrain hides them through the depth buffers as it does every effect. Always at full resolution.

## Where an effect is shown

Decided on the CPU, in this order, before any GL call. If nothing survives, the frame does no effect work at all.

1. **Config.** The master switch `effectsEnabled`, then the effect's own switch in `effects` (`{"smoke_plume": false}`; an effect that is not listed is on). The config screen has a "World Effects" page with the master switch, the quality slider and one toggle per registered effect.
2. **Region mask.** An effect is anchored to a block and is shown exactly where LOD terrain at that block is shown: the anchor must lie inside the rectangles of the frame's `VisibilitySnapshot`, the same snapshot the DH and Voxy masks use. The plume therefore disappears with the main map in the Realm of Light, the Void, unlisted areas (`NONE`) and under any fixture override. With "Enable LOD Masking" off there is no mask, so the region the mask *would* select from the player's position is used instead. Effects never appear outside Wynncraft or the local fixture.
3. **Distance.** `WorldEffect.inRange`: horizontal distance from the anchor against the effect's `maxViewDistance`, or for an effect spread over an area (the Sky Islands) the distance from its box.
4. **Frustum.** The effect's bounding box is projected; a box entirely outside the view is skipped. The far plane is deliberately not tested. A box that reaches behind the camera is cut at the camera's plane first, so only the camera being inside it gives the whole view: a layer below the camera gets the view up to the horizon, where it got all of it before.

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

Fixture runs: `python scripts/lod_fixture.py --backend <dh|voxy> run --masking --override MAIN --commands '...' --timeline '...' --capture-prefix <name>`, optionally with `--iris --shaderpack ComplementaryReimagined`, `--better-clouds`, `--no-effects` or `--disable-effects smoke_plume,nether_fog`. Screenshots are under the ignored `run-*/screenshots/`.

| Check | Result |
| --- | --- |
| `gradlew build`: 74 JUnit tests including `SmokePlumeTest`, `NetherFogTest`, `SkyIslandsVoidTest`, `EffectCullingTest`, `EffectRegionTest`, `EffectFogTest`, `FogModelsTest` | PASS |
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
| Fog models, 854x480, noon then as stated (2026-10-07). Views: 2,000 blocks from the peak at y 140 and 2,900 at y 420. Log line `fog: <model>` in each run | |
| Voxy, no pack: clear, then rain | PASS by eye: the plume has the terrain's light haze in clear weather and sinks into the grey fog with it in rain (`fm-voxy-*`) |
| DH (256 chunks), no pack: clear, then rain | PASS by eye: clear at 2,000; at 2,900 the plume and its mountain are both nearly lost in DH's far fog; gone in rain (`fm-dh-*`) |
| Voxy + Complementary Reimagined, defaults: noon, then sunrise | PASS by eye at noon: the plume has its mountain's pale haze at 2,000 and is all but gone at 2,900, where the earlier border-only version left it standing out (`fm-comp-*` against `border2-*`). At sunrise it is a little darker than the pink fog around it |
| Voxy + Complementary with `BORDER_FOG_DISTANCE_OVERWORLD=10` (run with the border-only version) | PASS: the option was read (`strength 10.0` in that version's log); the plume is half faded at 2,000 and gone at 2,900 with the terrain (`border3-*`) |
| Voxy + BSL v10.1.8, defaults: noon, then midnight | Recognised and applied. By eye the plume is brighter than BSL's dim, blue scene by day and a dark shape at night: its own lighting does not follow the pack's exposure, which no fog amount corrects. From y 420 BSL's cloud layer lies between the camera and the terrain and does not cover the plume (`fm-bsl-*`) |
| Voxy + Photon v1.3b, defaults: noon, sunrise, midnight | Recognised and applied (profile log: haze 0.01–0.04, fade 0.57 at the foot and 0.25 at the top at 2,900 blocks; horizon colour read as about 0.65 0.69 0.74). From y 140 the plume sits in the scene's haze. From y 420 Photon's cloud sea covers the terrain but not the plume (`fm-photon-*`) |
| Lighting match (2026-10-07), Voxy, 854x480, the plume from 2,000 blocks at y 140: noon, midnight, sunrise with Complementary Reimagined, BSL v10.1.8 and Photon v1.3b | PASS by eye and by pixel: the plume is within a few percent of the sky beside it at all three times in all three packs (for example Complementary at midnight: plume 34 43 58, sky 31 40 52; BSL at sunrise: plume 232 200 180, sky 227 198 167), where before it was a dark shape at night. Profile log: horizon sky read as 0.73 0.88 0.95 at noon, 0.09 0.17 0.22 at midnight and 0.95 0.85 0.70 at sunrise in BSL (`lit-bsl-*`, `lit-comp-*`, `lit-photon-*`, `lit-sheet.png`). The sun path rotation is unit-tested only: which side of the plume is lit was not compared with the packs' shadows. Not run: DH, the lava fog, the blocky styles, rain, the config toggle |
| Shader pack clouds (2026-10-07), Voxy, 854x480, noon. Log line `under the pack's clouds` in each run | |
| Photon v1.3b, overcast: 2,000 blocks at y 140; 2,900 at y 420; 850 at y 330 | PASS by eye: hidden with its mountain behind the overcast from 2,000; only the top shows above the cloud sea from 2,900; from 850 the plume rises out of the cloud tops with its base covered. Before, it was drawn over the clouds (`pc-photon-*` against `fm-photon-*`) |
| BSL v10.1.8, the same views | PASS by eye: the plume's base is behind the cloud layer from above, and it is untouched where no cloud is in front (`pc-bsl-*`) |
| Complementary Reimagined, the same views and two looking up at the plume from 450 and 650 blocks | PASS by eye: the pack's blocky clouds stay in front of the smoke where they overlap it (`pc3-comp-*`), and the plume is not hidden where they do not (`pc2-comp-*`). Reading the buffer at the end of the frame instead of copying it hid the plume everywhere (`pc-comp-*`), which is why the copy exists |
| Shader pack clouds not run: DH, Complementary Unbound's cloud style, the lava fog, the half-resolution path, Photon with `TAAU`, clouds switched off in a pack, night, the config toggle, an Iris build without the fields used | |
| Fog models not run: DH with any pack, the lava fog, a WynnIris ambiance preset, changed BSL or Photon options, rain under a pack, DH's height fog modes, the config toggle, an Iris build without the option classes, the live server | |
| Photon with the WynnIris ambiance pack on the live server (manual, by the maintainer, 2026-10-07) | PASS by eye: the plume is hidden by the presets' fog. No screenshots or probe readings recorded |
| Lava fog, Voxy, no shader pack and Complementary Reimagined (2026-10-07), 854x480 and 1920x1080: on the surface at the rim, in the pit looking up, inside the layer, 300 blocks away from above, straight down from y 520; midnight, noon, dusk | PASS: drawn with vanilla + LOD depth in both, spikes and terrain hide it, nothing below the floor (y 85 in these runs; lowered to y 67 afterwards and re-checked from the pit, `nx-*`), covers the corrupted ground (`nv-*`, `nw-*`) |
| Lava fog GPU time, 1920x1080, Voxy, RTX 4070, plume switched off, fog covering the whole view at half resolution | 1.05 ms average with Complementary Reimagined after the rim was dispersed (30 logged 100-frame averages, highest 2.05 ms); 0.86 ms before |
| Blocky plume style (`--plume-style BLOCKY`), Voxy, no shader pack, 854x480 (2026-10-07): 250, 580 and 1,270 blocks from the peak, under the column looking up, 70 blocks from the vent, above the vent looking down, inside the smoke at y 450; noon, dusk, midnight | PASS by eye: small cubes leave the crater and grow to 16 blocks at the top, terrain hides them, lighting follows the time of day, cubes near the camera fade (`blocky-*`, `blocky2-*` with one 16-block lattice; `b5-*` with the four lattices) |
| Blocky plume GPU time, 854x480, Voxy, RTX 4070, lava fog switched off | 0.31–0.38 ms from 580 blocks (plume 302x396 px); 0.64–0.67 ms covering the whole view, 70 blocks from the vent and from above it, at full resolution. Not measured at 1920x1080 |
| Blocky lava fog (`--fog-style BLOCKY`), Voxy, no shader pack, 854x480 (2026-10-07): 350 blocks away from above, inside the layer at two places; midnight and noon | PASS by eye: slabs over the corrupted ground, spikes and terrain hide them, the view from inside stays open (`f1-*`). The realistic fog was run through the same views after its shape code moved to `nether_fog_shape.glsl` and looks as before (`r1-*`) |
| Blocky lava fog GPU time, 854x480, Voxy, RTX 4070, plume switched off, full resolution | 0.59–0.68 ms from 350 blocks (fog 854x331 px), 0.62–0.82 ms inside the layer. The realistic fog measured 0.65 ms in the first view. Not measured at 1920x1080 |
| Better Clouds 1.13.11 (`--better-clouds`), Voxy, no shader pack, 854x480, noon (2026-10-07): 300 and 570 blocks from the peak with clouds in front of the plume; the same views with the mixin switched off | PASS by eye: with the mixin off the clouds cut sky-coloured holes into the plume (`bc0-*`); with it on the plume shows through them and they stay in front (`bc1-*`). Better Clouds' synchronous GL debug output reported no errors |
| Better Clouds with both blocky styles: the plume from 300 blocks and from under the column, the lava fog from above the clouds | PASS by eye: both shaders compile, clouds stay in front of the cubes (`bc2-*`). No cloud lay over the lava fog in that view |
| Better Clouds + Complementary Reimagined (WynnIris), the two plume views | PASS by eye: no errors, the plume shows through Better Clouds' clouds (`bc3-*`) |
| Without Better Clouds installed, after the change: the plume view from 300 blocks | PASS: both effects compile and draw, no cloud layer is registered (`bc4-*`) |
| Sky Islands void, cloud clumps over the dark void (2026-10-08), Voxy, no shader pack, 854x480: between the islands at `1300 60 -4400`, among the cubes at `1313 20 -4393`, from y 120 and y 140 looking down; noon and midnight | PASS by eye: separate clumps of mixed-size cubes with crumbs, blush and tints, open dark void between them, haze towards the horizon, terrain hides it (`cc1-*`). With the nebulae, four wide views at midnight and two at noon: patches with filaments and stars through the gaps in two of the night views, none seen by day (`ce1-*`, with a wider palette that was narrowed to purples and blues afterwards; `cf1-*` with the narrowed one); how often and how fast they come and go was not watched. With the fallen islands, from y 120 looking down and from below the cloud at y -30, noon and midnight: a few faint grey islands at different depths, sparse (`ch1-*`). Still frames only, with the air effect switched off. Not run with a shader pack or DH. GPU time not measured for this version; the continuous sea of cubes before it took 0.40 to 0.52 ms at 854x480 with two noise lookups per cell where this has three |
| Sky Islands void, the earlier flat-sheet version, Voxy, no shader pack, 854x480 (2026-10-07 and 08): between the islands at y 60 looking along the void, from y 120 looking down, at Sky Falls from y 30, at the south end (`1300 70 -4400`) and looking east from `1560 140 -4480`; noon and midnight | PASS by eye: terraced cloud cells around the spikes with dimmer sheets below them, haze towards the horizon, the abyss with its nebula and stars through the gaps, islands and bridges hide it, nothing on the islands; the sea carries on south of the mask and east to the horizon (`sa1-*`, `sx1-*`, `sy1-*`). The fixture has LOD terrain only, and a superflat floor at y -61 behind the abyss plane. The piece under the Colossus was not found: the view from `690 110 -4480` shows land |
| Sky Islands updrafts and motes, same setup: a gust in the gap at `1300 45 -4400`, Windwalker Temple, under an island at y 30, Wybel Island and Astraulus' Tower at midnight | PASS by eye: stepped white streaks in the gaps and none over the islands, sparse violet motes under the islands, pastel sparkles over Wybel Island, gold and blue motes around the tower, all hidden by terrain in front (`sa1-*`, `sa2-*`; `sp1-*` at 1920x1080 after the density was lowered); spray around the falls (`sx1-*`). Seen in still frames only, and no mote was seen from near enough to tell a cube from the earlier two-sided shape |
| Both, Voxy + Complementary Reimagined, the same views, noon and midnight (flat-sheet version of the void) | PASS by eye: both compile and draw; the sea takes the pack's light, streaks and motes are visible (`sc1-*`) |
| Both, Voxy + BSL v10.1.8 and Photon v1.3b, two views, noon and midnight (flat-sheet version of the void) | Both compile and draw (`sb1-*`, `sph1-*`). By day the cloud sea is dim and grey-violet under both, not white: it is brought to the brightness of the horizon sky those packs draw, which is darker than their lit terrain |
| Sky Islands under a shader pack (2026-10-08), Voxy + Complementary Reimagined, 854x480, cube version of the void: between the islands at `1300 60 -4400` and from y 120 looking down, at noon, 11300, 12500 and midnight; from an island at `1300 150 -4500` looking level and 25 degrees down; from outside at `300 220 -4600` | PASS by eye: both compile and draw under the pack's clouds and fog; cube tops are white at noon with dimmer sides, and the cubes are dim at night (`sl1-*`, `sl2-*`). Arriving low between the islands at noon and then setting the time to 11300, midnight, 1000 and noon with no sky in view (the probe held 0.75 0.81 0.89 throughout): the cubes dim, darken and brighten with the time (`sl4-*`); the run before the reference was kept showed them near black at 11300 with a darker sky held (`sl2-t99`). From the island the void is cut off nowhere by the smaller box and rectangle (`sl2-t255`, `sl2-t325`). From outside the void's pass covered 854x239 px and the air pass did not run. GPU time of both passes together 0.26 to 0.65 ms from between the islands, 0.12 to 0.17 ms for the void alone from outside. Not run: BSL, Photon, DH, no pack after these changes, 1920x1080, a low sun with the sunlit faces in view checked against the pack's own shadows |
| Sky Islands GPU time, 1920x1080, Voxy, no shader pack, RTX 4070, each effect alone covering the whole view at full resolution, views `1300 45 -4400` and `1230 60 -4690` | Void 0.21–0.38 ms, updrafts and motes 0.25–0.52 ms (13 logged 100-frame averages each), both before the spray was added. With it, updrafts and motes alone, flying low at Sky Falls and at `1230 20 -4690`: 0.41–0.73 ms (13 averages). The void was not measured again |
| LOD caches after all runs | PASS: `lod_fixture.py check` for both backends (not repeated after the fog runs) |

## Open

- **DH fixture height.** In the DH fixture the imported LOD terrain sits roughly 40–50 blocks lower than the same terrain in the Voxy fixture, so the plume floats above the cone there. In the Voxy fixture the supplied peak coordinates land exactly in the crater. This looks like a vertical shift of the copied DH database in the superflat save, not an effect error, but it must be confirmed with DH on the live server.
- **Up close in real chunks.** The fixture has no real Wynncraft blocks, so the view from inside vanilla render distance (standing on the mountain, in the crater, inside the smoke) is only covered by the pillar test. Half-resolution edges against real foliage have not been seen.
- **Shader packs.** The plume is composited after the pack's final pass with its own lighting (sun/moon direction from the time of day, sky colour from the fog colour). The pack's fog is matched by measurement (see Fog); it does not receive the pack's bloom or tonemapping, and pack clouds do not hide it (Better Clouds' clouds are handled, see Better Clouds). Only Complementary Reimagined was run.
- **Fog models.** See the approximations and the runs not made under Fog and Results.
- **Fog probe.** Now the fallback only. Not run with DH or without a shader pack; its horizon-colour mode was run with the three packs on Voxy only. Photon with the WynnIris ambiance pack has only the manual check of the measuring version; with the Photon model that view has not been looked at again.
- **Lava fog.** Run only in the Voxy fixture, which has LOD terrain and no real blocks; not run with DH, in rain, with Photon or on the live server. Its extent comes from a screenshot, so the rim may need adjusting against the real area. Density and colour were set by eye. Pack and vanilla clouds in front of the fog are drawn under it (see Translucents).
- **Blocky plume style.** Run only in the Voxy fixture without a shader pack; not run with DH, with a shader pack, at 1920x1080 or on the live server. Beyond about 3,000 blocks a cube is only a few pixels and may shimmer as it rises, and the change of lattice with distance is a visible switch; neither was looked at in motion. Band heights, cube sizes, opacity and shading were set by eye.
- **Blocky lava fog.** Run only in the Voxy fixture without a shader pack, at 854x480; not seen in motion, in rain, in the pit, near the portal's purple glow, or at the distance where the fine lattice is dropped. From inside it is brighter and more opaque than the realistic fog. Slab size, opacity and band height were set by eye.
- **Translucents.** Water, particles and clouds that do not write depth are not sorted against the plume. Vanilla clouds, and the clouds of a shader pack other than Complementary, BSL and Photon, are not handled the way Better Clouds' are.
- **Better Clouds.** Run only in the Voxy fixture at 854x480, with Better Clouds 1.13.11 and its default settings; not run with DH, in Fabulous graphics, on the live server, or in motion. The half-resolution path (plume filling the view) was not looked at with clouds in front: there the cloud's outline inside the smoke is at half resolution. On hardware where Better Clouds uses its depth fallback the clouds get no depth and all use the cloud-height estimate. The added GPU time was not measured.
- **Performance** was measured on one GPU with Voxy only; nothing was measured on integrated or older graphics. The switch between the direct and half-resolution paths at 12% coverage has no hysteresis.
- DH's Blaze3D renderer and vanilla with no LOD mod were not run. The fixture-only `FIXTURE_CUSTOM` mask rule is covered by unit tests, not by a run.

- **Sky Islands void.** Run only in the Voxy fixture. Not run on the live server, where the void behind it is the real one and the terrain is real blocks. Not run with DH, in rain, with Better Clouds, or in motion: the drift, the twinkle, the spray rings and the hand-over from cells to haze with distance have not been watched. Colours, coverage and levels were set by eye. The cube version was run without a shader pack only, and its cost is not measured; it walks up to 96 lattice cells per pixel where the sheets took a fixed handful of lookups. The updrafts still rise from y 10 everywhere, though the cloud is now clumps with open void between them and reaches y 20 in places. The Voxy fixture's superflat floor showed in front of the void plane from low viewpoints although it lies below it, which was not explained; the fixture save was changed to generate air instead (`level.dat`, its old one kept as `level.dat.superflat-backup`), while `FixtureWorldCreator` still creates a superflat save. Water is not sorted against it: a waterfall is drawn by the game and the cloud is laid over it, and the haze, which is laid over everything, greys the water for some blocks above the cloud. The box outside the terrain mask was set from two top-down fixture views and the maintainer's description, not from map data: the east side reaches x 1808 without knowing where the void ends there, and the piece under the Colossus is assumed to lie east of x 560. Anywhere inside the box where the world has no block above y 12 shows the sea. Under BSL and Photon the flat-sheet version was dim by day (see Results); the cubes were not run under them. The view from below the plane at y -40 was not looked at.
- **Sky Islands updrafts and motes.** They need the terrain map and so stop at the mask's box (x 704..1535, z -5008..-4369): south, east and south-west of it the void has its sea but no streaks, motes or spray. That is intended: the larger box is there to put the sea under the edges of the area, and the mask is not to be recorded again for it. The GPU times in Results are from before the cube test and were not taken again: a run on 2026-10-08 logged 0.7 to 10 ms while the frame rate of the whole client was a sixth of the usual, which is not a measurement of this pass. Run only in the Voxy fixture, in still frames: the speed of the streaks, the gusts moving across the area and the blinking have not been watched, and how much of it is too much while flying is not known. A streak stands over a column that is empty all the way up; it is not stopped by a bridge or branch in a neighbouring column, only hidden by what is in front of it. The three places use one point and a radius each, taken from territory banners and one discovery, not fitted to the builds. Not measured with a shader pack.

## Future locations

Places to add world effects to. None is started; each needs its anchor, bounds and a fixture run before it is listed as done.

- [ ] Lake Gylia
- [ ] The Forgery
- [ ] Lights Secret
- [x] Sky Islands: the void, updrafts, motes, and three named places (Windwalker Temple, Astraulus' Tower, Wybel Island)
- [ ] Ozoth's Spire
- [ ] Qira Hive
- [ ] Volcanic Isles
- [ ] Mistwoods
- [ ] Toxic Wastes
- [ ] Path to Darkness

## Adding an effect

1. Implement `WorldEffect`: an id, a config label, an anchor block, world-space bounds, a view distance and an `upload` that sets its uniforms.
2. Write its fragment shader beside `smoke_plume.fsh`. Start with `#include "scene.glsl"` for the view ray, `boxSpan`, `sceneDistance`, noise and the `uSteps` / `uOctaves` budget. Write `fragColor` (premultiplied, finished by `underClouds`, which applies the fog) and `fragDistance` for every pixel instead of discarding, so the half-resolution path works.
3. If it has to know where the ground is beyond the first surface along a ray, return a map from `terrainMap()` and read it as `uTerrainMap` (see [TERRAIN_MASKS.md](TERRAIN_MASKS.md)).
4. Add it to `EFFECTS` in `WorldEffects`. The config toggle, region masking, culling, scissor, fog and resolution choice then apply without further code.
