package me.jamino.wynndhrangelimiter.mixin.client;

import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.jamino.wynndhrangelimiter.compat.voxy.VoxyMaskState;
import me.jamino.wynndhrangelimiter.compat.voxy.VoxyShaderPatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ShaderLoader.class, remap = false)
public abstract class MixinVoxyShaderLoader {
    private static final Logger WYNNVISTA_LOGGER = LoggerFactory.getLogger("wynnvista-voxy");

    @Inject(method = "parse", at = @At("RETURN"), cancellable = true)
    private static void wynnvista$patchTerrainSource(String id, CallbackInfoReturnable<String> cir) {
        if (!VoxyShaderPatch.VERTEX.equals(id) && !VoxyShaderPatch.FRAGMENT.equals(id)) return;
        if (VoxyShaderPatch.FRAGMENT.equals(id) && VoxyMaskState.vertexFailed()) {
            VoxyMaskState.mark(id, false);
            return;
        }
        try {
            cir.setReturnValue(VoxyShaderPatch.patch(id, cir.getReturnValue()));
            VoxyMaskState.mark(id, true);
        } catch (IllegalArgumentException e) {
            VoxyMaskState.mark(id, false);
            WYNNVISTA_LOGGER.error("Voxy terrain shader did not match pinned source: {}", id, e);
        }
    }
}
