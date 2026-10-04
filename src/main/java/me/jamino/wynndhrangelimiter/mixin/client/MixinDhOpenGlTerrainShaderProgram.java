package me.jamino.wynndhrangelimiter.mixin.client;

import com.seibel.distanthorizons.common.render.openGl.terrain.GlDhTerrainShaderProgram;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider;
import me.jamino.wynndhrangelimiter.compat.dh.DhOpenGlExactState;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = GlDhTerrainShaderProgram.class, remap = false)
public abstract class MixinDhOpenGlTerrainShaderProgram {
    @Unique private static final Logger WYNNVISTA_LOGGER = LoggerFactory.getLogger("wynnvista-dh");
    @Unique private double wynnvista$cameraX;
    @Unique private double wynnvista$cameraZ;
    @Unique private int wynnvista$rectsLocation = -2;
    @Unique private int wynnvista$countLocation = -2;
    @Unique private boolean wynnvista$uniformReady;
    @Unique private long wynnvista$lastOpaqueRevision = -1;
    @Unique private long wynnvista$lastTransparentRevision = -1;

    @Inject(method = "render", at = @At("HEAD"))
    private void wynnvista$prepareCamera(RenderParams params, boolean opaque,
                                         SortedArraySet<LodBufferContainer> buffers,
                                         IProfilerWrapper profiler, CallbackInfo ci) {
        wynnvista$cameraX = params.exactCameraPosition.x;
        wynnvista$cameraZ = params.exactCameraPosition.z;
    }

    @Inject(method = "bind", at = @At("TAIL"))
    private void wynnvista$bindMask(CallbackInfo ci) {
        if (!DhOpenGlExactState.shadersPatched()) return;
        GlDhTerrainShaderProgram program = (GlDhTerrainShaderProgram) (Object) this;
        if (wynnvista$rectsLocation == -2) {
            wynnvista$rectsLocation = program.tryGetUniformLocation("wynnvistaRects[0]");
            wynnvista$countLocation = program.tryGetUniformLocation("wynnvistaRectCount");
        }
        if (wynnvista$rectsLocation < 0 || wynnvista$countLocation < 0) return;
        VisibilitySnapshot snapshot = VisibilityService.current();
        List<BlockRect> rects = snapshot.mask().rectangles();
        float[] data = new float[32];
        for (int i = 0; i < rects.size(); i++) {
            BlockRect r = rects.get(i);
            data[i * 4] = (float) (r.minX() - wynnvista$cameraX);
            data[i * 4 + 1] = (float) (r.minZ() - wynnvista$cameraZ);
            data[i * 4 + 2] = (float) (r.maxX() - wynnvista$cameraX);
            data[i * 4 + 3] = (float) (r.maxZ() - wynnvista$cameraZ);
        }
        LWJGLServiceProvider.LWJGL.glUniform4fv(wynnvista$rectsLocation, data);
        LWJGLServiceProvider.LWJGL.glUniform1i(wynnvista$countLocation,
                snapshot.mode() == MaskMode.PASSTHROUGH ? -1 : rects.size());
        wynnvista$uniformReady = true;
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void wynnvista$rendered(RenderParams params, boolean opaque,
                                    SortedArraySet<LodBufferContainer> buffers,
                                    IProfilerWrapper profiler, CallbackInfo ci) {
        if (!wynnvista$uniformReady) return;
        DhOpenGlExactState.rendered();
        VisibilitySnapshot snapshot = VisibilityService.current();
        if (snapshot.revision() != (opaque ? wynnvista$lastOpaqueRevision : wynnvista$lastTransparentRevision)) {
            if (opaque) wynnvista$lastOpaqueRevision = snapshot.revision();
            else wynnvista$lastTransparentRevision = snapshot.revision();
            WYNNVISTA_LOGGER.info("DH OpenGL terrain mask revision {} bound for {} pass: mode={}",
                    snapshot.revision(), opaque ? "opaque" : "transparent", snapshot.mode());
        }
    }

    @Inject(method = "free", at = @At("TAIL"))
    private void wynnvista$free(CallbackInfo ci) {
        wynnvista$rectsLocation = -2;
        wynnvista$countLocation = -2;
        wynnvista$uniformReady = false;
        DhOpenGlExactState.resetForProgramReload();
    }
}
