# WynnVista project update: selective LOD visibility

Research date: 2026-10-04. Status: DH implementation in progress. The Blaze3D fixture passes selected clipping, pass-binding, transition, and cache-retention checks. OpenGL validation is deferred to a PC after native crashes on the test Mac. See [the DH test record](TESTING_DH.md) for evidence and open gates.

## 1. Outcome and scope

Replace distance manipulation with a spatial visibility mask. In each managed Wynncraft region (main map, Realm of Light, or Void/Outer Void), render only that region's LOD terrain. Keep each area's cached data so switching regions is immediate and reversible.

“Unlimited distance” means **WynnVista imposes no distance limit and never changes the LOD mod's distance setting**. DH/Voxy still have their own distance, memory, hardware, and available-data limits. This change cannot create terrain absent from the cache.

The project has three ordered goals: **(1) Distant Horizons support, (2) Voxy support, and (3) explicit Iris support**. Finish and test DH's shader-free paths first. Then adapt the same region policy to Voxy. Iris is a separate compatibility goal for both renderers, including shader packs and shadow passes; do not infer Iris support from a working stock shader. Target Minecraft **1.21.11 / Fabric / Java 21** initially. Keep the existing mod ID, Java package, and config filename to avoid an unrelated migration.

Included:

- Main-map/Realm-of-Light/Void selection, geometry clipping, lifecycle handling, optional-mod isolation, and config migration.
- A reproducible, opt-in singleplayer superflat test fixture using copied Wynncraft LODs.
- Shader-free DH OpenGL and Blaze3D paths, followed by shader-free Voxy, then explicit Iris integration and verification.

Excluded from the first release: deleting/rebuilding users' databases, distance controls, automatic world downloads, cache conversion tooling, general polygon editing, support for every upstream version, and modifications to vanilla/Sodium's ordinary chunk rendering. Nearby real chunks can still show out-of-map structures; this project masks LOD terrain only. DH's separately rendered generic objects such as beacons/clouds are not automatically covered by terrain clipping.

### Implementation status on 2026-10-04

- The Java 21 build and 11 JUnit tests pass. The pure region policy, Wynncraft host check, world context resolver, immutable snapshot, schema-2 config migration, and removal of DH/Voxy distance writes are implemented. Voxy masking remains deferred.
- DH 3.3.3 has a version-gated shared render-list filter and checked stock shader patches for Blaze3D and OpenGL. When an exact shader path is unavailable, mixed sections are withheld conservatively. This fallback can leave missing strips at region edges.
- An opt-in, read-only superflat fixture preserves all 25,009 imported `FullData` rows, including 16,706 detail-0 rows. The two-launch harness, per-tick transition captures, and repeat full-file SQLite comparison are available under `scripts/`. The working saves, caches, logs, and screenshots are ignored by Git. Setup steps are in the [README](../README.md).
- Blaze3D visual checks cover a MAIN edge against an unmasked baseline, NONE, automatic LIGHT and VOID_OUTER, and the MAIN → LIGHT → VOID_OUTER → MAIN → LIGHT sequence at sampled ticks. Runtime diagnostics confirm `WynnVistaMask` binds in both opaque and transparent terrain calls. The DH frustum-off edge view also remained clipped.
- OpenGL has one exact-mode screenshot under a C1-only JVM setting; ordinary runs on the Mac crashed natively. PC validation is pending. Continuous-frame teleport capture, all edges and corners, synthetic translucent/depth landmarks, dimension/reload/camera modes, and performance measurements remain release gates. Iris remains the third project goal after Voxy.

## 2. Pre-update source baseline

Paths below are relative to the repository, under `src/main/java/me/jamino/wynndhrangelimiter/` unless otherwise specified.

| Location | Existing behavior | Required change |
| --- | --- | --- |
| `WynnVistaMod.java` | Chooses DH before Voxy; treats any multiplayer connection as eligible; tests one hardcoded X/Z rectangle each tick | Resolve an explicit world context and active region; publish immutable visibility state |
| `controller/DistantHorizonsController.java` | Writes DH's `chunkRenderDistance()` through the API | Replace with spatial mask integration; keep optional classloading isolation |
| `controller/VoxyController.java` | Writes Voxy config/live distance, applies an empirical conversion ratio, and toggles visibility | Remove distance conversion/writes; implement a renderer adapter |
| `mixin/client/MixinVoxyRenderPipeline.java` | Cancels all of `AbstractRenderPipeline.runPipeline` when hidden | Remove this normal-operation hook once selective filtering is ready |
| `util/VoxyVisibilityHandler.java` | One global visible/hidden boolean | Replace with the shared visibility snapshot |
| `ModConfig.java` | Captures/restores distance and presents distance sliders | Version config; expose masking, region data, diagnostics, and isolated fixture settings |
| `build.gradle`, `gradle.properties` | MC 1.21.11 but DH API 4.0.0, old DH 2.3/1.21.4 runtime value, Voxy 0.2.15; Java release 17 | Pin compatible artifacts; use Java 21; separate DH/Voxy/neither development runs |
| `src/main/resources/fabric.mod.json`, mixin JSON | Broad MC metadata, Java >=17, required Voxy mixin | Align metadata; conditionally apply backend mixins before target classes load |

Before this update, the source used `x >= -2512 && x <= 1553 && z >= -5774 && z <= -207`. The implementation now uses the user-supplied boundaries in section 4.1 and has a Realm of Light definition and tests. The table above remains as a record of the original code and the migration intent.

## 3. Verified upstream baselines

Do not implement against moving default branches. Target the **latest published Fabric build for Minecraft 1.21.11** verified on 2026-10-04, then pin it. Mod versions and DH API versions are different numbering systems. Recheck releases before implementation begins and update the pin only with a corresponding source review.

