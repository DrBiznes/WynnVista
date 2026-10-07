package me.jamino.wynndhrangelimiter.effects;

/**
 * Pure rules of the fog probe ({@code fog_probe.fsh}): where it looks for terrain, which terrain distances
 * it trusts and how fast its result follows a change.
 */
public final class EffectFog {
    /** Relative contrast of terrain detail at which the terrain counts as swallowed by fog, and as fully visible. */
    public static final float CONTRAST_GONE = 0.015f;
    public static final float CONTRAST_CLEAR = 0.05f;

    /** An effect nearer than this is measured as if it were this far: nearer terrain is never fogged out first. */
    static final double MIN_DISTANCE = 48;
    static final double SMOOTHING_SECONDS = 0.4;
    /** After a gap this long (the effect was out of view) the old value is dropped instead of blended. */
    static final double RESTART_SECONDS = 1.0;
    static final float MIN_WIDTH = 0.2f;

    /** Terrain distances, in blocks, whose visibility says something about an effect at a given distance. */
    public record Band(float near, float far) {}

    /** Horizontal part of the view searched for terrain, 0..1. */
    public record Columns(float min, float max) {}

    private EffectFog() {}

    public static Band band(double distance) {
        double d = Math.max(distance, MIN_DISTANCE);
        return new Band((float) (d * 0.5), (float) (d * 1.5));
    }

    /** The effect's own part of the view and half its width to either side: terrain in the same direction. */
    public static Columns columns(EffectCulling.ScreenRect rect) {
        float side = Math.max(rect.maxX() - rect.minX(), MIN_WIDTH) / 2;
        return new Columns(Math.max(0, rect.minX() - side), Math.min(1, rect.maxX() + side));
    }

    /** Share of a new measurement blended in after {@code seconds}; 1 replaces the old value outright. */
    public static float rate(double seconds) {
        if (seconds < 0 || seconds > RESTART_SECONDS) return 1;
        return (float) (1 - Math.exp(-seconds / SMOOTHING_SECONDS));
    }
}
