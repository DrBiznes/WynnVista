#!/usr/bin/env python3
"""Stage, run and check the isolated Voxy fixture (Windows, macOS or Linux).

The fixture is a disposable superflat save under run-voxy/ with a COPY of a Wynncraft Voxy storage
installed as that save's overworld storage. The source (e.g. a Modrinth profile's .voxy folder) is
only ever read. Subcommands:

  create-world   create an empty superflat save, record its Voxy world id
  install        copy a Wynncraft Voxy world storage into the save under that id
  run            launch the client once (masking/camera controlled by options), bounded in time
  check          verify log evidence and compare the installed storage with the master fingerprint
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUN = ROOT / "run-voxy"
SAVE_NAME = "New World"
WORLD = RUN / "saves" / SAVE_NAME
LOCAL = ROOT / ".local-fixtures" / "voxy"
ROCKSDB_JAR = LOCAL / "lib" / "rocksdbjni-10.2.1.jar"
STATE = RUN / "fixture-state.json"
WIN = os.name == "nt"


def log_path() -> Path:
    return RUN / "logs" / "latest.log"


def read_state() -> dict:
    return json.loads(STATE.read_text()) if STATE.exists() else {}


def write_state(update: dict) -> None:
    state = read_state()
    state.update(update)
    STATE.write_text(json.dumps(state, indent=2) + "\n")


def write_configs(masking: bool, override: str, render_distance: float, custom_rect: str = "") -> None:
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
    }, indent=2) + "\n")
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
    options = RUN / "options.txt"
    wanted = {"onboardAccessibility": "false", "pauseOnLostFocus": "false", "tutorialStep": "none",
              "joinedFirstServer": "true", "skipMultiplayerWarning": "true", "renderDistance": "6",
              "narrator": "0", "soundCategory_master": "0.0"}
    lines = [l for l in (options.read_text().splitlines() if options.exists() else [])
             if l.split(":", 1)[0] not in wanted]
    options.write_text("\n".join(lines + [f"{k}:{v}" for k, v in wanted.items()]) + "\n")


def gradle_command() -> list:
    base = [str(ROOT / "gradlew.bat")] if WIN else ["bash", "gradlew"]
    return base + ["runClient", "-PlodBackend=voxy", "--no-daemon"]


def kill_tree(process: subprocess.Popen) -> None:
    if WIN:
        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    else:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass


def run_client(extra_env: dict, timeout: int) -> int:
    env = dict(os.environ)
    env.update({k: v for k, v in extra_env.items() if v is not None})
    runtime = env.get("WYNNVISTA_TEST_JAVA")
    command = gradle_command()
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


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def fingerprint(storage: Path) -> dict:
    """Row-level fingerprint of a CLOSED Voxy storage directory (works on a scratch copy)."""
    scratch = ROOT / ".local-fixtures" / "fingerprint-scratch"
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


def java_executable() -> str:
    explicit = os.environ.get("WYNNVISTA_TEST_JAVA")
    if explicit:
        return explicit
    home = os.environ.get("JAVA_HOME")
    if home:
        return str(Path(home) / "bin" / ("java.exe" if WIN else "java"))
    return "java"


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
    match = re.search(r"Voxy fixture world: id=([0-9a-f]{32}), dimension=([^,]+), storage=(.+)", log)
    if not match:
        raise SystemExit("Voxy world identifier was not logged; see run-voxy/logs/latest.log")
    write_state({"voxyWorldId": match[1], "dimension": match[2], "worldCreatedAt": time.time()})
    print(f"Created {WORLD} with Voxy world id {match[1]} ({match[2]})")


def cmd_install(args) -> None:
    state = read_state()
    world_id = state.get("voxyWorldId")
    if not world_id:
        raise SystemExit("No Voxy world id recorded; run create-world first")
    source = args.storage.resolve(strict=True)
    if not (source / "CURRENT").is_file():
        raise SystemExit(f"{source} does not look like a Voxy RocksDB storage (no CURRENT file)")
    if (source / "LOCK").exists():
        try:
            with (source / "LOCK").open("ab"):
                pass
        except OSError:
            raise SystemExit(f"Storage is in use by another process: {source}")
    destination_root = WORLD / "voxy"
    if destination_root.exists():
        shutil.rmtree(destination_root)
    destination = destination_root / world_id / "storage"
    destination.parent.mkdir(parents=True)
    # Copy the whole closed database (never individual files); skip only RocksDB's lock and old info logs.
    shutil.copytree(source, destination, ignore=shutil.ignore_patterns("LOCK", "LOG.old.*"))
    if args.config:
        shutil.copy2(args.config.resolve(strict=True), destination_root / "config.json")
    installed = fingerprint(destination)
    write_state({"installedStorage": str(destination), "source": str(source), "installedFingerprint": installed})
    print(json.dumps(installed, indent=2))


def cmd_run(args) -> None:
    if not WORLD.exists() or not read_state().get("installedStorage"):
        raise SystemExit("Fixture is not installed; run create-world and install first")
    write_configs(args.masking, args.override, args.render_distance, args.custom_rect)
    env = {"WYNNVISTA_FIXTURE_COMMANDS": args.commands, "WYNNVISTA_FIXTURE_SCREENSHOT": args.screenshot,
           "WYNNVISTA_FIXTURE_TIMELINE": args.timeline, "WYNNVISTA_FIXTURE_CAPTURE_PREFIX": args.capture_prefix,
           "WYNNVISTA_FIXTURE_AUTOSTOP_TICKS": str(args.ticks)}
    code = run_client(env, args.timeout)
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
    required = [
        "Voxy fixture ingestion frozen before world start",
        "Voxy exact terrain mask ready",
        "Fixture smoke test completed after",
    ]
    for phrase in required:
        if phrase not in log:
            raise SystemExit(f"Missing fixture log evidence: {phrase}")
    forbidden = ["Voxy terrain mask UNSUPPORTED", "did not match pinned source", "Failed to compile shader patch"]
    for phrase in forbidden:
        if phrase in log:
            raise SystemExit(f"Forbidden log evidence present: {phrase}")
    installed = fingerprint(Path(state["installedStorage"]))
    reference = state["installedFingerprint"]
    for family in ("world_sections", "id_mappings"):
        if installed[family]["sha256"] != reference[family]["sha256"]:
            raise SystemExit(f"{family} changed after the run: {reference[family]} -> {installed[family]}")
    print(json.dumps({"logEvidence": "ok", "storage": installed}, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("create-world", "install", "run", "check"):
        p = sub.add_parser(name)
        p.add_argument("--timeout", type=int, default=420, help="seconds before the client is killed")
        p.add_argument("--render-distance", type=float, default=8.0,
                       help="Voxy section_render_distance in the disposable config")
        if name == "create-world":
            p.add_argument("--reset", action="store_true")
        if name == "install":
            p.add_argument("--storage", type=Path, required=True,
                           help="closed Voxy world storage directory (the one containing CURRENT)")
            p.add_argument("--config", type=Path, help="Voxy base config.json to install next to the storage")
        if name == "run":
            p.add_argument("--masking", action="store_true")
            p.add_argument("--override", default="AUTO",
                           help="AUTO, MAIN, LIGHT, VOID_OUTER, NONE, PASSTHROUGH or FIXTURE_CUSTOM")
            p.add_argument("--custom-rect", default="",
                           help='inclusive block corners "x1,z1,x2,z2" used by --override FIXTURE_CUSTOM')
            p.add_argument("--commands")
            p.add_argument("--screenshot")
            p.add_argument("--timeline")
            p.add_argument("--capture-prefix")
            p.add_argument("--ticks", type=int, default=400)
            p.add_argument("--results")
    args = parser.parse_args()
    {"create-world": cmd_create_world, "install": cmd_install, "run": cmd_run, "check": cmd_check}[args.command](args)


if __name__ == "__main__":
    main()
