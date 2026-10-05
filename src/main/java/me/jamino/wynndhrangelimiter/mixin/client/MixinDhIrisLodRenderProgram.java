package me.jamino.wynndhrangelimiter.mixin.client;

import me.jamino.wynndhrangelimiter.compat.dh.iris.DhIrisMaskState;
import me.jamino.wynndhrangelimiter.compat.dh.iris.DhIrisShaderPatch;
import me.jamino.wynndhrangelimiter.visibility.BlockRect;
import me.jamino.wynndhrangelimiter.visibility.MaskMode;
import me.jamino.wynndhrangelimiter.visibility.VisibilityService;
import me.jamino.wynndhrangelimiter.visibility.VisibilitySnapshot;
import net.irisshaders.iris.compat.dh.IrisLodRenderProgram;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL41C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Uploads the active visibility mask, camera-relative, to each Iris-built DH terrain program. */
@Mixin(value = IrisLodRenderProgram.class, remap = false)
public abstract class MixinDhIrisLodRenderProgram {
    @Unique private static final Logger WYNNVISTA_LOGGER = LoggerFactory.getLogger("wynnvista-dh-iris");

    @Shadow @Final private int id;

    @Unique private int wynnvista$rects = -1;
    @Unique private int wynnvista$count = -1;
    @Unique private long wynnvista$loggedRevision = -1;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void wynnvista$resolveUniforms(CallbackInfo ci) {
        wynnvista$rects = GL20C.glGetUniformLocation(id, DhIrisShaderPatch.RECTS_UNIFORM + "[0]");
        wynnvista$count = GL20C.glGetUniformLocation(id, DhIrisShaderPatch.COUNT_UNIFORM);
        if (wynnvista$rects < 0 || wynnvista$count < 0) {
            DhIrisMaskState.patchFailed();
            WYNNVISTA_LOGGER.warn("Iris DH terrain program {} has no mask uniforms ({}, {}); it will not be masked",
                    id, wynnvista$rects, wynnvista$count);
        } else {
            WYNNVISTA_LOGGER.info("Iris DH terrain program {} mask uniforms ready ({}, {})", id, wynnvista$rects, wynnvista$count);
        }
    }

    @Inject(method = "fillUniformData", at = @At("TAIL"))
    private void wynnvista$uploadMask(CallbackInfo ci) {
        if (wynnvista$rects < 0 || wynnvista$count < 0) return;
        VisibilitySnapshot snapshot = VisibilityService.current();
        Vec3d camera = MinecraftClient.getInstance().gameRenderer.getCamera().getCameraPos();
        List<BlockRect> rects = snapshot.mask().rectangles();
        float[] data = new float[DhIrisShaderPatch.MAX_RECTS * 4];
        for (int i = 0; i < rects.size(); i++) {
            BlockRect r = rects.get(i);
            data[i * 4] = (float) (r.minX() - camera.x);
            data[i * 4 + 1] = (float) (r.minZ() - camera.z);
            data[i * 4 + 2] = (float) (r.maxX() - camera.x);
            data[i * 4 + 3] = (float) (r.maxZ() - camera.z);
        }
        GL41C.glProgramUniform4fv(id, wynnvista$rects, data);
        GL41C.glProgramUniform1i(id, wynnvista$count, snapshot.mode() == MaskMode.PASSTHROUGH ? -1 : rects.size());
        DhIrisMaskState.maskUploaded();
        if (snapshot.revision() != wynnvista$loggedRevision) {
            wynnvista$loggedRevision = snapshot.revision();
            WYNNVISTA_LOGGER.info("Iris DH terrain mask revision {} bound to program {}: mode={}",
                    snapshot.revision(), id, snapshot.mode());
        }
    }
}
