package me.jamino.wynndhrangelimiter.mixin.client;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import me.jamino.wynndhrangelimiter.WynnVistaMod;
import me.jamino.wynndhrangelimiter.compat.voxy.VoxyMaskState;
import me.jamino.wynndhrangelimiter.compat.voxy.VoxyMaskUniforms;
import me.jamino.wynndhrangelimiter.compat.voxy.VoxyShaderPatch;
import me.jamino.wynndhrangelimiter.visibility.VisibilitySnapshot;
import net.minecraft.client.MinecraftClient;
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

/**
 * Uploads the active visibility mask to Voxy's opaque/temporal and translucent terrain programs. The mask
 * is pure fragment clipping: Voxy's traversal, scheduling, ingestion and draw-command generation are untouched.
 */
@Mixin(value = MDICSectionRenderer.class, remap = false)
public abstract class MixinVoxyMDICSectionRenderer {
    @Unique private static final Logger WYNNVISTA_LOGGER = LoggerFactory.getLogger("wynnvista-voxy");

    @Shadow @Final private Shader terrainShader;
    @Shadow @Final private Shader translucentTerrainShader;
    @Shadow @Final private AbstractRenderPipeline pipeline;

    @Unique private int wynnvista$opaqueRects = -1;
    @Unique private int wynnvista$opaqueCount = -1;
    @Unique private int wynnvista$translucentRects = -1;
    @Unique private int wynnvista$translucentCount = -1;
    @Unique private boolean wynnvista$exact;
    @Unique private VisibilitySnapshot wynnvista$snapshot;
    @Unique private long wynnvista$loggedOpaque = -1;
    @Unique private long wynnvista$loggedTemporal = -1;
    @Unique private long wynnvista$loggedTranslucent = -1;
    @Unique private final java.util.Set<Integer> wynnvista$viewports = new java.util.HashSet<>();

    @Inject(method = "<init>", at = @At("TAIL"))
    private void wynnvista$resolveUniforms(CallbackInfo ci) {
        String pipelineName = this.pipeline.getClass().getSimpleName();
        wynnvista$opaqueRects = GL20C.glGetUniformLocation(terrainShader.id(), VoxyShaderPatch.RECTS_UNIFORM + "[0]");
        wynnvista$opaqueCount = GL20C.glGetUniformLocation(terrainShader.id(), VoxyShaderPatch.COUNT_UNIFORM);
        wynnvista$translucentRects = GL20C.glGetUniformLocation(translucentTerrainShader.id(), VoxyShaderPatch.RECTS_UNIFORM + "[0]");
        wynnvista$translucentCount = GL20C.glGetUniformLocation(translucentTerrainShader.id(), VoxyShaderPatch.COUNT_UNIFORM);
        // The stock pipeline and the Iris pipeline both compile the patched quads.frag: the Iris pipeline appends the
        // shader pack's voxy_emitFragment implementation after it, so the mask runs in front of the pack's code.
        boolean supportedPipeline = "NormalRenderPipeline".equals(pipelineName)
                || "IrisVoxyRenderPipeline".equals(pipelineName);
        wynnvista$exact = supportedPipeline && VoxyMaskState.shadersPatched()
                && wynnvista$opaqueRects >= 0 && wynnvista$opaqueCount >= 0
                && wynnvista$translucentRects >= 0 && wynnvista$translucentCount >= 0;
        if (wynnvista$exact) {
            WYNNVISTA_LOGGER.info("Voxy exact terrain mask ready: pipeline={}, opaque uniforms=({}, {}), translucent uniforms=({}, {})",
                    pipelineName, wynnvista$opaqueRects, wynnvista$opaqueCount,
                    wynnvista$translucentRects, wynnvista$translucentCount);
        } else {
            WYNNVISTA_LOGGER.warn("Voxy terrain mask UNSUPPORTED for pipeline {} (shaders patched={}, locations={}/{}/{}/{}); "
                            + "Voxy terrain is rendered unmasked",
                    pipelineName, VoxyMaskState.shadersPatched(), wynnvista$opaqueRects, wynnvista$opaqueCount,
                    wynnvista$translucentRects, wynnvista$translucentCount);
        }
    }

    @Inject(method = "renderOpaque", at = @At("HEAD"))
    private void wynnvista$beginFrame(MDICViewport viewport, CallbackInfo ci) {
        if (wynnvista$exact && wynnvista$viewports.add(System.identityHashCode(viewport))) {
            // Iris shadow passes render through their own viewport; each one is masked with its own origin.
            WYNNVISTA_LOGGER.info("Voxy terrain mask now covers viewport #{} (base section {}, {})",
                    wynnvista$viewports.size(), viewport.section.x, viewport.section.z);
        }
        wynnvista$snapshot = WynnVistaMod.refreshForRender(MinecraftClient.getInstance());
        wynnvista$upload(viewport);
        if (wynnvista$exact && wynnvista$snapshot.revision() != wynnvista$loggedOpaque) {
            wynnvista$loggedOpaque = wynnvista$snapshot.revision();
            WYNNVISTA_LOGGER.info("Voxy terrain mask revision {} bound for opaque pass: mode={}",
                    wynnvista$snapshot.revision(), wynnvista$snapshot.mode());
        }
    }

    @Inject(method = "renderTemporal", at = @At("HEAD"))
    private void wynnvista$temporal(MDICViewport viewport, CallbackInfo ci) {
        wynnvista$upload(viewport);
        if (wynnvista$exact && wynnvista$snapshot != null && wynnvista$snapshot.revision() != wynnvista$loggedTemporal) {
            wynnvista$loggedTemporal = wynnvista$snapshot.revision();
            WYNNVISTA_LOGGER.info("Voxy terrain mask revision {} bound for temporal pass: mode={}",
                    wynnvista$snapshot.revision(), wynnvista$snapshot.mode());
        }
    }

    @Inject(method = "renderTranslucent", at = @At("HEAD"))
    private void wynnvista$translucent(MDICViewport viewport, CallbackInfo ci) {
        wynnvista$upload(viewport);
        if (wynnvista$exact && wynnvista$snapshot != null && wynnvista$snapshot.revision() != wynnvista$loggedTranslucent) {
            wynnvista$loggedTranslucent = wynnvista$snapshot.revision();
            WYNNVISTA_LOGGER.info("Voxy terrain mask revision {} bound for translucent pass: mode={}",
                    wynnvista$snapshot.revision(), wynnvista$snapshot.mode());
        }
    }

    /** Uploads the frame snapshot to both programs; glProgramUniform needs no bound program. */
    @Unique
    private void wynnvista$upload(MDICViewport viewport) {
        VisibilitySnapshot snapshot = wynnvista$snapshot;
        if (!wynnvista$exact || snapshot == null) return;
        float[] rects = VoxyMaskUniforms.pack(snapshot, (long) viewport.section.x << 5, (long) viewport.section.z << 5);
        int count = VoxyMaskUniforms.count(snapshot);
        GL41C.glProgramUniform4fv(terrainShader.id(), wynnvista$opaqueRects, rects);
        GL41C.glProgramUniform1i(terrainShader.id(), wynnvista$opaqueCount, count);
        GL41C.glProgramUniform4fv(translucentTerrainShader.id(), wynnvista$translucentRects, rects);
        GL41C.glProgramUniform1i(translucentTerrainShader.id(), wynnvista$translucentCount, count);
    }
}
