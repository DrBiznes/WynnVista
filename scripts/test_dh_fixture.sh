#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 3 || $# -gt 4 ]]; then
  echo "Usage: $0 <closed-superflat-save> <closed-overworld-DistantHorizons.sqlite> <matching-DistantHorizons.toml> [dh-worker-count]" >&2
  exit 2
fi

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

results="$root/run-dh/test-results/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$results"
stage="prepare"
archive() {
  result=$?
  if [[ -f run-dh/logs/latest.log ]]; then
    cp run-dh/logs/latest.log "$results/$stage.log"
  fi
  printf '%s\n' "$result" > "$results/exit-code.txt"
}
trap archive EXIT
prepare_args=(--world "$1" --lod "$2" --dh-config "$3" --reset)
if [[ $# -eq 4 ]]; then prepare_args+=(--dh-threads "$4"); fi
if [[ -n "${WYNNVISTA_DH_ENGINE:-}" ]]; then
  prepare_args+=(--rendering-engine "$WYNNVISTA_DH_ENGINE")
fi
python3 scripts/prepare_dh_fixture.py "${prepare_args[@]}"
cp run-dh/fixture-manifest.json "$results/fixture-manifest.json"
stage="first-client"
python3 scripts/run_fixture_client.py test runClient -PlodBackend=dh
cp run-dh/logs/latest.log "$results/$stage.log"
python3 scripts/check_dh_fixture.py | tee "$results/first-check.txt"

reference="$root/.local-fixtures/dh/post-migration-reference.sqlite"
mkdir -p "$(dirname "$reference")"
if [[ "$(uname)" == "Darwin" ]]; then
  cp -c "$root/run-dh/saves/New World/data/DistantHorizons.sqlite" "$reference"
else
  cp "$root/run-dh/saves/New World/data/DistantHorizons.sqlite" "$reference"
fi

stage="second-client"
python3 scripts/run_fixture_client.py runClient -PlodBackend=dh
python3 scripts/check_dh_fixture.py --reference "$reference" | tee "$results/second-check.txt"
echo "Test evidence saved to $results"
