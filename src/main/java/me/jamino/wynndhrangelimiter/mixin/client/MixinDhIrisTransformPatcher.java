package me.jamino.wynndhrangelimiter.mixin.client;

import me.jamino.wynndhrangelimiter.compat.dh.iris.DhIrisMaskState;
import me.jamino.wynndhrangelimiter.compat.dh.iris.DhIrisShaderPatch;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.EnumMap;
import java.util.Map;

/**
 * Adds the visibility mask to every DH terrain program Iris builds from a shader pack (solid, translucent
 * and, when the pack has one, shadow). Iris caches the returned map, so a patched copy is returned and the
 * cached original is never modified.
 */
@Mixin(value = TransformPatcher.class, remap = false)
public abstract class MixinDhIrisTransformPatcher {
    private static final Logger WYNNVISTA_LOGGER = LoggerFactory.getLogger("wynnvista-dh-iris");

    @Inject(method = "patchDHTerrain", at = @At("RETURN"), cancellable = true)
    private static void wynnvista$maskDhTerrain(CallbackInfoReturnable<Map<PatchShaderType, String>> cir) {
        Map<PatchShaderType, String> original = cir.getReturnValue();
        if (original == null) return;
        String vertex = original.get(PatchShaderType.VERTEX);
        String fragment = original.get(PatchShaderType.FRAGMENT);
        if (vertex == null || fragment == null || original.get(PatchShaderType.GEOMETRY) != null
                || original.get(PatchShaderType.TESS_CONTROL) != null || original.get(PatchShaderType.TESS_EVAL) != null) {
            DhIrisMaskState.patchFailed();
            WYNNVISTA_LOGGER.warn("Iris DH terrain program has no plain vertex+fragment pair; it will not be masked");
            return;
        }
        try {
            Map<PatchShaderType, String> patched = new EnumMap<>(PatchShaderType.class);
            patched.putAll(original);
            patched.put(PatchShaderType.VERTEX, DhIrisShaderPatch.patchVertex(vertex));
            patched.put(PatchShaderType.FRAGMENT, DhIrisShaderPatch.patchFragment(fragment));
            cir.setReturnValue(patched);
        } catch (IllegalArgumentException e) {
            DhIrisMaskState.patchFailed();
            WYNNVISTA_LOGGER.error("Iris DH terrain shader did not match the expected transformed form", e);
        }
    }
}
