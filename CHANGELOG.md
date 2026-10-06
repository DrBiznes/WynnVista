# Changelog

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
