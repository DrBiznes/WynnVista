# Changelog

## 2.2.4

**Fixed**
- Wynn-Photon: clouds in front of world effects, such as the Mount Wynn smoke plume, no longer jitter or flash over the effect while you move. Needs Wynn-Photon 0.10.0 or later.
- Photon and Wynn-Photon: specks of a world effect no longer show through the edge of a cloud, or through cloud next to a mountain.

**Removed**
- The "Smooth Shader Pack Cloud Edges" option from 2.2.3, since it didn't fix the jitter. With stock Photon, cloud edges in front of effects may shimmer slightly; use Wynn-Photon to avoid this.

## 2.2.3

Compatibility with the Wynn-Photon shader pack.

**Added**
- Wynn-Photon: the pack is recognised as Photon, so world effects get its fog, its lighting and its clouds in front of them as they do with Photon. "Photon Only" under "Smooth Shader Pack Cloud Edges" covers it too.
- "Smooth Shader Pack Cloud Edges" on the World Effects config tab: "Photon Only" (the default), "Always" or "Off". It chooses for which shader packs the edge of a cloud in front of a world effect is smoothed.

**Fixed**
- Photon and Wynn-Photon: where one of the pack's clouds passed in front of a world effect, such as the Mount Wynn smoke plume, the cloud's edge shimmered and broke into small squares. The cloud's edge now lies over the effect as softly as it does over the sky. "Smooth Shader Pack Cloud Edges" on the World Effects config tab is set to "Photon Only" by default, so other shader packs, which do not need it, are read as before. "Off" gives a little more speed on a slow graphics card, and the edge can then shimmer; "Always" also softens the cloud edges of Complementary and BSL.
- Roots of Corruption lava fog, Realistic style: the grain in the fog no longer stays in place on the screen while the fog moves behind it. Its detail is now fixed in the world and moves with the fog as you walk past.
- Wynntils: world effects such as the Mount Wynn smoke plume and the Roots of Corruption lava fog were drawn through walls and nearby blocks while the white box of Wynntils' "Highlight Gathering Nodes" was showing, which it does when you hold a gathering tool near a profession node. The effects now stay behind the terrain there, as everywhere else.

## 2.2.2

Compatibility with the Flashback replay mod.

**Changed**
- Region messages are back to the chat lines "The Fog lifts." (entering a region whose LOD terrain is shown) and "The Fog descends." (leaving it), in place of the "WynnVista: MAIN" line above the hotbar. They are now off by default for new installations; an existing installation keeps its setting. "Show Region Messages" on the General config tab switches them on or off.
- ReplayMod and Flashback replays get LOD masking and world effects however they were recorded: WynnVista now only notices that a replay is playing and no longer works out which server it came from, so a replay recorded on another server is treated as Wynncraft too. Other servers and ordinary singleplayer worlds are still left alone.

**Fixed**
- Flashback: LOD masking and world effects now work in replays, while watching them in the editor and in exported videos. Before, a replay was treated as an unknown world, with no masking and no effects.

## 2.2.1

A config screen with a tab for each area's world effects, and switches for the single parts of the Sky Islands effects.

**Changed**
- Config: world effects are now grouped by area, each on its own tab. "Wynn Plains" holds the Mount Wynn smoke plume and the Roots of Corruption lava fog; "Sky Islands" holds the void and the updrafts and motes. Each tab has a switch for the whole area, and each effect has its own group with its switch and its settings. The "World Effects" tab keeps the master switch, the quality slider and the shader pack settings shared by all effects.
- Config: the three style settings moved next to their effect ("Style" under the smoke plume, the lava fog and the Sky Islands void). Your existing choices are carried over.

**Added**
- Config: "Clouds" under the Sky Islands void. Switch it off to keep the void, its haze, nebulae and fallen islands without the cloud clumps floating over it.
- Config: the Sky Islands updrafts and motes can be switched off one kind at a time: "Updrafts", "Windwalker Temple Winds" (the stronger updrafts around the temple), "Violet Motes", "Astraulus' Tower Star Motes", "Wybel Island Sparkles" and "Waterfall Spray".

**Fixed**
- The game no longer crashes on launch without Mod Menu installed. Mod Menu and Cloth Config are now both optional: they are only needed for the in-game config screen, and without them the settings are changed in `config/WynnVista.json`.
- ReplayMod: LOD masking and world effects now work in replays recorded on Wynncraft, while watching them and in rendered videos. Before, a replay was treated as an unknown world, with no masking and no effects.
- Fabrishot and ReplayMod: world effects could be missing from a high-resolution screenshot or a rendered video, and stay missing afterwards, because the picture is resized for the capture. Effects now follow the resized picture.
- World effects: just before sunrise and just after sunset, the lit side of the Mount Wynn smoke plume and of the Sky Islands void clouds jumped from one side to the other in a single frame, as the light was handed between the sun and the moon. The light now swings over the top through twilight.

