package me.jamino.wynndhrangelimiter.mixin.client;

import com.google.common.collect.ImmutableSet;
import me.jamino.wynndhrangelimiter.compat.iris.IrisCloudCapture;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the world effects copy a shader pack's cloud buffer as soon as the pack's deferred passes, which draw
 * its clouds, have run: some packs overwrite it in their later passes.
 */
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public abstract class MixinIrisPipelineClouds {
    @Shadow @Final private RenderTargets renderTargets;
    @Shadow @Final private ImmutableSet<Integer> flippedAfterTranslucent;

    @Inject(method = "beginTranslucents", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/irisshaders/iris/pipeline/CompositeRenderer;renderAll()V"))
    private void wynnvista$captureClouds(CallbackInfo ci) {
        IrisCloudCapture.afterDeferred(renderTargets, flippedAfterTranslucent);
    }
}
