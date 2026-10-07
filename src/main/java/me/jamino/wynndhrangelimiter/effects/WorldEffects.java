package me.jamino.wynndhrangelimiter.effects;

import me.jamino.wynndhrangelimiter.ModConfig;
import me.jamino.wynndhrangelimiter.WynnVistaMod;
import me.jamino.wynndhrangelimiter.visibility.RegionPolicy;
import me.jamino.wynndhrangelimiter.visibility.VisibilitySnapshot;
import me.jamino.wynndhrangelimiter.visibility.WorldContextResolver;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Draws WynnVista's world effects over the finished world image, before the hand and HUD. Effects are
 * ray-marched in a view-covering pass that reads the vanilla depth buffer and, when a LOD mod is rendering,
 * that mod's depth through {@link LodDepth}; they therefore work at any distance, with or without LODs.
 *
 * <p>Each registered {@link WorldEffect} is independent: it has its own config toggle and program, is hidden
 * with the LOD region mask, is skipped when outside the view, and only shades its own screen rectangle.
 */
public final class WorldEffects {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista-effects");
    private static final int NOISE_SIZE = 32;
    /** {@code -Dwynnvista.effects.profile=true} logs the GPU time of the effect pass. */
    private static final boolean PROFILE = Boolean.getBoolean("wynnvista.effects.profile");
    private static final int PROFILE_FRAMES = 100;
    /** Share of the view an effect must cover before it is marched at half resolution. */
    private static final double HALF_RESOLUTION_COVERAGE = 0.12;

    private static final List<WorldEffect> EFFECTS = List.of(new SmokePlume(), new NetherFog());

    private static boolean failed;
    private static boolean created;
    private static int vertexArray;
    private static int framebuffer;
    private static int attachedColor;
    private static int sampler;
    private static int noiseTexture;
    private static EffectProgram upsample;
    private static int lowFramebuffer;
    private static int lowColor;
    private static int lowDistance;
    private static int lowSizeX;
    private static int lowSizeY;
    private static final Map<String, EffectProgram> PROGRAMS = new HashMap<>();
    private static final Map<String, FogProbe> FOG_PROBES = new HashMap<>();
    private static EffectProgram fogProgram;
    private static boolean fogBroken;
    private static final Set<String> BROKEN = new HashSet<>();
    private static String loggedState = "";
    private static int timerQuery;
    private static boolean timerPending;
    private static long profiledNanos;
    private static int profiledFrames;
    private static String profiledDetail = "";
    private static float profiledFog = 1;

    private WorldEffects() {}

    /** Every effect, in draw order; the config screen builds one toggle from each. */
    public static List<WorldEffect> all() {
        return EFFECTS;
    }

    /** Called before the world is rendered, so LOD backends start the frame with no stale depth. */
    public static void beginFrame() {
        LodDepth.beginFrame();
    }

    private record Visible(WorldEffect effect, EffectCulling.ScreenRect rect, double distance) {}

    /**
     * One effect's 1x1 fog measurement. It is smoothed over time, so each frame reads the previous result
     * from one texture and writes the new one to the other.
     */
    private static final class FogProbe {
        final int[] textures = new int[2];
        final int[] framebuffers = new int[2];
        int current;
        long measuredNanos;
    }

    /** Called once the world image is complete. The matrices are the ones vanilla terrain was drawn with. */
    public static void render(MinecraftClient client, Camera camera, Matrix4f view, Matrix4f projection,
                              Vector4f fogColor, float tickProgress) {
        if (failed || !ModConfig.effectsEnabled() || client.world == null || client.player == null) return;
        Vec3d cameraPos = camera.getCameraPos();
        Matrix4f viewProjection = new Matrix4f(projection).mul(view);
        List<Visible> visible = cull(client, cameraPos, viewProjection);
        if (visible.isEmpty()) {
            logState("none visible");
            return;
        }
        Framebuffer main = client.getFramebuffer();
        if (!(main.getColorAttachment() instanceof GlTexture color)
                || !(main.getDepthAttachment() instanceof GlTexture depth)) return;
        EffectFrame frame = new EffectFrame(cameraPos.x, cameraPos.y, cameraPos.z, client.world.getTime(),
                client.world.getTimeOfDay(), tickProgress, client.world.getRainGradient(tickProgress));
        try {
            if (!created) create();
            draw(visible, frame, viewProjection, projection.m11(), fogColor, main, color.getGlId(), depth.getGlId());
        } catch (RuntimeException e) {
            failed = true;
            LOGGER.error("World effects disabled after a rendering error", e);
        }
    }

