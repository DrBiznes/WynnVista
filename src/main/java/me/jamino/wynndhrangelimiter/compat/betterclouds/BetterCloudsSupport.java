package me.jamino.wynndhrangelimiter.compat.betterclouds;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * Detects Better Clouds and probes the one call {@code MixinBetterCloudsRenderer} wraps, so a build of it
 * with a different renderer is left alone instead of failing to load. Reads class bytes without loading the class.
 */
public final class BetterCloudsSupport {
    public static final String RENDERER = "com.qendolin.betterclouds.clouds.Renderer";
    public static final String SHADING_METHOD = "drawShading";
    public static final String DRAW_OWNER = "org/lwjgl/opengl/GL32";
    public static final String DRAW_NAME = "glDrawArrays";
    public static final String DRAW_DESCRIPTOR = "(III)V";

    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista");
    private static Boolean supported;

    private BetterCloudsSupport() {}

    public static boolean loaded() {
        return FabricLoader.getInstance().isModLoaded("betterclouds");
    }

    /** Whether Better Clouds shades its clouds into the world image the way the mixin expects. */
    public static synchronized boolean supported() {
        if (supported == null) {
            supported = loaded() && drawsInShading();
            if (loaded()) {
                LOGGER.info("Better Clouds world effect integration {}",
                        supported ? "supported" : "unavailable (its renderer differs); its clouds will hide world effects");
            }
        }
        return supported;
    }

    private static boolean drawsInShading() {
        String resource = RENDERER.replace('.', '/') + ".class";
        try (InputStream in = BetterCloudsSupport.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) return false;
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            for (MethodNode method : node.methods) {
                if (!method.name.equals(SHADING_METHOD)) continue;
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call && call.owner.equals(DRAW_OWNER)
                            && call.name.equals(DRAW_NAME) && call.desc.equals(DRAW_DESCRIPTOR)) return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}
