#!/usr/bin/env python3
"""Loader smoke test: start the dev client with neither, one, or both LOD mods and check that WynnVista loads.

  python scripts/loader_smoke.py neither|dh|voxy|both

Creates a disposable superflat world under run-neither/, run-dh/, run-voxy/ or run-both/ (replacing its
'New World' save), joins it for 60 ticks and exits. Fails on mixin application errors or crashes.
No LOD data is installed, so this only validates class loading, optional-mixin gating and shutdown.
"""

import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WIN = os.name == "nt"
RUN_DIRS = {"neither": "run-neither", "dh": "run-dh", "voxy": "run-voxy", "both": "run-both"}


def main() -> None:
    if len(sys.argv) != 2 or sys.argv[1] not in RUN_DIRS:
        raise SystemExit(__doc__)
    backend = sys.argv[1]
    run = ROOT / RUN_DIRS[backend]
    world = run / "saves" / "New World"
    if backend in ("dh", "voxy") and run.exists() and (run / "fixture-state.json").exists():
        raise SystemExit(f"{run} holds an installed fixture; refusing to replace it. "
                         f"Use a different working copy or delete it deliberately.")
    if world.exists():
        shutil.rmtree(world)
    (run / "config").mkdir(parents=True, exist_ok=True)
    (run / "config" / "WynnVista.json").write_text(json.dumps({
        "schemaVersion": 2, "showMessage": True, "maskingEnabled": True, "fixtureEnabled": True,
        "fixtureSavePath": str(world.resolve()), "fixtureOverride": "AUTO"}, indent=2) + "\n")
    options = run / "options.txt"
    wanted = {"onboardAccessibility": "false", "pauseOnLostFocus": "false", "tutorialStep": "none",
              "joinedFirstServer": "true", "skipMultiplayerWarning": "true", "narrator": "0",
              "soundCategory_master": "0.0"}
    kept = [l for l in (options.read_text().splitlines() if options.exists() else []) if l.split(":", 1)[0] not in wanted]
    options.write_text("\n".join(kept + [f"{k}:{v}" for k, v in wanted.items()]) + "\n")
    env = dict(os.environ, WYNNVISTA_FIXTURE_CREATE_WORLD="New World", WYNNVISTA_FIXTURE_AUTOSTOP_TICKS="60")
    gradle = [str(ROOT / "gradlew.bat")] if WIN else ["bash", "gradlew"]
    command = gradle + ["runClient", f"-PlodBackend={backend}", "--no-daemon"]
    code = subprocess.run(command, cwd=ROOT, env=env, timeout=420).returncode
    log = (run / "logs" / "latest.log").read_text(errors="replace")
    expected_dh = backend in ("dh", "both")
    expected_voxy = backend in ("voxy", "both")
    detected = f"LOD backends detected: Distant Horizons={str(expected_dh).lower()}, Voxy={str(expected_voxy).lower()}"
    problems = []
    if code != 0:
        problems.append(f"client exit code {code}")
    if detected not in log:
        problems.append(f"missing '{detected}'")
    if "Fixture smoke test completed after" not in log:
        problems.append("fixture smoke test did not complete")
    for pattern in (r"InvalidInjectionException", r"Mixin apply (for mod )?wynn.* failed", r"MixinApplyError",
                    r"NoClassDefFoundError: (com/seibel|me/cortex)", r"Voxy terrain mask UNSUPPORTED",
                    r"did not match pinned source"):
        if re.search(pattern, log):
            problems.append(f"log matches /{pattern}/")
    if not expected_voxy and "wynnvista-voxy" in log:
        problems.append("Voxy integration logged without Voxy installed")
    if not expected_dh and "wynnvista-dh" in log:
        problems.append("DH integration logged without DH installed")
    print(json.dumps({"backend": backend, "exitCode": code, "problems": problems}, indent=2))
    raise SystemExit(1 if problems else 0)


if __name__ == "__main__":
    main()
