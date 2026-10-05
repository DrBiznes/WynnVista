package me.jamino.wynndhrangelimiter.mixin.client;

import com.seibel.distanthorizons.common.render.blaze.BlazeDhTerrainRenderer;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPassWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPipelineBuilderWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.uniform.BlazeUniformBufferWrapper;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import me.jamino.wynndhrangelimiter.compat.dh.DhBlazeExactState;
import me.jamino.wynndhrangelimiter.visibility.BlockRect;
import me.jamino.wynndhrangelimiter.visibility.MaskMode;
import me.jamino.wynndhrangelimiter.visibility.VisibilityService;
import me.jamino.wynndhrangelimiter.visibility.VisibilitySnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = BlazeDhTerrainRenderer.class, remap = false)
public abstract class MixinDhBlazeTerrainRenderer {
    @Unique private static final Logger WYNNVISTA_LOGGER = LoggerFactory.getLogger("wynnvista-dh");
    @Unique private BlazeUniformBufferWrapper wynnvista$maskBuffer;
    @Unique private boolean wynnvista$opaque;
    @Unique private long wynnvista$lastOpaqueRevision = -1;
    @Unique private long wynnvista$lastTransparentRevision = -1;

    @Inject(method = "render", at = @At("HEAD"))
    private void wynnvista$prepareMask(RenderParams params, boolean opaque,
                                       SortedArraySet<LodBufferContainer> buffers,
                                       IProfilerWrapper profiler, CallbackInfo ci) {
        wynnvista$opaque = opaque;
        // Blaze disallows buffer writes once RenderPassWrapper opens the pass.
        if (wynnvista$maskBuffer == null) wynnvista$maskBuffer = new BlazeUniformBufferWrapper("WynnVistaMask");
        VisibilitySnapshot snapshot = VisibilityService.current();
        List<BlockRect> rects = snapshot.mask().rectangles();
        for (int i = 0; i < 8; i++) {
            BlockRect r = i < rects.size() ? rects.get(i) : null;
            wynnvista$maskBuffer.putVec4f(r == null ? 0 : r.minX(), r == null ? 0 : r.minZ(),
                    r == null ? 0 : r.maxX(), r == null ? 0 : r.maxZ());
        }
        wynnvista$maskBuffer.putInt(snapshot.mode() == MaskMode.PASSTHROUGH ? -1 : rects.size());
        wynnvista$maskBuffer.finishAndUpload();
    }

    @Redirect(method = "tryInit", at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/common/render/blaze/wrappers/RenderPipelineBuilderWrapper;withFragmentShader(Ljava/lang/String;)Lcom/seibel/distanthorizons/common/render/blaze/wrappers/RenderPipelineBuilderWrapper;"))
    private RenderPipelineBuilderWrapper wynnvista$registerMaskBlock(
            RenderPipelineBuilderWrapper builder, String path) {
        builder.withUniformBuffer("WynnVistaMask");
        return builder.withFragmentShader(path);
    }

    @Redirect(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/common/render/blaze/wrappers/RenderPassWrapper;setUniform(Ljava/lang/String;Lcom/seibel/distanthorizons/common/render/blaze/wrappers/uniform/BlazeUniformBufferWrapper;)V",
            ordinal = 0))
    private void wynnvista$bindMask(RenderPassWrapper pass, String name,
                                    BlazeUniformBufferWrapper original) {
        pass.setUniform(name, original);
        if (!DhBlazeExactState.shadersPatched()) return;
        pass.setUniform("WynnVistaMask", wynnvista$maskBuffer);
        VisibilitySnapshot snapshot = VisibilityService.current();
        if (snapshot.revision() != (wynnvista$opaque
                ? wynnvista$lastOpaqueRevision : wynnvista$lastTransparentRevision)) {
            if (wynnvista$opaque) wynnvista$lastOpaqueRevision = snapshot.revision();
            else wynnvista$lastTransparentRevision = snapshot.revision();
            WYNNVISTA_LOGGER.info("DH Blaze3D terrain mask revision {} bound for {} pass: mode={}",
                    snapshot.revision(), wynnvista$opaque ? "opaque" : "transparent", snapshot.mode());
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void wynnvista$rendered(RenderParams params, boolean opaque,
                                    SortedArraySet<LodBufferContainer> buffers,
                                    IProfilerWrapper profiler, CallbackInfo ci) {
        if (DhBlazeExactState.shadersPatched()) DhBlazeExactState.rendered();
    }
}
