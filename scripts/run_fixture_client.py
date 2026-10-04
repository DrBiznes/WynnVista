#!/usr/bin/env python3
"""Run the disposable client with a bounded lifetime, leaving no orphan game on timeout."""

import os
from pathlib import Path
import signal
import subprocess
import sys


root = Path(__file__).resolve().parents[1]
command = ["bash", "gradlew", *sys.argv[1:], "--no-daemon"]
runtime = os.environ.get("WYNNVISTA_TEST_JAVA")
if runtime:
    if not Path(runtime).is_file():
        raise SystemExit(f"Test Java executable does not exist: {runtime}")
    command.append(f"-PfixtureJava={runtime}")
    subprocess.run([runtime, "-version"], check=True)
process = subprocess.Popen(command, cwd=root, start_new_session=True)
try:
    result = process.wait(timeout=180)
except (subprocess.TimeoutExpired, KeyboardInterrupt):
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        pass
    # Descendants can outlive the Gradle wrapper; also clean up the process group.
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    raise SystemExit("Fixture client timed out or was interrupted; its process group was stopped")
raise SystemExit(result)
