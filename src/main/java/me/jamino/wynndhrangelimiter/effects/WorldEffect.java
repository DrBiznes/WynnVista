package me.jamino.wynndhrangelimiter.effects;

/**
 * One world effect: a fragment shader ray-marched over the finished world image, plus the rules that decide
 * where it exists. Register implementations in {@link WorldEffects}; each gets its own config toggle.
 */
public interface WorldEffect {
    /** Stable key used in the config file. */
    String id();

    /** Label and tooltip of this effect's config toggle. */
    String name();

    String description();

    /** Fragment shader file under {@code assets/wynnvista/shaders/effects/}; may change with the config. */
    String shader();

    /** Whether the effect may be marched at half resolution and upsampled when it covers much of the view. */
    default boolean halfResolution() { return true; }

    /**
     * Terrain map this effect reads as {@code uTerrainMap}: a PNG under
     * {@code assets/wynnvista/textures/effects/}, one texel per block column; null for none.
     */
    default String terrainMap() { return null; }

    /**
     * Whether the effect is mirrored in a shader pack's water. Its shader must then start its ray with
     * {@code beginRay()} and march from {@code rayOrigin}.
     */
    default boolean reflects() { return true; }

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

    /**
     * Whether the effect can be in view from a camera position at all. An effect spread over an area
     * measures from its box instead of its anchor.
     */
    default boolean inRange(double x, double y, double z) {
        return Math.hypot(anchorX() - x, anchorZ() - z) <= maxViewDistance();
    }

    /** Sets this effect's own uniforms. The scene, depth and quality uniforms are already set. */
    void upload(EffectProgram program, EffectFrame frame);

    record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        /** Blocks from a position to the box; 0 inside it. */
        public double distance(double x, double y, double z) {
            return Math.hypot(groundDistance(x, z), Math.max(0, Math.max(minY - y, y - maxY)));
        }

        /** The same along the ground only: 0 anywhere above or below the box. */
        public double groundDistance(double x, double z) {
            return Math.hypot(Math.max(0, Math.max(minX - x, x - maxX)), Math.max(0, Math.max(minZ - z, z - maxZ)));
        }
    }
}