    /** Everything that can be decided without touching the GPU: config, region mask, distance and frustum. */
    private static List<Visible> cull(MinecraftClient client, Vec3d cameraPos, Matrix4f viewProjection) {
        List<Visible> visible = new ArrayList<>(EFFECTS.size());
        String host = client.getCurrentServerEntry() == null ? "" : client.getCurrentServerEntry().address;
        boolean recognized = RegionPolicy.isWynncraftHost(host) || WorldContextResolver.isFixture(client);
        if (!recognized) return visible;
        VisibilitySnapshot snapshot = WynnVistaMod.refreshForRender(client);
        String dimension = client.world.getRegistryKey().getValue().toString();
        for (WorldEffect effect : EFFECTS) {
            if (!ModConfig.effectEnabled(effect.id()) || BROKEN.contains(effect.id())) continue;
            if (!EffectRegion.shows(snapshot, true, dimension, client.player.getX(), client.player.getZ(),
                    effect.anchorX(), effect.anchorZ())) continue;
            double distance = Math.hypot(effect.anchorX() - cameraPos.x, effect.anchorZ() - cameraPos.z);
            if (distance > effect.maxViewDistance()) continue;
            WorldEffect.Bounds box = effect.bounds();
            EffectCulling.ScreenRect rect = EffectCulling.project(viewProjection,
                    (float) (box.minX() - cameraPos.x), (float) (box.minY() - cameraPos.y),
                    (float) (box.minZ() - cameraPos.z), (float) (box.maxX() - cameraPos.x),
                    (float) (box.maxY() - cameraPos.y), (float) (box.maxZ() - cameraPos.z));
            if (rect != null) visible.add(new Visible(effect, rect, distance));
        }
        return visible;
    }

