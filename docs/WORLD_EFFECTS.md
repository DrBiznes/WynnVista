# World effects

Date: 2026-10-06. Branch `feature/world-effects`. First effect: the smoke plume rising from Mount Wynn (peak at `-183 205 -1964`). Tested on Windows 11, RTX 4070, Minecraft 1.21.11, DH 3.3.3, Voxy 0.2.16-beta, WynnIris 1.2.2.

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
| The plume: placement, shape, sun/moon lighting (pure, unit-tested) | `effects/SmokePlume` |
| Shaders: shared scene code, the plume, the half-resolution composite | `assets/wynnvista/shaders/effects/{scene.glsl,smoke_plume.fsh,upsample.fsh}` |

Occlusion uses two depth sources, each unprojected with the projection it was rendered with, and takes the nearer hit:

- **Vanilla depth** (Minecraft's main framebuffer; under Iris this is the pack's `depthtex0`, so the hand is included). Values at the far plane are ignored, because Voxy clamps its LOD depth onto the vanilla far plane there.
- **LOD depth**, when a LOD mod drew this frame. DH never writes Minecraft's depth buffer, and Voxy's own depth holds the true distance of far terrain.

The DH side uses only the DH API, so it is not tied to the pinned 3.3.3 terrain shaders (it is registered whenever DH is loaded). The Voxy side is a mixin and follows the existing Voxy version gate.

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

Fixture runs: `python scripts/lod_fixture.py --backend <dh|voxy> run --masking --override MAIN --commands '...' --timeline '...' --capture-prefix <name>`, optionally with `--iris --shaderpack ComplementaryReimagined`, `--no-effects` or `--disable-effects smoke_plume`. Screenshots are under the ignored `run-*/screenshots/`.

| Check | Result |
| --- | --- |
| `gradlew build`: 38 JUnit tests including `SmokePlumeTest`, `EffectCullingTest`, `EffectRegionTest` | PASS |
| Voxy, no shader pack: Ragni view, crater close-ups, far view from the east, noon / sunset / night | PASS: the plume rises out of the crater, the crater rim and nearer LOD terrain hide it, lava glow at night (`pw-*`, `final-*`, `half-*`) |
| Voxy + Complementary Reimagined | PASS (`iv-*`), run before the culling and half-resolution work |
| DH (OpenGL), no shader pack | PASS for drawing and LOD occlusion (`pv-*`), run before the culling and half-resolution work |
| DH + Complementary Reimagined, direct and half-resolution paths | PASS for drawing and LOD occlusion (`id2-*`) |
| Vanilla geometry in front of the plume (stone pillar 12 blocks from the camera) | PASS: `pillar-t179` shows only the plume top above the pillar; run before the half-resolution work |
| Mask `LIGHT` or `NONE` while standing at Ragni | PASS: `World effects: none visible`, the plume program is never compiled |
| `--disable-effects smoke_plume`; `--no-effects` | PASS: nothing drawn |
| Masking off at Ragni | PASS: plume drawn |
| Facing away from the mountain | PASS: `none visible`, then drawn again from the next viewpoint |
| LOD caches after all runs | PASS: `lod_fixture.py check` for both backends |

## Open

- **DH fixture height.** In the DH fixture the imported LOD terrain sits roughly 40–50 blocks lower than the same terrain in the Voxy fixture, so the plume floats above the cone there. In the Voxy fixture the supplied peak coordinates land exactly in the crater. This looks like a vertical shift of the copied DH database in the superflat save, not an effect error, but it must be confirmed with DH on the live server.
- **Up close in real chunks.** The fixture has no real Wynncraft blocks, so the view from inside vanilla render distance (standing on the mountain, in the crater, inside the smoke) is only covered by the pillar test. Half-resolution edges against real foliage have not been seen.
- **Shader packs.** The plume is composited after the pack's final pass with its own lighting (sun/moon direction from the time of day, sky colour from the fog colour). It does not receive the pack's fog, bloom or tonemapping, and pack clouds do not hide it. Only Complementary Reimagined was run.
- **Translucents.** Water, particles and clouds that do not write depth are not sorted against the plume.
- **Performance** was measured on one GPU with Voxy only; nothing was measured on integrated or older graphics. The switch between the direct and half-resolution paths at 12% coverage has no hysteresis.
- DH's Blaze3D renderer and vanilla with no LOD mod were not run. The fixture-only `FIXTURE_CUSTOM` mask rule is covered by unit tests, not by a run.

## Adding an effect

1. Implement `WorldEffect`: an id, a config label, an anchor block, world-space bounds, a view distance and an `upload` that sets its uniforms.
2. Write its fragment shader beside `smoke_plume.fsh`. Start with `#include "scene.glsl"` for the view ray, `boxSpan`, `sceneDistance`, noise and the `uSteps` / `uOctaves` budget. Write `fragColor` (premultiplied) and `fragDistance` for every pixel instead of discarding, so the half-resolution path works.
3. Add it to `EFFECTS` in `WorldEffects`. The config toggle, region masking, culling, scissor and resolution choice then apply without further code.