| Component | Inspected source / published artifact | Implementation rule |
| --- | --- | --- |
| DH | Tag `3.3.3`; wrapper commit `a54dd3eb1df0078e63a71266d042c10d9cda4493`; core submodule `b02c66d778beea11a931291815d652cd8abaa7ad` | Latest verified Fabric release: `3.3.3-1.21.11`, Modrinth version ID `oNqCUHFk` |
| DH API | DH 3.3.3 declares **7.2.0**; API project `Xs0XTOVv`, artifact `distanthorizonsapi` | Compile against `maven.modrinth:distanthorizonsapi:7.2.0`, API version ID `LTP8vW3B`; verify class signatures against source and runtime jar |
| Voxy | Branch `12111`, commit `59b62bee821518e06612e1d5c2c58c487bda761d`, source version `0.2.16-beta` | Latest verified Fabric build for 1.21.11: `0.2.16-beta`, version ID `H3w2nVdU`; verify embedded commit / signatures against the source |
| Voxy default branch | Inspected `dev` at `534d58ec8b4aa412ef314b884295552c69d480a6`, targeting MC 26.2 | Do not use its class layout as the 1.21.11 implementation contract |

Published artifact versions were checked using Modrinth's project-version API filtered to Fabric and 1.21.11. DH 3.3.3 is a stable release; Voxy 0.2.16-beta is marked beta. This section records the original source research. Subsequent DH binary inspection and fixture runs are recorded in `TESTING_DH.md`; Voxy has not been executed. Compatibility claims apply only to the pinned builds until other versions are checked.

### DH findings

1. `RenderBufferHandler.buildRenderList(RenderParams)` gathers enabled `LodRenderSection`s into `loadedNearToFarBuffers`. At the inspected 3.3.3 core commit the per-section cull/list flow remains: each section has packed `pos`; `DhSectionPos.getMinCornerBlockX/Z`, `getBlockWidth`, and `getDetailLevel` provide its footprint. The terrain renderers consume this list. This is a useful shared coarse-filter point for OpenGL and Blaze3D.
2. `IDhApiCullingFrustum.intersects(minX, minZ, width, detail)` is public, but the call is bypassed when users disable frustum culling. Shadows use a separate frustum override. Consequently a custom frustum alone cannot enforce the visibility contract. Do not replace another mod's frustum or change the user's culling config.
3. `DhApiBeforeBufferRenderEvent` exists but **is not cancellable**. Its `modelPos` is a render offset, not a complete world-space section footprint. In API 7.2.0 the parameter object is mutable and reused to reduce allocations, so copy needed values immediately; never retain the event parameter. Do not invent a cancellable per-buffer API or use that offset as absolute X/Z.
4. DH 3.3.3 uses terrain shaders at `assets/distanthorizons/shaders/terrain/gl/{vert.vert,frag.frag}` and `terrain/blaze/{vert.vsh,frag.fsh}`. OpenGL program setup is in `GlDhTerrainShaderProgram`; Blaze3D pipeline setup/draws are in `BlazeDhTerrainRenderer`. The Blaze terrain vertex format now includes `textureTile`; its bindings, per-buffer uniform flow, antialiasing/TAA, and render-pipeline wrappers differ from 3.0.3. Patch these 3.3.3 sources and pipeline, not the former `shared/gl/*` or `lod/blaze/*` paths.
5. In OpenGL, the model offset is section origin minus camera position. In Blaze3D, the vertex shader subtracts `uCameraPos` from the model offset. The misleadingly named `vertexWorldPos` is therefore camera-relative and is later altered by micro-offset/earth curvature. Capture the unmodified position for region tests, preserve DH 3.3.3's TAA jitter, and repeat the mask in both regular and reverse-Z modes.
6. `DhApi.Delayed.worldProxy.setReadOnly(true)` is a public API intended to stop LOD creation/updates. `SharedApi.applyChunkUpdate`, server update paths, and generation queues consult it. It requires a loaded DH world; DH resets it on unload. This is the preferred fixture ingestion freeze.

Sources: [3.3.3 render-list construction][dh-list], [frustum API][dh-frustum], [buffer event][dh-event], [OpenGL terrain program][dh-gl], [Blaze terrain renderer][dh-blaze], [read-only API][dh-readonly], [shared lifecycle/update implementation][dh-shared].

### Voxy findings

1. `AbstractRenderPipeline.runPipeline` orchestrates opaque rendering, a temporal terrain draw, and translucency. Cancelling this is the current blanket suppression and is not the new architecture.
2. `MDICSectionRenderer` builds terrain programs from `quads3.vert` and `quads.frag`. Opaque and temporal rendering share `renderTerrain`; translucency uses a separate program. All three paths must see the same mask.
3. Visibility selection/draw command generation is GPU-based. A CPU predicate on one Java “section render” method will not necessarily filter every GPU-generated draw. Initial correctness should come from clipping terrain fragments; GPU traversal/command culling is a later optimization.
4. `ShaderLoader.parse(String)` expands shader imports using classpath resources. A normal resource-pack override is not a reliable integration. Use a version-checked transformation of the selected shader sources and verify the compiled result retains it.
5. `pos_util.glsl` decodes signed section coordinates and LOD level. A section spans `32 * 2^lodLevel` **blocks**. In `quad_util.glsl`, vertex positions are relative to `baseSectionPos * 32`. This is unrelated to the old controller's empirical distance ratio.
6. The 1.21.11 default storage is RocksDB wrapped in compression/serialization. Storage includes mappings as well as geometry. Copy the complete closed database, not individual `.sst` files or geometry records.
7. Voxy supplies a DH importer, but it expects a particular `FullData` schema, detail level 0, format version 1, and compression modes 3/4. The command appears only if required SQLite/XZ classes are present. “There is an importer” does not establish compatibility with every DH 3.0 cache.

