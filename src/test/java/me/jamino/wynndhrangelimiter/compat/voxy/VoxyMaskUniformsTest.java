package me.jamino.wynndhrangelimiter.compat.voxy;

import me.jamino.wynndhrangelimiter.visibility.MaskMode;
import me.jamino.wynndhrangelimiter.visibility.RegionPolicy;
import me.jamino.wynndhrangelimiter.visibility.VisibilitySnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VoxyMaskUniformsTest {
    private static VisibilitySnapshot snapshot(MaskMode mode) {
        return new VisibilitySnapshot("token", "minecraft:overworld", mode, RegionPolicy.mask(mode), 1);
    }

    @Test
    void countDistinguishesPassthroughFromEmptyMask() {
        assertEquals(-1, VoxyMaskUniforms.count(snapshot(MaskMode.PASSTHROUGH)));
        assertEquals(0, VoxyMaskUniforms.count(snapshot(MaskMode.NONE)));
        assertEquals(1, VoxyMaskUniforms.count(snapshot(MaskMode.MAIN)));
    }

    @Test
    void rectanglesAreRelativeToBaseSectionOrigin() {
        // Camera in section (-10, -100): block origin (-320, -3200).
        float[] data = VoxyMaskUniforms.pack(snapshot(MaskMode.MAIN), -320, -3200);
        assertEquals(32, data.length);
        assertArrayEquals(new float[] {-2518 + 320, -5813 + 3200, 1629 + 320, -59 + 3200},
                java.util.Arrays.copyOf(data, 4));
        for (int i = 4; i < data.length; i++) assertEquals(0f, data[i]);
    }

    @Test
    void voidRegionKeepsPrecisionFarFromTheCamera() {
        // Camera over the main map, Void region ~14000 blocks away: block-exact in float.
        float[] data = VoxyMaskUniforms.pack(snapshot(MaskMode.VOID_OUTER), 0, -3200);
        assertEquals(13393f, data[0]);
        assertEquals(-4704f + 3200, data[1]);
        assertEquals(14381f, data[2]);
        assertEquals(-3194f + 3200, data[3]);
    }
}
