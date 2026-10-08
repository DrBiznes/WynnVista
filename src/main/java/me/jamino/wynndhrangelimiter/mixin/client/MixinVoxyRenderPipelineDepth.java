package me.jamino.wynndhrangelimiter.mixin.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.NormalRenderPipeline;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.jamino.wynndhrangelimiter.compat.voxy.VoxyEffectDepth;
import me.jamino.wynndhrangelimiter.effects.FogModel;
import me.jamino.wynndhrangelimiter.effects.FogModels;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Publishes the depth Voxy rendered this frame, with the projection it used, for the world effects. */
@Mixin(value = AbstractRenderPipeline.class, remap = false)
public abstract class MixinVoxyRenderPipelineDepth {
    @Shadow @Final public DepthFramebuffer fb;
    @Shadow @Final public RenderProperties properties;

    @Inject(method = "runPipeline", at = @At("RETURN"))
    private void wynnvista$publishDepth(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight,
                                        CallbackInfo ci) {
        // Only the main view: a shader pack's shadow pass runs the same pipeline with its own viewport.
        Framebuffer main = MinecraftClient.getInstance().getFramebuffer();
        if (viewport.width != main.textureWidth || viewport.height != main.textureHeight) return;
        // A section is 32 chunks. Shader packs are given the same figure (vxRenderDistance, in chunks).
        VoxyEffectDepth.publish(fb.getDepthTex().id, viewport.MVP, properties.clearDepth(), properties.isZero2One(),
                VoxyConfig.CONFIG.sectionRenderDistance * 512, wynnvista$ownFog(viewport));
    }

    /** The fog Voxy's own pipeline blends over its terrain; a shader pack's pipeline draws none of it. */
    @Unique
    private FogModel wynnvista$ownFog(Viewport<?> viewport) {
        FogParameters fog = viewport.fogParameters;
        if (!((Object) this instanceof NormalRenderPipeline) || !VoxyConfig.CONFIG.useEnvironmentalFog || fog == null) {
            return null;
        }
        return FogModels.Voxy.of(fog.environmentalStart(), fog.environmentalEnd(),
                MinecraftClient.getInstance().gameRenderer.getViewDistanceBlocks(), fog.red(), fog.green(), fog.blue(),
                fog.alpha());
    }
}
