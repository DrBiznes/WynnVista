package me.jamino.wynndhrangelimiter.mixin.client;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.jamino.wynndhrangelimiter.compat.voxy.VoxyEffectDepth;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
        VoxyEffectDepth.publish(fb.getDepthTex().id, viewport.MVP, properties.clearDepth(), properties.isZero2One());
    }
}
