package me.jamino.wynndhrangelimiter.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.jamino.wynndhrangelimiter.compat.betterclouds.BetterCloudsLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Captures the Better Clouds layer for the world effects. The first draw of {@code drawShading} is the one
 * that blends the clouds into the world image; the names here are the ones {@code BetterCloudsSupport} probes.
 */
@Pseudo
@Mixin(targets = "com.qendolin.betterclouds.clouds.Renderer", remap = false)
public abstract class MixinBetterCloudsRenderer {
    @WrapOperation(method = "drawShading", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/GL32;glDrawArrays(III)V", ordinal = 0))
    private void wynnvista$captureCloudLayer(int mode, int first, int count, Operation<Void> original) {
        BetterCloudsLayer.draw(() -> original.call(mode, first, count));
    }
}
