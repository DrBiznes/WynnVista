package me.jamino.wynndhrangelimiter.compat.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiHeightFogDirection;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiBlazeTextureWrapper;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeFogRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiFogRenderParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import me.jamino.wynndhrangelimiter.effects.FogModel;
import me.jamino.wynndhrangelimiter.effects.FogModels;
import me.jamino.wynndhrangelimiter.effects.LodDepth;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.GlTexture;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;

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
    private FogModel fog;

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
            DhApiEventRegister.on(DhApiBeforeFogRenderEvent.class, new DhApiBeforeFogRenderEvent() {
                @Override
                public void beforeRender(DhApiCancelableEventParam<DhApiBeforeFogRenderEvent.EventParam> event) {
                    provider.captureFog(event.value.getFogRenderParam());
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

    /**
     * DH fires this only in frames in which it draws its own fog, so a frame without it (fog switched off,
     * or a shader pack rendering) has no model.
     */
    private void captureFog(DhApiFogRenderParam param) {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            float worldHeight = client.world == null ? 320 : client.world.getBottomY() + client.world.getHeight();
            EDhApiHeightFogDirection direction = param.getHeightFogDirection();
            Color colour = param.getFogColor();
            fog = new FogModels.DistantHorizons(param.getFarFogFalloff().value, param.getFarFogStartPercent(),
                    param.getFarFogEndPercent(), param.getFarFogMinThickness(), param.getFarFogMaxThickness(),
                    param.getFarFogDensity(), param.getHeightFogFalloff().value, param.getHeightFogMixingMode().value,
                    direction.basedOnCamera, direction.fogAppliesUp, direction.fogAppliesDown,
                    param.getHeightFogBaseHeight(), param.getHeightFogStartPercent(), param.getHeightFogEndPercent(),
                    renderDistance(), worldHeight, colour.getRed() / 255f, colour.getGreen() / 255f,
                    colour.getBlue() / 255f);
        } catch (LinkageError | RuntimeException e) {
            fog = null;
        }
    }

    /** DH matrices are row-major; JOML reads column-major. */
    private Matrix4f toJoml(DhApiMat4f matrix) {
        matrix.putValuesInArray(scratch);
        return new Matrix4f().set(scratch).transpose();
    }

    @Override
    public void beginFrame() {
        rendered = false;
        fog = null;
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
                    proxy.getDepthRange() == EDhApiDepthRange.ZERO_TO_POS_ONE, LodDepth.Backend.DISTANT_HORIZONS,
                    renderDistance(), fog);
        } catch (IllegalStateException e) {
            return null; // DH has not finished choosing a renderer yet
        } catch (LinkageError | RuntimeException e) {
            failed = true;
            LOGGER.warn("Could not read Distant Horizons depth; LODs will not hide world effects", e);
            return null;
        }
    }

    /** In blocks; shader packs are given the same figure (dhRenderDistance). */
    private static float renderDistance() {
        IDhApiConfig configs = DhApi.Delayed.configs;
        if (configs == null) return 0;
        Integer chunks = configs.graphics().chunkRenderDistance().getValue();
        return chunks == null ? 0 : chunks * 16f;
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
