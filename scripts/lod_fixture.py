#!/usr/bin/env python3
"""Stage, run and check the isolated Voxy or Distant Horizons fixture (Windows, macOS or Linux).

The fixture is a disposable superflat save under run-voxy/ or run-dh/ with a COPY of a real Wynncraft
LOD cache installed as that save's overworld cache. The source (e.g. a Modrinth profile's .voxy or
Distant_Horizons_server_data folder) is only ever read. Select the backend with --backend voxy|dh.

  create-world   create an empty superflat save (and the backend's default config)
  install        copy a Wynncraft cache into the save (Voxy: storage directory; DH: DistantHorizons.sqlite)
  run            launch the client once (masking/camera/shader pack controlled by options), bounded in time
  check          verify log evidence and compare the installed cache with its install-time fingerprint
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import signal
import sqlite3
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SAVE_NAME = "New World"
WIN = os.name == "nt"
LOCAL = ROOT / ".local-fixtures"
ROCKSDB_JAR = LOCAL / "voxy" / "lib" / "rocksdbjni-10.2.1.jar"

RUN: Path
WORLD: Path
STATE: Path
BACKEND = "voxy"


def select_backend(name: str) -> None:
    global RUN, WORLD, STATE, BACKEND
    BACKEND = name
    RUN = ROOT / ("run-voxy" if name == "voxy" else "run-dh")
    WORLD = RUN / "saves" / SAVE_NAME
    STATE = RUN / "fixture-state.json"


def log_path() -> Path:
    return RUN / "logs" / "latest.log"


def read_state() -> dict:
    return json.loads(STATE.read_text()) if STATE.exists() else {}


def write_state(update: dict) -> None:
    state = read_state()
    state.update(update)
    STATE.write_text(json.dumps(state, indent=2) + "\n")


def write_configs(masking: bool, override: str, render_distance: float, custom_rect: str = "",
                  dh_engine: str = None, effects: bool = True, disabled_effects: str = "") -> None:
    config = RUN / "config"
    config.mkdir(parents=True, exist_ok=True)
    (config / "WynnVista.json").write_text(json.dumps({
        "schemaVersion": 2,
        "showMessage": True,
        "maskingEnabled": masking,
        "fixtureEnabled": True,
        "fixtureSavePath": str(WORLD.resolve()),
        "fixtureOverride": override,
        "fixtureCustomRect": custom_rect,
        "effectsEnabled": effects,
        "effects": {name: False for name in disabled_effects.split(",") if name},
    }, indent=2) + "\n")
    if BACKEND == "voxy":
        # ingest_enabled=false is a second layer of protection; the mod also freezes it in memory.
        (config / "voxy-config.json").write_text(json.dumps({
            "enabled": True,
            "enable_rendering": True,
            "ingest_enabled": False,
            "section_render_distance": render_distance,
            "service_threads": 4,
            "sub_division_size": 64.0,
            "use_environmental_fog": True,
            "dont_use_sodium_builder_threads": False,
        }, indent=2) + "\n")
    else:
        patch_dh_config(config / "DistantHorizons.toml", dh_engine)
    options = RUN / "options.txt"
    wanted = {"onboardAccessibility": "false", "pauseOnLostFocus": "false", "tutorialStep": "none",
              "joinedFirstServer": "true", "skipMultiplayerWarning": "true", "renderDistance": "6",
              "narrator": "0", "soundCategory_master": "0.0"}
    lines = [l for l in (options.read_text().splitlines() if options.exists() else [])
             if l.split(":", 1)[0] not in wanted]
    options.write_text("\n".join(lines + [f"{k}:{v}" for k, v in wanted.items()]) + "\n")


def patch_dh_config(toml: Path, engine: str = None) -> None:
    """Disable DH distant generation and its updater in the disposable instance (config exists after a launch)."""
    if not toml.exists():
        return
    text = toml.read_text()
    text = re.sub(r'(?m)^(\s*generatorPlan\s*=\s*)"[A-Z_]+"', r'\1"DISABLED"', text)
    text = re.sub(r'(?m)^(\s*enableAutoUpdater\s*=\s*)true', r'\1false', text)
    if engine:
        text = re.sub(r'(?m)^(\s*renderingEngine\s*=\s*)"[A-Z_0-9]+"', lambda m: m[1] + '"' + engine + '"', text)
    toml.write_text(text)


def gradle_command(iris: bool = False) -> list:
    base = [str(ROOT / "gradlew.bat")] if WIN else ["bash", "gradlew"]
    return base + ["runClient", f"-PlodBackend={BACKEND}", "--no-daemon"] + (["-Piris=true"] if iris else [])


def write_iris(pack: str, source: Path = None) -> None:
    """Enable (or disable, for pack == "off") WynnIris shaders in the disposable instance."""
    config = RUN / "config"
    config.mkdir(parents=True, exist_ok=True)
    if pack != "off" and source is not None:
        target = RUN / "shaderpacks" / pack
        if not target.exists():
            target.parent.mkdir(parents=True, exist_ok=True)
            if source.is_dir():
                shutil.copytree(source, target)
            else:
                shutil.copy2(source, target)
    lines = ["enableShaders=" + ("false" if pack == "off" else "true"),
             "shaderPack=" + ("" if pack == "off" else pack),
             "enableDebugOptions=true", "maxShadowRenderDistance=32", "disableUpdateMessage=true"]
    (config / "iris.properties").write_text("\n".join(lines) + "\n")


def kill_tree(process: subprocess.Popen) -> None:
    if WIN:
        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    else:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass


def run_client(extra_env: dict, timeout: int, iris: bool = False) -> int:
    env = dict(os.environ)
    env.update({k: v for k, v in extra_env.items() if v is not None})
    runtime = env.get("WYNNVISTA_TEST_JAVA")
    command = gradle_command(iris)
    if runtime:
        if not Path(runtime).is_file():
            raise SystemExit(f"Test Java executable does not exist: {runtime}")
        command.append(f"-PfixtureJava={runtime}")
    kwargs = {} if WIN else {"start_new_session": True}
    process = subprocess.Popen(command, cwd=ROOT, env=env, **kwargs)
    try:
        return process.wait(timeout=timeout)
    except (subprocess.TimeoutExpired, KeyboardInterrupt):
        kill_tree(process)
        raise SystemExit("Fixture client timed out or was interrupted; its process tree was stopped")


def java_executable() -> str:
    explicit = os.environ.get("WYNNVISTA_TEST_JAVA")
    if explicit:
        return explicit
    home = os.environ.get("JAVA_HOME")
    if home:
        return str(Path(home) / "bin" / ("java.exe" if WIN else "java"))
    return "java"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def voxy_fingerprint(storage: Path) -> dict:
    """Row-level fingerprint of a CLOSED Voxy storage directory (works on a scratch copy)."""
    scratch = LOCAL / "fingerprint-scratch"
    if scratch.exists():
        shutil.rmtree(scratch)
    shutil.copytree(storage, scratch, ignore=shutil.ignore_patterns("LOCK"))
    try:
        result = subprocess.run(
            [java_executable(), "-cp", str(ROCKSDB_JAR), str(ROOT / "scripts" / "VoxyStorageFingerprint.java"),
             str(scratch)], capture_output=True, text=True, check=True)
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    return json.loads(result.stdout.strip().splitlines()[-1])


def dh_fingerprint(path: Path) -> dict:
    """Fingerprint of DH terrain: every key, plus a hash of the detail-0 data and mapping blobs.

    DH migrates the schema on first launch and may rewrite coarse parent rows, so only detail-0 payloads
    are compared byte for byte; the full key set must stay identical.
    """
    with sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True) as db:
        quick = db.execute("PRAGMA quick_check").fetchone()[0]
        if quick != "ok":
            raise SystemExit(f"SQLite quick_check failed for {path}: {quick}")
        keys = hashlib.sha256()
        detail0 = hashlib.sha256()
        total = low = 0
        for level, x, z, data, mapping in db.execute(
                "SELECT DetailLevel, PosX, PosZ, Data, Mapping FROM FullData ORDER BY DetailLevel, PosX, PosZ"):
            keys.update(f"{level},{x},{z};".encode())
            total += 1
            if level == 0:
                low += 1
                detail0.update(f"{x},{z}".encode())
                detail0.update(hashlib.sha256(data or b"").digest())
                detail0.update(hashlib.sha256(mapping or b"").digest())
    return {"rows": total, "detail0Rows": low, "keysSha256": keys.hexdigest(), "detail0Sha256": detail0.hexdigest()}


def cmd_create_world(args) -> None:
    if WORLD.exists():
        if not args.reset:
            raise SystemExit(f"{WORLD} exists; use --reset to replace this disposable fixture")
        shutil.rmtree(WORLD)
    write_configs(False, "AUTO", args.render_distance)
    code = run_client({"WYNNVISTA_FIXTURE_CREATE_WORLD": SAVE_NAME, "WYNNVISTA_FIXTURE_AUTOSTOP_TICKS": "80"},
                      args.timeout)
    if code != 0:
        raise SystemExit(f"World creation client exited with {code}")
    log = log_path().read_text(errors="replace")
    if BACKEND == "voxy":
        match = re.search(r"Voxy fixture world: id=([0-9a-f]{32}), dimension=([^,]+), storage=(.+)", log)
        if not match:
            raise SystemExit("Voxy world identifier was not logged; see the client log")
        write_state({"voxyWorldId": match[1], "dimension": match[2]})
        print(f"Created {WORLD} with Voxy world id {match[1]} ({match[2]})")
    else:
        # The freeze is not expected here: for a brand-new save DH's world-load event precedes the fixture's
        # server-starting hook. Installing the cache afterwards replaces whatever this run wrote.
        patch_dh_config(RUN / "config" / "DistantHorizons.toml", args.dh_engine)
        print(f"Created {WORLD}; DH config patched (distant generation and updater disabled)")


def cmd_install(args) -> None:
    state = read_state()
    source = args.source.resolve(strict=True)
    if BACKEND == "voxy":
        world_id = state.get("voxyWorldId")
        if not world_id:
            raise SystemExit("No Voxy world id recorded; run create-world first")
        if not (source / "CURRENT").is_file():
            raise SystemExit(f"{source} does not look like a Voxy RocksDB storage (no CURRENT file)")
        if (source / "LOCK").exists():
            try:
                with (source / "LOCK").open("ab"):
                    pass
            except OSError:
                raise SystemExit(f"Storage is in use by another process: {source}")
        root = WORLD / "voxy"
        if root.exists():
            shutil.rmtree(root)
        destination = root / world_id / "storage"
        destination.parent.mkdir(parents=True)
        # Copy the whole closed database (never individual files); skip only RocksDB's lock and old info logs.
        shutil.copytree(source, destination, ignore=shutil.ignore_patterns("LOCK", "LOG.old.*"))
        if args.config:
            shutil.copy2(args.config.resolve(strict=True), root / "config.json")
        installed = voxy_fingerprint(destination)
        write_state({"installedStorage": str(destination), "source": str(source), "installedFingerprint": installed})
    else:
        for suffix in ("-wal", "-shm", "-journal"):
            sidecar = Path(str(source) + suffix)
            if sidecar.exists() and sidecar.stat().st_size:
                raise SystemExit(f"DH database has an active journal: {sidecar}; close/checkpoint it first")
        destination = WORLD / "data" / "DistantHorizons.sqlite"
        destination.parent.mkdir(parents=True, exist_ok=True)
        for leftover in (destination, *(Path(str(destination) + s) for s in ("-wal", "-shm", "-journal"))):
            leftover.unlink(missing_ok=True)
        shutil.copy2(source, destination)
        if sha256_file(source) != sha256_file(destination):
            raise SystemExit("Copied DH database does not match the master")
        installed = dh_fingerprint(destination)
        write_state({"installedStorage": str(destination), "source": str(source), "installedFingerprint": installed,
                     "installedSha256": sha256_file(destination)})
    print(json.dumps(installed, indent=2))


def cmd_run(args) -> None:
    if not WORLD.exists() or not read_state().get("installedStorage"):
        raise SystemExit("Fixture is not installed; run create-world and install first")
    write_configs(args.masking, args.override, args.render_distance, args.custom_rect, args.dh_engine,
                  not args.no_effects, args.disable_effects)
    env = {"WYNNVISTA_FIXTURE_COMMANDS": args.commands, "WYNNVISTA_FIXTURE_SCREENSHOT": args.screenshot,
           "WYNNVISTA_FIXTURE_TIMELINE": args.timeline, "WYNNVISTA_FIXTURE_CAPTURE_PREFIX": args.capture_prefix,
           "WYNNVISTA_FIXTURE_AUTOSTOP_TICKS": str(args.ticks)}
    if args.iris:
        write_iris(args.shaderpack or "off", Path(args.shaderpack_source) if args.shaderpack_source else None)
    code = run_client(env, args.timeout, args.iris)
    out = Path(args.results) if args.results else RUN / "test-results" / time.strftime("%Y%m%d-%H%M%S")
    out.mkdir(parents=True, exist_ok=True)
    shutil.copy2(log_path(), out / "client.log")
    (out / "exit-code.txt").write_text(f"{code}\n")
    print(f"Client exit code {code}; log saved to {out}")
    if code != 0:
        raise SystemExit(code)


def cmd_check(args) -> None:
    state = read_state()
    log = log_path().read_text(errors="replace")
    if BACKEND == "voxy":
        required = ["Voxy fixture ingestion frozen before world start", "Voxy exact terrain mask ready",
                    "Fixture smoke test completed after"]
        forbidden = ["Voxy terrain mask UNSUPPORTED", "did not match pinned source", "Failed to compile shader patch"]
    else:
        required = ["DH fixture frozen read-only at world load:", "DH world set to read-only.",
                    "Fixture smoke test completed after"]
        forbidden = ["DH world-load event did not include designated fixture", "did not match pinned source"]
    for phrase in required:
        if phrase not in log:
            raise SystemExit(f"Missing fixture log evidence: {phrase}")
    for phrase in forbidden:
        if phrase in log:
            raise SystemExit(f"Forbidden log evidence present: {phrase}")
    reference = state["installedFingerprint"]
    if BACKEND == "voxy":
        installed = voxy_fingerprint(Path(state["installedStorage"]))
        for family in ("world_sections", "id_mappings"):
            if installed[family]["sha256"] != reference[family]["sha256"]:
                raise SystemExit(f"{family} changed after the run: {reference[family]} -> {installed[family]}")
    else:
        installed = dh_fingerprint(Path(state["installedStorage"]))
        for key in ("rows", "detail0Rows", "keysSha256", "detail0Sha256"):
            if installed[key] != reference[key]:
                raise SystemExit(f"DH terrain changed after the run ({key}): {reference[key]} -> {installed[key]}")
    print(json.dumps({"logEvidence": "ok", "cache": installed}, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--backend", choices=("voxy", "dh"), default="voxy")
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("create-world", "install", "run", "check"):
        p = sub.add_parser(name)
        p.add_argument("--timeout", type=int, default=420, help="seconds before the client is killed")
        p.add_argument("--render-distance", type=float, default=8.0,
                       help="Voxy section_render_distance in the disposable config")
        p.add_argument("--dh-engine", choices=("AUTO", "OPEN_GL", "BLAZE_3D"),
                       help="DH rendering engine written to the disposable DH config")
        if name == "create-world":
            p.add_argument("--reset", action="store_true")
        if name == "install":
            p.add_argument("--source", "--storage", dest="source", type=Path, required=True,
                           help="closed cache: Voxy storage directory (containing CURRENT) or DH DistantHorizons.sqlite")
            p.add_argument("--config", type=Path, help="Voxy base config.json to install next to the storage")
        if name == "run":
            p.add_argument("--masking", action="store_true")
            p.add_argument("--override", default="AUTO",
                           help="AUTO, MAIN, LIGHT, VOID_OUTER, NONE, PASSTHROUGH or FIXTURE_CUSTOM")
            p.add_argument("--custom-rect", default="",
                           help='inclusive block corners "x1,z1,x2,z2" used by --override FIXTURE_CUSTOM')
            p.add_argument("--no-effects", action="store_true", help="turn the world-effects master switch off")
            p.add_argument("--disable-effects", default="", help="comma-separated effect ids to switch off")
            p.add_argument("--commands")
            p.add_argument("--screenshot")
            p.add_argument("--timeline")
            p.add_argument("--capture-prefix")
            p.add_argument("--ticks", type=int, default=400)
            p.add_argument("--iris", action="store_true", help="run with WynnIris (shaders stay off unless --shaderpack)")
            p.add_argument("--shaderpack", help="pack folder/zip name inside <run dir>/shaderpacks, or 'off'")
            p.add_argument("--shaderpack-source", help="local pack folder or zip copied into shaderpacks once")
            p.add_argument("--results")
    args = parser.parse_args()
    select_backend(args.backend)
    {"create-world": cmd_create_world, "install": cmd_install, "run": cmd_run, "check": cmd_check}[args.command](args)


if __name__ == "__main__":
    main()
