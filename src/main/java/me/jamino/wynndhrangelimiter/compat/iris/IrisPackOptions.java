package me.jamino.wynndhrangelimiter.compat.iris;

import me.jamino.wynndhrangelimiter.effects.EffectFog;
import com.mojang.blaze3d.textures.GpuTexture;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.compat.dh.DHCompat;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.option.OptionSet;
import net.irisshaders.iris.shaderpack.option.values.OptionValues;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;

import net.minecraft.client.texture.GlTexture;

import java.lang.reflect.Field;

/**
 * Reads the active shader pack's option values, sun path, colour buffers and depth copies. These are Iris internals, not its API, so they are kept in
 * this class alone: {@link IrisSupport#packOptions()} catches the linkage error of a build that lacks them.
 */
final class IrisPackOptions {
    private static ShaderPack pack;
    private static Field renderTargets;
    private static EffectFog.PackOptions options;

    private IrisPackOptions() {}

    /** The active pack's {@code sunPathRotation} in degrees; 0 while no pipeline exists. */
    static float sunPathRotation() {
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        return pipeline == null ? 0 : pipeline.getSunPathRotation();
    }

    /**
     * GL name of the texture holding the finished contents of one of the pack's colour buffers
     * ({@code colortexN}), or 0 when the pack has no such buffer. After the final pass Iris has copied
     * every buffer that was written this frame back into its main texture.
     */
    static int colorTexture(int index) throws ReflectiveOperationException {
        RenderTargets targets = renderTargets();
        if (targets == null || index < 0 || index >= targets.getRenderTargetCount()) return 0;
        RenderTarget target = targets.get(index);
        return target == null ? 0 : target.getMainTexture();
    }

    /**
     * GL name of the pack's {@code depthtex1}: the depth of the world as it was just before translucents,
     * water among them, were drawn. 0 while no pack renders.
     */
    static int opaqueDepthTexture() throws ReflectiveOperationException {
        RenderTargets targets = renderTargets();
        GpuTexture depth = targets == null ? null : targets.getDepthTextureNoTranslucents();
        return depth instanceof GlTexture texture ? texture.getGlId() : 0;
    }

    /**
     * GL names of the two depth textures Iris gives a pack for Distant Horizons, with its water
     * ({@code dhDepthTex0}) and from before it ({@code dhDepthTex1}); null while there are none.
     */
    static int[] dhDepthTextures() {
        if (!(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline)) return null;
        DHCompat compat = pipeline.getDHCompat();
        if (compat == null) return null;
        int surface = compat.getDepthTex();
        int opaque = compat.getDepthTexNoTranslucent();
        return surface > 0 && opaque > 0 ? new int[] {surface, opaque} : null;
    }

    private static RenderTargets renderTargets() throws ReflectiveOperationException {
        if (!(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline)) return null;
        if (renderTargets == null) {
            renderTargets = IrisRenderingPipeline.class.getDeclaredField("renderTargets");
            renderTargets.setAccessible(true);
        }
        return (RenderTargets) renderTargets.get(pipeline);
    }

    /** Null when Iris has no pack loaded. Changing an option reloads the pack, so one reading per pack holds. */
    static EffectFog.PackOptions current() {
        ShaderPack current = Iris.getCurrentPack().orElse(null);
        if (current == null) {
            pack = null;
            options = null;
            return null;
        }
        if (current != pack) {
            String name = String.valueOf(Iris.getCurrentPackName());
            OptionValues values = current.getShaderPackOptions().getOptionValues();
            OptionSet set = values.getOptionSet();
            pack = current;
            options = new EffectFog.PackOptions() {
                @Override public String name() { return name; }

                @Override
                public boolean defines(String option) {
                    return set.getStringOptions().containsKey(option) || set.getBooleanOptions().containsKey(option);
                }

                @Override
                public String value(String option) {
                    return set.getStringOptions().containsKey(option) ? values.getStringValueOrDefault(option) : null;
                }

                @Override
                public boolean enabled(String option, boolean fallback) {
                    return set.getBooleanOptions().containsKey(option) ? values.getBooleanValueOrDefault(option) : fallback;
                }
            };
        }
        return options;
    }
}
