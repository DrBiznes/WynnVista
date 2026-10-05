package me.jamino.wynndhrangelimiter.compat.voxy;

import me.jamino.wynndhrangelimiter.visibility.BlockRect;
import me.jamino.wynndhrangelimiter.visibility.MaskMode;
import me.jamino.wynndhrangelimiter.visibility.VisibilitySnapshot;

import java.util.List;

/** Pure packing of a visibility snapshot into the uniforms read by the patched Voxy fragment shader. */
public final class VoxyMaskUniforms {
    private VoxyMaskUniforms() {}

    /** A negative count disables clipping; zero clips every fragment. */
    public static int count(VisibilitySnapshot snapshot) {
        return snapshot.mode() == MaskMode.PASSTHROUGH ? -1 : snapshot.mask().rectangles().size();
    }

    /**
     * Rectangles as (minX, minZ, maxX, maxZ) relative to the block origin of Voxy's base section,
     * the same space as the pre-MVP quad corner position.
     */
    public static float[] pack(VisibilitySnapshot snapshot, long originX, long originZ) {
        float[] data = new float[VoxyShaderPatch.MAX_RECTS * 4];
        List<BlockRect> rects = snapshot.mask().rectangles();
        for (int i = 0; i < rects.size(); i++) {
            BlockRect r = rects.get(i);
            data[i * 4] = (float) (r.minX() - originX);
            data[i * 4 + 1] = (float) (r.minZ() - originZ);
            data[i * 4 + 2] = (float) (r.maxX() - originX);
            data[i * 4 + 3] = (float) (r.maxZ() - originZ);
        }
        return data;
    }
}
