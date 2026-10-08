package me.jamino.wynndhrangelimiter.compat.iris;

import me.jamino.wynndhrangelimiter.effects.PackClouds;
import me.jamino.wynndhrangelimiter.effects.WorldEffects;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL30C;

import java.nio.ByteBuffer;
import java.util.Set;

/**
 * Keeps a copy of a shader pack's cloud buffer from the moment its deferred passes have drawn the clouds,
 * for packs that reuse that buffer for something else before the frame is finished
 * ({@link PackClouds.Kind#afterDeferred()}). Called from {@code MixinIrisPipelineClouds}.
 */
public final class IrisCloudCapture {
    private static int texture;
    private static int source;
    private static int target;
    private static int width;
    private static int height;
    private static boolean captured;

    private IrisCloudCapture() {}

    /**
     * @param flipped the buffers whose newest contents are in their alternate texture once the deferred
     *                passes have run
     */
    public static void afterDeferred(RenderTargets targets, Set<Integer> flipped) {
        PackClouds.Kind kind = WorldEffects.packCloudsWanted();
        if (kind == null || !kind.afterDeferred() || kind.first() >= targets.getRenderTargetCount()) return;
        RenderTarget buffer = targets.get(kind.first());
        if (buffer == null) return;
        int from = flipped.contains(kind.first()) ? buffer.getAltTexture() : buffer.getMainTexture();
        if (from <= 0 || buffer.getWidth() <= 0 || buffer.getHeight() <= 0) return;

        // Iris and Minecraft cache what is bound, so everything touched is read back and put back.
        int read = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int draw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int bound = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        try {
            if (texture == 0) {
                texture = GL11C.glGenTextures();
                source = GL30C.glGenFramebuffers();
                target = GL30C.glGenFramebuffers();
            }
            if (width != buffer.getWidth() || height != buffer.getHeight()) {
                width = buffer.getWidth();
                height = buffer.getHeight();
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
                GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA16F, width, height, 0, GL11C.GL_RGBA,
                        GL11C.GL_FLOAT, (ByteBuffer) null);
                GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, target);
                GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                        GL11C.GL_TEXTURE_2D, texture, 0);
            }
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, source);
            GL30C.glFramebufferTexture2D(GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D,
                    from, 0);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, target);
            boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
            if (scissor) GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL30C.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
            if (scissor) GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
            captured = true;
        } finally {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, read);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, draw);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, bound);
        }
    }

    /** The copy made since the last call, or 0 if the pack's deferred passes did not run in between. */
    static int take() {
        if (!captured) return 0;
        captured = false;
        return texture;
    }
}
