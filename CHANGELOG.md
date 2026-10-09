# Changelog

## Unreleased

**Added**
- World effect: the void under the Sky Islands. Clumps of cloud built from a few large blocks each, in the style of the clouds that launch you between the islands, float around the lowest spikes, over a dark void where here and there a glowing pixel-art nebula in purples, indigo and blue drifts by far below, and a few fallen islands fade into the dark at different depths. It has its own toggle ("Sky Islands Void") on the World Effects config page.
- World effect: updrafts and motes in the Sky Islands ("Sky Islands Updrafts and Motes"). Near you, white streaks of rising wind blow in gusts through the gaps between the islands, strongest around Windwalker Temple, small violet motes rise under the islands, and spray rises where a waterfall lands in the cloud. Astraulus' Tower has motes of starlight around it and Wybel Island pastel sparkles.
- Volcanic Isles smoke plumes: each of the three volcanoes has a smaller copy of the Mount Wynn plume, sized to its crater. One config toggle, "Volcanic Isles Smoke Plumes", switches all three; "Smoke Plume Style" applies to them too.
- Better Clouds support (optional): world effects are now seen through its translucent clouds. Before, a cloud in front of the smoke plume showed the sky behind the plume instead of the smoke.

**Changed**
- Under a shader pack, world effects are lit by the sky behind them instead of by the sky at the horizon: looking up at the smoke plume from nearby, it now has the colours and brightness of the pack's sky and clouds there. Fixes a brown or dark grey plume against Photon's sunset and overcast skies.
- Photon's clouds pass in front of world effects with soft edges instead of stepped holes.
- Config: "Smoke Plume Style" chooses how the Mount Wynn smoke plume is drawn. "Blocky" (the default) builds it from translucent cubes in the style of the Better Clouds mod; "Realistic" is the existing soft smoke.
- Config: "Lava Fog Style" does the same for the Roots of Corruption lava fog, independently of the plume: "Blocky" draws its billows and wisps as translucent slabs.
- Config: "Sky Islands Void Style" does the same for the Sky Islands void. "Blocky" (the default) is the cloud cubes and pixel-art nebulae; "Realistic" draws soft volumetric clouds in the same white, rose and blue, in the same places, over smooth nebulae with point stars and soft-edged fallen islands. The updrafts and motes are the same in both.
- World effects now sit behind the fog of the active shader pack, including WynnIris ambiance presets: the smoke plume fades into fog the same way the mountain under it does.
- World effects now follow the fog settings of the shader pack in use: Complementary Reimagined/Unbound, BSL and Photon. The amount of fog on the smoke plume and lava fog is worked out from the pack's own fog options (including any you changed), and with Distant Horizons or Voxy they fade out towards the LOD render distance the way the pack fades the terrain there. Other packs are still measured from the picture as before.
- Without a shader pack, world effects now follow Distant Horizons' own fog settings and Voxy's environmental fog, instead of measuring them.
- Config: "Follow Fog Settings" switches this off and goes back to measuring the fog from the picture.
- With a shader pack, world effects are now lit to match it: their brightness and tint follow the sky the pack draws, and their lit side follows the pack's sun and moon path. Before, the smoke plume was a dark shape at night and off-colour at sunrise under shader packs.
- Config: "Match Shader Pack Lighting" switches this off.
- The clouds of Complementary Reimagined/Unbound, BSL and Photon now pass in front of world effects. Before, the smoke plume was drawn over a shader pack's clouds.
- Config: "Shader Pack Clouds Hide Effects" switches this off.
- With a shader pack whose water reflects, world effects are now mirrored in the water too, in nearby water and in Distant Horizons / Voxy water. Before, the sea showed Mount Wynn's reflection without its smoke plume.
- Config: "Shader Pack Water Reflections" switches this off.
- With a shader pack, the Sky Islands cloud cubes are lit on the faces turned to the pack's sun or moon, and world effects keep following the time of day while no sky is in view. Before, effects seen from a place without a view of the sky, such as under the Sky Islands, kept the brightness of the last sky seen.
- The Sky Islands effects do less work: the void is skipped when looking up from the islands and from further than 640 blocks away, and the updrafts and motes are skipped unless you are within 64 blocks of the area.

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
