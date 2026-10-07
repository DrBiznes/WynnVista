package me.jamino.wynndhrangelimiter.effects;

import me.jamino.wynndhrangelimiter.visibility.BlockRect;
import me.jamino.wynndhrangelimiter.visibility.MaskMode;
import me.jamino.wynndhrangelimiter.visibility.RegionPolicy;
import me.jamino.wynndhrangelimiter.visibility.VisibilityMask;
import me.jamino.wynndhrangelimiter.visibility.VisibilitySnapshot;

/** Ties world effects to the LOD region mask: an effect exists only where the terrain it stands on is shown. */
public final class EffectRegion {
    private EffectRegion() {}

    /**
     * @param snapshot   the mask the LOD renderers are using this frame
     * @param recognized Wynncraft or the local fixture; effects never appear anywhere else
     */
    public static boolean shows(VisibilitySnapshot snapshot, boolean recognized, String dimension,
                                double playerX, double playerZ, double anchorX, double anchorZ) {
        if (!recognized) return false;
        VisibilityMask mask = snapshot.mask();
        if (snapshot.mode() == MaskMode.PASSTHROUGH) {
            // LOD masking is switched off; follow the region the mask would have selected.
            mask = RegionPolicy.mask(RegionPolicy.select(true, dimension, playerX, playerZ));
        }
        for (BlockRect allowed : mask.rectangles()) {
            if (allowed.contains(anchorX, anchorZ)) return true;
        }
        return false;
    }
}