Sources: [pipeline][vx-pipeline], [MDIC renderer][vx-mdic], [shader loader][vx-loader], [position decode][vx-pos], [quad construction][vx-quad], [default storage][vx-storage], [DH importer][vx-import].

## 4. Shared visibility architecture

### 4.1 Region data and selection

Use explicit allowlists of terrain rectangles, not “everything outside the main map is Realm of Light.” Quest instances and staging builds also exist outside the main map.

Use block-aligned, half-open terrain rectangles: `[minX, maxXExclusive) × [minZ, maxZExclusive)`. The user supplied these X/Z corners on 2026-10-04. Treat the supplied corners as inclusive block positions, so the entire last block is included; add one to each normalized maximum when constructing the internal rectangle.

| Region | Supplied opposite corners (X, Z) | Inclusive block bounds | Internal half-open bounds |
| --- | --- | --- | --- |
| Main map | `(-2518, -60)`, `(1628, -5813)` | X `-2518..1628`, Z `-5813..-60` | `[-2518, 1629) × [-5813, -59)` |
| Realm of Light | `(-636, -6616)`, `(-1111, -5815)` | X `-1111..-636`, Z `-6616..-5815` | `[-1111, -635) × [-6616, -5814)` |
| Void / Outer Void | `(13393, -3195)`, `(14380, -4704)` | X `13393..14380`, Z `-4704..-3195` | `[13393, 14381) × [-4704, -3194)` |

These are the implementation defaults, replacing the old main rectangle. Normalize corner order independently on each axis. At overlapping X coordinates, the block row `z = -5814` belongs to neither region: retain this one-block gap rather than expanding either rectangle. Test the corresponding continuous interval `[-5814, -5813)` as `NONE` in a recognized Wynncraft context.

The supplied rectangles do not overlap in X/Z, so use them across the full vertical range of the recognized level; do not invent Y bounds. The gaps between them remain `NONE`. Dimension/backend level identity was not supplied and remains a fixture/live diagnostic check. Record the actual dimension and level keys during M2 and bind production region definitions to them. If later regions overlap in X/Z, establish whether Y/dimension/level identity separates them before using an X/Z-only renderer mask.

Separate `activationBounds` (where the player is considered in a realm) from `terrainBounds` (what terrain is allowed). They may initially match, but a portal landing pad may require different activation bounds. Use player position to select a realm; third-person/free-camera movement must not switch realms.

| Context | Policy |
| --- | --- |
| Unrelated server or ordinary singleplayer | `PASSTHROUGH`: upstream rendering untouched |
| Wynncraft, player in verified main activation area | `MAIN`: allow main terrain rectangles only |
| Wynncraft, player in verified Realm of Light activation area | `LIGHT`: allow Light terrain rectangles only |
| Wynncraft, player in verified Void / Outer Void activation area | `VOID_OUTER`: allow Void/Outer Void terrain rectangles only |
| Wynncraft, unknown/other area or world transition without a valid destination | `NONE`: no allowed LOD terrain |
| Explicitly selected local fixture | Same resolver, with a clearly indicated test-only override |

`NONE` means the spatial allowlist is empty. It must not cancel the entire renderer, change distance, disable background services, or delete data. For a connection to an unrelated world, reset to `PASSTHROUGH` immediately.

Normalize multiplayer hostnames and accept `wynncraft.com` or a true `.wynncraft.com` subdomain, with explicit configured aliases if needed. Do not use substring matching. Include the Minecraft dimension and backend level identity in context. Unknown dimensions/levels on Wynncraft must not inherit an old region. Investigate overlapping worlds that reuse the same dimension/coordinates: a spatial mask cannot recover two terrain versions already merged into the same upstream database.

### 4.2 Suggested types and ownership

Create these under the existing Java package; names are proposals, not existing upstream APIs.

| Type | Responsibility |
| --- | --- |
| `visibility/RegionId` | `MAIN`, `LIGHT`, `VOID_OUTER`; keep pass-through/none as policy modes |
| `visibility/BlockRect` | Pure point containment and rectangle classification using block coordinates |
| `visibility/RegionDefinition` | ID, activation rectangles, terrain rectangles, dimension/level constraints |
| `visibility/WorldContextResolver` | Recognized server or fixture, world identity, player-derived region |
| `visibility/VisibilitySnapshot` | Immutable mode, allowed rectangles, world token, monotonically increasing revision |
| `visibility/VisibilityService` | Atomically publish state; initialize/reset on join, unload, dimension changes, disconnect |
| `compat/LodVisibilityBackend` | `initialize`, `capabilities`, `beginFrame(snapshot)`, `reset`; no distance getter/setter |
| `compat/dh/`, `compat/voxy/` | Version-specific renderer integration and their optional mixins |
| `debug/FixtureController` | Opt-in local fixture identity, ingestion freeze, status, region override |
| `mixin/WynnVistaMixinPlugin` | Apply backend mixins only for present, explicitly supported mods/versions |

Keep pure visibility types free of Minecraft, DH, and Voxy imports. The client owns region selection; rendering captures one snapshot before the first LOD pass and uses it throughout that frame. GL/Blaze resource allocation and uploads happen only on the render thread. World transitions clear the old context before any destination rendering; evaluate a fresh player/world identity before rendering the next LOD frame, not only at the end of a possibly delayed client tick.

Backend registration must not eagerly load absent mod classes. Do not retain the DH-first `else if` as a way to ignore another installed backend. Initial validation profiles use one renderer at a time; if both are installed, detect both and report whether each is covered. Simultaneous renderer compatibility is not established by this research.

### 4.3 Two levels of filtering

For an upstream section AABB, classify its full X/Z footprint:

