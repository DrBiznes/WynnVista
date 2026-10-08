package me.jamino.wynndhrangelimiter.compat.iris;

import me.jamino.wynndhrangelimiter.effects.EffectFog;
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
    private static boolean packOptionsBroken;

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

    private static final EffectFog.PackOptions UNKNOWN_PACK = new EffectFog.PackOptions() {
        @Override public String name() { return ""; }
        @Override public boolean defines(String option) { return false; }
        @Override public String value(String option) { return null; }
        @Override public boolean enabled(String option, boolean fallback) { return fallback; }
    };

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
