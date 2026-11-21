package me.jamino.wynndhrangelimiter.effects;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectAreasTest {
    @Test
    void configKeysAreUnique() {
        Set<String> areas = new HashSet<>();
        Set<String> effects = new HashSet<>();
        for (EffectArea area : EffectAreas.all()) {
            assertTrue(areas.add(area.id()), area.id());
            assertFalse(area.name().isBlank());
            assertFalse(area.effects().isEmpty(), area.id());
            for (WorldEffect effect : area.effects()) {
                assertTrue(effects.add(effect.id()), effect.id());
                Set<String> options = new HashSet<>();
                for (EffectOption option : effect.options()) {
                    assertTrue(options.add(option.id()), effect.id() + "." + option.id());
                    assertFalse(option.name().isBlank());
                    assertFalse(option.description().isBlank());
                }
            }
        }
    }

    @Test
    void areasHoldTheEffectsOfTheirPartOfTheMap() {
        assertEquals(List.of(SmokePlume.ID, NetherFog.ID), ids(EffectAreas.WYNN_PLAINS));
        assertEquals(List.of(SkyIslandsVoid.ID, SkyIslandsAir.ID), ids(EffectAreas.SKY_ISLANDS));
        assertEquals(List.of(MistwoodsMist.ID), ids(EffectAreas.FRUMA));
    }

    @Test
    void aChoiceFallsBackToOneOfItsOwnValues() {
        for (EffectArea area : EffectAreas.all()) {
            for (WorldEffect effect : area.effects()) {
                for (EffectOption option : effect.options()) {
                    if (option instanceof EffectOption.Choice<?> choice) {
                        assertTrue(choice.type().isInstance(choice.fallback()), effect.id() + "." + option.id());
                        assertTrue(choice.type().getEnumConstants().length > 1);
                    }
                }
            }
        }
    }

    @Test
    void skyIslandsPartsHaveASwitchTheShaderReads() {
        // The clouds of the void are left out by both styles without leaving the void out.
        for (SkyIslandsVoid.Style style : SkyIslandsVoid.Style.values()) {
            String source = EffectProgram.source(style.shader());
            assertTrue(source.contains("uniform float uClouds;"), style.name());
            assertTrue(source.contains("if (uClouds > 0.5 && abs(dir.y) > 1.0e-4)"), style.name());
        }
        assertTrue(new SkyIslandsVoid().options().contains(SkyIslandsVoid.CLOUDS));
        // Every kind of updraft and mote is a part of its own.
        String air = EffectProgram.source(new SkyIslandsAir().shader());
        assertTrue(air.contains("uniform vec3 uShow;"));
        for (String read : List.of("uShow.x < 0.5", "uShow.y < 0.5", "uShow.z > 0.5", "uWind.z > 0.0",
                "uStars.w > 0.0", "uSparkles.w > 0.0")) {
            assertTrue(air.contains(read), read);
        }
        assertEquals(List.of(SkyIslandsAir.UPDRAFTS, SkyIslandsAir.TEMPLE_WIND, SkyIslandsAir.MOTES,
                SkyIslandsAir.STARS, SkyIslandsAir.SPARKLES, SkyIslandsAir.SPRAY), new SkyIslandsAir().options());
    }

    private static List<String> ids(EffectArea area) {
        return area.effects().stream().map(WorldEffect::id).toList();
    }
}