```text
PASSTHROUGH                -> upstream result unchanged
NONE                       -> OUTSIDE
no intersection with mask  -> OUTSIDE
entire footprint allowed   -> INSIDE
otherwise                  -> INTERSECTING
```

Use union-aware containment if one region has adjacent rectangles; corner-only checks do not prove containment in a non-convex union. Start with at most eight rectangles per active region and reject excess/invalid input visibly rather than silently truncating it. Use `long` arithmetic for section extents and proper floor division for negative coordinates.

- **Coarse culling:** skip `OUTSIDE` sections from draw submission when there is a suitable integration point.
- **Exact clipping:** keep `INTERSECTING` sections and discard terrain fragments outside the active allowlist before color/depth output. A section center test leaks; rejecting every intersecting section creates large missing strips at low detail.

Use an unwarped, pre-projection X/Z varying and upload bounds relative to the same camera/render origin. Do not compare clip-space coordinates, jittered screen coordinates, or blindly assume a variable called `worldPos` is absolute. Use the same mask for opaque, cutout, translucent, depth-writing, deferred/temporal, and supported shadow terrain passes. Fully transparent output is insufficient because it may still write depth.

The mask clips rendered geometry; it cannot undo terrain/color information already averaged into a coarse LOD cell across a boundary. Test this explicitly. If unacceptable artifacts remain *inside* the allowed side, add targeted boundary refinement or conservative rejection of straddling coarse cells as a separately measured change. Do not promise that fragment clipping reconstructs original blocks or that dropping a coarse parent automatically draws its children.

No database mutations, terrain regeneration, remeshing of the entire map, or shader recompilation on region switches. A switch updates the snapshot/uniforms; redraw existing cached data.

## 5. Distant Horizons implementation

### 5.1 Coarse selection

Use a small, version-pinned mixin at `RenderBufferHandler.buildRenderList` to enforce the mask independently of the user's frustum settings. Preferred injection: wrap the addition to `loadedNearToFarBuffers` and omit only `OUTSIDE` entries, using `LodBufferContainer.pos`/`DhSectionPos`. Verify the exact invocation descriptor against the released jar. Avoid copying the whole method or changing `LodRenderSection.getRenderingEnabled()` persistently.

Capture the snapshot before list construction, not separately for each section/pass. Shared-list filtering should cover both terrain backends and shadow list construction; confirm this in runtime diagnostics. Leave sorting, locks, buffer ownership, upload, and upstream occlusion behavior intact. Record rejection counts without per-section logging.

A preliminary smoke-test may temporarily keep only `INSIDE` sections to prove hook coverage. Label this **conservative prototype**, not completed masking; it is not acceptable as the final map-edge implementation.

### 5.2 Exact clipping: mandatory feasibility spike

Implement one shader-free path first, demonstrate a rectangle cutting through a coarse buffer, then implement the other path.

- **OpenGL:** transform only the two terrain shader sources named in section 3. Introduce WynnVista-prefixed varyings/uniforms, derive original camera-relative X/Z before micro-offset/curvature, and discard outside the bounds in the terrain fragment shader. Upload uniforms to the actual bound terrain program at the appropriate pass/bind hook. Cache locations per program and invalidate them on reload/destruction. `DhApiBeforeBufferRenderEvent` may help with timing but is not the rejection mechanism.
- **Blaze3D:** add equivalent varyings to the terrain vertex/fragment pair; declare a dedicated mask uniform block on both terrain pipelines, allocate/upload with the supported Blaze GPU API, and bind it to every terrain render pass. Keep std140 layout and buffer lifecycle explicit. Do not assume OpenGL `glUniform` updates work for Blaze pipelines.
- Transform at a verified shader-loading/pipeline construction point. Fail if expected source anchors occur zero or multiple times. Do not ship complete copied upstream shaders just to alter a few lines. Add patch tests against the pinned sources.
- A frame snapshot with zero allowed rectangles clips all terrain but lets the pipeline finish normally. Off-server pass-through must bypass clipping entirely.

The source confirms suitable list, shader, and uniform paths; it does **not** establish exact clipping as a ready-made public DH API. This spike must produce compilable mixin targets and an actual GPU screenshot before UI work or a DH support claim. DH 3.3.3 changed the render-list matrix setup, terrain shaders, per-buffer API event parameter, Blaze pipeline wrappers, and draw uniforms after 3.0.3; recheck injection descriptors and shader/uniform lifetime against the pinned binary. If the shader integration cannot be made reliable for a selected backend, mark that backend unsupported and document the missing hook; do not silently call coarse filtering “exact.”

### 5.3 Iris/custom shaders and unsupported versions

Iris can supply a different terrain shader program. Modifying DH's stock fragment shader does not prove shader-pack support. The initial exact support claim is shader-free. For recognized DH versions with the shared-list hook but an unverified shader path, use a reported `CONSERVATIVE` mode that draws only wholly contained sections; this prevents out-of-bounds section geometry but can leave gaps. Test shadows separately.

For an unrecognized binary where even the coarse hook is unverified, skip unsafe mixins, report `UNSUPPORTED`, and state that filtering is unavailable. Do not silently change distance or revive whole-pipeline cancellation. The support matrix must distinguish `EXACT`, `CONSERVATIVE`, `UNSUPPORTED`, and `ABSENT`.

## 6. Voxy implementation after DH passes

