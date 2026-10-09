package me.jamino.wynndhrangelimiter.effects;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackWaterTest {
    private static final Map<String, String> COMPLEMENTARY = Map.of("BORDER_FOG_DISTANCE_OVERWORLD", "3",
            "ATM_FOG_DISTANCE", "100");
    private static final Map<String, String> BSL = Map.of("FOG_DENSITY_NIGHT", "1.0", "FAR_VANILLA_FOG", "0");
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

    @Test
    void waterIsAMirrorAtAGrazingViewAndReflectsLittleFromAbove() {
        PackWater water = PackWater.forPack(pack("Some Pack", Map.of(), Map.of()));
        assertNotNull(water, "a pack that is not recognised is taken to reflect");
        assertEquals(1.0f, water.reflectance(0), 1e-6f);
        assertEquals(0.02f, water.reflectance(1), 1e-6f);
        assertEquals(0.02f + 0.98f / 32, water.reflectance(0.5), 1e-5f);
        assertTrue(water.reflectance(0.2) > water.reflectance(0.3));
    }

    @Test
    void complementaryReflectsMoreFromAboveAndFollowsItsMultiplier() {
        PackWater water = PackWater.forPack(pack("ComplementaryReimagined", COMPLEMENTARY, Map.of()));
        assertEquals(0.15f, water.reflectance(1), 1e-6f);
        assertEquals(0.15f + 0.85f / 8, water.reflectance(0.5), 1e-5f);
        assertEquals(1.0f, water.reflectance(0), 1e-6f);

        PackWater halved = PackWater.forPack(pack("ComplementaryReimagined",
                with(COMPLEMENTARY, "FRESNEL_MULTIPLIER", "0.5"), Map.of()));
        assertEquals(0.5f, halved.reflectance(0), 1e-6f);
        PackWater doubled = PackWater.forPack(pack("ComplementaryReimagined",
                with(COMPLEMENTARY, "FRESNEL_MULTIPLIER", "2.0"), Map.of()));
        assertEquals(1.0f, doubled.reflectance(0), 1e-6f, "the pack never reflects more than everything");
    }

    @Test
    void packsWithReflectionsSwitchedOffHaveNoWater() {
        assertNull(PackWater.forPack(pack("ComplementaryReimagined",
                with(COMPLEMENTARY, "WATER_REFLECT_QUALITY", "-1"), Map.of())));
        assertNotNull(PackWater.forPack(pack("ComplementaryReimagined",
                with(COMPLEMENTARY, "WATER_REFLECT_QUALITY", "0"), Map.of())), "sky reflections only still reflect");
        assertNull(PackWater.forPack(pack("BSL_v10.1.8", with(BSL, "REFLECTION", "0"), Map.of())));
        assertNotNull(PackWater.forPack(pack("BSL_v10.1.8", with(BSL, "REFLECTION", "1"), Map.of())));
        assertNull(PackWater.forPack(pack("photon_v1.3b", PHOTON,
                Map.of("ENVIRONMENT_REFLECTIONS", false, "SKY_REFLECTIONS", false))));
        assertNotNull(PackWater.forPack(pack("photon_v1.3b", PHOTON, Map.of("ENVIRONMENT_REFLECTIONS", false))));
        assertNotNull(PackWater.forPack(pack("photon_v1.3b", Map.of(), Map.of())), "recognised by name alone");
    }
}
