package me.jamino.wynndhrangelimiter.effects;

/**
 * Pure rules of how effects sit in fog: which {@link FogModel} applies, and for the fog probe
 * ({@code fog_probe.fsh}) where it looks for terrain, which terrain distances it trusts and how fast its
 * result follows a change.
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
    /**
     * Bands of elevation the probe keeps the sky's colour in, in equal steps of the sine of the elevation
     * from the horizon to straight up. {@code SKY_BANDS} in {@code scene.glsl}.
     */
    public static final int SKY_BANDS = 8;

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

    /** The active shader pack's name and options, as far as the fog rules need them. */
    public interface PackOptions {
        String name();

        /** Whether the pack has an option of this name, of either kind. */
        boolean defines(String option);

        /** Current value of a value option; null when the pack has no such option. */
        String value(String option);

        /** Current state of an on/off option; {@code fallback} when the pack has no such option. */
        boolean enabled(String option, boolean fallback);
    }

    /**
     * The fade a shader pack gives LOD terrain just before it ends:
     * {@code density * (1 - exp(-strength * (d / distance)^power))}, less {@code upwardRelief} of it for
     * view directions above the horizon. {@code d} is the distance along the ground, or with
     * {@code alongGround} false the height difference when that is larger. No fade for a distance of 0 or less.
     */
    public static float borderFog(float distance, float strength, float power, float density, boolean alongGround,
                                  float upwardRelief, double along, double height) {
        if (distance <= 0) return 0;
        double reach = alongGround ? along : Math.max(along, Math.abs(height));
        double fog = density * (1 - Math.exp(-strength * Math.pow(reach / distance, power)));
        double up = Math.max(0, Math.min(1, height / Math.max(Math.hypot(along, height), 1e-6) / UPWARD_RANGE));
        return (float) (fog * (1 - upwardRelief * up * (2 - up)));
    }

    /** Sine of the elevation at which a pack's upward relief is complete. */
    static final double UPWARD_RANGE = 0.2;

    private static PackOptions modelledPack;
    private static FogModel packModel;

    /**
     * The fog model for this frame, or null to rely on the fog probe alone. A shader pack's fog comes from
     * the pack's own options; without a pack it is the LOD mod's own fog, when that mod draws one.
     *
     * @param pack the active pack, null without one
     * @param lod  this frame's LOD layer, null without one
     */
    public static FogModel model(PackOptions pack, LodDepth.Layer lod) {
        if (pack == null) return lod == null ? null : lod.fog();
        // One reading of the options per loaded pack: changing an option reloads the pack.
        if (pack != modelledPack) {
            modelledPack = pack;
            packModel = FogModels.forPack(pack);
        }
        return packModel;
    }

    /** Share of a new measurement blended in after {@code seconds}; 1 replaces the old value outright. */
    public static float rate(double seconds) {
        if (seconds < 0 || seconds > RESTART_SECONDS) return 1;
        return (float) (1 - Math.exp(-seconds / SMOOTHING_SECONDS));
    }
}
