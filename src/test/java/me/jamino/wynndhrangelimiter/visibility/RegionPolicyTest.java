package me.jamino.wynndhrangelimiter.visibility;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RegionPolicyTest {
    @Test
    void suppliedCornersAndOneBlockGap() {
        assertEquals(MaskMode.MAIN, select(-2518, -60));
        assertEquals(MaskMode.MAIN, select(1628, -5813));
        assertEquals(MaskMode.LIGHT, select(-636, -6616));
        assertEquals(MaskMode.LIGHT, select(-1111, -5815));
        assertEquals(MaskMode.VOID_OUTER, select(13393, -3195));
        assertEquals(MaskMode.VOID_OUTER, select(14380, -4704));
        assertEquals(MaskMode.LIGHT, select(-800, -5815));
        assertEquals(MaskMode.NONE, select(-800, -5814));
        assertEquals(MaskMode.MAIN, select(-800, -5813));
    }

    @Test
    void halfOpenEdgesAndFractions() {
        assertEquals(MaskMode.MAIN, select(1628.999, -60.001));
        assertEquals(MaskMode.NONE, select(1629, -60));
        assertEquals(MaskMode.NONE, select(0, -59));
        assertEquals(MaskMode.NONE, select(-635, -6000));
        assertEquals(MaskMode.NONE, select(-800, -5814));
        assertEquals(MaskMode.NONE, select(13392.999, -4000));
        assertEquals(MaskMode.NONE, select(14381, -4000));
        assertEquals(MaskMode.NONE, select(14000, -4705));
        assertEquals(MaskMode.NONE, select(14000, -3194));
        assertEquals(MaskMode.NONE, select(Double.NaN, -4000));
    }

    @Test
    void contextAndHostIsolation() {
        assertTrue(RegionPolicy.isWynncraftHost("play.wynncraft.com:25565"));
        assertTrue(RegionPolicy.isWynncraftHost("WYNNCRAFT.COM."));
        assertFalse(RegionPolicy.isWynncraftHost("wynncraft.com.example.org"));
        assertFalse(RegionPolicy.isWynncraftHost("notwynncraft.com"));
        assertEquals(MaskMode.PASSTHROUGH,
                RegionPolicy.select(false, "minecraft:overworld", 0, -1000));
        assertEquals(MaskMode.NONE,
                RegionPolicy.select(true, "minecraft:the_nether", 0, -1000));
    }

    @Test
    void sectionClassificationRetainsMixedEdges() {
        VisibilityMask main = RegionPolicy.mask(MaskMode.MAIN);
        assertEquals(VisibilityMask.Classification.INSIDE, main.classify(-2000, -4000, 64));
        assertEquals(VisibilityMask.Classification.OUTSIDE, main.classify(2000, -4000, 64));
        assertEquals(VisibilityMask.Classification.INTERSECTING, main.classify(1600, -4000, 64));
        assertEquals(VisibilityMask.Classification.OUTSIDE, main.classify(1629, -4000, 64));
        assertEquals(VisibilityMask.Classification.INTERSECTING,
                main.classify(new BlockRect(-3000, 2000, -7000, 100)));
        assertEquals(VisibilityMask.Classification.OUTSIDE,
                RegionPolicy.mask(MaskMode.NONE).classify(-2000, -4000, 64));
        assertEquals(VisibilityMask.Classification.INSIDE,
                RegionPolicy.mask(MaskMode.PASSTHROUGH).classify(2000, -4000, 64));
    }

    @Test
    void adjacentRectanglesCoverASectionTogether() {
        VisibilityMask union = new VisibilityMask(MaskMode.MAIN,
                List.of(new BlockRect(0, 32, 0, 64), new BlockRect(32, 64, 0, 64)));
        assertEquals(VisibilityMask.Classification.INSIDE, union.classify(0, 0, 64));
        assertThrows(ArithmeticException.class, () -> union.classify(Long.MAX_VALUE, 0, 64));
    }

    @Test
    void cornerCoverageDoesNotHideAnInteriorGap() {
        VisibilityMask union = new VisibilityMask(MaskMode.MAIN, List.of(
                new BlockRect(0, 16, 0, 64), new BlockRect(48, 64, 0, 64)));
        assertEquals(VisibilityMask.Classification.INTERSECTING, union.classify(0, 0, 64));
        assertEquals(VisibilityMask.Classification.OUTSIDE, union.classify(16, 0, 32));
    }

    @Test
    void worldChangesAndDisconnectResetWithoutMutatingCapturedState() {
        VisibilityService.reset();
        VisibilitySnapshot captured = VisibilityService.publish("fixture-a", "minecraft:overworld", MaskMode.MAIN);
        VisibilitySnapshot other = VisibilityService.publish("fixture-b", "minecraft:overworld", MaskMode.NONE);
        assertTrue(other.revision() > captured.revision());
        assertEquals(MaskMode.NONE, other.mode());
        VisibilityService.reset();
        assertEquals(MaskMode.PASSTHROUGH, VisibilityService.current().mode());
        assertEquals("", VisibilityService.current().worldToken());
        assertEquals(MaskMode.MAIN, captured.mode());
        assertEquals("fixture-a", captured.worldToken());
    }

    private static MaskMode select(double x, double z) {
        return RegionPolicy.select(true, "minecraft:overworld", x, z);
    }
}
