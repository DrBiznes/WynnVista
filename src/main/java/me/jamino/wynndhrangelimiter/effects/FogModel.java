package me.jamino.wynndhrangelimiter.effects;

/**
 * The fog a shader pack or a LOD mod puts over distant terrain, worked out from its own settings instead of
 * measured from the image. Implementations are value types: two models with the same settings are equal,
 * which is what lets {@link FogTable} skip rebuilding.
 */
public interface FogModel {
    /**
     * What a model may read about the frame. Values are rounded on creation so that standing still, or a
     * slowly moving sun, does not make every frame a new environment.
     *
     * @param cameraY           world height of the camera
     * @param rain              0..1
     * @param sunX              cosine of the sun's angle along its path: positive in the morning
     * @param sunY              sine of the sun's elevation: positive by day
     * @param backend           the LOD mod drawing this frame, or null
     * @param lodRenderDistance blocks, as that mod reports it to shader packs; 0 without one
     */
    record Env(float cameraY, float rain, float sunX, float sunY, LodDepth.Backend backend,
               float lodRenderDistance) {
        public static Env of(double cameraY, float rain, long timeOfDay, LodDepth.Backend backend,
                             float lodRenderDistance) {
            double angle = Math.floorMod(timeOfDay, 24000L) / 24000.0 * Math.PI * 2;
            return new Env(Math.round(cameraY * 2) / 2f, Math.round(Math.max(0, Math.min(1, rain)) * 100) / 100f,
                    Math.round(Math.cos(angle) * 200) / 200f, Math.round(Math.sin(angle) * 200) / 200f,
                    backend, lodRenderDistance);
        }
    }

    /** For the log: whose fog this is. */
    String name();

    /**
     * The fog between the camera and a point {@code along} blocks away over the ground, at world height
     * {@code y}. {@code out[0]} is the share of an effect there that is replaced by the fog's colour,
     * {@code out[1]} the share that fades into whatever is behind it, as terrain does into the sky at the
     * LOD border.
     */
    void sample(Env env, double along, double y, float[] out);

    /** The fog's colour as it appears on screen, when the model knows it; null to take it from the image. */
    default float[] colour() { return null; }

    /**
     * True when the model only knows part of the fog, so the fog probe's measurement of the image is still
     * applied on top (an unrecognised shader pack).
     */
    default boolean measured() { return false; }
}
