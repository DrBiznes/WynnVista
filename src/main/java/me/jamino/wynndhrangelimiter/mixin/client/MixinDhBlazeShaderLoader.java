package me.jamino.wynndhrangelimiter.mixin.client;

import com.mojang.blaze3d.shaders.ShaderType;
import me.jamino.wynndhrangelimiter.compat.dh.DhBlazeExactState;
import me.jamino.wynndhrangelimiter.compat.dh.DhBlazeShaderPatch;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gl.ShaderLoader;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.profiler.Profiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ShaderLoader.class)
public abstract class MixinDhBlazeShaderLoader {
    private static final Logger WYNNVISTA_LOGGER = LoggerFactory.getLogger("wynnvista-dh");

    @Inject(method = "prepare(Lnet/minecraft/resource/ResourceManager;Lnet/minecraft/util/profiler/Profiler;)Lnet/minecraft/client/gl/ShaderLoader$Definitions;",
            at = @At("HEAD"))
    private void wynnvista$resetOnShaderReload(ResourceManager manager, Profiler profiler,
                                               CallbackInfoReturnable<?> cir) {
        DhBlazeExactState.resetForShaderReload();
    }

    @Inject(method = "getSource", at = @At("RETURN"), cancellable = true)
    private void wynnvista$patchTerrainSource(Identifier id, ShaderType type,
                                              CallbackInfoReturnable<String> cir) {
        String name = id.toString();
        if (!DhBlazeShaderPatch.VERTEX.equals(name) && !DhBlazeShaderPatch.FRAGMENT.equals(name)) return;
        if (FabricLoader.getInstance().isModLoaded("iris")) return;
        String source = cir.getReturnValue();
        if (source == null) {
            DhBlazeExactState.mark(name, false);
            return;
        }
        try {
            cir.setReturnValue(DhBlazeShaderPatch.patch(name, source));
            DhBlazeExactState.mark(name, true);
        } catch (IllegalArgumentException e) {
            DhBlazeExactState.mark(name, false);
            WYNNVISTA_LOGGER.error("DH Blaze terrain shader did not match pinned source: {}", name, e);
        }
    }
}
