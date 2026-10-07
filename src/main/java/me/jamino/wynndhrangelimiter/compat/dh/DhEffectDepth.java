package me.jamino.wynndhrangelimiter.compat.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiBlazeTextureWrapper;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import me.jamino.wynndhrangelimiter.effects.LodDepth;
import net.minecraft.client.texture.GlTexture;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands Distant Horizons' LOD depth to the world effects. DH draws LODs into its own colour and depth
 * textures with its own projection and only copies the colour into Minecraft's framebuffer, so its depth is
 * read through the DH API. Uses the public API only, so it does not depend on the pinned terrain shaders.
 */
public final class DhEffectDepth implements LodDepth.Provider {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista-effects");

    private final Matrix4f inverseViewProjection = new Matrix4f();
    private final float[] scratch = new float[16];
    private boolean rendered;
    private boolean failed;

    private DhEffectDepth() {}

    public static void register() {
        try {
            DhEffectDepth provider = new DhEffectDepth();
            DhApiEventRegister.on(DhApiBeforeRenderEvent.class, new DhApiBeforeRenderEvent() {
                @Override
                public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
                    provider.capture(event.value);
                }
            });
            LodDepth.register(provider);
            LOGGER.info("Distant Horizons depth registered for world effects");
        } catch (LinkageError | RuntimeException e) {
            LOGGER.warn("Distant Horizons API is not usable for world effects; LODs will not hide them", e);
        }
    }

    /** The event parameter is reused by DH, so the matrices are copied immediately. */
    private void capture(DhApiRenderParam param) {
        inverseViewProjection.set(toJoml(param.dhProjectionMatrix)).mul(toJoml(param.dhModelViewMatrix)).invert();
        rendered = true;
    }

    /** DH matrices are row-major; JOML reads column-major. */
    private Matrix4f toJoml(DhApiMat4f matrix) {
        matrix.putValuesInArray(scratch);
        return new Matrix4f().set(scratch).transpose();
    }

    @Override
    public void beginFrame() {
        rendered = false;
    }

    @Override
    public LodDepth.Layer resolve() {
        if (!rendered || failed) return null;
        try {
            IDhApiRenderProxy proxy = DhApi.Delayed.renderProxy;
            if (proxy == null) return null;
            int texture = textureId(proxy);
            if (texture <= 0) return null;
            return new LodDepth.Layer(texture, inverseViewProjection, proxy.getDepthDirection().farDepth,
                    proxy.getDepthRange() == EDhApiDepthRange.ZERO_TO_POS_ONE);
        } catch (IllegalStateException e) {
            return null; // DH has not finished choosing a renderer yet
        } catch (LinkageError | RuntimeException e) {
            failed = true;
            LOGGER.warn("Could not read Distant Horizons depth; LODs will not hide world effects", e);
            return null;
        }
    }

    private static int textureId(IDhApiRenderProxy proxy) {
        DhApiResult<Integer> gl = proxy.getDhDepthTextureGlId();
        if (gl.success && gl.payload != null && gl.payload > 0) return gl.payload;
        DhApiResult<IDhApiBlazeTextureWrapper> blaze = proxy.getDhDepthTextureBlazeWrapper();
        if (!blaze.success || blaze.payload == null) return -1;
        // The Blaze3D renderer exposes {texture, view, sampler}; Minecraft's only device is OpenGL.
        Object wrapped = blaze.payload.getWrappedMcObject();
        Object texture = wrapped instanceof Object[] parts && parts.length > 0 ? parts[0] : wrapped;
        return texture instanceof GlTexture glTexture ? glTexture.getGlId() : -1;
    }
}