1. Replace the existing `runPipeline` cancellation with a non-cancelling frame-state capture/upload hook. Keep Voxy's scheduling, ingestion, distance tracking, GPU traversal, and storage alive.
2. At the pinned `ShaderLoader.parse` or the MDIC construction call sites, transform only `voxy:lod/gl46/quads3.vert` and `voxy:lod/gl46/quads.frag`. Account for expanded imports and assert patch markers survive later `pipeline.patchOpaqueShader` / `patchTranslucentShader` processing and compilation fallback.
3. Capture the pre-MVP `point` assembled in `getQuadCornerPos`. It is relative to `baseSectionPos * 32`; upload rectangle bounds relative to that same origin, not the DH camera origin. Preserve TAA position changes and Voxy's UV/barycentric paths. Ensure the varying also works when `USE_SINGLE_TRI`/`USE_NV_BARRY` paths are compiled.
4. Put the same fragment discard in opaque/temporal and translucent programs. Upload the current mask after binding each actual program, before its draws. `renderTemporal` uses `renderTerrain`, but prove this coverage with counters/tests instead of assuming one injection hits everything.
5. Handle first-frame defaults, `/voxy reload`, shader switches, world recreation, and uniform program lifetime. No GPU readback, per-section CPU scan, or shader compile on a realm switch.
6. Once clipping is correct, consider rejecting wholly outside sections during command generation/traversal to save GPU work. Inspect opaque, temporal and translucent command streams together. A stale command list must never bypass the current fragment mask.

Shader-pack compatibility is a separate spike because Voxy patches its shaders through its pipeline. Do not claim support unless patch markers, outputs, depth, and transitions all pass. Unlike DH, no conservative Voxy command-filter fallback is specified as implemented here; unsupported paths must be reported honestly.

### Third project goal: explicit Iris support after Voxy

Iris support is an independent delivery goal, not a side effect of the stock DH/Voxy shader patches. Inspect how Iris replaces or transforms each backend's terrain programs, add version-gated integration for the selected Iris build, and verify the active region mask in ordinary terrain, shader-pack terrain, depth, and shadow passes. Test both backend combinations with Iris separately, including reloads, transitions, and unsupported shader packs. Publish a capability result for each path; remain conservative or unsupported where exact clipping cannot be verified. This goal is recorded now but implementation is deferred until the DH and Voxy goals pass their own gates.

## 7. Configuration and migration

The implemented schema 2 fields are `maskingEnabled`, `showMessage`, `fixtureEnabled`, `fixtureSavePath`, and `fixtureOverride = AUTO|MAIN|LIGHT|VOID_OUTER|NONE|PASSTHROUGH`. Region rectangles are currently fixed in the pure policy. Configurable regions, server aliases, broader validation, and a reload workflow remain proposed work.

- Migrate `WynnVista.json` once, preserving `showMessage` and making a backup before rewriting. Retire `maxRenderDistance`, `reducedRenderDistance`, and `originalRenderDistance`; do not convert them into masks.
- Never capture, force, or restore DH/Voxy distance in the new mode. Tell upgrading users to choose their preferred distance in the LOD mod, because prior versions may have left a changed value.
- Remove distance sliders and the current first-run capture flow. Validate finite, ordered bounds, dimension constraints, rectangle count, and ambiguous activation overlaps. Failed reloads retain the previous valid config and display an actionable error.
- Messages should reflect region/masking transitions, not claim to change fog. Suppress join spam as before.
- Debug overrides apply only to the designated local fixture; they must not silently activate in every singleplayer world or on multiplayer.
- Diagnostic output: recognized context, dimension/level identity, player position, region, revision, backend/version/path, capability status, allowed bounds, rejected section count where available, shader patch state, and ingestion-freeze state. Log on changes, not every tick.

## 8. Singleplayer superflat test fixture

The user's proposal is the main integration fixture: a flat vanilla world underneath copied Wynncraft LOD data. No full Wynncraft world file is necessary. **LOD caches are not playable blocks**: collision and nearby chunks remain superflat, and LODs may fade near the vanilla render radius. View landmarks from farther away than that radius.

### 8.1 Preparation shared by both backends

1. Use a separate Minecraft instance, separate save per backend, and exact pinned mod/dependency versions. Create creative superflat saves with commands enabled, structures off, and a low flat surface. Match the source dimension's vertical range; a mismatched range can shift/crop imported data.
2. Close the game before copying databases. Keep an untouched master copy and a disposable working copy. Do not symlink to a live multiplayer cache. For SQLite, copy a consistent closed/checkpointed database; unresolved WAL data cannot simply be discarded. For Voxy, copy the entire closed storage directory with its mappings/metadata.
3. Record source server/level identity, source coordinates, MC/mod versions, resource pack, cache format, file checksums, destination paths, and known landmarks in a local fixture manifest. Do not commit large caches or the user's world files.
4. Create and open each empty destination once to discover its generated storage path/identity; then exit. Install the working copy and enable the named fixture's ingestion freeze **before** rejoining.
5. Preserve original X/Z and heights. Do not move Wynncraft to superflat spawn. Teleport to recorded coordinates; set vanilla render distance low enough to see LODs without excessive nearby flat occlusion. Make a baseline screenshot with masking off that proves main, Light, Void/Outer Void, and unwanted terrain actually exist in the cache. A missing cache region is not a passing hide test.
6. Run fixed camera positions/yaw/pitch with masking off, MAIN, LIGHT, VOID_OUTER, NONE, and AUTO. Override modes test rendering independently of actual portal placement; AUTO tests region selection. Freeze time/weather and keep all upstream quality settings unchanged between captures.

### 8.2 DH cache placement and freeze

DH's database filename is `DistantHorizons.sqlite`. Multiplayer paths are selected by `ClientOnlySaveStructure`, potentially using server-provided keys; singleplayer uses `LocalSaveStructure` and the vanilla level data directory. Common paths are under `Distant_Horizons_server_data/...` and `<save>/data/`, but **use the files/logs actually generated by the pinned build**, including the correct dimension. Do not guess a server folder from its display name.

Copy the correct closed source level DB into the destination level's generated DB location. If main and Light reside in separate source databases, use separate fixture saves (or a separately verified multi-level mapping); do not merge SQLite files by hand. At least one mixed-region fixture is still needed to prove spatial masking when both areas coexist in one level.

