package me.jamino.wynndhrangelimiter.visibility;

import java.util.List;
import java.util.Locale;

public final class RegionPolicy {
    public static final BlockRect MAIN = BlockRect.fromInclusive(-2518, -60, 1628, -5813);
    public static final BlockRect LIGHT = BlockRect.fromInclusive(-636, -6616, -1111, -5815);
    public static final BlockRect VOID_OUTER = BlockRect.fromInclusive(13393, -3195, 14380, -4704);

    private RegionPolicy() {}

    public static boolean isWynncraftHost(String address) {
        if (address == null) return false;
        String host = address.trim().toLowerCase(Locale.ROOT);
        int colon = host.lastIndexOf(':');
        if (colon >= 0 && host.indexOf(':') == colon) host = host.substring(0, colon);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host.equals("wynncraft.com") || host.endsWith(".wynncraft.com");
    }

    public static MaskMode select(boolean recognized, String dimension, double x, double z) {
        if (!recognized) return MaskMode.PASSTHROUGH;
        if (!"minecraft:overworld".equals(dimension)) return MaskMode.NONE;
        if (MAIN.contains(x, z)) return MaskMode.MAIN;
        if (LIGHT.contains(x, z)) return MaskMode.LIGHT;
        if (VOID_OUTER.contains(x, z)) return MaskMode.VOID_OUTER;
        return MaskMode.NONE;
    }

    public static VisibilityMask mask(MaskMode mode) {
        return switch (mode) {
            case PASSTHROUGH, NONE -> new VisibilityMask(mode, List.of());
            case MAIN -> new VisibilityMask(mode, List.of(MAIN));
            case LIGHT -> new VisibilityMask(mode, List.of(LIGHT));
            case VOID_OUTER -> new VisibilityMask(mode, List.of(VOID_OUTER));
        };
    }
}
