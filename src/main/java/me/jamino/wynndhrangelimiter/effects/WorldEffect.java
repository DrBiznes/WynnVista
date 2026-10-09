package me.jamino.wynndhrangelimiter.effects;

/**
 * One world effect: a fragment shader ray-marched over the finished world image, plus the rules that decide
 * where it exists. Register implementations in {@link WorldEffects}; each gets its own config toggle.
 */
public interface WorldEffect {
    /** Stable key used in the config file. Effects with the same id are switched by one config toggle. */
    String id();

    /** Label and tooltip of this effect's config toggle. */
    String name();

    String description();

    /** Fragment shader file under {@code assets/wynnvista/shaders/effects/}; may change with the config. */
    String shader();

    /** Whether the effect may be marched at half resolution and upsampled when it covers much of the view. */
    default boolean halfResolution() { return true; }

    /**
     * Block the effect stands on. It is shown exactly where LOD terrain at this block is shown, so it is
     * culled with the region mask.
     */
    double anchorX();

    double anchorZ();

    /** World-space box containing everything the effect can draw; used for frustum culling and the scissor. */
    Bounds bounds();

    /** Horizontal distance from the anchor beyond which the effect is not drawn. */
    double maxViewDistance();

    /** Sets this effect's own uniforms. The scene, depth and quality uniforms are already set. */
    void upload(EffectProgram program, EffectFrame frame);

    record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}
}
