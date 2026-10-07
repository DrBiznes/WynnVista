package me.jamino.wynndhrangelimiter.effects;

import me.jamino.wynndhrangelimiter.visibility.MaskMode;
import me.jamino.wynndhrangelimiter.visibility.RegionPolicy;
import me.jamino.wynndhrangelimiter.visibility.VisibilitySnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetherFogTest {
    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void layerCoversTheCorruptedGroundAndNeverReachesBelowItsFloor() {
        WorldEffect.Bounds box = new NetherFog().bounds();
        assertEquals(NetherFog.FLOOR_Y, box.minY(), "the portal's pit below the floor stays clear");
        assertEquals(NetherFog.FLOOR_Y + NetherFog.THICKNESS, box.maxY());
        assertEquals(NetherFog.CENTER_X - NetherFog.RADIUS_X, box.minX());
        assertEquals(NetherFog.CENTER_X + NetherFog.RADIUS_X, box.maxX());
        assertEquals(NetherFog.CENTER_Z - NetherFog.RADIUS_Z, box.minZ());
        assertEquals(NetherFog.CENTER_Z + NetherFog.RADIUS_Z, box.maxZ());
        // The Roots of Corruption territory's Nether portal side lies well inside.
        assertTrue(box.minX() < 171 && box.maxX() > 353 && box.minZ() < -1345 && box.maxZ() > -1254);
    }

    @Test
    void fogIsShownWithTheMainMapTerrainUnderIt() {
        NetherFog fog = new NetherFog();
        VisibilitySnapshot main = new VisibilitySnapshot("play.wynncraft.com", OVERWORLD, MaskMode.MAIN,
                RegionPolicy.mask(MaskMode.MAIN), 1);
        VisibilitySnapshot light = new VisibilitySnapshot("play.wynncraft.com", OVERWORLD, MaskMode.LIGHT,
                RegionPolicy.mask(MaskMode.LIGHT), 1);
        assertTrue(EffectRegion.shows(main, true, OVERWORLD, 171, -1291, fog.anchorX(), fog.anchorZ()));
        assertFalse(EffectRegion.shows(light, true, OVERWORLD, 171, -1291, fog.anchorX(), fog.anchorZ()));
        assertFalse(EffectRegion.shows(main, false, OVERWORLD, 171, -1291, fog.anchorX(), fog.anchorZ()));
    }

    @Test
    void driftingPatternWrapsWithoutAJump() {
        // 32000 ticks at 1.2 blocks/s is exactly three repeats of the noise pattern.
        assertEquals(0.0, 32000 / 20.0 * NetherFog.DRIFT_SPEED / NetherFog.NOISE_PERIOD % 1.0, 1e-9);
        assertEquals(NetherFog.drift(0, 0.5f), NetherFog.drift(32000, 0.5f), 1e-6);
        assertEquals(NetherFog.drift(100, 0), NetherFog.drift(-31900, 0), 1e-6);
        float step = NetherFog.drift(201, 0) - NetherFog.drift(200, 0);
        assertEquals(NetherFog.DRIFT_SPEED / NetherFog.NOISE_PERIOD / 20.0, step, 1e-6);
        for (long time = 0; time < 32000; time += 777) {
            float phase = NetherFog.drift(time, 0);
            assertTrue(phase >= 0 && phase < 1, "time " + time);
        }
    }

    @Test
    void lavaGlowIsStrongestAtNightAndSurvivesDaylight() {
        float noon = NetherFog.emission(SmokePlume.lighting(6000, 0).glow());
        float midnight = NetherFog.emission(SmokePlume.lighting(18000, 0).glow());
        assertEquals(1.0f, midnight, 1e-4);
        assertTrue(noon < midnight && noon >= 0.5f);
    }
}
