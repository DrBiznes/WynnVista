package me.jamino.wynndhrangelimiter.effects;

import me.jamino.wynndhrangelimiter.visibility.BlockRect;
import me.jamino.wynndhrangelimiter.visibility.MaskMode;
import me.jamino.wynndhrangelimiter.visibility.RegionPolicy;
import me.jamino.wynndhrangelimiter.visibility.VisibilitySnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectRegionTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final double RAGNI_X = -759, RAGNI_Z = -1588;
    private static final double LIGHT_X = -800, LIGHT_Z = -6100;

    @AfterEach
    void clearFixtureRect() {
        RegionPolicy.setFixtureCustomRect(null);
    }

    private static VisibilitySnapshot snapshot(MaskMode mode) {
        return new VisibilitySnapshot("play.wynncraft.com", OVERWORLD, mode, RegionPolicy.mask(mode), 1);
    }

    private static boolean plume(VisibilitySnapshot snapshot, boolean recognized, String dimension, double x, double z) {
        return EffectRegion.shows(snapshot, recognized, dimension, x, z, SmokePlume.PEAK_X, SmokePlume.PEAK_Z);
    }

    @Test
    void plumeIsShownExactlyWhenMainMapTerrainIs() {
        assertTrue(plume(snapshot(MaskMode.MAIN), true, OVERWORLD, RAGNI_X, RAGNI_Z));
        assertFalse(plume(snapshot(MaskMode.LIGHT), true, OVERWORLD, LIGHT_X, LIGHT_Z), "Realm of Light");
        assertFalse(plume(snapshot(MaskMode.VOID_OUTER), true, OVERWORLD, 14000, -4000), "Void");
        assertFalse(plume(snapshot(MaskMode.NONE), true, OVERWORLD, 5000, 5000), "unlisted area");
    }

    @Test
    void theMaskDecidesEvenWhenThePlayerStandsOnTheMainMap() {
        // A fixture override (or any future mask rule) hides the plume together with the terrain under it.
        assertFalse(plume(snapshot(MaskMode.LIGHT), true, OVERWORLD, RAGNI_X, RAGNI_Z));
        assertFalse(plume(snapshot(MaskMode.NONE), true, OVERWORLD, RAGNI_X, RAGNI_Z));
    }

    @Test
    void customMaskHidesThePlumeWhenItCutsTheMountainAway() {
        RegionPolicy.setFixtureCustomRect(BlockRect.fromInclusive(-30000000, -30000000, -901, 30000000));
        assertFalse(plume(snapshot(MaskMode.FIXTURE_CUSTOM), true, OVERWORLD, RAGNI_X, RAGNI_Z));
        RegionPolicy.setFixtureCustomRect(BlockRect.fromInclusive(-1000, -3000, 500, -1000));
        assertTrue(plume(snapshot(MaskMode.FIXTURE_CUSTOM), true, OVERWORLD, RAGNI_X, RAGNI_Z));
    }

    @Test
    void withMaskingOffTheSameRegionRuleStillApplies() {
        VisibilitySnapshot off = VisibilitySnapshot.passthrough();
        assertTrue(plume(off, true, OVERWORLD, RAGNI_X, RAGNI_Z));
        assertTrue(plume(off, true, OVERWORLD, 1628, -5813), "far main-map corner");
        assertFalse(plume(off, true, OVERWORLD, LIGHT_X, LIGHT_Z), "Realm of Light");
        assertFalse(plume(off, true, OVERWORLD, 14000, -4000), "Void");
        assertFalse(plume(off, true, "minecraft:the_nether", RAGNI_X, RAGNI_Z), "other dimension");
    }

    @Test
    void neverOutsideWynncraft() {
        assertFalse(plume(VisibilitySnapshot.passthrough(), false, OVERWORLD, RAGNI_X, RAGNI_Z));
        assertFalse(plume(snapshot(MaskMode.MAIN), false, OVERWORLD, RAGNI_X, RAGNI_Z));
    }
}
