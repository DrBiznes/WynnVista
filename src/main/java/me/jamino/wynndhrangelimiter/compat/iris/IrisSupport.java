package me.jamino.wynndhrangelimiter.compat.iris;

import me.jamino.wynndhrangelimiter.effects.EffectFog;
import me.jamino.wynndhrangelimiter.effects.PackClouds;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.api.v0.IrisApi;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * Detects Iris (stock or WynnIris, which {@code provides} {@code iris}) and probes the exact classes and
 * method signatures the DH shader-pack mask patches. Probing reads class bytes without loading the classes.
 */
public final class IrisSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista");
    private static Boolean dhTerrain;
    private static Boolean pipeline;
    private static boolean packOptionsBroken;
    private static boolean sunPathBroken;
    private static boolean packCloudsBroken;
    private static boolean opaqueDepthBroken;
    private static boolean dhDepthBroken;

    private IrisSupport() {}

    public static boolean loaded() {
        return FabricLoader.getInstance().isModLoaded("iris");
    }

    /** Whether a shader pack is rendering the world right now. */
    public static boolean shaderPackInUse() {
        if (!loaded()) return false;
        try {
            return IrisApi.getInstance().isShaderPackInUse();
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }

    /**
     * The active shader pack's options, or null when no pack is rendering. If this Iris build does not expose
     * them the pack is reported as one that defines no options.
     */
    public static EffectFog.PackOptions packOptions() {
        if (!shaderPackInUse()) return null;
        if (!packOptionsBroken) {
            try {
                EffectFog.PackOptions options = IrisPackOptions.current();
                if (options != null) return options;
            } catch (LinkageError | RuntimeException e) {
                packOptionsBroken = true;
                LOGGER.warn("Shader pack options are not readable from this Iris build; world effects will "
                        + "assume default fog settings", e);
            }
        }
        return UNKNOWN_PACK;
    }

    /**
     * Degrees by which the active shader pack tilts the sun's path, 0 when it cannot be read. Only meaningful
     * while {@link #shaderPackInUse()}.
     */
    public static float sunPathRotation() {
        if (sunPathBroken) return 0;
        try {
            float rotation = IrisPackOptions.sunPathRotation();
            return Float.isFinite(rotation) ? rotation : 0;
        } catch (LinkageError | RuntimeException e) {
            sunPathBroken = true;
            LOGGER.warn("The shader pack's sun path is not readable from this Iris build; world effects will "
                    + "be lit from the vanilla sun path", e);
            return 0;
        }
    }

    /**
     * GL names of the pack's colour buffers that hold its clouds, in the order of
     * {@link PackClouds.Kind#first()} and {@code second()} (0 for an unused second), or null when they are
     * not there or not readable.
     */
    public static int[] packCloudTextures(PackClouds.Kind kind) {
        if (packCloudsBroken) return null;
        try {
            if (kind.afterDeferred()) {
                int copy = pipelineSupported() ? IrisCloudCapture.take() : 0;
                return copy > 0 ? new int[] {copy, 0} : null;
            }
            int first = IrisPackOptions.colorTexture(kind.first());
            int second = kind.second() < 0 ? 0 : IrisPackOptions.colorTexture(kind.second());
            return first <= 0 || kind.second() >= 0 && second <= 0 ? null : new int[] {first, second};
        } catch (LinkageError | ReflectiveOperationException | RuntimeException e) {
            packCloudsBroken = true;
            LOGGER.warn("The shader pack's buffers are not readable from this Iris build; its clouds will not "
                    + "hide world effects", e);
            return null;
        }
    }

    /**
     * GL name of the depth the active shader pack is given as the world without its translucents, 0 when
     * no pack renders or it is not readable. Where it differs from the finished depth there is water, or
     * glass.
     */
    public static int opaqueDepthTexture() {
        if (opaqueDepthBroken || !shaderPackInUse()) return 0;
        try {
            return IrisPackOptions.opaqueDepthTexture();
        } catch (LinkageError | ReflectiveOperationException | RuntimeException e) {
            opaqueDepthBroken = true;
            LOGGER.warn("The shader pack's depth buffers are not readable from this Iris build; world effects "
                    + "will not be reflected in nearby water", e);
            return 0;
        }
    }

    /**
     * GL names of Distant Horizons' depth under the active shader pack, with its water and without, or null
     * when no pack renders, DH draws nothing under it or they are not readable.
     */
    public static int[] dhDepthTextures() {
        if (dhDepthBroken || !shaderPackInUse()) return null;
        try {
            return IrisPackOptions.dhDepthTextures();
        } catch (LinkageError | RuntimeException e) {
            dhDepthBroken = true;
            LOGGER.warn("Distant Horizons' depth under a shader pack is not readable from this Iris build; "
                    + "world effects will not be reflected in its water", e);
            return null;
        }
    }

    private static final EffectFog.PackOptions UNKNOWN_PACK = new EffectFog.PackOptions() {
        @Override public String name() { return ""; }
        @Override public boolean defines(String option) { return false; }
        @Override public String value(String option) { return null; }
        @Override public boolean enabled(String option, boolean fallback) { return fallback; }
    };

    /** Whether Iris's pipeline has the shape {@code MixinIrisPipelineClouds} hooks into. */
    public static synchronized boolean pipelineSupported() {
        if (pipeline == null) {
            String owner = "net/irisshaders/iris/pipeline/IrisRenderingPipeline";
            pipeline = loaded() && hasMethod(owner, "beginTranslucents", "()V")
                    && hasField(owner, "renderTargets", "Lnet/irisshaders/iris/targets/RenderTargets;")
                    && hasField(owner, "flippedAfterTranslucent", "Lcom/google/common/collect/ImmutableSet;")
                    && hasMethod("net/irisshaders/iris/pipeline/CompositeRenderer", "renderAll", "()V");
            if (loaded() && !pipeline) {
                LOGGER.info("Iris pipeline hook unavailable (Iris signatures differ); some packs' clouds will "
                        + "not hide world effects");
            }
        }
        return pipeline;
    }

    /** Whether Iris's DH terrain program pipeline has the expected shape for {@code compat.dh.iris}. */
    public static synchronized boolean dhTerrainSupported() {
        if (dhTerrain == null) {
            dhTerrain = loaded()
                    && hasMethod("net/irisshaders/iris/pipeline/transform/TransformPatcher", "patchDHTerrain",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
                            + "Ljava/lang/String;Lit/unimi/dsi/fastutil/objects/Object2ObjectMap;)Ljava/util/Map;")
                    && hasMethod("net/irisshaders/iris/compat/dh/IrisLodRenderProgram", "fillUniformData",
                    "(Lorg/joml/Matrix4fc;Lorg/joml/Matrix4fc;IF)V")
                    && hasField("net/irisshaders/iris/compat/dh/IrisLodRenderProgram", "id", "I");
            if (loaded()) {
                LOGGER.info("Iris DH terrain mask integration {}",
                        dhTerrain ? "supported" : "unavailable (Iris signatures differ)");
            }
        }
        return dhTerrain;
    }

    private static ClassNode read(String internalName) {
        try (InputStream in = IrisSupport.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            if (in == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean hasMethod(String owner, String name, String descriptor) {
        ClassNode node = read(owner);
        if (node == null) return false;
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) return true;
        }
        return false;
    }

    private static boolean hasField(String owner, String name, String descriptor) {
        ClassNode node = read(owner);
        return node != null && node.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(descriptor));
    }
}
