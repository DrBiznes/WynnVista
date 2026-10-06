# WynnVista

![WynnVistaBanner](https://github.com/user-attachments/assets/495e19de-1f5b-40b0-a24b-7f28692427e5)

WynnVista shows only the active Wynncraft region's Distant Horizons or Voxy LOD terrain without changing either mod's render distance. Made for [World of Wynncraft](https://github.com/bob10234/World-of-Wynncraft).

## Development status

Version 2.0.0 replaces the old distance-limiting behavior with region masking for both Distant Horizons and Voxy, including shader-pack (WynnIris) support. The settings file is migrated on first launch (a `WynnVista.json.bak` is kept) and the old distance options are gone; set your preferred render distance in DH or Voxy directly.

- The Java region policy recognizes the main map, Realm of Light, and Void/Outer Void. Unit tests cover supplied corners, half-open edges, gaps, and section classification.
- The DH fixture can identify one explicit local superflat save and set DH read-only at world load. This prevents ordinary flat chunks from replacing imported detail-0 terrain during the isolated smoke test.
- DH 3.3.3 on Minecraft 1.21.11 uses a version-gated render-list filter. Stock Blaze3D and OpenGL terrain shaders clip mixed sections at the active region bounds. If an exact shader path is unavailable, the filter keeps only wholly contained sections; edges may have missing strips.
- The isolated Blaze3D fixture passed build, shader patch, empty-mask, main, Light, Void, sampled realm transitions, opaque and transparent uniform binding, and cache-retention checks. OpenGL runs on the Mac crashed natively, so the OpenGL backend was validated separately on a Windows PC (RTX 4070): twelve stock-shader launches covering baseline, repeat launches, main/Light/Void masks, a `NONE` override, DH frustum culling off, sampled realm transitions and an exact planar cut all passed with the cache unchanged. Depth, shadow, continuous-frame, and performance checks remain open. See [test status](docs/TESTING_DH.md#opengl-backend-windows-pc).
- Voxy 0.2.16-beta on Minecraft 1.21.11 uses the same region policy: a checked patch of Voxy's stock terrain shaders clips opaque, temporal and translucent terrain fragments to the active region (`EXACT` for the stock pipeline and for Iris shader-pack pipelines). Voxy's pipeline, distance, ingestion and storage are untouched. An isolated Voxy fixture built from a copy of a real Wynncraft Voxy cache passed build, GPU shader compile/link, MAIN/LIGHT/VOID_OUTER/NONE/AUTO, an exact clip through terrain, sampled realm transitions and cache-retention checks on a Windows PC (RTX 4070). See [Voxy test status](docs/TESTING_VOXY.md).
- Iris (WynnIris 1.2.2): both DH and Voxy terrain are masked under a shader pack. For DH, the transformed `dh_terrain`/`dh_water` programs Iris builds are wrapped with the clip; for Voxy the existing patch runs ahead of the pack's fragment code. Verified with Complementary Reimagined (baseline, LIGHT, MAIN, an exact clip, sampled transitions, shader-pack off). Shadow passes and other shader packs are not yet verified. See [Iris test status](docs/TESTING_IRIS.md).
- WynnVista no longer reads or changes either LOD mod's render distance.

## Build and isolated DH test

Install Java 21 and Python 3. The Gradle build resolves the pinned Minecraft 1.21.11, Fabric, and DH 3.3.3 dependencies. From the repository root:

```bash
bash gradlew build
```

Prepare three inputs **outside** `run-dh/`: a closed empty superflat save containing `level.dat`, a closed copy of the Wynncraft **overworld** `DistantHorizons.sqlite`, and a matching DH 3.3.3 `DistantHorizons.toml`. Close Minecraft before copying the save or database; the script rejects an active save lock or database journal. Keep the original LOD database as a master. The script replaces the disposable `run-dh/saves/New World` on each invocation.

The Gradle `runClient` task uses only `run-dh/`; it does not launch the Modrinth profile. The fixture script copies the inputs, disables DH distant generation and its update prompt in the isolated directory, runs the game twice, and checks the cache after each normal exit. On this Mac, select Blaze3D:

```bash
WYNNVISTA_DH_ENGINE=BLAZE_3D bash scripts/test_dh_fixture.sh \
  "/path/to/closed/saves/New World" \
  "/path/to/closed/Wynncraft-overworld-master.sqlite" \
  "/path/to/matching/DistantHorizons.toml"
```

The first run may migrate DH's SQLite schema and update coarse parent rows. The test preserves that result as a disposable reference and requires every `FullData` row to remain stable across the second run. The original master is never opened for writing. See [the implementation plan](docs/PROJECT_UPDATE_LOD_VISIBILITY.md) for the remaining masking and visual test gates.

Set `WYNNVISTA_TEST_JAVA` to a Java 21 executable to select the game runtime independently of Gradle's JDK. Each launch has a three-minute timeout. Logs and check results are saved under `run-dh/test-results/`; failures stop the script immediately. An optional fourth argument selects a DH worker count for diagnostics in the disposable installation. Omitting it retains the copied DH worker setting.

`WYNNVISTA_DH_ENGINE` explicitly selects `AUTO`, `OPEN_GL`, or `BLAZE_3D` in the test client. Omitting it preserves the copied setting. OpenGL testing is deferred to a PC. See [test results and limits](docs/TESTING_DH.md).

The preparation script writes `run-dh/config/WynnVista.json` with the fixture save path and `maskingEnabled: false` so the two-launch baseline does not clip terrain. To run masked visual checks afterward, set that value to `true` in the disposable config:

```bash
python3 -c 'import json; from pathlib import Path; p=Path("run-dh/config/WynnVista.json"); d=json.loads(p.read_text()); d["maskingEnabled"]=True; p.write_text(json.dumps(d, indent=2)+"\n")'
WYNNVISTA_FIXTURE_COMMANDS='gamemode spectator @p|tp @p 1200 150 -3000 90 0' \
WYNNVISTA_FIXTURE_SCREENSHOT='main-east-edge.png' \
python3 scripts/run_fixture_client.py runClient -PlodBackend=dh
```

The sample teleports to the main-map east edge. `WYNNVISTA_FIXTURE_COMMANDS` accepts `|`-separated integrated-server commands at loaded-world tick 40. `WYNNVISTA_FIXTURE_SCREENSHOT` saves a HUD-free image in `run-dh/screenshots/` at tick 250. The client exits at tick 400. `run-dh/logs/latest.log` records visibility revisions and the active DH mask path. Use a second run with `maskingEnabled: false` and the same camera for a visual baseline; the two-launch cache script also starts unmasked.

For sampled realm transitions, set `WYNNVISTA_FIXTURE_TIMELINE` to semicolon-separated `worldTick:command` events and `WYNNVISTA_FIXTURE_CAPTURE_PREFIX` to a screenshot prefix. The fixture captures ticks -1, +1, +2, +3, +5, and +15 around each event. The exact transition commands and results are in [the DH test notes](docs/TESTING_DH.md). These are sampled game-tick captures, so use continuous video/frame capture for the final flash gate.

Fixture mode is off by default in ordinary installations and applies only to the designated save. An unsupported DH version leaves its renderer untouched. All large cache copies, saves, screenshots, logs, and crash reports under `run-dh/` are ignored by Git.

## Build and isolated Voxy test

Install Java 21 and Python 3. `bash gradlew build` (or `gradlew.bat build` on Windows) resolves the pinned Minecraft, Fabric, Voxy 0.2.16-beta and Sodium 0.8.12 dependencies. The Voxy fixture works on Windows, macOS and Linux and **never writes to the source Voxy cache**: it copies a closed Wynncraft Voxy world storage (the folder containing `CURRENT`, e.g. `<profile>/.voxy/saves/play.wynncraft.com/<world id>/storage`) and that folder's parent `config.json` into a disposable superflat save under `run-voxy/`.

```bash
python scripts/lod_fixture.py --backend voxy create-world --reset
python scripts/lod_fixture.py --backend voxy install --storage "/path/to/closed/<world id>/storage" --config "/path/to/play.wynncraft.com/config.json"
python scripts/lod_fixture.py --backend voxy run --masking --override LIGHT --commands 'gamemode spectator @p|tp @p -800 160 -6100 0 12' --screenshot light-view.png
python scripts/lod_fixture.py --backend voxy check
```

Close Minecraft before copying a cache. `scripts/loader_smoke.py neither|dh|voxy|both` checks that WynnVista loads with each backend combination. Options, evidence and open gates are in [the Voxy test notes](docs/TESTING_VOXY.md).

## Requirements

- Minecraft 1.21.11
- Fabric Loader 0.18.1 or higher
- Fabric API
- Java 21
- Distant Horizons 3.3.3 or Voxy 0.2.16-beta (with Sodium 0.8.12) for the isolated fixture runs
- Cloth Config
- Mod Menu
- Optional: WynnIris 1.2.2 (or another Iris with the same DH program classes) for shader-pack masking; WynnVista never requires it

## Go Ham

- I don't know nothing about java so if you wanna fork this and fix this up go ham.
- Feel free to use this in your modpack

## Acknowledgments

- Thanks to the Distant Horizons team for their amazing mod
- Thanks to Cortex for the incredible Voxy mod
- Thanks to the Wynncraft team for creating an awesome MMORPG experience in Minecraft
- Thanks to igbarvonsquid!!!

## Support and My Mods
Please report any bugs or feature suggestions on the Github Issues page, I'll be updating this frequently with community feedback and ideas! You can also [join my discord](https://discord.gg/jqFF64rXZZ) if you need direct support, or want to stay updated with all of my mods.
### Check out all my projects!
>   [World of Wynncraft Modpack](https://modrinth.com/modpack/world-of-wynncraft)

>   [WynnVista](https://modrinth.com/mod/wynnvista)

>   [Wynn Weapon Bigger](https://modrinth.com/mod/wynnweaponbigger)

>   [Nimble ReWynnded](https://modrinth.com/mod/nimble-rewynnded)

>   [Class Keybind Profiles](https://modrinth.com/mod/class-keybind-profiles)

>   [WynnBubbles](https://modrinth.com/mod/wynnbubbles)

>   [WynnLODGrabber](https://modrinth.com/mod/wynnlodgrabber)
