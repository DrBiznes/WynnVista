"""The Wynn-Photon shader pack (Photon v1.4 with Wynn-Photon's patches), as a ready-to-use zip from Wynn-Photon's
GitHub release. The version and SHA-256 are pinned in gradle.properties (wynnphoton_version, wynnphoton_sha256).

    python scripts/wynnphoton_pack.py run-voxy/shaderpacks     downloads the pinned zip there (once) and prints its name

Wynn-Photon builds this zip for test setups only; it is not on Modrinth or CurseForge (Photon's licence).
"""
import hashlib
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ALIAS = "wynnphoton"  # what --shaderpack accepts in place of the file name


def _properties() -> dict:
    props = {}
    for line in (ROOT / "gradle.properties").read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props


def version() -> str:
    return _properties()["wynnphoton_version"]


def pack_name() -> str:
    return f"photon_v1.4_WynnPhoton_{version()}.zip"


def url() -> str:
    return f"https://github.com/DrBiznes/wynn-photon/releases/download/v{version()}/{pack_name()}"


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def ensure(shaderpacks: Path) -> str:
    """Put the pinned zip in shaderpacks/ (downloaded and hash-checked once) and return its file name."""
    expected = _properties()["wynnphoton_sha256"].lower()
    target = shaderpacks / pack_name()
    if target.exists() and _sha256(target) == expected:
        return target.name
    shaderpacks.mkdir(parents=True, exist_ok=True)
    partial = target.with_suffix(".zip.part")
    request = urllib.request.Request(url(), headers={"User-Agent": "WynnVista-test-fixture"})
    with urllib.request.urlopen(request, timeout=120) as response, partial.open("wb") as out:
        while block := response.read(1 << 20):
            out.write(block)
    actual = _sha256(partial)
    if actual != expected:
        partial.unlink()
        raise SystemExit(f"{pack_name()} from {url()} has SHA-256 {actual}, expected {expected}")
    partial.replace(target)
    print(f"Downloaded {target.name} into {shaderpacks}")
    return target.name


if __name__ == "__main__":
    print(ensure(Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "run-voxy" / "shaderpacks"))
