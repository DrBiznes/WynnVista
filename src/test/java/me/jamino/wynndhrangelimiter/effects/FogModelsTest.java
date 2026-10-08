package me.jamino.wynndhrangelimiter.effects;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FogModelsTest {
    private static final long NOON = 6000;
    private static final long MIDNIGHT = 18000;
    private static final long SUNRISE = 500;

    private static final Map<String, String> COMPLEMENTARY = Map.of("BORDER_FOG_DISTANCE_OVERWORLD", "3",
            "BORDER_FOG_DENSITY_OVERWORLD", "1.00", "ATM_FOG_DISTANCE", "100", "ATM_FOG_ALTITUDE", "63",
            "ATM_FOG_MULT", "0.95", "ATMOSPHERIC_FOG_DENSITY", "1.00");
    private static final Map<String, String> BSL = Map.of("FOG_DENSITY", "1.00", "FOG_DENSITY_NIGHT", "4.00",
            "FOG_DENSITY_WEATHER", "1.50", "FOG_DENSITY_LOD", "1.00", "FAR_VANILLA_FOG", "2", "FOG_HEIGHT_Y", "62",
            "FOG_HEIGHT_FALLOFF", "7.00");
    private static final Map<String, String> PHOTON = Map.of("AIR_FOG_MIE_DENSITY_NOON", "0.0001");

    private static EffectFog.PackOptions pack(String name, Map<String, String> values, Map<String, Boolean> flags) {
        return new EffectFog.PackOptions() {
            @Override public String name() { return name; }
            @Override public boolean defines(String option) { return values.containsKey(option) || flags.containsKey(option); }
            @Override public String value(String option) { return values.get(option); }
            @Override public boolean enabled(String option, boolean fallback) { return flags.getOrDefault(option, fallback); }
        };
    }

    private static Map<String, String> with(Map<String, String> base, String option, String value) {
        Map<String, String> changed = new HashMap<>(base);
        changed.put(option, value);
        return changed;
    }

    private static FogModel.Env env(double cameraY, float rain, long time, LodDepth.Backend backend, float distance) {
        return FogModel.Env.of(cameraY, rain, time, backend, distance);
    }

    private static float[] sample(FogModel model, FogModel.Env env, double along, double y) {
        float[] out = new float[2];
        model.sample(env, along, y, out);
        return out;
    }

    @Test
    void packsAreRecognisedByTheirOptionsThenTheirName() {
        assertInstanceOf(FogModels.Complementary.class, FogModels.forPack(pack("renamed.zip", COMPLEMENTARY, Map.of())));
        assertInstanceOf(FogModels.Bsl.class, FogModels.forPack(pack("renamed.zip", BSL, Map.of())));
        assertInstanceOf(FogModels.Bsl.class, FogModels.forPack(pack("BSL_v10.1.8.zip", Map.of(), Map.of())));
        assertInstanceOf(FogModels.Photon.class, FogModels.forPack(pack("ambiance", PHOTON, Map.of())));
        assertInstanceOf(FogModels.Photon.class, FogModels.forPack(pack("photon_v1.3b.zip", Map.of(), Map.of())));
        FogModel unknown = FogModels.forPack(pack("SomePack", Map.of(), Map.of()));
        assertInstanceOf(FogModels.UnknownPack.class, unknown);
        assertTrue(unknown.measured(), "an unknown pack's fog is still measured from the image");
        assertNull(unknown.colour());
    }

    @Test
    void modelComesFromThePackOrElseFromTheLodMod() {
        FogModel own = new FogModels.Voxy(0.001f, 0, 1, 1, 0.5f, 0.6f, 0.7f);
        LodDepth.Layer lod = new LodDepth.Layer(1, new Matrix4f(), 1, false, LodDepth.Backend.VOXY, 4096, own);
        assertNull(EffectFog.model(null, null), "vanilla alone: the probe");
        assertSame(own, EffectFog.model(null, lod));
        EffectFog.PackOptions complementary = pack("x", COMPLEMENTARY, Map.of());
        FogModel first = EffectFog.model(complementary, lod);
        assertInstanceOf(FogModels.Complementary.class, first, "a pack's fog replaces the LOD mod's own");
        assertSame(first, EffectFog.model(complementary, lod), "one reading of the options per loaded pack");
        assertInstanceOf(FogModels.Bsl.class, EffectFog.model(pack("y", BSL, Map.of()), lod));
    }

    @Test
    void environmentIsRoundedSoAStillFrameKeepsItsTable() {
        assertEquals(env(100.1, 0.501f, NOON, null, 0), env(100.2, 0.499f, NOON + 2, null, 0));
        FogModel.Env morning = env(64, 0, SUNRISE, null, 0);
        assertTrue(morning.sunX() > 0.9f && morning.sunY() > 0 && morning.sunY() < 0.2f, morning.toString());
        assertEquals(-1f, env(64, 0, MIDNIGHT, null, 0).sunY());
    }

    @Test
    void complementaryBorderFadeIsLateAndSteepAndFollowsItsOptions() {
        FogModel model = FogModels.forPack(pack("x", COMPLEMENTARY, Map.of("BORDER_FOG", true)));
        FogModel.Env dh = env(100, 0, NOON, LodDepth.Backend.DISTANT_HORIZONS, 4096);
        assertTrue(sample(model, dh, 4096 * 0.36, 100)[1] < 0.06f, "terrain well inside the border is untouched");
        float previous = 0;
        for (double along = 0; along <= 4096 * 1.5; along += 64) {
            float fade = sample(model, dh, along, 100)[1];
            assertTrue(fade >= previous && fade <= 1, along + ": " + fade);
            previous = fade;
        }
        assertEquals(1 - Math.exp(-3), sample(model, dh, 4096, 100)[1], 1e-6);
        assertEquals(sample(model, dh, 3000, 100)[1], sample(model, dh, 100, 3100)[1], 1e-6, "height counts when it is the larger");
        assertEquals(1 - Math.exp(-3), sample(model, env(100, 0, NOON, LodDepth.Backend.VOXY, 4096), 3840, 100)[1], 1e-6,
                "the pack takes 256 blocks off Voxy's distance");
        assertEquals(0f, sample(model, env(100, 0, NOON, null, 0), 3000, 100)[1], "no LOD mod, no border");

        FogModel tuned = FogModels.forPack(pack("x", with(with(COMPLEMENTARY, "BORDER_FOG_DISTANCE_OVERWORLD", "10"),
                "BORDER_FOG_DENSITY_OVERWORLD", "0.50"), Map.of()));
        assertEquals(0.5 * (1 - Math.exp(-10 * Math.pow(0.5, 4))), sample(tuned, dh, 2048, 100)[1], 1e-6);
        for (String off : new String[] {"BORDER_FOG", "BORDER_FOG_OVERWORLD"}) {
            assertEquals(0f, sample(FogModels.forPack(pack("x", COMPLEMENTARY, Map.of(off, false))), dh, 4096, 100)[1], off);
        }
    }

    @Test
    void complementaryAtmosphereIsNearlyFullWithinAFewHundredBlocks() {
        FogModel model = FogModels.forPack(pack("x", COMPLEMENTARY, Map.of()));
        FogModel.Env clear = env(100, 0, NOON, LodDepth.Backend.VOXY, 4096);
        assertEquals(0f, sample(model, clear, 30, 100)[0], "nothing within 40 blocks");
        // Far terrain: full fog of 0.95 - 0.1 - 0.15, less the thinning for a camera 37 blocks above the fog's altitude.
        assertEquals(0.66f, sample(model, clear, 2000, 100)[0], 0.01f);
        assertEquals(sample(model, clear, 2000, 100)[0], sample(model, clear, 2000, 500)[0], 0.01f,
                "far away the fog no longer thins with height");
        assertTrue(sample(model, clear, 300, 100)[0] > sample(model, clear, 300, 200)[0], "near terrain is clearer higher up");
        assertEquals(0.85f, sample(model, env(100, 1, NOON, LodDepth.Backend.VOXY, 4096), 2000, 100)[0], 0.01f, "rain");
        FogModel thin = FogModels.forPack(pack("x", with(COMPLEMENTARY, "ATM_FOG_DISTANCE", "300"), Map.of()));
        assertTrue(sample(thin, clear, 200, 100)[0] < sample(model, clear, 200, 100)[0], "the pack's fog distance is followed");
        assertEquals(0f, sample(FogModels.forPack(pack("x", COMPLEMENTARY, Map.of("ATMOSPHERIC_FOG", false))), clear, 2000, 100)[0]);
        assertNull(model.colour(), "the colour is the pack's sky, taken from the image");
    }

    @Test
    void bslFogGrowsWithDistanceNightAndRainAndThinsWithHeight() {
        FogModel model = FogModels.forPack(pack("x", BSL, Map.of()));
        FogModel.Env noon = env(62, 0, NOON, LodDepth.Backend.DISTANT_HORIZONS, 4096);
        // 1024 blocks: fog 1, dampened to 0.625, 1 - exp(-2 * 0.625^1.6)
        assertEquals(1 - Math.exp(-2 * Math.pow(0.625, 1.6)), sample(model, noon, 1024, 62)[0], 1e-5);
        FogModel.Env night = env(62, 0, MIDNIGHT, LodDepth.Backend.DISTANT_HORIZONS, 4096);
        assertEquals(1 - Math.exp(-2 * Math.pow(1.375, 1.25)), sample(model, night, 1024, 62)[0], 1e-5, "four times denser at night");
        assertTrue(sample(model, env(62, 1, NOON, LodDepth.Backend.DISTANT_HORIZONS, 4096), 1024, 62)[0]
                > sample(model, noon, 1024, 62)[0], "denser in rain");
        assertTrue(sample(model, noon, 1024, 62 + 128)[0] < sample(model, noon, 1024, 62)[0] * 0.75f, "half the fog 128 blocks up");
        assertEquals(0f, sample(model, noon, 4000, 62)[1], "the pack's overworld border fade is off by default");

        FogModel bordered = FogModels.forPack(pack("x", with(BSL, "FAR_VANILLA_FOG", "1"), Map.of()));
        assertEquals(0f, sample(bordered, noon, 4096 * 0.6, 62)[1], 1e-6, "a linear ramp over the last 40%");
        assertEquals(0.5f, sample(bordered, noon, 4096 * 0.8, 62)[1], 1e-5);
        assertEquals(1f, sample(bordered, noon, 4096, 62)[1], 1e-6);
        FogModel halved = FogModels.forPack(pack("x", with(BSL, "FOG_DENSITY", "0.50"), Map.of()));
        assertTrue(sample(halved, noon, 600, 62)[0] < sample(model, noon, 600, 62)[0], "the pack's density is followed");
    }

    @Test
    void photonBorderFadeIsEarlySoftAndLiftedAboveTheHorizon() {
        FogModel model = FogModels.forPack(pack("photon_v1.3b.zip", Map.of(), Map.of()));
        FogModel.Env voxy = env(100, 0, NOON, LodDepth.Backend.VOXY, 4096);
        assertEquals(1 - Math.pow(2, -2.4), sample(model, voxy, 4096, 100)[1], 1e-6, "no margin for Voxy");
        assertEquals(1 - Math.pow(2, -2.4 * 0.25), sample(model, voxy, 2048, 100)[1], 1e-6);
        assertEquals(sample(model, voxy, 2048, 100)[1], sample(model, voxy, 2048, -900)[1], 1e-6, "measured along the ground only");
        assertEquals(0.25 * sample(model, voxy, 2048, 100)[1], sample(model, voxy, 2048, 2148)[1], 1e-6, "well above the horizon");
        assertEquals(0f, sample(FogModels.forPack(pack("photon", Map.of(), Map.of("BORDER_FOG", false))), voxy, 4096, 100)[1]);
    }

    @Test
    void photonMistFollowsTheTimeOfDayAndTheRainAndLiesLow() {
        FogModels.Photon model = (FogModels.Photon) FogModels.forPack(pack("photon", PHOTON, Map.of()));
        FogModel.Env noon = env(70, 0, NOON, LodDepth.Backend.VOXY, 4096);
        FogModel.Env morning = env(70, 0, SUNRISE, LodDepth.Backend.VOXY, 4096);
        FogModel.Env rain = env(70, 1, NOON, LodDepth.Backend.VOXY, 4096);
        assertTrue(model.mistDensity(morning) > 10 * model.mistDensity(noon), "mist in the morning, almost none at noon");
        assertEquals(0.03, model.mistDensity(rain), 1e-6);
        float atNoon = sample(model, noon, 1500, 70)[0];
        assertTrue(atNoon > 0.02f && atNoon < 0.5f, "a light haze at noon: " + atNoon);
        assertTrue(sample(model, morning, 1500, 70)[0] > atNoon + 0.3f, "thick in the morning");
        assertTrue(sample(model, rain, 1500, 70)[0] > 0.95f, "and in rain");
        float previous = 0;
        for (double along = 0; along <= 4000; along += 250) {
            float haze = sample(model, morning, along, 70)[0];
            assertTrue(haze >= previous, along + ": " + haze);
            previous = haze;
        }
        assertTrue(sample(model, env(400, 0, SUNRISE, LodDepth.Backend.VOXY, 4096), 1500, 400)[0] < 0.05f,
                "from high up to a point high up there is almost no air to look through");
        FogModel faint = FogModels.forPack(pack("photon", with(PHOTON, "OVERWORLD_FOG_INTENSITY", "0.10"), Map.of()));
        assertTrue(sample(faint, morning, 1500, 70)[0] < sample(model, morning, 1500, 70)[0] * 0.5f, "the pack's intensity is followed");
    }

    @Test
    void unknownPackOnlyAssumesComplementarysDefaultBorder() {
        FogModel unknown = new FogModels.UnknownPack();
        FogModel known = FogModels.forPack(pack("x", COMPLEMENTARY, Map.of()));
        FogModel.Env voxy = env(100, 0, NOON, LodDepth.Backend.VOXY, 4096);
        assertEquals(sample(known, voxy, 3000, 100)[1], sample(unknown, voxy, 3000, 100)[1]);
        assertEquals(0f, sample(unknown, voxy, 3000, 100)[0], "its haze is measured, not assumed");
    }

    @Test
    void distantHorizonsFarFogCoversAShareOfTheLodDistance() {
        FogModel linear = new FogModels.DistantHorizons(0, 0.4f, 1, 0, 1, 2.5f, 0, 1, true, true, true, 80, 0.4f, 1,
                4096, 320, 0.5f, 0.6f, 0.7f);
        FogModel.Env env = env(100, 0, NOON, LodDepth.Backend.DISTANT_HORIZONS, 4096);
        assertEquals(0f, sample(linear, env, 4096 * 0.4, 100)[0], 1e-6);
        assertEquals(0.5f, sample(linear, env, 4096 * 0.7, 100)[0], 1e-5);
        assertEquals(1f, sample(linear, env, 5000, 100)[0], 1e-6);
        assertEquals(0.5f, sample(linear, env, 4096 * 0.7, 900)[0], 1e-5, "mode 1 measures along the ground");
        assertArrayEquals(new float[] {0.5f, 0.6f, 0.7f}, linear.colour(), "DH's fog colour is known");
        assertEquals(0f, sample(linear, env, 3000, 100)[1], "no fade into the sky");

        FogModel spherical = new FogModels.DistantHorizons(0, 0.4f, 1, 0, 1, 2.5f, 0, 0, true, true, true, 80, 0.4f, 1,
                4096, 320, 0, 0, 0);
        assertTrue(sample(spherical, env, 4096 * 0.7, 900)[0] > 0.5f, "mode 0 measures along the view ray");

        FogModel squared = new FogModels.DistantHorizons(2, 0.4f, 1, 0.1f, 0.9f, 2.5f, 0, 1, true, true, true, 80,
                0.4f, 1, 4096, 320, 0, 0, 0);
        assertEquals(0.1f, sample(squared, env, 1000, 100)[0], 1e-6, "its minimum thickness everywhere");
        assertEquals(0.9 - 0.8 / Math.exp(1.25 * 1.25), sample(squared, env, 4096 * 0.7, 100)[0], 1e-5);

        // Height fog below a set height, mixed by taking the thicker of the two.
        FogModel height = new FogModels.DistantHorizons(0, 0.4f, 1, 0, 1, 2.5f, 0, 2, false, false, true, 200, 0, 0.5f,
                4096, 320, 0, 0, 0);
        assertEquals(0.25f, sample(height, env, 100, 160)[0], 1e-5, "40 of 320 blocks below, over half the height");
        assertEquals(0f, sample(height, env, 100, 240)[0], 1e-6, "none above it");
    }

    @Test
    void voxyFogIsTheGamesOwnCappedAtTheVanillaCorner() {
        FogModels.Voxy model = FogModels.Voxy.of(100, 1100, 512, 0.5f, 0.6f, 0.7f, 1);
        FogModel.Env env = env(100, 0, NOON, LodDepth.Backend.VOXY, 4096);
        assertEquals(0f, sample(model, env, 50, 100)[0], "before the game's fog starts");
        assertEquals(0.5f, sample(model, env, 600, 100)[0], 1e-5);
        float limit = (float) ((512 * Math.sqrt(3) - 100) / 1000);
        assertEquals(limit, sample(model, env, 3000, 100)[0], 1e-5, "no thicker over LOD terrain than at the vanilla corner");
        assertEquals(0.5f, sample(model, env, 0, 700)[0], 1e-5, "measured along the view ray");
        assertArrayEquals(new float[] {0.5f, 0.6f, 0.7f}, model.colour());
        assertEquals(0.25f, sample(FogModels.Voxy.of(100, 1100, 512, 0, 0, 0, 0.5f), env, 600, 100)[0], 1e-5);
        assertNull(FogModels.Voxy.of(100, 100.5f, 512, 0, 0, 0, 1), "Voxy draws no fog without a fog length");
        assertNull(FogModels.Voxy.of(100, 1100, 512, 0, 0, 0, 0));
    }

    @Test
    void tableCoversEveryEffectAndHoldsTheModelAtItsCellCentres() {
        WorldEffect.Bounds plume = new WorldEffect.Bounds(-353, 193, -2134, 137, 713, -1724);
        WorldEffect.Bounds fog = new WorldEffect.Bounds(-46, 67, -1550, 554, 197, -1050);
        FogTable.Range range = FogTable.range(0, 0, List.of(plume, fog));
        assertTrue(range.maxAlong() >= Math.hypot(353, 2134) && range.maxAlong() % 64 == 0, range.toString());
        assertTrue(range.minY() < 67 && range.maxY() > 713, range.toString());
        assertEquals(range, FogTable.range(3, -2, List.of(plume, fog)), "a few steps do not move it");

        FogModel model = FogModels.forPack(pack("x", COMPLEMENTARY, Map.of()));
        FogModel.Env env = env(100, 0, NOON, LodDepth.Backend.VOXY, 4096);
        float[] table = FogTable.build(model, env, range);
        assertEquals(FogTable.WIDTH * FogTable.HEIGHT * 2, table.length);
        int column = 70;
        int row = 5;
        float[] expected = sample(model, env, (column + 0.5) / FogTable.WIDTH * range.maxAlong(),
                range.minY() + (row + 0.5) / FogTable.HEIGHT * (range.maxY() - range.minY()));
        assertEquals(expected[0], table[(row * FogTable.WIDTH + column) * 2]);
        assertEquals(expected[1], table[(row * FogTable.WIDTH + column) * 2 + 1]);

        FogModel broken = new FogModel() {
            @Override public String name() { return "broken"; }
            @Override public void sample(Env e, double along, double y, float[] out) { out[0] = Float.NaN; out[1] = 7; }
        };
        float[] clamped = FogTable.build(broken, env, range);
        assertEquals(0f, clamped[0]);
        assertEquals(1f, clamped[1]);
    }
}
