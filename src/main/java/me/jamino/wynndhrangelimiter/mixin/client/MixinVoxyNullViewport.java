package me.jamino.wynndhrangelimiter.mixin.client;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.jamino.wynndhrangelimiter.util.VoxyVisibilityHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to soft-toggle Voxy rendering using the "null viewport" trick.
 * Voxy's render methods check for null viewport and early-exit safely.
 * This uses Voxy's own safety mechanism, ensuring zero lag.
 */
@Mixin(value = VoxyRenderSystem.class, remap = false)
public class MixinVoxyNullViewport {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista");
    private static boolean lastOpaqueState = true;
    private static boolean lastTranslucentState = true;

    /**
     * Intercept renderOpaque and cancel if Voxy should be hidden.
     * When cancelled, Voxy skips all rendering logic, compute shaders, and draw calls.
     */
    @Inject(method = "renderOpaque", at = @At("HEAD"), cancellable = true)
    private void wynnvista$cancelOpaqueRendering(Viewport<?> viewport, CallbackInfo ci) {
        boolean visible = VoxyVisibilityHandler.voxyVisible;

        // Log state changes
        if (lastOpaqueState != visible) {
            LOGGER.info("[MIXIN] renderOpaque - VoxyVisibilityHandler.voxyVisible changed to: {}", visible);
            lastOpaqueState = visible;
        }

        if (!visible) {
            LOGGER.debug("[MIXIN] renderOpaque - CANCELLING render (voxyVisible=false)");
            ci.cancel(); // Skip all opaque rendering
        }
    }

    /**
     * Intercept renderTranslucent and cancel if Voxy should be hidden.
     * Using require = 0 to make this optional in case the method doesn't exist.
     */
    @Inject(method = "renderTranslucent", at = @At("HEAD"), cancellable = true, require = 0)
    private void wynnvista$cancelTranslucentRendering(Viewport<?> viewport, CallbackInfo ci) {
        boolean visible = VoxyVisibilityHandler.voxyVisible;

        // Log state changes
        if (lastTranslucentState != visible) {
            LOGGER.info("[MIXIN] renderTranslucent - VoxyVisibilityHandler.voxyVisible changed to: {}", visible);
            lastTranslucentState = visible;
        }

        if (!visible) {
            LOGGER.debug("[MIXIN] renderTranslucent - CANCELLING render (voxyVisible=false)");
            ci.cancel(); // Skip all translucent rendering
        }
    }
}
