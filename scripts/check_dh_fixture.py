#!/usr/bin/env python3
"""Check the isolated DH fixture after runClient exits normally."""

import json
import argparse
import sqlite3
import hashlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RUN = ROOT / "run-dh"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--reference", type=Path, help="post-migration DB to compare every FullData row")
args = parser.parse_args()
manifest = json.loads((RUN / "fixture-manifest.json").read_text())
log = (RUN / "logs" / "latest.log").read_text()
required = (
    "Distant Horizons, Version: 3.3.3",
    "Auto-opening isolated DH fixture: New World",
    "Designated fixture server starting:",
    "DH fixture frozen read-only at world load:",
    "DH world set to read-only.",
    "Fixture smoke test completed after",
)
for phrase in required:
    if phrase not in log:
        raise SystemExit(f"Missing fixture log evidence: {phrase}")
if "DH world-load event did not include designated fixture" in log:
    raise SystemExit("Fixture was not recognized at DH world load")
level_started = "Started DhClientServerLevel for"
if level_started not in log or log.index("DH fixture frozen read-only at world load:") > log.index(level_started):
    raise SystemExit("Fixture read-only flag was not set before DH level startup")
engine = manifest.get("renderingEngine", "AUTO")
renderer_evidence = {
    "OPEN_GL": "DH Rendering successfully bound to: [OpenGL]",
    "BLAZE_3D": "DH Rendering successfully bound to: [Blaze3D:",
}
if engine in renderer_evidence and renderer_evidence[engine] not in log:
    raise SystemExit(f"Requested DH renderer was not bound: {engine}")


def fingerprint(path: Path):
    with sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True) as db:
        check = db.execute("PRAGMA quick_check").fetchone()[0]
        if check != "ok":
            raise SystemExit(f"SQLite quick_check failed for {path}: {check}")
        rows = db.execute("""
            SELECT DetailLevel, PosX, PosZ, MinY, DataChecksum, DataFormatVersion, CompressionMode,
                   Data, ColumnGenerationStep, ColumnWorldCompressionMode, Mapping
            FROM FullData ORDER BY DetailLevel, PosX, PosZ
        """)
        # Hash actual terrain and mapping blobs, rather than trusting DH's stored checksum.
        return {tuple(row[:3]): tuple(row[3:7]) + tuple(
            None if blob is None else hashlib.sha256(blob).digest() for blob in row[7:])
            for row in rows}


source = fingerprint(Path(manifest["masterLod"]))
installed = fingerprint(Path(manifest["destinationLod"]))
if source.keys() != installed.keys():
    raise SystemExit("FullData terrain keys were added or removed during the fixture run")
source_detail0 = {key: value for key, value in source.items() if key[0] == 0}
installed_detail0 = {key: value for key, value in installed.items() if key[0] == 0}
if source_detail0 != installed_detail0:
    raise SystemExit("Detail-0 terrain changed during the fixture run")
if args.reference is not None and fingerprint(args.reference.resolve()) != installed:
    raise SystemExit("FullData terrain changed relative to the post-migration reference")
if args.reference is not None:
    def file_hash(path):
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(8 * 1024 * 1024), b""):
                digest.update(block)
        return digest.hexdigest()
    if file_hash(args.reference) != file_hash(Path(manifest["destinationLod"])):
        raise SystemExit("SQLite file changed relative to the post-migration reference")

print(f"PASS: DH read-only event preceded level load; {len(source_detail0)} detail-0 terrain rows retained")
print(f"FullData rows: source={len(source)}, fixture={len(installed)}; "
      f"terrain rows changed since master={sum(source.get(key) != installed.get(key) for key in source.keys() | installed.keys())}")
if args.reference is not None:
    print(f"PASS: all {len(installed)} terrain rows and the complete SQLite file are unchanged on repeat launch")
