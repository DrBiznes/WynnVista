package me.jamino.wynndhrangelimiter.effects;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectFogTest {
    @Test
    void bandSurroundsTheEffectDistance() {
        EffectFog.Band band = EffectFog.band(2000);
        assertEquals(1000f, band.near());
        assertEquals(3000f, band.far());
    }

    @Test
    void bandNeverCollapsesOntoTheCamera() {
        EffectFog.Band inside = EffectFog.band(0);
        assertEquals(EffectFog.band(EffectFog.MIN_DISTANCE), inside);
        assertTrue(inside.near() > 16, "the hand and the block underfoot are not scenery: " + inside);
    }

    @Test
    void columnsWidenAroundTheEffectAndStayInView() {
        EffectFog.Columns centred = EffectFog.columns(new EffectCulling.ScreenRect(0.4f, 0.3f, 0.6f, 0.9f));
        assertEquals(0.3f, centred.min(), 1e-6);
        assertEquals(0.7f, centred.max(), 1e-6);

        EffectFog.Columns sliver = EffectFog.columns(new EffectCulling.ScreenRect(0.50f, 0.5f, 0.51f, 0.6f));
        assertTrue(sliver.max() - sliver.min() >= EffectFog.MIN_WIDTH, "a distant effect still gets terrain: " + sliver);

        assertEquals(new EffectFog.Columns(0, 1), EffectFog.columns(EffectCulling.ScreenRect.FULL));
    }

    @Test
    void rateFollowsFrameTimeAndRestartsAfterAGap() {
        float at60 = EffectFog.rate(1 / 60.0);
        float at30 = EffectFog.rate(1 / 30.0);
        assertTrue(at60 > 0 && at60 < at30 && at30 < 0.1f, at60 + " " + at30);
        // Two short frames move the value as far as one frame twice as long.
        assertEquals(at30, 1 - (1 - at60) * (1 - at60), 1e-6);
        assertEquals(1f, EffectFog.rate(EffectFog.RESTART_SECONDS + 0.1));
        assertEquals(1f, EffectFog.rate(-1));
    }
}
