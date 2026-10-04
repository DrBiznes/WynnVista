package me.jamino.wynndhrangelimiter.visibility;

public record VisibilitySnapshot(String worldToken, String dimension, MaskMode mode,
                                 VisibilityMask mask, long revision) {
    public static VisibilitySnapshot passthrough() {
        return new VisibilitySnapshot("", "", MaskMode.PASSTHROUGH,
                RegionPolicy.mask(MaskMode.PASSTHROUGH), 0);
    }
}