Register the fixture's DH handler early. In `DhApiWorldLoadEvent`, identify the designated integrated save and call `DhApi.Delayed.worldProxy.setReadOnly(true)` before normal chunk updates. The inspected `SharedApi` assigns the loaded world before firing this event, and resets read-only on unload. Confirm integrated client/server ordering at runtime and handle already-loaded initialization without claiming the early race is solved by a later tick. Disable distant generation for the fixture as an additional precaution. Never set read-only globally for ordinary play.

Before testing masks, prove the freeze: inspect representative LOD data in areas where vanilla flat chunks load, leave/rejoin, and verify it has not been replaced by flat terrain. A bytewise DB hash may change through migration/metadata maintenance; distinguish that from changed terrain records. Read-only is a logical DH mode, not a guarantee of zero filesystem writes. The untouched master remains the recovery source.

Sources: [save structures][dh-save], [singleplayer save structure][dh-local], [read-only contract][dh-readonly], [lifecycle][dh-shared].

### 8.3 Voxy cache placement and freeze

The inspected branch uses multiplayer `<game>/.voxy/saves/<server>/` and singleplayer `<save>/voxy/`. Within that base, the default path includes `<world_identifier>/storage/`. The identifier depends on world/dimension information including biome seed; merely copying the multiplayer server folder into the save will not select it.

Create the destination first, identify its current world ID and generated storage config, close the game, and replace that destination world's complete empty storage with a disposable copy of the source world's complete storage. Preserve compatible serializer/compressor settings. Verify mappings by inspecting recognizable blocks/biomes after reopening. Never merge two RocksDB directories. Record the exact mapping in the manifest.

The fixture can use `VoxyClientInstance.isIngestEnabled(WorldIdentifier)` as a narrowly scoped override returning false for the designated world. Alternatively, in the isolated test instance only, set `VoxyConfig.CONFIG.ingestEnabled = false` before loading the fixture; record and restore the previous value. Verify it before teleporting through source landmark chunks. Rendering remains enabled.

Optional alternate input: the pinned command is `/voxy import distant_horizons "<absolute path to copied DistantHorizons.sqlite>"`. Confirm it exists in tab completion and that importer dependencies/schema match. Use a small throwaway sample first, count imported sections, and compare heights/materials. Do not fall back to importing the superflat `.mca` files: that creates flat LODs, not Wynncraft. Native Voxy cache copies are preferred when available; a converter is not required for this project.

Sources: [Voxy path/ingestion selection][vx-client], [world identity][vx-identity], [command registration][vx-commands], [importer format checks][vx-import].

## 9. Testing and release gates

### Automated tests

Use JUnit for the pure policy code and meaningful renderer-patch checks. Eleven tests now pass, covering selected policy and pinned shader-patch cases; the remaining bullets are the broader release test matrix.

- Points exactly on each min/max boundary, fractional player positions, negative coordinates, all four corners, and non-finite/invalid config inputs.
- User-boundary regression cases in a recognized level: both supplied corners of each region are inside. At `x = -800`, `z = -5815` selects LIGHT, `z = -5814` selects NONE, and `z = -5813` selects MAIN. At `x = 14000`, `z = -4000` selects VOID_OUTER, while coordinates just beyond each of its four edges select NONE. Also test fractional positions throughout all gaps. Main includes block X `1628` and Z `-60` but excludes X `1629` and Z `-59`; Light includes block X `-636` and Z `-5815` but excludes X `-635` and Z `-5814`; Void/Outer Void includes X `13393..14380` and Z `-4704..-3195`, and excludes X `13392`, X `14381`, Z `-4705`, and Z `-3194`. Test each axis with the other coordinate inside that region, and test all normalized minima as inclusive.
- AABBs wholly inside/outside, straddling every edge of all three regions, enclosing a region, touching without overlap, and spanning multiple adjacent rectangles. Include very coarse LODs and overflow-safe extent calculations.
- MAIN/LIGHT/VOID_OUTER/NONE/PASSTHROUGH truth table; dimension mismatch; hostname suffix attacks such as `wynncraft.com.example.org`; ordinary singleplayer; test override restricted to one save.
- Join before/after backend initialization, teleport, level change, unload, disconnect/reconnect, config reload, and stale world tokens. Every frame uses one revision; no destination frame uses a source realm mask.
- Legacy config migration is idempotent and preserves messages; no distance-setting calls in normal backend lifecycle or config migration.
- Shader transformations insert exactly once into pinned source fixtures, retain matching varyings/uniform layouts, and reject altered/unrecognized inputs. GPU compile/render tests are still required; text tests alone do not validate GLSL.
- Backend absence and version gating: launch with neither mod, DH only, Voxy only, and both present for a loader smoke test. No optional-class loading crashes.

### In-game matrix

| Test | Expected result / evidence |
| --- | --- |
| Each managed region center, all edges/corners; low and high viewpoints | Only landmarks from the active region visible; landmarks from the other regions and staging areas absent |
| Mask cuts through a coarse section and merged quad | Clean geometry cutoff without a section-sized missing strip |
| Unknown region / NONE | No LOD terrain, while vanilla chunks and renderer lifecycle continue |
| Distances such as 64, 256, 1024 chunks where upstream supports them, plus highest practical supported setting | No forbidden terrain at any distance; native distance setting unchanged |
| Rotate camera, third person/free camera, FOV and resize | No mask motion or realm change caused by camera offset |
| Water, glass/cutout foliage, fog/depth interaction, temporal draw | No translucent/depth/temporal leak |
| Rapid MAIN↔LIGHT↔VOID_OUTER teleports, reconnect, resource/shader reload | No stale-region flash; retained data becomes visible again |
| DH frustum culling toggled off; OpenGL and Blaze tested separately | Mask still enforced |
| Shader pack and shadows, only after compatibility work | Hidden terrain neither appears nor casts LOD shadows; unsupported paths visibly identified |
| Other server, ordinary singleplayer, disconnect to menu | Pass-through; no leftover mask/freeze; distance untouched |
| Fixture travel and restart | Imported cache survives flat chunk loads |

