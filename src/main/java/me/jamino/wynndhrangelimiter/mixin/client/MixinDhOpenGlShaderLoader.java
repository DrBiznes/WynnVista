package me.jamino.wynndhrangelimiter.mixin.client;

import com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShader;
import me.jamino.wynndhrangelimiter.compat.dh.DhOpenGlExactState;
import me.jamino.wynndhrangelimiter.compat.dh.DhOpenGlShaderPatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GlShader.class, remap = false)
public abstract class MixinDhOpenGlShaderLoader {
    private static final Logger WYNNVISTA_LOGGER = LoggerFactory.getLogger("wynnvista-dh");

    @Inject(method = "loadFile", at = @At("RETURN"), cancellable = true)
    private static void wynnvista$patchTerrainSource(String path, boolean mandatory,
                                                     CallbackInfoReturnable<String> cir) {
        if (!DhOpenGlShaderPatch.VERTEX.equals(path) && !DhOpenGlShaderPatch.FRAGMENT.equals(path)) return;
        try {
            cir.setReturnValue(DhOpenGlShaderPatch.patch(path, cir.getReturnValue()));
            DhOpenGlExactState.mark(path, true);
        } catch (IllegalArgumentException e) {
            DhOpenGlExactState.mark(path, false);
            WYNNVISTA_LOGGER.error("DH OpenGL terrain shader did not match pinned source: {}", path, e);
        }
    }
}
