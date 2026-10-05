package me.jamino.wynndhrangelimiter.compat.dh.iris;

/** Tracks whether Iris-built DH terrain programs carry the mask, so the render-list filter knows if mixed sections are safe. */
public final class DhIrisMaskState {
    private static final long FRESH_NANOS = 2_000_000_000L;
    private static volatile long lastUploadNanos;
    private static volatile int failures;

    private DhIrisMaskState() {}

    public static void patchFailed() { failures++; }

    public static void maskUploaded() { lastUploadNanos = System.nanoTime(); }

    public static void reset() {
        failures = 0;
        lastUploadNanos = 0;
    }

    /** True while an Iris DH terrain program is actively receiving the mask (a pack is in use and DH draws through Iris). */
    public static boolean canClip() {
        return failures == 0 && lastUploadNanos != 0 && System.nanoTime() - lastUploadNanos < FRESH_NANOS;
    }
}
