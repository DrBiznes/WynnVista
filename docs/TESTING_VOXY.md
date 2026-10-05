# Voxy update and isolated fixture testing

Date: 2026-10-04. Voxy 0.2.16-beta on Minecraft 1.21.11 / Fabric. Hardware for every run below: Windows 11, NVIDIA GeForce RTX 4070 (OpenGL 4.6 driver; an AMD iGPU is also present but Minecraft ran on the NVIDIA GPU), Oracle JDK 21.0.4. The first PC validation of this project, so the results are for this machine only.

## What is implemented

Voxy's own pipeline keeps running; WynnVista only **clips terrain fragments** to the active region's rectangles, in the exact stock shader pair Voxy builds its terrain programs from.

| Piece | Location |
| --- | --- |
| Version gate (Voxy `0.2.16-beta`, embedded commit `59b62bee…`, MC `1.21.11`) | `compat/voxy/VoxyVersionSupport` |
| Checked shader patch on the import-expanded `voxy:lod/gl46/quads3.vert` / `quads.frag` | `compat/voxy/VoxyShaderPatch`, applied by `MixinVoxyShaderLoader` (`ShaderLoader.parse` return) |
| Uniform packing relative to Voxy's base-section origin | `compat/voxy/VoxyMaskUniforms` |
| Per-frame snapshot, uniform upload for opaque, temporal and translucent passes | `MixinVoxyMDICSectionRenderer` (`renderOpaque`/`renderTemporal`/`renderTranslucent` HEAD, constructor TAIL) |
| Fixture-only ingestion freeze | `debug/VoxyFixtureController` |
| Optional-mixin gating | `WynnVistaMixinPlugin` (Voxy mixins load only for the pinned build) |

How it works: the vertex shader exports the quad corner position `point` (relative to `baseSectionPos * 32`, captured **before** MVP and TAA) as varying `wynnvistaWorldXZ`. The fragment shader tests it against up to eight rectangles from uniform `wynnvistaRects[8]` and `discard`s outside them, after Voxy's derivative computation and before any depth, tint or colour output. `wynnvistaRectCount` defaults to `-1` in the shader (pass-through), so a program that never receives an upload is never clipped. The uniforms are uploaded with `glProgramUniform*` to both terrain programs once per pass; a region switch only changes uniform values (no shader compile, no cache work, no distance writes).

The old whole-pipeline cancellation and all Voxy distance writes are gone. Voxy scheduling, ingestion, traversal, GPU command generation and storage are untouched; hidden terrain is still traversed and drawn by the GPU and discarded per fragment (a coarse GPU-side cull is the optional M8 optimisation).

### Capability matrix (Voxy 0.2.16-beta)

| Path | Status |
| --- | --- |
| Stock `NormalRenderPipeline` (no shader pack): opaque, temporal, translucent | `EXACT` (this section's evidence) |
| Iris shader-pack pipeline (`IrisVoxyRenderPipeline`) | `UNSUPPORTED` — logged once; terrain is rendered **unmasked**. Explicit Iris support is the third project goal (M7) |
| Other Voxy versions | Mixins not applied; Voxy untouched |
| Distant Horizons and Voxy installed together | Both adapters initialise; simultaneous operation is untested (warned at startup) |

## The fixture

- Source (read only, never modified): the Modrinth profile's `.voxy/saves/play.wynncraft.com/`. It contains four world folders; only `1649b5d2ccbe735ca969eedc7f3ef39e` (546 MB, the Wynncraft overworld) has terrain. The three others are tiny (≤0.2 MB) and unused.
- Master copy: `.local-fixtures/voxy/master/` (whole closed database copied, minus RocksDB `LOCK` and `LOG.old.*` info logs). Git-ignored.
- Row-level fingerprint of the master (via `scripts/VoxyStorageFingerprint.java`, read-only RocksDB, on a scratch copy): `world_sections` = **294,082** entries (per LOD level 0–4: 253,332 / 34,538 / 5,046 / 831 / 335), SHA-256 `27d6f1d3…c271f3`; `id_mappings` = 3,949 entries, SHA-256 `eb6bb53b…df67`.
- Disposable save: `run-voxy/saves/New World`, a creative superflat world created headlessly by the client itself (`wynnvista.fixture.createWorld`, seed `wynnvista-fixture`, so the Voxy world id is stable: `b1736cf471c64fc682f84b5ed087f927`, dimension `minecraft:overworld`). The master storage was copied to `New World/voxy/<id>/storage` with the source `config.json` beside it (the source's storage config is Voxy's default ZSTD level 1 + RocksDB). The installed copy fingerprints identically to the master.
- No `/voxy import` was needed: the native storage copy preserves everything, including mappings. (`/voxy import distant_horizons` also exists in Voxy if a DH database is ever the source.)
- Ingestion freeze: `run-voxy/config/voxy-config.json` has `ingest_enabled=false`, and `VoxyFixtureController` also sets `VoxyConfig.CONFIG.ingestEnabled=false` in memory when the designated save starts (logged as `Voxy fixture ingestion frozen before world start`) and restores it when that server stops. Ordinary worlds and servers are never touched. Voxy rendering stays enabled and Voxy's `section_render_distance` is set to 8.0 (about 4,096 blocks) in this disposable config only.

### Reproduce

Windows, macOS or Linux; Java 21; Python 3. `WYNNVISTA_TEST_JAVA` may select the game's Java executable. From the repository root:

```bash
python scripts/voxy_fixture.py create-world --reset
python scripts/voxy_fixture.py install --storage "<closed .../play.wynncraft.com/<id>/storage>" --config "<.../play.wynncraft.com/config.json>"
python scripts/voxy_fixture.py run --commands 'gamemode spectator @p|tp @p 1200 150 -3000 90 0' --screenshot main-view.png
python scripts/voxy_fixture.py run --masking --override LIGHT --commands 'gamemode spectator @p|tp @p -800 160 -6100 0 12' --screenshot light-view.png
python scripts/voxy_fixture.py check
```

`run` options: `--masking` (default off = pass-through baseline), `--override AUTO|MAIN|LIGHT|VOID_OUTER|NONE|PASSTHROUGH|FIXTURE_CUSTOM`, `--custom-rect "x1,z1,x2,z2"` (inclusive block corners; fixture-only slice for exact-clip checks), `--commands` (`|`-separated, run at tick 40), `--timeline`/`--capture-prefix` (same format as the DH fixture), `--ticks`, `--results`. Screenshots go to `run-voxy/screenshots/`, logs to `run-voxy/test-results/<run>/`. `check` verifies the log evidence and compares the installed storage with the install-time fingerprint. The launcher needs Gradle's loopback networking; on a machine where the JVM cannot create a Selector (observed under this agent's sandbox, a Unix-domain-socket temp path problem) set `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=<short ASCII dir>`.

