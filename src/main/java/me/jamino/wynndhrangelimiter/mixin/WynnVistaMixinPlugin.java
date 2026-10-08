package me.jamino.wynndhrangelimiter.mixin;

import me.jamino.wynndhrangelimiter.compat.betterclouds.BetterCloudsSupport;
import me.jamino.wynndhrangelimiter.compat.dh.DhVersionSupport;
import me.jamino.wynndhrangelimiter.compat.iris.IrisSupport;
import me.jamino.wynndhrangelimiter.compat.voxy.VoxyVersionSupport;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Keeps optional DH, Voxy, Iris and Better Clouds targets out of a client without the exact verified binary. */
public final class WynnVistaMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".MixinVoxy")) return VoxyVersionSupport.supported();
        if (mixinClassName.contains(".MixinBetterClouds")) return BetterCloudsSupport.supported();
        if (mixinClassName.contains(".MixinIris")) return IrisSupport.pipelineSupported();
        if (mixinClassName.contains(".MixinDhIris")) {
            return DhVersionSupport.supported() && IrisSupport.dhTerrainSupported();
        }
        if (!mixinClassName.endsWith(".MixinDhRenderBufferHandler")
                && !mixinClassName.endsWith(".MixinDhBlazeShaderLoader")
                && !mixinClassName.endsWith(".MixinDhBlazeTerrainRenderer")
                && !mixinClassName.endsWith(".MixinDhOpenGlShaderLoader")
                && !mixinClassName.endsWith(".MixinDhOpenGlTerrainShaderProgram")) return true;
        return DhVersionSupport.supported();
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