Record identical camera captures with/without masking, context/mask revision, backend path, exact versions, GPU/driver, and cache landmarks. Video/frame capture is required for teleport flashes; a single screenshot can miss them. For synthetic tests, create strongly colored allowed/forbidden landmarks and a water strip crossing the boundary before capturing their LODs. Use the supplied coordinates, including the one-block gap, to test geometry even before a real Light cache is available.

Measure warmed median/p95 frame time, transition hitch, allocation rate, and memory in three comparable runs. Initial target: no shader compilation/full cache rebuild during switching and no more than roughly 5% steady-state p95 regression in the same scene; report measurements rather than treating that target as guaranteed. Large view-distance changes made by the user can still cost upstream work.

Use hardware that actually runs the backend. Voxy checks compute/indirect-draw capabilities and uses GLSL 460; do not treat a successful Java build on this Mac as a Voxy GPU validation. Record skipped GPU cases and run them on supported hardware. Shader-free DH paths must also be validated on the intended platform.

## 10. Small, ordered implementation tasks

Complete one milestone and its exit evidence before starting the next. Do not implement both renderer adapters in a single large patch.

| Milestone | Work and files | Exit evidence |
| --- | --- | --- |
| **M0 — Reproducible baseline** | Fix Java/MC metadata; pin DH 3.3.3/API 7.2.0 and Voxy 0.2.16-beta; verify released jar commit/signatures; add isolated Gradle run profiles and optional mixin gating | `./gradlew build` plus neither/DH/Voxy launch logs, dependency versions and checked hook descriptors recorded |
| **M1 — Pure region policy** | Add `visibility/*`, schema migration, context resolver and diagnostics using all three supplied region defaults; no renderer changes yet | Policy/migration tests pass, including all supplied corners and gaps; dry-run diagnostic transitions correct |
| **M2 — Fixture** | Add opt-in `debug/FixtureController`, DH world-load freeze, debug commands/status; document actual cache paths; prepare synthetic and copied-cache superflat saves locally | Baseline LODs visible; flat chunk loads do not replace them; ordinary worlds unaffected |
| **M3 — DH coarse hook** | Replace DH distance controller with backend adapter; enforce shared render-list filter; use conservative prototype until M4 | Supported list hook verified with culling disabled, both terrain paths; native distance unchanged |
| **M4 — DH exact edges** | Shader/uniform feasibility spike, then OpenGL and Blaze implementations, reload/lifecycle fixes | Both shader-free paths pass edge/translucency/depth/teleport matrix; no whole-section holes accepted as exact |
| **M5 — DH delivery** | Remove retired distance UI/logic; update README/mod description; publish precise capability matrix and test results | All DH release gates pass; supplied bounds checked visually, dimension/level identity confirmed, and transitions among all three regions verified in the fixture |
| **M6 — Voxy** | Replace boolean/cancellation controller; add MDIC shader/uniform integration and fixture ingestion override | Opaque + temporal + translucent matrix passes on supported GPU; native distance and cache preserved |
| **M7 — Iris support** | Version-gated Iris integration for DH and Voxy; shader-pack, depth, shadow, reload, and transition testing | Explicit per-backend Iris capability matrix and GPU evidence for every supported path |
| **M8 — Performance and version expansion** | Optional GPU coarse culling for Voxy; extend pinned versions only with evidence | Reproducible frame-time results and per-version capability status |

If a milestone encounters an unknown renderer hook, resolve only that spike and record the result. Do not redesign storage, overwrite user LODs, or substitute distance limiting to make a demo pass. A blocked exact backend remains explicitly unverified; completed independent milestones can still be reviewed.

### Definition of done for the requested behavior

- Main, Realm of Light, and Void/Outer Void each render their own available LOD terrain at the user's chosen distance, hiding the other regions and all unlisted areas.
- Geometry crossing a boundary is handled deliberately; stock supported paths provide exact clipping without dropping entire mixed sections.
- All supported terrain passes and transitions obey the same mask.
- Cached data remains reusable; changing realms does not delete data, rebuild the map, or write distance settings.
- Other servers/worlds are unaffected; fixture mode never leaks into ordinary play.
- A second developer/model can reproduce results from the pinned versions, fixture manifest, commands, camera positions, and recorded test matrix.

The third project goal adds a separate definition of done: Iris-enabled DH and Voxy paths that are claimed supported must enforce the same mask in their active shader-pack, depth, and shadow programs, including reload and realm transitions. Until M7 passes, report Iris as conservative or unsupported per path.

## 11. Open inputs and evidence limits

- Main-map, Realm of Light, and Void/Outer Void X/Z bounds were supplied by the user and are recorded in section 4.1. Inclusive block endpoints are the implementation convention. Dimension/backend level identity remains to be confirmed; Y is unrestricted within the recognized level unless later evidence requires a vertical bound.
- The fixture's overworld cache path and representative MAIN, Light, and Void terrain have been inspected. Production Wynncraft backend level identity, complete landmark coverage, and behavior in other dimensions still need confirmation; use synthetic data for missing edge and material cases.
- Exact clipping hooks originated as architecture proposals. Stock Blaze3D and OpenGL shader patches are implemented; [the DH test record](TESTING_DH.md) distinguishes Blaze3D runtime evidence from the unverified OpenGL path. Iris and multi-renderer coexistence need their own proof.
- The original research pass did not run Minecraft. Subsequent disposable DH fixture runs and cache retention checks are recorded in [the DH test record](TESTING_DH.md). The Mac OpenGL crash is unresolved; finish that backend's runtime matrix on a PC before claiming support.

