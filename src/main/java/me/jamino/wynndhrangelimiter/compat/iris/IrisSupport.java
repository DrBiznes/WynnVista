package me.jamino.wynndhrangelimiter.compat.iris;

import net.fabricmc.loader.api.FabricLoader;
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

    private IrisSupport() {}

    public static boolean loaded() {
        return FabricLoader.getInstance().isModLoaded("iris");
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
