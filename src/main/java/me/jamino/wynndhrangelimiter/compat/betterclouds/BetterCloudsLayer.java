package me.jamino.wynndhrangelimiter.compat.betterclouds;

import me.jamino.wynndhrangelimiter.compat.iris.IrisSupport;
import me.jamino.wynndhrangelimiter.effects.CloudLayer;
import me.jamino.wynndhrangelimiter.effects.WorldEffects;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Hands the Better Clouds layer to the world effects. Better Clouds blends its clouds into the world image
 * and writes their depth into Minecraft's depth buffer, which leaves neither the clouds' opacity nor the
 * terrain behind them to be read afterwards. So the depth is copied just before that draw, and the draw is
 * repeated into a buffer of its own, which then holds the clouds alone.
 *
 * <p>Better Clouds draws with raw GL calls, so the state touched here is read back from the driver and put
 * back exactly.
 */
public final class BetterCloudsLayer implements CloudLayer.Provider {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista-effects");
    private static final BetterCloudsLayer INSTANCE = new BetterCloudsLayer();

    private int width;
    private int height;
    private int depthFormat;
    private int terrainFramebuffer;
    private int terrainDepth;
    private int layerFramebuffer;
    private int layerColor;
    private int layerDepth;
    private boolean captured;
    private boolean failed;

    private BetterCloudsLayer() {}

    public static void register() {
        CloudLayer.register(INSTANCE);
        LOGGER.info("Better Clouds layer registered for world effects");
    }

    /** Called from the mixin in place of the draw that shades the clouds into the world image. */
    public static void draw(Runnable shading) {
        INSTANCE.capture(shading);
    }

    private void capture(Runnable shading) {
        if (failed || !WorldEffects.active()) {
            shading.run();
            return;
        }
        boolean copied = false;
        try {
            copied = copyTerrainDepth();
        } catch (RuntimeException e) {
            fail(e);
        }
        shading.run();
        if (!copied) return;
        try {
            drawLayer(shading);
            captured = true;
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    private void fail(RuntimeException e) {
        failed = true;
        LOGGER.error("Better Clouds layer is unusable; its clouds will hide world effects", e);
    }

    /** Copies the depth of the framebuffer the clouds are about to be drawn into. */
    private boolean copyTerrainDepth() {
        int target = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        if (target == 0 || GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER,
                GL30C.GL_DEPTH_ATTACHMENT, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL11C.GL_TEXTURE) return false;
        int texture = GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER,
                GL30C.GL_DEPTH_ATTACHMENT, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        int read = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int boundTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        boolean depthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        try {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
            int targetWidth = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_WIDTH);
            int targetHeight = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_HEIGHT);
            int format = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT);
            if (targetWidth <= 0 || targetHeight <= 0 || pixelFormat(format) == 0) return false;
            if (targetWidth != width || targetHeight != height || format != depthFormat) {
                allocate(targetWidth, targetHeight, format);
            }
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glDepthMask(true);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, target);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, terrainFramebuffer);
            GL30C.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11C.GL_DEPTH_BUFFER_BIT, GL11C.GL_NEAREST);
            return true;
        } finally {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, read);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, target);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, boundTexture);
            GL11C.glDepthMask(depthMask);
            toggle(GL11C.GL_SCISSOR_TEST, scissor);
        }
    }

    /** Repeats the cloud draw into an empty buffer. Its blending leaves premultiplied colour, and opacity in alpha. */
    private void drawLayer(Runnable shading) {
        int target = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        boolean depthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Better Clouds sets the first draw buffer's mask apart from the others.
            ByteBuffer firstMask = stack.malloc(4);
            ByteBuffer otherMask = stack.malloc(4);
            GL30C.glGetBooleani_v(GL11C.GL_COLOR_WRITEMASK, 0, firstMask);
            GL30C.glGetBooleani_v(GL11C.GL_COLOR_WRITEMASK, 1, otherMask);
            float[] clearColor = new float[4];
            GL11C.glGetFloatv(GL11C.GL_COLOR_CLEAR_VALUE, clearColor);
            double clearDepth = GL11C.glGetDouble(GL11C.GL_DEPTH_CLEAR_VALUE);
            try {
                GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, layerFramebuffer);
                GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
                GL11C.glDepthMask(true);
                GL11C.glColorMask(true, true, true, true);
                GL11C.glClearColor(0, 0, 0, 0);
                GL11C.glClearDepth(1);
                GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT | GL11C.GL_DEPTH_BUFFER_BIT);
                GL11C.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
                GL11C.glClearDepth(clearDepth);
                GL11C.glColorMask(otherMask.get(0) != 0, otherMask.get(1) != 0, otherMask.get(2) != 0, otherMask.get(3) != 0);
                GL30C.glColorMaski(0, firstMask.get(0) != 0, firstMask.get(1) != 0, firstMask.get(2) != 0, firstMask.get(3) != 0);
                GL11C.glDepthMask(depthMask);
                toggle(GL11C.GL_SCISSOR_TEST, scissor);
                shading.run();
            } finally {
                GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, target);
            }
        }
    }

    /** Called with the texture unit's 2D binding free to change. The depth format is the target's, so equal depths compare equal. */
    private void allocate(int newWidth, int newHeight, int format) {
        int draw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int read = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int unpackBuffer = GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING);
        try {
            GL21C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
            if (terrainFramebuffer == 0) {
                terrainFramebuffer = GL30C.glGenFramebuffers();
                layerFramebuffer = GL30C.glGenFramebuffers();
                terrainDepth = GL11C.glGenTextures();
                layerDepth = GL11C.glGenTextures();
                layerColor = GL11C.glGenTextures();
            }
            width = 0;
            texture(terrainDepth, format, newWidth, newHeight, pixelFormat(format), pixelType(format));
            texture(layerDepth, format, newWidth, newHeight, pixelFormat(format), pixelType(format));
            texture(layerColor, GL11C.GL_RGBA8, newWidth, newHeight, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE);

            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, terrainFramebuffer);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, terrainDepth, 0);
            GL11C.glDrawBuffer(GL11C.GL_NONE);
            GL11C.glReadBuffer(GL11C.GL_NONE);
            complete("terrain depth copy");
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, layerFramebuffer);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, layerColor, 0);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, layerDepth, 0);
            complete("cloud layer");
            width = newWidth;
            height = newHeight;
            depthFormat = format;
        } finally {
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, draw);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, read);
            GL21C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
        }
    }

    private static void texture(int id, int internalFormat, int width, int height, int format, int type) {
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, id);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, (ByteBuffer) null);
    }

    private static void complete(String name) {
        if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Better Clouds " + name + " buffer is incomplete");
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

    private static void toggle(int capability, boolean enabled) {
        if (enabled) GL11C.glEnable(capability);
        else GL11C.glDisable(capability);
    }

    @Override
    public void beginFrame() {
        captured = false;
    }

    @Override
    public CloudLayer.Layer resolve() {
        if (!captured) return null;
        // A shader pack tone-maps the image after the clouds went in, so their colour there is not this one.
        return new CloudLayer.Layer(layerColor, layerDepth, terrainDepth, !IrisSupport.shaderPackInUse());
    }
}
