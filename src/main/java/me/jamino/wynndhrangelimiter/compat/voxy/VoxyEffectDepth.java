package me.jamino.wynndhrangelimiter.compat.voxy;

import me.jamino.wynndhrangelimiter.effects.LodDepth;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands Voxy's LOD depth to the world effects. Voxy also writes depth into Minecraft's framebuffer, but
 * clamped onto the vanilla far plane, so the true distance of far terrain is only in Voxy's own depth texture.
 */
public final class VoxyEffectDepth implements LodDepth.Provider {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista-effects");
    private static final VoxyEffectDepth INSTANCE = new VoxyEffectDepth();

    private final Matrix4f inverseViewProjection = new Matrix4f();
    private int texture;
    private float clearDepth;
    private boolean zeroToOne;
    private boolean rendered;

    private VoxyEffectDepth() {}

    public static void register() {
        LodDepth.register(INSTANCE);
        LOGGER.info("Voxy depth registered for world effects");
    }

    /** Called from the pipeline mixin once Voxy has drawn a viewport. */
    public static void publish(int texture, Matrix4fc viewProjection, float clearDepth, boolean zeroToOne) {
        viewProjection.invert(INSTANCE.inverseViewProjection);
        INSTANCE.texture = texture;
        INSTANCE.clearDepth = clearDepth;
        INSTANCE.zeroToOne = zeroToOne;
        INSTANCE.rendered = true;
    }

    @Override
    public void beginFrame() {
        rendered = false;
    }

    @Override
    public LodDepth.Layer resolve() {
        return rendered ? new LodDepth.Layer(texture, inverseViewProjection, clearDepth, zeroToOne) : null;
    }
}
