package me.jamino.wynndhrangelimiter.effects;

import org.joml.Matrix4f;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The LOD renderer's own depth for the current frame. LOD terrain is drawn with a different projection than
 * vanilla chunks and (for DH) never reaches Minecraft's depth buffer, so world effects need it separately
 * to be hidden behind distant terrain.
 */
public final class LodDepth {
    /**
     * @param textureId             GL name of a depth texture covering the whole view
     * @param inverseViewProjection maps that texture's NDC to camera-relative world space
     * @param clearDepth            stored value of a texel no LOD was drawn to
     * @param zeroToOne             true when stored depth is NDC z directly, false when NDC z is {@code 2d - 1}
     * @param backend               the LOD mod that drew it
     * @param renderDistance        the LOD render distance in blocks as a shader pack is told it, 0 when unknown
     * @param fog                   the fog the backend itself drew over its terrain this frame, or null
     */
    public record Layer(int textureId, Matrix4f inverseViewProjection, float clearDepth, boolean zeroToOne,
                        Backend backend, float renderDistance, FogModel fog) {}

    public enum Backend { DISTANT_HORIZONS, VOXY }

    /** Implemented by each LOD backend; called on the render thread only. */
    public interface Provider {
        /** Forget the previous frame before the world is rendered. */
        void beginFrame();

        /** The depth the backend rendered since {@link #beginFrame()}, or null if it drew nothing. */
        Layer resolve();
    }

    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    private LodDepth() {}

    public static void register(Provider provider) {
        PROVIDERS.add(provider);
    }

    static void beginFrame() {
        for (Provider provider : PROVIDERS) provider.beginFrame();
    }

    static Layer resolve() {
        for (Provider provider : PROVIDERS) {
            Layer layer = provider.resolve();
            if (layer != null && layer.textureId() > 0) return layer;
        }
        return null;
    }
}