## 2.2.0

Sky Islands shader effects. The Sky Islands get a void of drifting clouds and nebulae beneath them, and wind and motes in their air. World effects also now follow your shader pack's fog, lighting, clouds and water.

**Added**
- World effect: the void under the Sky Islands. Clumps of cloud built from a few large blocks each, in the style of the clouds that launch you between the islands, float around the lowest spikes, over a dark void where here and there a glowing pixel-art nebula in purples, indigo and blue drifts by far below, and a few fallen islands fade into the dark at different depths. It has its own toggle ("Sky Islands Void") on the World Effects config page.
- World effect: updrafts and motes in the Sky Islands ("Sky Islands Updrafts and Motes"). Near you, white streaks of rising wind blow in gusts through the gaps between the islands, strongest around Windwalker Temple, small violet motes rise under the islands, and spray rises where a waterfall lands in the cloud. Astraulus' Tower has motes of starlight around it and Wybel Island pastel sparkles.
- Config: "Sky Islands Void Style". "Blocky" (the default) is the cloud cubes and pixel-art nebulae; "Realistic" draws soft volumetric clouds in the same white, rose and blue, in the same places, over smooth nebulae with point stars and soft-edged fallen islands. The updrafts and motes are the same in both.
- Config: "Smoke Plume Style" and "Lava Fog Style" choose how the Mount Wynn smoke plume and the Roots of Corruption lava fog are drawn, each on its own. "Blocky" (the default) builds them from translucent cubes and slabs in the style of the Better Clouds mod; "Realistic" is the existing soft smoke.
- Better Clouds support (optional): world effects are now seen through its translucent clouds. Before, a cloud in front of the smoke plume showed the sky behind the plume instead of the smoke.

**Changed**
- Shader pack fog: world effects now sit behind the fog of the active shader pack, including WynnIris ambiance presets, so the smoke plume fades into fog the same way the mountain under it does. For Complementary Reimagined/Unbound, BSL and Photon the amount of fog is worked out from the pack's own fog options (including any you changed), and with Distant Horizons or Voxy it fades out towards the LOD render distance the way the pack fades the terrain there. Other packs are still measured from the picture as before.
- Without a shader pack, world effects now follow Distant Horizons' own fog settings and Voxy's environmental fog, instead of measuring them.
- Config: "Follow Fog Settings" switches this off and goes back to measuring the fog from the picture.
- Shader pack lighting: world effects are lit to match the pack. Their brightness and tint follow the sky the pack draws, looking up at the smoke plume from nearby it has the colours and brightness of the pack's sky and clouds there, and their lit side follows the pack's sun and moon path. Before, the smoke plume was a dark shape at night, off-colour at sunrise, and brown or dark grey against Photon's sunset and overcast skies.
- Config: "Match Shader Pack Lighting" switches this off.
- Shader pack clouds: the clouds of Complementary Reimagined/Unbound, BSL and Photon now pass in front of world effects, with soft edges on Photon instead of stepped holes. Before, the smoke plume was drawn over a shader pack's clouds.
- Config: "Shader Pack Clouds Hide Effects" switches this off.
- Shader pack water: with a shader pack whose water reflects, world effects are now mirrored in the water too, in nearby water and in Distant Horizons / Voxy water. Before, the sea showed Mount Wynn's reflection without its smoke plume.
- Config: "Shader Pack Water Reflections" switches this off.
- With a shader pack, the Sky Islands cloud cubes are lit on the faces turned to the pack's sun or moon, and world effects keep following the time of day while no sky is in view. Before, effects seen from a place without a view of the sky, such as under the Sky Islands, kept the brightness of the last sky seen.
- The Sky Islands void now reaches the horizon and fades into the sky. Before, flying low showed where it ended, as a band of haze and a gap with the sky's lower half showing through.

**Performance**
- The Sky Islands effects do less work: the void is skipped when looking up from the islands and from further than 640 blocks away, and the updrafts and motes are skipped unless you are within 64 blocks of the area.
- The blocky Sky Islands void is cheaper to draw: the cloud cover is worked out once a frame instead of for every pixel. It looks the same.
- World effects spend less time reading the sky and fog from the picture: about 0.4 ms less per frame, and none at all without a shader pack when Distant Horizons' or Voxy's fog is followed.

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