## Source index

All source links below are pinned to inspected commits. For DH, the wrapper repository contains the Minecraft render backends; its separate core submodule contains the core/API. Re-fetch the listed commits when temporary research checkouts are unavailable.

- [DH 3.3.3 tag](https://gitlab.com/distant-horizons-team/distant-horizons/-/tags/3.3.3), [3.3.3 version properties](https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/a54dd3eb1df0078e63a71266d042c10d9cda4493/gradle.properties), [published 1.21.11 artifact](https://modrinth.com/mod/distanthorizons/version/oNqCUHFk), [published DH API 7.2.0](https://modrinth.com/mod/distanthorizonsapi/version/LTP8vW3B).
- [Voxy 1.21.11 commit](https://github.com/MCRcortex/voxy/tree/59b62bee821518e06612e1d5c2c58c487bda761d), [version properties](https://github.com/MCRcortex/voxy/blob/59b62bee821518e06612e1d5c2c58c487bda761d/gradle.properties), [published artifact](https://modrinth.com/mod/voxy/version/H3w2nVdU).

[dh-list]: https://gitlab.com/jeseibel/distant-horizons-core/-/blob/b02c66d778beea11a931291815d652cd8abaa7ad/core/src/main/java/com/seibel/distanthorizons/core/render/RenderBufferHandler.java
[dh-frustum]: https://gitlab.com/jeseibel/distant-horizons-core/-/blob/b02c66d778beea11a931291815d652cd8abaa7ad/api/src/main/java/com/seibel/distanthorizons/api/interfaces/override/rendering/IDhApiCullingFrustum.java
[dh-event]: https://gitlab.com/jeseibel/distant-horizons-core/-/blob/b02c66d778beea11a931291815d652cd8abaa7ad/api/src/main/java/com/seibel/distanthorizons/api/methods/events/abstractEvents/DhApiBeforeBufferRenderEvent.java
[dh-gl]: https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/a54dd3eb1df0078e63a71266d042c10d9cda4493/common/src/main/java/com/seibel/distanthorizons/common/render/openGl/terrain/GlDhTerrainShaderProgram.java
[dh-blaze]: https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/a54dd3eb1df0078e63a71266d042c10d9cda4493/common/src/main/java/com/seibel/distanthorizons/common/render/blaze/BlazeDhTerrainRenderer.java
[dh-readonly]: https://gitlab.com/jeseibel/distant-horizons-core/-/blob/b02c66d778beea11a931291815d652cd8abaa7ad/api/src/main/java/com/seibel/distanthorizons/api/interfaces/world/IDhApiWorldProxy.java
[dh-shared]: https://gitlab.com/jeseibel/distant-horizons-core/-/blob/b02c66d778beea11a931291815d652cd8abaa7ad/core/src/main/java/com/seibel/distanthorizons/core/api/internal/SharedApi.java
[dh-save]: https://gitlab.com/jeseibel/distant-horizons-core/-/blob/b02c66d778beea11a931291815d652cd8abaa7ad/core/src/main/java/com/seibel/distanthorizons/core/file/structure/ClientOnlySaveStructure.java
[dh-local]: https://gitlab.com/jeseibel/distant-horizons-core/-/blob/b02c66d778beea11a931291815d652cd8abaa7ad/core/src/main/java/com/seibel/distanthorizons/core/file/structure/LocalSaveStructure.java
[vx-pipeline]: https://github.com/MCRcortex/voxy/blob/59b62bee821518e06612e1d5c2c58c487bda761d/src/main/java/me/cortex/voxy/client/core/AbstractRenderPipeline.java
[vx-mdic]: https://github.com/MCRcortex/voxy/blob/59b62bee821518e06612e1d5c2c58c487bda761d/src/main/java/me/cortex/voxy/client/core/rendering/section/backend/mdic/MDICSectionRenderer.java
[vx-loader]: https://github.com/MCRcortex/voxy/blob/59b62bee821518e06612e1d5c2c58c487bda761d/src/main/java/me/cortex/voxy/client/core/gl/shader/ShaderLoader.java
[vx-pos]: https://github.com/MCRcortex/voxy/blob/59b62bee821518e06612e1d5c2c58c487bda761d/src/main/resources/assets/voxy/shaders/lod/pos_util.glsl
[vx-quad]: https://github.com/MCRcortex/voxy/blob/59b62bee821518e06612e1d5c2c58c487bda761d/src/main/resources/assets/voxy/shaders/lod/quad_util.glsl
[vx-storage]: https://github.com/MCRcortex/voxy/blob/59b62bee821518e06612e1d5c2c58c487bda761d/src/main/java/me/cortex/voxy/common/StorageConfigUtil.java
[vx-import]: https://github.com/MCRcortex/voxy/blob/59b62bee821518e06612e1d5c2c58c487bda761d/src/main/java/me/cortex/voxy/commonImpl/importers/DHImporter.java
[vx-client]: https://github.com/MCRcortex/voxy/blob/59b62bee821518e06612e1d5c2c58c487bda761d/src/main/java/me/cortex/voxy/client/VoxyClientInstance.java
[vx-identity]: https://github.com/MCRcortex/voxy/blob/59b62bee821518e06612e1d5c2c58c487bda761d/src/main/java/me/cortex/voxy/commonImpl/WorldIdentifier.java
[vx-commands]: https://github.com/MCRcortex/voxy/blob/59b62bee821518e06612e1d5c2c58c487bda761d/src/main/java/me/cortex/voxy/client/VoxyCommands.java
