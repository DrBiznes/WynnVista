package me.jamino.wynndhrangelimiter.visibility;

import java.util.concurrent.atomic.AtomicReference;

public final class VisibilityService {
    private static final AtomicReference<VisibilitySnapshot> CURRENT =
            new AtomicReference<>(VisibilitySnapshot.passthrough());

    private VisibilityService() {}

    public static VisibilitySnapshot current() {
        return CURRENT.get();
    }

    public static VisibilitySnapshot publish(String worldToken, String dimension, MaskMode mode) {
        return CURRENT.updateAndGet(old -> {
            if (old.worldToken().equals(worldToken) && old.dimension().equals(dimension)
                    && old.mode() == mode) return old;
            return new VisibilitySnapshot(worldToken, dimension, mode,
                    RegionPolicy.mask(mode), old.revision() + 1);
        });
    }

    public static void reset() {
        publish("", "", MaskMode.PASSTHROUGH);
    }
}