    private static void draw(List<Visible> visible, EffectFrame frame, Matrix4f viewProjection, float projectionScaleY,
                             Vector4f fogColor, Framebuffer main, int color, int depth) {
        LodDepth.Layer lod = LodDepth.resolve();
        logState(visible.size() + " drawn with " + (lod == null ? "vanilla depth only" : "vanilla + LOD depth"));
        int width = main.textureWidth;
        int height = main.textureHeight;
        Matrix4f sceneInverse = new Matrix4f(viewProjection).invert();

        GlState saved = GlState.capture();
        try {
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDisable(GL11C.GL_CULL_FACE);
            GL11C.glDisable(GL11C.GL_STENCIL_TEST);
            GL11C.glDepthMask(false);
            GL11C.glColorMask(true, true, true, true);
            GL11C.glEnable(GL11C.GL_BLEND);
            GL20C.glBlendEquationSeparate(GL14C.GL_FUNC_ADD, GL14C.GL_FUNC_ADD);
            bind(0, GL11C.GL_TEXTURE_2D, depth);
            bind(1, GL11C.GL_TEXTURE_2D, lod == null ? depth : lod.textureId());
            bind(2, GL12C.GL_TEXTURE_3D, noiseTexture);
            GL30C.glBindVertexArray(vertexArray);

            beginProfile();
            // The fog already in the image is measured before anything is drawn over it.
            GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            for (Visible entry : visible) measureFog(entry, sceneInverse, lod, color);
            GL11C.glEnable(GL11C.GL_BLEND);

            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
            if (attachedColor != color) {
                GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                        GL11C.GL_TEXTURE_2D, color, 0);
                attachedColor = color;
            }
            GL11C.glViewport(0, 0, width, height);
            GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
            // Premultiplied colour over the world image; the target's alpha is left alone.
            GL14C.glBlendFuncSeparate(GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ZERO, GL11C.GL_ONE);
            for (Visible entry : visible) {
                WorldEffect effect = entry.effect();
                EffectProgram program = program(effect);
                if (program == null) continue;
                // Only the pixels the effect's box can cover are shaded at all.
                int x0 = Math.max(0, (int) Math.floor(entry.rect().minX() * width) - 1);
                int y0 = Math.max(0, (int) Math.floor(entry.rect().minY() * height) - 1);
                int x1 = Math.min(width, (int) Math.ceil(entry.rect().maxX() * width) + 1);
                int y1 = Math.min(height, (int) Math.ceil(entry.rect().maxY() * height) + 1);
                if (x1 <= x0 || y1 <= y0) continue;
                int steps = EffectCulling.steps(ModConfig.effectSteps(), Math.max(x1 - x0, y1 - y0));
                int octaves = EffectCulling.octaves(entry.distance(), projectionScaleY, height);
                // A large effect is marched at half resolution and upsampled; a small one is drawn directly.
                boolean half = (long) (x1 - x0) * (y1 - y0) > HALF_RESOLUTION_COVERAGE * width * height;
                int targetWidth = half ? (width + 1) / 2 : width;
                int targetHeight = half ? (height + 1) / 2 : height;

                program.use();
                setScene(program, sceneInverse, lod, targetWidth, targetHeight);
                program.set("uNoise", 2);
                FogProbe fog = FOG_PROBES.get(effect.id());
                bind(6, GL11C.GL_TEXTURE_2D, fog.textures[fog.current]);
                program.set("uFogProbe", 6);
                program.set("uFogColor", fogColor.x, fogColor.y, fogColor.z);
                program.set("uSteps", steps);
                program.set("uOctaves", octaves);
                WorldEffect.Bounds box = effect.bounds();
                program.set("uBoxMin", (float) (box.minX() - frame.cameraX()), (float) (box.minY() - frame.cameraY()),
                        (float) (box.minZ() - frame.cameraZ()));
                program.set("uBoxMax", (float) (box.maxX() - frame.cameraX()), (float) (box.maxY() - frame.cameraY()),
                        (float) (box.maxZ() - frame.cameraZ()));
                effect.upload(program, frame);
                if (half) {
                    drawHalfResolution(sceneInverse, lod, width, height, x0, y0, x1, y1);
                } else {
                    GL11C.glScissor(x0, y0, x1 - x0, y1 - y0);
                    GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
                }
                if (PROFILE) {
                    profiledDetail = effect.id() + " " + (x1 - x0) + "x" + (y1 - y0) + " px of " + width + "x" + height
                            + (half ? " at half resolution, " : ", ") + steps + " steps, " + octaves + " octaves"
                            + String.format(", fog visibility %.2f", profiledFog);
                }
            }
            endProfile();
        } finally {
            saved.restore();
        }
    }

    private static void setScene(EffectProgram program, Matrix4f sceneInverse, LodDepth.Layer lod,
                                 int targetWidth, int targetHeight) {
        program.set("uSceneDepth", 0);
        program.set("uLodDepth", 1);
        program.set("uSceneInverse", sceneInverse);
        program.set("uLodInverse", lod == null ? sceneInverse : lod.inverseViewProjection());
        program.set("uLodParams", lod == null ? 0 : 1, lod == null ? 1 : lod.clearDepth(),
                lod != null && lod.zeroToOne() ? 1 : 0);
        program.set("uViewSize", (float) targetWidth, (float) targetHeight);
    }

    /**
     * Updates an effect's fog probe from the finished world image: how much detail the terrain at the
     * effect's distance still has, and its colour. If the probe shader is unusable the probe keeps its
     * initial "clear".
     */
    private static void measureFog(Visible entry, Matrix4f sceneInverse, LodDepth.Layer lod, int color) {
        FogProbe probe = FOG_PROBES.computeIfAbsent(entry.effect().id(), id -> createFogProbe());
        if (fogBroken) return;
        if (fogProgram == null) {
            try {
                fogProgram = EffectProgram.link("fog_probe.fsh");
            } catch (RuntimeException e) {
                fogBroken = true;
                LOGGER.error("World effects will ignore fog: the fog probe shader is unusable", e);
                return;
            }
        }
        long now = System.nanoTime();
        float rate = probe.measuredNanos == 0 ? 1 : EffectFog.rate((now - probe.measuredNanos) / 1.0e9);
        probe.measuredNanos = now;
        EffectFog.Band band = EffectFog.band(entry.distance());
        EffectFog.Columns columns = EffectFog.columns(entry.rect());

        int previous = probe.current;
        probe.current = 1 - previous;
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, probe.framebuffers[probe.current]);
        GL11C.glViewport(0, 0, 1, 1);
        bind(5, GL11C.GL_TEXTURE_2D, color);
        bind(6, GL11C.GL_TEXTURE_2D, probe.textures[previous]);
        fogProgram.use();
        setScene(fogProgram, sceneInverse, lod, 1, 1);
        fogProgram.set("uSceneColor", 5);
        fogProgram.set("uPrevious", 6);
        fogProgram.set("uColumns", columns.min(), columns.max());
        fogProgram.set("uBand", band.near(), band.far());
        fogProgram.set("uContrast", EffectFog.CONTRAST_GONE, EffectFog.CONTRAST_CLEAR);
        fogProgram.set("uRate", rate);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);

        if (PROFILE && profiledFrames == 0) {
            int packBuffer = GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
            GL21C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
            float[] value = new float[4];
            GL11C.glReadPixels(0, 0, 1, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT, value);
            GL21C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, packBuffer);
            profiledFog = value[3];
        }
    }

    private static FogProbe createFogProbe() {
        FogProbe probe = new FogProbe();
        GL13C.glActiveTexture(GL13C.GL_TEXTURE6);
        resetUnpack();
        for (int i = 0; i < 2; i++) {
            probe.textures[i] = GL11C.glGenTextures();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, probe.textures[i]);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
            // No fog colour, full visibility. Full floats: at high frame rates each step of the smoothing
            // is smaller than a half float can hold near 1.
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA32F, 1, 1, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT,
                    new float[] {0, 0, 0, 1});
            probe.framebuffers[i] = GL30C.glGenFramebuffers();
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, probe.framebuffers[i]);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D,
                    probe.textures[i], 0);
            if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Fog probe buffer is incomplete");
            }
        }
        return probe;
    }

    /** Client memory, tightly packed: Minecraft leaves its own unpack state behind. */
    private static void resetUnpack() {
        GL21C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, 0);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, 0);
        GL11C.glPixelStorei(GL12C.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_IMAGES, 0);
    }

    /**
     * Marches the bound effect into a half-size buffer, then composites it over the world image with
     * {@code upsample.fsh}. Called with the effect's program bound and its uniforms set for the half-size target.
     */
    private static void drawHalfResolution(Matrix4f sceneInverse, LodDepth.Layer lod, int width, int height,
                                           int x0, int y0, int x1, int y1) {
        int lowWidth = (width + 1) / 2;
        int lowHeight = (height + 1) / 2;
        if (lowFramebuffer == 0 || lowWidth != lowSizeX || lowHeight != lowSizeY) createLowResolution(lowWidth, lowHeight);
        if (upsample == null) upsample = EffectProgram.link("upsample.fsh");

        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, lowFramebuffer);
        GL11C.glViewport(0, 0, lowWidth, lowHeight);
        // One texel wider than the effect, so the upsample always finds marched neighbours.
        int lx0 = Math.max(0, x0 / 2 - 1);
        int ly0 = Math.max(0, y0 / 2 - 1);
        GL11C.glScissor(lx0, ly0, Math.min(lowWidth, (x1 + 1) / 2 + 1) - lx0, Math.min(lowHeight, (y1 + 1) / 2 + 1) - ly0);
        GL11C.glDisable(GL11C.GL_BLEND);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);

        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
        GL11C.glViewport(0, 0, width, height);
        GL11C.glScissor(x0, y0, x1 - x0, y1 - y0);
        GL11C.glEnable(GL11C.GL_BLEND);
        bind(3, GL11C.GL_TEXTURE_2D, lowColor);
        bind(4, GL11C.GL_TEXTURE_2D, lowDistance);
        upsample.use();
        setScene(upsample, sceneInverse, lod, width, height);
        upsample.set("uLowColor", 3);
        upsample.set("uLowDistance", 4);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
    }

    private static void createLowResolution(int lowWidth, int lowHeight) {
        if (lowFramebuffer == 0) {
            lowFramebuffer = GL30C.glGenFramebuffers();
            lowColor = GL11C.glGenTextures();
            lowDistance = GL11C.glGenTextures();
        }
        GL13C.glActiveTexture(GL13C.GL_TEXTURE3);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, lowColor);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA16F, lowWidth, lowHeight, 0, GL11C.GL_RGBA,
                GL11C.GL_FLOAT, (ByteBuffer) null);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, lowDistance);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_R32F, lowWidth, lowHeight, 0, GL11C.GL_RED,
                GL11C.GL_FLOAT, (ByteBuffer) null);
        int bound = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, lowFramebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, lowColor, 0);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT1, GL11C.GL_TEXTURE_2D, lowDistance, 0);
        GL20C.glDrawBuffers(new int[] {GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_COLOR_ATTACHMENT1});
        if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Half-resolution effect buffer is incomplete");
        }
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, bound);
        lowSizeX = lowWidth;
        lowSizeY = lowHeight;
    }

    /** Compiles an effect's program on first use; a shader that fails disables only that effect. */
    private static EffectProgram program(WorldEffect effect) {
        EffectProgram program = PROGRAMS.get(effect.id());
        if (program != null) return program;
        try {
            program = EffectProgram.link(effect.shader());
            PROGRAMS.put(effect.id(), program);
            LOGGER.info("World effect '{}' compiled and linked", effect.id());
            return program;
        } catch (RuntimeException e) {
            BROKEN.add(effect.id());
            LOGGER.error("World effect '{}' disabled: its shader is unusable", effect.id(), e);
            return null;
        }
    }

    private static void logState(String state) {
        if (state.equals(loggedState)) return;
        loggedState = state;
        LOGGER.info("World effects: {}", state);
    }

    private static void beginProfile() {
        if (!PROFILE) return;
        if (timerQuery == 0) timerQuery = GL15C.glGenQueries();
        if (timerPending) {
            // Last frame's result; waiting here is fine for a diagnostic run.
            profiledNanos += GL33C.glGetQueryObjecti64(timerQuery, GL15C.GL_QUERY_RESULT);
            if (++profiledFrames == PROFILE_FRAMES) {
                LOGGER.info("World effects GPU time: {} ms/frame over {} frames ({})",
                        String.format("%.3f", profiledNanos / 1.0e6 / PROFILE_FRAMES), PROFILE_FRAMES, profiledDetail);
                profiledNanos = 0;
                profiledFrames = 0;
            }
        }
        GL15C.glBeginQuery(GL33C.GL_TIME_ELAPSED, timerQuery);
    }

    private static void endProfile() {
        if (!PROFILE) return;
        GL15C.glEndQuery(GL33C.GL_TIME_ELAPSED);
        timerPending = true;
    }

    private static void bind(int unit, int target, int texture) {
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
        GL11C.glBindTexture(target, texture);
        GL33C.glBindSampler(unit, sampler);
    }

    private static void create() {
        GlState saved = GlState.capture();
        try {
            vertexArray = GL30C.glGenVertexArrays();
            framebuffer = GL30C.glGenFramebuffers();
            // Depth textures are read texel-exact, the noise smoothly and repeating; a sampler object keeps
            // this independent of whatever filtering the textures' owners set.
            sampler = GL33C.glGenSamplers();
            GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
            GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
            GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_WRAP_S, GL11C.GL_REPEAT);
            GL33C.glSamplerParameteri(sampler, GL11C.GL_TEXTURE_WRAP_T, GL11C.GL_REPEAT);
            GL33C.glSamplerParameteri(sampler, GL12C.GL_TEXTURE_WRAP_R, GL11C.GL_REPEAT);
            GL33C.glSamplerParameteri(sampler, GL14C.GL_TEXTURE_COMPARE_MODE, GL11C.GL_NONE);
            noiseTexture = createNoise();
            created = true;
        } finally {
            saved.restore();
        }
    }

    private static int createNoise() {
        ByteBuffer pixels = MemoryUtil.memAlloc(NOISE_SIZE * NOISE_SIZE * NOISE_SIZE);
        try {
            byte[] values = new byte[pixels.capacity()];
            new Random(0x57594E4EL).nextBytes(values);
            pixels.put(values).flip();
            int texture = GL11C.glGenTextures();
            GL13C.glActiveTexture(GL13C.GL_TEXTURE2);
            GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, texture);
            GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
            resetUnpack();
            GL12C.glTexImage3D(GL12C.GL_TEXTURE_3D, 0, GL30C.GL_R8, NOISE_SIZE, NOISE_SIZE, NOISE_SIZE, 0,
                    GL11C.GL_RED, GL11C.GL_UNSIGNED_BYTE, pixels);
            return texture;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    /**
     * The GL state this pass touches. Minecraft and the LOD mods cache GL state, so everything is read
     * back from the driver and put back exactly, leaving their caches valid.
     */
    private record GlState(int drawFramebuffer, int readFramebuffer, int program, int vertexArray, int activeTexture,
                           int[] textures, int[] samplers, int[] viewport, int[] scissorBox, boolean blend,
                           boolean depthTest, boolean cull, boolean scissor, boolean stencil, boolean depthMask,
                           boolean[] colorMask, int[] blendFunc, int[] blendEquation, int unpackBuffer,
                           int[] pixelStore) {
        private static final int[] TARGETS = {GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_3D,
                GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_2D};
        private static final int[] BINDINGS = {GL11C.GL_TEXTURE_BINDING_2D, GL11C.GL_TEXTURE_BINDING_2D,
                GL12C.GL_TEXTURE_BINDING_3D, GL11C.GL_TEXTURE_BINDING_2D, GL11C.GL_TEXTURE_BINDING_2D,
                GL11C.GL_TEXTURE_BINDING_2D, GL11C.GL_TEXTURE_BINDING_2D};
        private static final int[] PIXEL_STORE = {GL11C.GL_UNPACK_ALIGNMENT, GL11C.GL_UNPACK_ROW_LENGTH,
                GL11C.GL_UNPACK_SKIP_PIXELS, GL11C.GL_UNPACK_SKIP_ROWS, GL12C.GL_UNPACK_IMAGE_HEIGHT,
                GL12C.GL_UNPACK_SKIP_IMAGES};

        static GlState capture() {
            int active = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
            int[] textures = new int[TARGETS.length];
            int[] samplers = new int[TARGETS.length];
            for (int unit = 0; unit < TARGETS.length; unit++) {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
                textures[unit] = GL11C.glGetInteger(BINDINGS[unit]);
                samplers[unit] = GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING);
            }
            GL13C.glActiveTexture(active);
            int[] viewport = new int[4];
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
            int[] scissorBox = new int[4];
            GL11C.glGetIntegerv(GL11C.GL_SCISSOR_BOX, scissorBox);
            boolean[] colorMask = new boolean[4];
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer mask = stack.malloc(4);
                GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, mask);
                for (int i = 0; i < 4; i++) colorMask[i] = mask.get(i) != 0;
            }
            int[] pixelStore = new int[PIXEL_STORE.length];
            for (int i = 0; i < PIXEL_STORE.length; i++) pixelStore[i] = GL11C.glGetInteger(PIXEL_STORE[i]);
            return new GlState(GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING),
                    GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING),
                    GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM), GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING),
                    active, textures, samplers, viewport, scissorBox, GL11C.glIsEnabled(GL11C.GL_BLEND),
                    GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST), GL11C.glIsEnabled(GL11C.GL_CULL_FACE),
                    GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST), GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST),
                    GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK), colorMask,
                    new int[] {GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB), GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB),
                            GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA), GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA)},
                    new int[] {GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_RGB),
                            GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_ALPHA)},
                    GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING), pixelStore);
        }

        void restore() {
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer);
            GL20C.glUseProgram(program);
            GL30C.glBindVertexArray(vertexArray);
            for (int unit = 0; unit < TARGETS.length; unit++) {
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
                GL11C.glBindTexture(TARGETS[unit], textures[unit]);
                GL33C.glBindSampler(unit, samplers[unit]);
            }
            GL13C.glActiveTexture(activeTexture);
            GL11C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL11C.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
            toggle(GL11C.GL_BLEND, blend);
            toggle(GL11C.GL_DEPTH_TEST, depthTest);
            toggle(GL11C.GL_CULL_FACE, cull);
            toggle(GL11C.GL_SCISSOR_TEST, scissor);
            toggle(GL11C.GL_STENCIL_TEST, stencil);
            GL11C.glDepthMask(depthMask);
            GL11C.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            GL14C.glBlendFuncSeparate(blendFunc[0], blendFunc[1], blendFunc[2], blendFunc[3]);
            GL20C.glBlendEquationSeparate(blendEquation[0], blendEquation[1]);
            GL21C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
            for (int i = 0; i < PIXEL_STORE.length; i++) GL11C.glPixelStorei(PIXEL_STORE[i], pixelStore[i]);
        }

        private static void toggle(int capability, boolean enabled) {
            if (enabled) GL11C.glEnable(capability);
            else GL11C.glDisable(capability);
        }
    }
}
