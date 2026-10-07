# Changelog

## Unreleased

**Changed**
- Config: "Smoke Plume Style" chooses how the Mount Wynn smoke plume is drawn. "Blocky" builds it from translucent cubes in the style of the Better Clouds mod; "Realistic" (the default) is the existing soft smoke.
- Config: "Lava Fog Style" does the same for the Roots of Corruption lava fog, independently of the plume: "Blocky" draws its billows and wisps as translucent slabs.
- World effects now sit behind the fog of the active shader pack, including WynnIris ambiance presets: the smoke plume fades into fog the same way the mountain under it does.

## 2.1.0

**Added**
- World effects: custom shader effects drawn over both LOD terrain and the normal game world. Works with Distant Horizons, Voxy, neither, and under shader packs.
- First effect: a smoke plume rising from Mount Wynn, visible across the main map, lit by the time of day, with a lava glow at night.
- Second effect: a glowing red lava fog over the Roots of Corruption, with a purple glow around the Nether portal. It disperses at its edges, thins around the player, and leaves the portal pit clear.
- Effects follow the LOD region mask: the plume is hidden whenever main-map terrain is (Realm of Light, Void, unknown areas), and skipped when out of view.
- Config: a "World Effects" page with a master switch, a quality slider and one toggle per effect.

## 2.0.0

WynnVista no longer changes DH or Voxy render distance. It now hides LOD terrain outside the active Wynncraft region.

**Changed**
- Region masking replaces distance limiting. The main map, Realm of Light and Void/Outer Void each show only their own LOD terrain.
- Edges are clipped exactly in the terrain shader, not at section borders. If an exact shader path isn't available, DH falls back to keeping only fully contained sections (edges may have missing strips).
- Config: "Max/Reduced Render Distance" removed; new "Enable LOD Masking" toggle. Old settings migrate on first launch (`WynnVista.json.bak` is kept). Set render distance in DH or Voxy directly.

**Added**
- Distant Horizons 3.3.3: masking on both renderers (OpenGL and Blaze3D).
- Voxy 0.2.16-beta: masking for opaque, temporal and translucent terrain.
- WynnIris shader packs: masking for both DH and Voxy under a shader pack.
- Cached LODs and LOD databases are never modified.

**Not covered yet**
- Shadow passes and shader packs other than Complementary Reimagined.
- DH generic objects (beacon beams, clouds) are not clipped.
