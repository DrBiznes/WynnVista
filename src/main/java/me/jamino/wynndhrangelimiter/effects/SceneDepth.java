package me.jamino.wynndhrangelimiter.effects;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * The vanilla depth the world effects read. That is the game's own depth buffer, except in a frame where the
 * game clears it before the effects run: the last pass of the world, {@code late_debug}, clears the depth so
 * that gizmos marked "always on top" are drawn over everything, and any mod may submit such a gizmo, as is
 * seen for the outline of a targeted resource node. With the depth cleared the effects would find no terrain
 * in front of them and be drawn over the whole view, so the depth is copied just before that clear and the
 * copy is read for the rest of the frame.
 *
 * <p>The state touched here is read back from the driver and put back exactly, leaving the game's cache of it
 * valid.
 */
public final class SceneDepth {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista-effects");

    private static int width;
    private static int height;
    private static int format;
    private static int readFramebuffer;
    private static int copyFramebuffer;
    private static int copy;
    private static boolean kept;
    private static boolean failed;

    private SceneDepth() {}

    /** Called before the world is rendered. */
    static void beginFrame() {
        kept = false;
    }

    /** The texture holding this frame's vanilla depth, given the game's own depth texture. */
    static int texture(int gameDepth) {
        return kept ? copy : gameDepth;
    }

    /** Called just before the game clears {@code gameDepth}, its depth texture, while the world image is not yet complete. */
    public static void keep(int gameDepth) {
        if (failed || !WorldEffects.active() || gameDepth <= 0) return;
        try {
            kept = copyFrom(gameDepth);
        } catch (RuntimeException e) {
            failed = true;
            LOGGER.error("The world's depth could not be kept; world effects will be drawn over the view while "
                    + "something is drawn on top of the world", e);
        }
    }

    private static boolean copyFrom(int gameDepth) {
        int draw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int read = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int boundTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        int unpackBuffer = GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING);
        boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        boolean depthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        try {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, gameDepth);
            int gameWidth = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_WIDTH);
            int gameHeight = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_HEIGHT);
            int gameFormat = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT);
            if (gameWidth <= 0 || gameHeight <= 0 || pixelFormat(gameFormat) == 0) return false;
            if (copyFramebuffer == 0) {
                readFramebuffer = GL30C.glGenFramebuffers();
                copyFramebuffer = GL30C.glGenFramebuffers();
                copy = GL11C.glGenTextures();
            }
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, copyFramebuffer);
            if (gameWidth != width || gameHeight != height || gameFormat != format) {
                // The game's own format, so that a depth blit between the two is allowed.
                width = 0;
                GL21C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, copy);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
                GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, gameFormat, gameWidth, gameHeight, 0,
                        pixelFormat(gameFormat), pixelType(gameFormat), (ByteBuffer) null);
                GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                        GL11C.GL_TEXTURE_2D, copy, 0);
                GL11C.glDrawBuffer(GL11C.GL_NONE);
                if (GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("Scene depth copy buffer is incomplete");
                }
                width = gameWidth;
                height = gameHeight;
                format = gameFormat;
            }
            // Attached anew every time: the game recreates its depth texture when the view is resized.
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer);
            GL30C.glFramebufferTexture2D(GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT,
                    GL11C.GL_TEXTURE_2D, gameDepth, 0);
            GL11C.glReadBuffer(GL11C.GL_NONE);
            if (GL30C.glCheckFramebufferStatus(GL30C.GL_READ_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Scene depth read buffer is incomplete");
            }
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glDepthMask(true);
            GL30C.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11C.GL_DEPTH_BUFFER_BIT,
                    GL11C.GL_NEAREST);
            return true;
        } finally {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, read);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, draw);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, boundTexture);
            GL21C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
            GL11C.glDepthMask(depthMask);
            if (scissor) GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
            else GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        }
    }

    /** The pixel format to allocate a depth texture of this internal format with; 0 for anything else. */
    private static int pixelFormat(int internalFormat) {
        return switch (internalFormat) {
            case GL11C.GL_DEPTH_COMPONENT, GL14C.GL_DEPTH_COMPONENT16, GL14C.GL_DEPTH_COMPONENT24,
                 GL14C.GL_DEPTH_COMPONENT32, GL30C.GL_DEPTH_COMPONENT32F -> GL11C.GL_DEPTH_COMPONENT;
            case GL30C.GL_DEPTH24_STENCIL8, GL30C.GL_DEPTH32F_STENCIL8 -> GL30C.GL_DEPTH_STENCIL;
            default -> 0;
        };
    }

    private static int pixelType(int internalFormat) {
        return switch (internalFormat) {
            case GL30C.GL_DEPTH24_STENCIL8 -> GL30C.GL_UNSIGNED_INT_24_8;
            case GL30C.GL_DEPTH32F_STENCIL8 -> GL30C.GL_FLOAT_32_UNSIGNED_INT_24_8_REV;
            default -> GL11C.GL_FLOAT;
        };
    }
}
