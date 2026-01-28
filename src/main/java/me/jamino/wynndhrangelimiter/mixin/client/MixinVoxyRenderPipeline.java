package me.jamino.wynndhrangelimiter.mixin.client;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.jamino.wynndhrangelimiter.util.VoxyVisibilityHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to soft-toggle Voxy rendering by cancelling the pipeline orchestration.
 * Targeting runPipeline blocks all rendering passes (Opaque, Translucent, Temporal)
 * in one shot, preventing any compute shaders or draw calls from executing.
 */
@Mixin(value = AbstractRenderPipeline.class, remap = false)
public class MixinVoxyRenderPipeline {

    /**
     * Target the main orchestration method.
     * Cancelling this is the most efficient 'Lens Cap' as it prevents
     * any compute shaders or draw calls from even being considered.
     */
    @Inject(method = "runPipeline", at = @At("HEAD"), cancellable = true)
    private void wynnvista$onRunPipeline(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight, CallbackInfo ci) {
        if (!VoxyVisibilityHandler.voxyVisible) {
            ci.cancel();
        }
    }
}
