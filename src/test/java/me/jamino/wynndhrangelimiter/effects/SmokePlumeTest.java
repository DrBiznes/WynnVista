package me.jamino.wynndhrangelimiter.effects;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokePlumeTest {
    @Test
    void boundsContainTheWholeBentColumn() {
        WorldEffect.Bounds box = new SmokePlume().bounds();
        assertTrue(box.minY() < SmokePlume.PEAK_Y && box.maxY() == SmokePlume.VENT_Y + SmokePlume.HEIGHT);
        assertTrue(box.minX() <= SmokePlume.PEAK_X - SmokePlume.TOP_RADIUS);
        assertTrue(box.maxX() >= SmokePlume.PEAK_X + SmokePlume.DRIFT_X + SmokePlume.TOP_RADIUS);
        assertTrue(box.maxZ() >= SmokePlume.PEAK_Z + SmokePlume.DRIFT_Z + SmokePlume.TOP_RADIUS);
    }

    @Test
    void everyStyleHasAShaderBuiltOnTheSharedColumn() {
        for (SmokePlume.Style style : SmokePlume.Style.values()) {
            String source = EffectProgram.source(style.shader());
            assertTrue(source.contains("float density(vec3 q, int octaves)"), style.name());
            assertTrue(!source.contains("#include"), style.name());
        }
    }

    @Test
    void sunRisesInTheEastAndTheMoonTakesOverAtNight() {
        SmokePlume.Lighting noon = SmokePlume.lighting(6000, 0);
        assertTrue(noon.dirY() > 0.9f, "noon light comes from above");
        assertTrue(SmokePlume.lighting(1500, 0).dirX() > 0.5f, "morning light comes from the east");
        assertTrue(SmokePlume.lighting(10500, 0).dirX() < -0.5f, "evening light comes from the west");

        SmokePlume.Lighting midnight = SmokePlume.lighting(18000, 0);
        assertTrue(midnight.dirY() > 0.9f, "the moon is overhead at midnight");
        assertTrue(midnight.red() < 0.2f && midnight.blue() > midnight.red(), "moonlight is dim and blue");
        assertTrue(midnight.glow() > noon.glow(), "the crater glow shows at night");
        assertTrue(SmokePlume.lighting(6000, 1).green() < noon.green(), "rain dims the sun");
    }

    @Test
    void lightDirectionIsAlwaysAUnitVectorAboveTheHorizon() {
        for (long time = 0; time < 24000; time += 250) {
            SmokePlume.Lighting light = SmokePlume.lighting(time, 0);
            double length = Math.sqrt(light.dirX() * light.dirX() + light.dirY() * light.dirY()
                    + light.dirZ() * light.dirZ());
            assertEquals(1.0, length, 1e-4, "time " + time);
            assertTrue(light.dirY() > 0, "time " + time);
        }
    }

    @Test
    void risingPatternWrapsWithoutAJump() {
        // 32000 ticks at 4.8 blocks/s is exactly six repeats of the noise pattern.
        assertEquals(0.0, 32000 / 20.0 * SmokePlume.RISE_SPEED % SmokePlume.SCROLL_PERIOD, 1e-9);
        assertEquals(SmokePlume.scroll(0, 0.5f), SmokePlume.scroll(32000, 0.5f), 1e-4);
        assertEquals(SmokePlume.scroll(100, 0), SmokePlume.scroll(-31900, 0), 1e-4);
        float step = SmokePlume.scroll(201, 0) - SmokePlume.scroll(200, 0);
        assertEquals(SmokePlume.RISE_SPEED / 20.0, step, 1e-3);
    }

    @Test
    void packSunPathTiltsTheLightOutOfTheEastWestPlane() {
        SmokePlume.Lighting flat = SmokePlume.lighting(6000, 0, true, 0);
        assertEquals(0f, flat.dirZ(), 1e-6, "an untilted pack sun passes straight overhead");
        assertEquals(1f, flat.dirY(), 1e-6);

        SmokePlume.Lighting tilted = SmokePlume.lighting(6000, 0, true, -40);
        assertEquals(Math.sin(Math.toRadians(40)), tilted.dirZ(), 1e-5, "a negative rotation leans the noon sun south");
        assertEquals(Math.cos(Math.toRadians(40)), tilted.dirY(), 1e-5);
        assertTrue(SmokePlume.lighting(6000, 0, true, 40).dirZ() < -0.6f, "and a positive one north");

        SmokePlume.Lighting moon = SmokePlume.lighting(18000, 0, true, -40);
        assertEquals(tilted.dirZ(), moon.dirZ(), 1e-5, "the moon is opposite the sun, so it leans the same way at midnight");
        assertTrue(moon.dirY() > 0.7f);

        SmokePlume.Lighting morning = SmokePlume.lighting(1500, 0, true, -40);
        assertTrue(morning.dirX() > 0.5f, "still from the east in the morning");
        for (long time = 0; time < 24000; time += 500) {
            SmokePlume.Lighting light = SmokePlume.lighting(time, 0, true, -40);
            assertEquals(1.0, Math.sqrt(light.dirX() * light.dirX() + light.dirY() * light.dirY()
                    + light.dirZ() * light.dirZ()), 1e-5, "unit direction at " + time);
            assertTrue(light.dirY() > 0, "light from above at " + time);
        }
        assertEquals(SmokePlume.lighting(6000, 0), SmokePlume.lighting(6000, 0, false, -40), "without a pack the path is fixed");
    }

    @Test
    void skyReferenceFollowsTheLightSoTheMatchKeepsItsShape() {
        float noon = SmokePlume.skyReference(SmokePlume.lighting(6000, 0));
        float midnight = SmokePlume.skyReference(SmokePlume.lighting(18000, 0));
        assertTrue(noon > 0.7f && noon < 1.1f, "a sunlit plume is about as bright as a daytime horizon: " + noon);
        assertTrue(midnight > 0.05f && midnight < 0.2f, "and dim under the moon: " + midnight);
        assertTrue(SmokePlume.skyReference(SmokePlume.lighting(6000, 1)) < noon, "dimmer in rain");
    }

    @Test
    void skyIsComparedWithTheReferenceOfTheTimeItWasSeen() {
        // The probe keeps the reference beside the sky's colour, and the effects divide by that one.
        String probe = EffectProgram.source("fog_probe.fsh");
        assertTrue(probe.contains("vec4 seen = vec4(colour / count, uReference);"));
        assertTrue(probe.contains("skyGain = clamp(seen / max(sky.a, 1.0e-3), GAIN_MIN, GAIN_MAX);"));
    }
}
