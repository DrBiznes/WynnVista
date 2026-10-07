package me.jamino.wynndhrangelimiter.effects;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A translucent cloud layer another mod drew into the world image this frame. World effects are drawn over
 * the finished image, so without it a cloud is either terrain that hides an effect completely (when the mod
 * writes the clouds' depth) or is painted over. With it an effect is split where its ray meets the clouds:
 * the part in front is drawn over them and the part behind is seen through them.
 */
public final class CloudLayer {
    /**
     * @param colorTexture        GL name of the layer alone: premultiplied colour, and opacity in alpha
     * @param depthTexture        GL name of the layer's depth, in the vanilla projection; 1 where nothing
     *                            was written
     * @param terrainDepthTexture GL name of a copy of the vanilla depth from before the layer was drawn
     * @param colorInImage        true when the world image holds the layer's colour unchanged, false when
     *                            something (a shader pack) processed the image afterwards
     */
    public record Layer(int colorTexture, int depthTexture, int terrainDepthTexture, boolean colorInImage) {}

    /** Implemented by each cloud mod's integration; called on the render thread only. */
    public interface Provider {
        /** Forget the previous frame before the world is rendered. */
        void beginFrame();

        /** The layer drawn since {@link #beginFrame()}, or null if there was none. */
        Layer resolve();
    }

    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    private CloudLayer() {}

    public static void register(Provider provider) {
        PROVIDERS.add(provider);
    }

    static void beginFrame() {
        for (Provider provider : PROVIDERS) provider.beginFrame();
    }

    static Layer resolve() {
        for (Provider provider : PROVIDERS) {
            Layer layer = provider.resolve();
            if (layer != null) return layer;
        }
        return null;
    }
}
