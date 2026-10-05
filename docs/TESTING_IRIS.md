# Iris (WynnIris) shader-pack support for DH and Voxy

Date: 2026-10-04. Developed and tested against **WynnIris 1.2.2** (Modrinth version ID `guakKXm0`; mod id `wynniris`, which `provides` `iris`; Iris base 1.10.8 for Minecraft 1.21.11) with Sodium 0.8.12, Distant Horizons 3.3.3 and Voxy 0.2.16-beta. Shader pack under test: **Complementary Reimagined r5.9.3 + Euphoria Patches 1.10.5** (the pack in the user's modpack; it ships both `dh_*` and `voxy_*` programs). Hardware: Windows 11, RTX 4070. Only this pack was exercised, so every claim below is for this pack unless stated.

## How WynnIris draws LODs (what the code relies on)

| | Voxy | Distant Horizons |
| --- | --- | --- |
| Who draws | Voxy's own `MDICSectionRenderer`, with an `IrisVoxyRenderPipeline` | DH's renderer iterates its section list; Iris binds its own program per buffer |
| Terrain programs | Voxy's `quads3.vert` + `quads.frag`, with the pack's `voxy_emitFragment` implementation **appended** to the fragment source | Iris builds each program from the pack's `dh_terrain` / `dh_water` / `dh_shadow` source through `TransformPatcher.patchDHTerrain` and `IrisLodRenderProgram` |
| Where the mask goes | The existing Voxy patch already sits in front of the appended pack code, so it simply works once the uniforms are uploaded | After Iris's transform, the pack's `main` is wrapped (see below) |

### Voxy under Iris

`ShaderLoader.parse` is patched before the pipeline composes the shader (`pipeline.patchOpaqueShader` appends the pack's code to the already patched source), so the pack's programs keep the `wynnvistaWorldXZ` varying and the `wynnvistaRects`/`wynnvistaRectCount` uniforms (log: `locations=5/4/6/5`). The change in this milestone was to accept `IrisVoxyRenderPipeline` next to `NormalRenderPipeline` when all four uniform locations resolve. The rectangles are relative to each **viewport's** base section, and every viewport (a shadow viewport would be a second one) is uploaded with its own origin. `Voxy terrain mask now covers viewport #N` is logged for each new viewport. If Voxy's patched shader ever fails to compile Voxy falls back to its unpatched pipeline shader, which still contains the mask, and the capability log reports it.

### DH under Iris

After Iris's transform every DH terrain vertex shader contains `uniform vec3 modelOffset;` and `vec3 _vert_position;`, and `void main() {`. `DhIrisShaderPatch` (applied from `MixinDhIrisTransformPatcher` to the **return value** of `TransformPatcher.patchDHTerrain`, as a copy, because Iris caches the original map):

- Vertex: rename the pack's `main` to `wynnvista_packMain`, add `out vec2 wynnvistaXZ;` and a new `main` that calls it and then exports `modelOffset.xz + _vert_position.xz` (camera-relative, before any projection, TAA jitter or pack-specific warping).
- Fragment: rename the pack's `main`, add `in vec2 wynnvistaXZ;`, `uniform vec4 wynnvistaRects[8];`, `uniform int wynnvistaRectCount = -1;` and a new `main` that discards outside the allowed rectangles **before** running the pack's `main`.
- It refuses (and marks the integration failed, so the list filter stays conservative) when the shader has no or more than one `main`, lacks those two declarations, was already patched, or the program has geometry/tessellation stages.

`MixinDhIrisLodRenderProgram` resolves the uniform locations when Iris constructs a program and uploads camera-relative rectangles at the end of `fillUniformData`, which Iris calls once per pass for the solid, translucent and (if the pack has one) shadow programs. DH's own list filter (`MixinDhRenderBufferHandler`) already runs under Iris; it now keeps mixed sections whenever an Iris program has received the mask in the last two seconds (`DhIrisMaskState.canClip()`), otherwise it stays conservative. The stock DH patches no longer step aside just because Iris is installed: with Iris loaded and no shader pack active DH uses its stock shaders, which are masked exactly as before.

DH's separately rendered generic objects (beacon beams, clouds; `patchDHGeneric`) are not clipped, as before.

### Version gating

WynnIris keeps the Iris base version in its metadata (`1.10.8+mc1.21.11` for 1.2.1 and 1.2.2 alike), so the release counter cannot be read from the loader. `IrisSupport` instead probes the class bytes (without loading them) for the exact `TransformPatcher.patchDHTerrain` and `IrisLodRenderProgram.fillUniformData` signatures and the `id` field; the DH Iris mixins are applied only when those exist and the pinned DH build is present. The Voxy side depends on Voxy's own Iris pipeline class and on the uniforms linking, which is checked at runtime per pipeline.

## Results (Complementary Reimagined, WynnIris 1.2.2)

| Gate | Result |
| --- | --- |
| `gradlew build`: 24 JUnit tests including `DhIrisShaderPatchTest` | PASS |
| Voxy + Iris pipeline: patched shaders compile and link inside the pack's pipeline | PASS: `Voxy exact terrain mask ready: pipeline=IrisVoxyRenderPipeline, opaque uniforms=(5, 4), translucent uniforms=(6, 5)` |
| Voxy + Iris, LIGHT / MAIN overrides at the Light/main seam view | PASS: `ivoxy-tomain-light` (Light islands kept, distant main mountains gone), `ivoxy-tomain-main` (mountains kept, Light terrain gone), versus `ivoxy-tomain-baseline` |
| Voxy + Iris realm transitions MAIN → LIGHT → VOID_OUTER → MAIN → LIGHT | PASS for sampled ticks: revisions 3–7, 24 captures (`ivt-*`), only destination terrain in every frame |
| DH + Iris: Iris-built terrain and water programs carry the mask | PASS: `Iris DH terrain program 211 mask uniforms ready (44, 43)` and program `4` `(31, 30)`; each revision logs `bound to program 211/4` |
| DH + Iris, LIGHT / MAIN overrides | PASS: `idh-tomain-light`, `idh-tomain-main` against `idh-tomain-baseline` |
| DH + Iris exact clip through terrain | PASS: `idh-cut-x900` (fixture-only rectangle x ≤ −901, top-down) shows a crisp planar cut through the island; `idh-cut-baseline` is the unmasked view |
| DH + Iris realm transitions (same sequence) | PASS for sampled ticks: revisions 2–7, 24 captures (`idt-*`); the first frames after a teleport are sky while DH loads the destination, never source-realm terrain |
| Iris installed, shader pack **off**: DH and Voxy | PASS: `DH exact OpenGL mask revision 1: mode=LIGHT…` and `Voxy exact terrain mask ready: pipeline=NormalRenderPipeline` |
| Stock DH without Iris (regression after removing the Iris guards) | PASS: `DH exact OpenGL mask…` |
| Loader smoke: neither / both mods | PASS |
| Cache retention | PASS: Voxy `world_sections` 294,082 and `id_mappings` 3,949 fingerprints unchanged; DH 22,907 rows, 15,311 detail-0 payload hash unchanged, after all runs |

DH fixture on this PC: the Wynncraft overworld database from a Modrinth profile (1.6 GB, 22,907 rows: main 5,564, Light 116 and Void 137 detail-0 sections by block rectangle) copied to `.local-fixtures/dh/master/` and installed into `run-dh/saves/New World/data/`, with `generatorPlan=DISABLED`, the updater off and the fixture read-only freeze. DH's OpenGL renderer ran without native crashes on this machine (the Mac crashes recorded in `TESTING_DH.md` did not reproduce).

### Reproduce

```bash
python scripts/lod_fixture.py --backend dh create-world --reset
python scripts/lod_fixture.py --backend dh install --source ".local-fixtures/dh/master/Wynncraft-overworld.sqlite"
python scripts/lod_fixture.py --backend dh run --iris --shaderpack MyPack --shaderpack-source "<pack folder or zip>" --masking --override LIGHT --commands 'gamemode spectator @p|tp @p -800 160 -6100 0 12' --screenshot dh-iris-light.png --ticks 700
python scripts/lod_fixture.py --backend dh check
```

`--backend voxy` works the same with a Voxy storage as `--source`. `--iris` adds WynnIris (`-Piris=true` to Gradle); `--shaderpack off` loads Iris with shaders disabled. With Iris debug options on (the script enables them) Iris writes the transformed shaders to `<run dir>/patched_shaders/`, which is where the DH anchors were read.

## Not verified / open

- **Shadows.** Complementary's DH support has no `dh_shadow` program, so DH is cancelled for the shadow pass and nothing from DH casts shadows; and in this pack Voxy produced only one viewport (no shadow viewport), so shadow-pass masking is **not** exercised. Both paths are implemented the same way (DH shadow programs are built by the same patched `patchDHTerrain`; Voxy uploads per viewport) but are unproven. A pack with DH/Voxy shadows is needed; none is installed locally (the other pack entries in the profile are download links only).
- **Other shader packs and other WynnIris/Iris versions.** One pack and one WynnIris build were tested. A pack whose transformed DH terrain program contains geometry/tessellation stages, or whose `main` is not unique, will be left unmasked (logged), not broken.
- **DH under Blaze3D with a shader pack**: Iris drives DH through OpenGL programs; the Blaze3D renderer was not tested with Iris.
- **Continuous-frame captures, depth/translucent landmark tests, performance**: as for the other backends, sampled game ticks only; no frame-time measurements. The extra per-fragment loop is cheap but unmeasured.
- DH's own `Opaque + Translucent pass ran with shaders on` error from WynnIris appears in the first frames of a world load, before the pack's override takes effect; it predates this work.
- Official Iris (not WynnIris) is not tested; the probed signatures match upstream Iris 1.10.x in principle.
