# DH update and isolated fixture testing

Date: 2026-10-04 (macOS results; Windows PC results for OpenGL and Iris are in [TESTING_IRIS.md](TESTING_IRIS.md)). Development baseline and selective terrain clipping implementation. The README now contains the [reproducible fixture setup](../README.md#build-and-isolated-dh-test). Release validation remains incomplete; the OpenGL matrix was run on 2026-10-06 and is recorded in [OpenGL backend (Windows PC)](#opengl-backend-windows-pc).

## Installation and cache

- Minecraft 1.21.11, Fabric, Java 21; pinned DH `3.3.3-1.21.11` and API `7.2.0`.
- Verified DH jar SHA-512 against Modrinth version `oNqCUHFk`: `317bed5ff65a71233959508aded32ef12923a6575c0dc852fc683834d4112113d2130dc1dae0592ed1ed564298a91313d1508e921aff43992a3362eb0f21ee27`.
- Overworld LODs belong in `saves/New World/data/DistantHorizons.sqlite`. `DIM-1` is Nether; `DIM1` is End.
- The requested profile overworld DB was replaced with the Wynncraft overworld copy. Its previous DB and the profile's removed server-data directory are recoverable under ignored `.local-fixtures/dh/`.
- Original master: 25,009 `FullData` rows, including 16,706 detail-0 rows. SHA-256: `2ef815bd8cf2b7016c3734a060e62a35eaf4d7f50eaa188d8262a763b00737e7`.

The development game runs under ignored `run-dh/`, with its own copied save, configuration, and dependencies. Modrinth is not launched. Fixture mode names that save's exact real path, sets DH read-only before the first DH level starts, and disables distant generation. Production fixture mode defaults off. No WynnVista distance writes remain.

`run-dh/`, `.local-fixtures/`, the separate `run-neither/` client, SQLite databases and sidecars, world region files, screenshots, logs, and crash reports are Git-ignored. The commit contains the fixture scripts and this evidence record, not the 1.7 GB working database or the source cache copies.

## Checks

| Gate | Result |
| --- | --- |
| Java 21 build and remapped jar | PASS |
| Region edges, gaps, host isolation, section unions, immutable snapshots and disconnect reset | PASS: seven JUnit tests |
| Client with neither DH nor Voxy | PASS: automatic world load, 400 loaded-world ticks, normal exit |
| Active world lock guard | PASS: preparation rejects a save held open by another process |
| DH first launch from master | PASS: read-only precedes level startup; all keys and all detail-0 terrain/mapping bytes retained |
| DH repeat launch, OpenGL on Mac | FAIL: repeated native crashes; PC runtime validation pending |
| DH two-launch harness, Blaze3D | PASS: five workers, Temurin 21.0.10, both normal exits; second SQLite file unchanged |

## DH terrain mask update

The DH 3.3.3 render-list mixin filters sections independently of DH's frustum setting. Stock Blaze3D and OpenGL terrain shaders test an unwarped X/Z varying before color or depth output. A mixed section is submitted only after the active stock shader path has completed a render; otherwise the filter conservatively retains only wholly contained sections. The shader patcher rejects missing or duplicated anchors in the pinned source. Iris and other shader replacements remain a separate project goal.

| Check | Result |
| --- | --- |
| `bash gradlew build --offline` | PASS: Java 21 build, remapped jar, 11 JUnit tests including both pinned shader pairs |
| Neither backend, separate `run-neither/` save | PASS: DH=false, Voxy=false; joined and exited normally after 400 ticks |
| Blaze3D stock shader and list hook | PASS: programs linked, exact-mode diagnostics appeared, 400-tick normal exits |
| MAIN east-edge view versus unmasked view | PASS for the captured camera: forbidden floating cache geometry visible in baseline is absent with MAIN; main terrain remains. See `run-dh/screenshots/wynnvista-east-edge-{baseline,exact}.png` |
| DH frustum culling disabled | PASS for the same east-edge camera: exact mask diagnostics and `wynnvista-east-edge-no-frustum.png` retained the clipped view; DH's setting was restored afterward |
| NONE override | PASS: no LOD terrain in `run-dh/screenshots/wynnvista-none.png` |
| Automatic LIGHT and VOID_OUTER | PASS: resolver transitions and isolated cached terrain visible in `wynnvista-light-auto.png` and `wynnvista-void-auto.png` |
| Scripted Blaze3D realm transitions | PASS for sampled frames: MAIN → LIGHT → VOID_OUTER → MAIN → LIGHT in one 400-tick run; list diagnostics changed to revisions 3–6 at each teleport, and 24 adjacent-tick captures show the destination view without an obvious source-realm terrain flash |
| Blaze3D opaque and transparent terrain calls | PASS: `run-dh/pass-binding-launch.log` records `WynnVistaMask` binding in both calls at MAIN revision 2; this establishes the binding hook, while synthetic translucent/depth leak tests remain open |
| OpenGL default JVM | FAIL: native SIGSEGV before screenshot, `run-dh/hs_err_pid19226.log` |
| OpenGL with `JAVA_TOOL_OPTIONS=-XX:TieredStopAtLevel=1` | One normal exit and exact-mode screenshot `wynnvista-east-edge-opengl-c1.png`; not a stable-backend claim |
| Repeated fixture cache check after implementation | PASS: all 25,009 terrain rows and the complete 1.7 GB SQLite file are byte-for-byte unchanged against the post-migration reference |

Fixture visual commands used `gamemode spectator @p` and fixed `tp` coordinates. MAIN edge: `(1200, 150, -3000)` facing east; LIGHT: `(-800, 150, -6200)` facing north; VOID_OUTER: `(14000, 150, -4000)` facing east. Screenshots were taken at tick 250 with HUD hidden. These screenshots show cached terrain and selected mask paths, but are not full coverage of every edge, translucent/depth pass, camera mode, or rapid transition. The Light/Void cache has incomplete floating geometry; some missing terrain is intrinsic to the source cache.

The fixture-only timeline accepts `WYNNVISTA_FIXTURE_TIMELINE` as semicolon-separated `worldTick:command` events. Set `WYNNVISTA_FIXTURE_CAPTURE_PREFIX` to save frames at ticks -1, +1, +2, +3, +5, and +15 around each event. The transition run used teleports at ticks 100, 170, 240, and 310, after the original MAIN teleport at tick 40. `run-dh/transition-launch.log` records the exact commands, visibility revisions, DH list counts and normal exit; `run-dh/screenshots/transition-t*.png` contains the sampled frames. The first frames after a teleport sometimes show only sky while DH loads destination buffers; this is a loading gap, not evidence of a source-region leak. The 24 captures sample game ticks rather than every rendered frame, so they do not yet satisfy the continuous-frame flash gate. Cache retention passed again after this run.

The subsequent `run-dh/pass-binding-launch.log` shows the stock Blaze3D mask buffer bound in both opaque and transparent terrain calls. After that run, the disposable and post-migration reference SQLite files both had SHA-256 `12f4a5634921ea38d4c05ae2a663be5971df3f7a1c525dc4181c85f32d77b910`.

The source DB uses schema 11; DH updates the disposable DB to schema 12. Nine coarse terrain rows change during the first launch. Checks allow this initialization but retain every terrain key and compare actual detail-0 data, column metadata and mapping blobs. The second launch must preserve all terrain and the complete SQLite file byte for byte against the first normal exit.

## OpenGL backend (Windows PC)

Date: 2026-10-06. Windows 11, RTX 4070 (driver 617.14, OpenGL 3.3 core), Java 21.0.4, Minecraft 1.21.11, DH 3.3.3, no Iris and no Voxy. Every launch forced `renderingEngine = "OPEN_GL"` and logged `DH Rendering successfully bound to: [OpenGL]`. The fixture is the same Wynncraft overworld cache (22,907 rows, 15,311 detail-0) used for the Iris tests, driven by `scripts/lod_fixture.py --backend dh run --dh-engine OPEN_GL`. Evidence is under the ignored `run-dh/test-results/opengl-matrix/` and `run-dh/screenshots/ogl-*.png`.

| Check | Result |
| --- | --- |
| `gradlew cleanTest test`: 24 JUnit tests including `DhOpenGlShaderPatchTest` | PASS |
| Baseline (masking off), launches 1 and 2 | PASS: both normal exits after 400 ticks; `PASSTHROUGH` mask bound in opaque and transparent passes; cache fingerprint unchanged after each |
| Masked MAIN east edge, and with DH frustum culling disabled | PASS for startup, binding and exit (`mixed=2`; `inside=36 mixed=22 outside=131` without frustum culling). The screenshots differ from baseline by about 2.5% of pixels but show no obvious floating geometry in either view at this camera, so they do not demonstrate clipping on their own |
| `NONE` override | PASS: `ogl-none.png` shows no LOD terrain |
| Automatic LIGHT and VOID_OUTER views | PASS: only that realm's cached terrain visible (`ogl-light-auto.png`, `ogl-void-auto.png`) |
| Exact clip: `FIXTURE_CUSTOM` allowed x ≤ −901, top-down at (−800, 300, −6200) | PASS: `ogl-cut-x900.png` shows a crisp planar cut through the island and tree canopy; `ogl-cut-baseline.png` is the same camera unmasked |
| Realm transitions MAIN → LIGHT → VOID_OUTER → MAIN → LIGHT, 24 captures | PASS for sampled ticks: revisions 2–7; sheet `sheet-ogl-trans.png` shows only destination terrain, plus sky frames while DH loads |
| Opaque and transparent pass binding | PASS: `WynnVista mask … bound for opaque pass` and `… transparent pass` logged at every revision in all twelve launches |
| Repeated launches (twelve OpenGL launches, default JVM) | PASS: no native crash, no `hs_err` file, no `did not match pinned source`; `check` passed after every launch (cache keys and detail-0 payload hashes unchanged) |

This shows the Mac OpenGL crashes do not reproduce on this PC, as recorded in [TESTING_IRIS.md](TESTING_IRIS.md). Still open for OpenGL: depth and translucent-pass landmark tests, third-person and other camera modes, continuous-frame capture, config reload, dimension changes, other GPUs and drivers (AMD, Intel, Linux), and performance.

Environment note: `gradlew` can fail on Windows with `Unable to establish loopback connection` when `TEMP` is an 8.3 short path such as `C:\Users\JAMFEM~1\...`. Setting `TEMP`, `TMP` and `GRADLE_OPTS=-Djava.io.tmpdir=<plain path>` fixes it.

## Native crash investigation

Hardware/runtime: macOS 15.6.1, Apple M4 arm64. OpenGL runs crashed with Temurin 21.0.10 and Zulu 21.0.7. Reducing DH workers from five to one did not resolve it. A successful standalone repeat was observed, but complete repeat tests subsequently failed, so that result is insufficient for a stability claim.

Preserved reports under `run-dh/`:

- `hs_err_pid10492.log`: native SQLite statement preparation, DH render loader thread.
- `hs_err_pid12971.log`: JVM class loading, DH render loader thread.
- `hs_err_pid13434.log`: G1 garbage collection under Zulu.
- `hs_err_pid13890.log`: Metal command-buffer completion under `-Xcheck:jni`.
- `hs_err_pid19226.log`: C2 compiler arena teardown during a normal OpenGL fixture run after world load.

Several failures share an invalid pointer pattern. This suggests native memory corruption; the responsible component has not been established. Changing the Java vendor is not a demonstrated fix. The harness stops on failure and does not retry crashes into a passing result.

The complete Blaze3D test passed with the original five-worker setting and Temurin. Evidence is in `run-dh/test-results/20261004-141807/`: both client logs, the fixture manifest, both checker outputs, and exit code `0`. Subsequent masked launches and the repeated full-file check passed too. This isolates a working renderer baseline for this fixture; it is not a general compatibility or long-duration stability claim. Blaze3D itself uses Minecraft's OpenGL device on this Mac, so the result distinguishes DH's two renderer paths rather than the operating system's entire graphics stack.

## Remaining verification

- Extend visual comparison to every supplied edge and synthetic coarse sections; verify geometry and depth in opaque and translucent passes.
- Complete continuous-frame realm-change capture; exercise dimension changes, config reloads, third-person cameras, and all required terrain passes.
- The OpenGL edge, transition, cut and repeated-launch matrix now passes on one Windows PC (see above). Remaining OpenGL work: other GPUs and drivers, depth and translucent landmark tests, camera modes, continuous-frame capture, performance.
- Verify cached legacy biome names/materials visually; DH emits obsolete/empty-biome warnings while reading this cache.
- Voxy masking is now implemented and tested separately in [TESTING_VOXY.md](TESTING_VOXY.md) (the 0.2.16-beta jar targets Java 21, not 25). Explicit Iris support follows Voxy as the third project goal.

The automation checks startup, lifecycle ordering, shader source anchors and cache retention. The recorded screenshots establish selected shader-free mask cases; the remaining matrix is still required before a general DH release claim.
