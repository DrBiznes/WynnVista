#!/usr/bin/env python3
"""Stage an isolated DH test client from a closed singleplayer save and LOD master."""

import argparse
import hashlib
import fcntl
import json
import re
import shutil
import sqlite3
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RUN = ROOT / "run-dh"
DESTINATION = RUN / "saves" / "New World"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def clone_tree(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if sys.platform == "darwin":
        subprocess.run(["cp", "-R", "-c", str(source), str(destination)], check=True)
    else:
        shutil.copytree(source, destination)


def clone_file(source: Path, destination: Path) -> None:
    if sys.platform == "darwin":
        subprocess.run(["cp", "-c", str(source), str(destination)], check=True)
    else:
        shutil.copy2(source, destination)


def check_world_closed(world: Path) -> None:
    lock = world / "session.lock"
    if lock.exists():
        with lock.open("rb") as stream:
            try:
                fcntl.lockf(stream, fcntl.LOCK_SH | fcntl.LOCK_NB)
            except BlockingIOError:
                raise RuntimeError(f"World is open in Minecraft: {world}") from None
            fcntl.lockf(stream, fcntl.LOCK_UN)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", required=True, type=Path, help="closed empty superflat save")
    parser.add_argument("--lod", required=True, type=Path, help="closed Wynncraft overworld SQLite master")
    parser.add_argument("--dh-config", required=True, type=Path, help="DH TOML from the matching build")
    parser.add_argument("--reset", action="store_true", help="replace an existing disposable run-dh save")
    parser.add_argument("--dh-threads", type=int, help="override DH worker count in the isolated client only")
    parser.add_argument("--rendering-engine", choices=("AUTO", "OPEN_GL", "BLAZE_3D"),
                        help="select the DH renderer in the disposable installation")
    args = parser.parse_args()

    world = args.world.resolve(strict=True)
    lod = args.lod.resolve(strict=True)
    dh_config = args.dh_config.resolve(strict=True)
    if args.dh_threads is not None and not 1 <= args.dh_threads <= 64:
        parser.error("--dh-threads must be between 1 and 64")
    if (world == DESTINATION.resolve() or DESTINATION.resolve() in world.parents
            or world in DESTINATION.resolve().parents):
        parser.error("--world must be outside the disposable destination")
    if lod == DESTINATION.resolve() or DESTINATION.resolve() in lod.parents:
        parser.error("--lod must be outside the disposable destination")
    for suffix in ("-wal", "-journal"):
        sidecar = Path(str(lod) + suffix)
        if sidecar.exists() and sidecar.stat().st_size:
            parser.error(f"LOD database has an active journal: {sidecar}; close/checkpoint it before copying")
    if not (world / "level.dat").is_file():
        parser.error("--world must contain level.dat")
    check_world_closed(world)
    if DESTINATION.exists():
        if not args.reset:
            parser.error(f"{DESTINATION} already exists; use --reset to replace this disposable fixture")
        check_world_closed(DESTINATION)
        shutil.rmtree(DESTINATION)

    clone_tree(world, DESTINATION)
    (DESTINATION / "session.lock").unlink(missing_ok=True)
    installed = DESTINATION / "data" / "DistantHorizons.sqlite"
    installed.parent.mkdir(parents=True, exist_ok=True)
    installed.unlink(missing_ok=True)
    for suffix in ("-wal", "-shm", "-journal"):
        Path(str(installed) + suffix).unlink(missing_ok=True)
    clone_file(lod, installed)

    with sqlite3.connect(installed.as_uri() + "?mode=ro&immutable=1", uri=True) as db:
        full_data = db.execute("SELECT COUNT(*) FROM FullData").fetchone()[0]
        check = db.execute("PRAGMA quick_check").fetchone()[0]
    if check != "ok":
        raise RuntimeError(f"Copied DH database failed quick_check: {check}")
    source_hash = sha256(lod)
    copied_hash = sha256(installed)
    if copied_hash != source_hash:
        raise RuntimeError("Copied DH database does not match the master")

    config_dir = RUN / "config"
    config_dir.mkdir(parents=True, exist_ok=True)
    dh_text = dh_config.read_text()
    old = 'generatorPlan = "SURFACE_THEN_CHUNKS"'
    if dh_text.count(old) != 1:
        raise RuntimeError("Expected one SURFACE_THEN_CHUNKS generatorPlan in DH config")
    updater = "enableAutoUpdater = true"
    if dh_text.count(updater) != 1:
        raise RuntimeError("Expected one enableAutoUpdater setting in DH config")
    dh_text = dh_text.replace(old, 'generatorPlan = "DISABLED"').replace(updater, "enableAutoUpdater = false")
    if args.dh_threads is not None:
        dh_text, count = re.subn(r"(?m)^(\s*numberOfThreads\s*=\s*)\d+\s*$",
                                lambda match: match[1] + str(args.dh_threads), dh_text)
        if count != 1:
            raise RuntimeError("Expected one numberOfThreads setting in DH config")
    if args.rendering_engine is not None:
        dh_text, count = re.subn(r'(?m)^(\s*renderingEngine\s*=\s*)"[A-Z_0-9]+"\s*$',
                                lambda match: match[1] + '"' + args.rendering_engine + '"', dh_text)
        if count != 1:
            raise RuntimeError("Expected one renderingEngine setting in DH config")
    (config_dir / "DistantHorizons.toml").write_text(dh_text)
    (config_dir / "WynnVista.json").write_text(json.dumps({
        "schemaVersion": 2,
        "showMessage": True,
        "maskingEnabled": False,
        "fixtureEnabled": True,
        "fixtureSavePath": str(DESTINATION.resolve()),
        "fixtureOverride": "AUTO",
    }, indent=2) + "\n")
    options = RUN / "options.txt"
    option_lines = options.read_text().splitlines() if options.exists() else []
    option_lines = [line for line in option_lines if not line.startswith("onboardAccessibility:")]
    option_lines.append("onboardAccessibility:false")
    options.write_text("\n".join(option_lines) + "\n")

    manifest = {
        "sourceWorld": str(world),
        "masterLod": str(lod),
        "destinationWorld": str(DESTINATION),
        "destinationLod": str(installed),
        "sourceSha256": source_hash,
        "destinationSha256": copied_hash,
        "fullDataRows": full_data,
        "sqliteQuickCheck": check,
        "distantGeneration": "DISABLED",
        "autoUpdater": False,
        "maskingEnabled": False,
    }
    manifest["dhThreads"] = int(re.search(r"numberOfThreads\s*=\s*(\d+)", dh_text)[1])
    manifest["renderingEngine"] = re.search(r'renderingEngine\s*=\s*"([A-Z_0-9]+)"', dh_text)[1]
    (RUN / "fixture-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