## Results

| Gate | Result |
| --- | --- |
| `gradlew build`: Java 21, 20 JUnit tests | PASS. Includes both Voxy shaders patched exactly once on the **expanded pinned sources**, anchor/duplicate/re-patch rejection, varying location 5 unused in the stock shaders, uniform packing and precision 14,000 blocks from the camera section |
| Shaders on a real GPU | PASS: both programs (opaque/temporal and translucent) compile and link; `Voxy exact terrain mask ready: pipeline=NormalRenderPipeline, opaque uniforms=(4, 3), translucent uniforms=(4, 3)` |
| Cache retention | PASS: after ~25 fixture launches (baselines, masks, transitions) with flat chunks loaded around the player, `world_sections` (294,082) and `id_mappings` (3,949) fingerprints are identical to install time |
| Pass coverage | PASS: each revision logs `bound for opaque / temporal / translucent pass` (`run-voxy/test-results/transitions/client.log`) |
| Baseline (no masking): main map, Realm of Light, Void/Outer Void all visible | PASS: `west-main` view, `light-baseline`, `void-baseline` |
| MAIN keeps main terrain, hides Light | PASS: `tomain-main` (distant main mountains, no Light terrain) vs `tomain-baseline` |
| LIGHT keeps Light terrain, hides main | PASS: `tomain-light` (Light islands, no main mountains); `seam-light`/`seam-main` top-down at the main/Light seam show the two sides swapping |
| LIGHT/VOID_OUTER hide the main map | PASS: `west-light` (main-map view, LIGHT override) shows no main terrain |
| AUTO selection | PASS: `light-auto` and `void-auto` selected LIGHT / VOID_OUTER by player position and kept the region's terrain |
| NONE | PASS: `east-none` empty sky, renderer lifecycle continues (normal exit) |
| Exact clipping through terrain and coarse LOD parents | PASS: `cut-x900` (top-down) and `cut-x900-oblique`, using the fixture-only `FIXTURE_CUSTOM` rectangle x ≤ −901. A crisp planar cut passes through the island, a tree canopy and coarse cells; no section-sized hole |
| Realm transitions MAIN → LIGHT → VOID_OUTER → MAIN → LIGHT in one run | PASS for sampled ticks: revisions 3–7 at each teleport, 24 captures at ticks −1/+1/+2/+3/+5/+15; no source-realm terrain appears in any destination frame. The first frames after a teleport show Voxy's coarse destination LODs refining |
| Loader smoke: neither / DH only / Voxy only (fixture runs) / both | PASS (`scripts/loader_smoke.py`): no mixin errors, expected backend lines, normal exits; with both present the two adapters initialise (untested together) |

Legacy biome names in the copied cache (`minecraft:snowy_tundra`, `mountains`, `taiga_hills`, `jungle_edge`, `snowy_mountains`, `wooded_hills`) make Voxy log `Could not find biome … using default`; this comes from the source cache, not from WynnVista.

## Not verified / open

- **Iris shader packs** with Voxy: not masked (see the matrix); M7.
- **Continuous-frame teleport capture** and every region edge/corner: transitions are sampled game ticks, not rendered frames; only the main/Light seam, Light's west and east edges (top-down) and the custom slice were viewed. Light's island lies wholly inside its rectangle, so real boundary cuts were demonstrated with the fixture-only custom rectangle rather than the production edges.
- **Synthetic translucent/depth landmarks**: coverage of the translucent program is shown by compile/link plus per-pass binding logs, not by a landmark water strip crossing a boundary.
- **Performance** (median/p95 frame time, transition hitch, memory): not measured. Hidden terrain is still drawn and discarded, so GPU cost is unchanged by masking; the optional GPU command-stream cull (M8) is unimplemented.
- **Dimension/level identity**: the fixture world's overworld id was recorded (`minecraft:overworld`). The production Wynncraft backend level identity was not independently confirmed here; the region policy still requires `minecraft:overworld`.
- Negative control for the ingestion freeze (ingestion on, flat chunks overwrite LODs) was not run.
- Other GPUs/drivers, macOS (Voxy cannot run there), long-duration stability.
- Distant Horizons OpenGL: the PC loader smoke run bound DH's OpenGL renderer and the WynnVista OpenGL mask without a crash, but that run had no LOD data; the DH OpenGL visual/transition matrix remains open.
