package me.jamino.wynndhrangelimiter.visibility;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixtureCustomMaskTest {
    @AfterEach
    void clear() {
        RegionPolicy.setFixtureCustomRect(null);
    }

    @Test
    void unsetCustomRectangleClipsEverything() {
        assertTrue(RegionPolicy.mask(MaskMode.FIXTURE_CUSTOM).rectangles().isEmpty());
        assertEquals(VisibilityMask.Classification.OUTSIDE,
                RegionPolicy.mask(MaskMode.FIXTURE_CUSTOM).classify(0, 0, 32));
    }

    @Test
    void customRectangleSplitsASectionExactly() {
        RegionPolicy.setFixtureCustomRect(BlockRect.fromInclusive(-1111, -6616, -901, -5815));
        VisibilityMask mask = RegionPolicy.mask(MaskMode.FIXTURE_CUSTOM);
        // A 512-block LOD cell straddling x = -900 is mixed; cells wholly on either side are not.
        assertEquals(VisibilityMask.Classification.INTERSECTING, mask.classify(-1024, -6144, 512));
        assertEquals(VisibilityMask.Classification.INSIDE, mask.classify(-1088, -6144, 128));
        assertEquals(VisibilityMask.Classification.OUTSIDE, mask.classify(-900, -6144, 128));
    }
}
