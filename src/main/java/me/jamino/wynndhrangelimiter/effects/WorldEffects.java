package me.jamino.wynndhrangelimiter.effects;

import me.jamino.wynndhrangelimiter.ModConfig;
import me.jamino.wynndhrangelimiter.WynnVistaMod;
import me.jamino.wynndhrangelimiter.compat.iris.IrisSupport;
import me.jamino.wynndhrangelimiter.visibility.RegionPolicy;
import me.jamino.wynndhrangelimiter.visibility.VisibilitySnapshot;
import me.jamino.wynndhrangelimiter.visibility.WorldContextResolver;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.attribute.EnvironmentAttributes;
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
    /** Blocks after which the ripples on water repeat: the noise texture's size times RIPPLE_SIZE in scene.glsl. */
    private static final double RIPPLE_PERIOD = NOISE_SIZE * 6.0;
    /** Ticks after which they repeat in time: the same size, at half a noise cell per second. */
    private static final long RIPPLE_TICKS = NOISE_SIZE * 2 * 20;

    private static final List<WorldEffect> EFFECTS;

    static {
        List<WorldEffect> effects = new ArrayList<>();
        effects.add(SmokePlume.MOUNT_WYNN);
        effects.addAll(SmokePlume.VOLCANIC_ISLES);
        effects.add(new NetherFog());
        EFFECTS = List.copyOf(effects);
    }

    private static boolean failed;
    private static boolean created;
    private static int vertexArray;
    private static int framebuffer;
    private static int attachedColor;
    private static int sampler;
    private static int noiseTexture;
    private static int fogTable;
    private static EffectProgram packCloudProgram;
    private static boolean packCloudsBroken;
    private static PackClouds.Kind wantedPackClouds;
    private static int packCloudFramebuffer;
    private static int packCloudTexture;
    private static int packCloudWidth;
    private static int packCloudHeight;
    private static EffectFog.PackOptions waterPack;
    private static PackWater packWater;
    private static FogModel tableModel;
    private static FogModel.Env tableEnv;
    private static FogTable.Range tableRange;
    private static EffectProgram upsample;
    private static int lowFramebuffer;
    private static int lowColor;
    private static int lowDistance;
    private static int lowSizeX;
    private static int lowSizeY;
    private static final Map<String, EffectProgram> PROGRAMS = new HashMap<>();
    private static final Map<WorldEffect, FogProbe> FOG_PROBES = new HashMap<>();
    private static EffectProgram fogProgram;
    private static boolean fogBroken;
    private static final Set<String> BROKEN = new HashSet<>();
    private static String loggedState = "";
    private static int timerQuery;
    private static boolean timerPending;
    private static long profiledNanos;
    private static int profiledFrames;
    private static String profiledDetail = "";
    private static final int PROBE_WIDTH = 1 + EffectFog.SKY_BANDS;
    private static final float[] profiledProbe = new float[PROBE_WIDTH * 2 * 4];
    private static boolean active;

    private WorldEffects() {}

    /** Whether an effect was in view last frame, for work during the world pass that only effects need. */
    public static boolean active() {
        return active;
    }

    /**
     * The shader pack whose clouds the effects in view are drawn behind, for work during the world pass that
     * only that needs; null when there is none.
     */
    public static PackClouds.Kind packCloudsWanted() {
        return active ? wantedPackClouds : null;
    }

    /** Every effect, in draw order; the config screen builds one toggle from each id. */
    public static List<WorldEffect> all() {
        return EFFECTS;
    }

    /** Called before the world is rendered, so LOD backends start the frame with no stale depth. */
    public static void beginFrame() {
        LodDepth.beginFrame();
        CloudLayer.beginFrame();
    }

    /**
     * An effect with something to draw: {@code rect} is where it is in view, {@code mirror} where its
     * reflection in water can be. Either may be null, not both.
     */
    private record Visible(WorldEffect effect, EffectCulling.ScreenRect rect, EffectCulling.ScreenRect mirror,
                           double distance) {
        /** The part of the view that has to do with the effect. */
        EffectCulling.ScreenRect seen() {
            return rect != null ? rect : mirror;
        }
    }

    /**
     * What every program needs to place a pixel in the world: the vanilla projection, the LOD depth and the
     * cloud layer of this frame, either of which may be null, the cloud height relative to the camera, and
     * the modelled fog with the part of the world its table covers (see {@link EffectFog#model}), or null,
     * and whether a shader pack's clouds are bound in place of a cloud layer.
     */
    private record Scene(Matrix4f forward, Matrix4f inverse, LodDepth.Layer lod, CloudLayer.Layer clouds,
                         float cloudHeight, float cameraY, FogModel fog, FogTable.Range fogRange,
                         boolean packClouds) {}

    /**
     * One effect's readings of the world image, side by side in a small texture: the fog measurement, then the
     * colour of the sky in each band of elevation, horizon first, with the colour of each band's bright parts
     * in a second row. All are smoothed over time, so each frame reads the previous results from one texture
     * and writes the new ones to the other.
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
        active = false;
        if (failed || !ModConfig.effectsEnabled() || client.world == null || client.player == null) return;
        Vec3d cameraPos = camera.getCameraPos();
        Matrix4f viewProjection = new Matrix4f(projection).mul(view);
        PackWater water = water();
        List<Visible> visible = cull(client, cameraPos, viewProjection, water != null);
        if (visible.isEmpty()) {
            logState("none visible");
            return;
        }
        active = true;
        Framebuffer main = client.getFramebuffer();
        if (!(main.getColorAttachment() instanceof GlTexture color)
                || !(main.getDepthAttachment() instanceof GlTexture depth)) return;
        // Under a shader pack the effects are lit from that pack's sun path and matched to the sky it drew.
        boolean packLighting = ModConfig.effectPackLighting() && IrisSupport.shaderPackInUse();
        EffectFrame frame = new EffectFrame(cameraPos.x, cameraPos.y, cameraPos.z, client.world.getTime(),
                client.world.getTimeOfDay(), tickProgress, client.world.getRainGradient(tickProgress),
                packLighting, packLighting ? IrisSupport.sunPathRotation() : 0);
        float cloudHeight = client.world.getEnvironmentAttributes()
                .getAttributeValue(EnvironmentAttributes.CLOUD_HEIGHT_VISUAL, cameraPos) - (float) cameraPos.y;
        try {
            if (!created) create();
            draw(visible, frame, viewProjection, projection.m11(), cloudHeight,
                    client.gameRenderer.getViewDistanceBlocks(), fogColor, main, color.getGlId(), depth.getGlId(),
                    water);
        } catch (RuntimeException e) {
            failed = true;
            LOGGER.error("World effects disabled after a rendering error", e);
        }
    }

    /**
     * How the active shader pack's water reflects, or null when effects are not to be mirrored in it: no
     * pack, its reflections switched off, or ours.
     */
    private static PackWater water() {
        EffectFog.PackOptions pack = ModConfig.effectPackReflections() ? IrisSupport.packOptions() : null;
        if (pack == null) return null;
        // One reading of the options per loaded pack: changing an option reloads the pack.
        if (pack != waterPack) {
            waterPack = pack;
            packWater = PackWater.forPack(pack);
        }
        return packWater;
    }

    /**
     * Everything that can be decided without touching the GPU: config, region mask, distance and frustum.
     * With {@code reflections}, an effect outside the view is kept while its reflection in water may be in it.
     */
    private static List<Visible> cull(MinecraftClient client, Vec3d cameraPos, Matrix4f viewProjection,
                                      boolean reflections) {
        List<Visible> visible = new ArrayList<>(EFFECTS.size());
        String host = client.getCurrentServerEntry() == null ? "" : client.getCurrentServerEntry().address;
        boolean recognized = RegionPolicy.isWynncraftHost(host) || WorldContextResolver.isFixture(client);
        if (!recognized) return visible;
        VisibilitySnapshot snapshot = WynnVistaMod.refreshForRender(client);
        String dimension = client.world.getRegistryKey().getValue().toString();
        for (WorldEffect effect : EFFECTS) {
            if (!ModConfig.effectEnabled(effect.id()) || BROKEN.contains(effect.shader())) continue;
            if (!EffectRegion.shows(snapshot, true, dimension, client.player.getX(), client.player.getZ(),
                    effect.anchorX(), effect.anchorZ())) continue;
            double distance = Math.hypot(effect.anchorX() - cameraPos.x, effect.anchorZ() - cameraPos.z);
            if (distance > effect.maxViewDistance()) continue;
            WorldEffect.Bounds box = effect.bounds();
            EffectCulling.ScreenRect rect = EffectCulling.project(viewProjection,
                    (float) (box.minX() - cameraPos.x), (float) (box.minY() - cameraPos.y),
                    (float) (box.minZ() - cameraPos.z), (float) (box.maxX() - cameraPos.x),
                    (float) (box.maxY() - cameraPos.y), (float) (box.maxZ() - cameraPos.z));
            EffectCulling.ScreenRect mirror = !reflections ? null : EffectCulling.reflection(viewProjection,
                    (float) (box.minX() - cameraPos.x), (float) (box.minZ() - cameraPos.z),
                    (float) (box.maxX() - cameraPos.x), (float) (box.maxZ() - cameraPos.z));
            if (rect != null || mirror != null) visible.add(new Visible(effect, rect, mirror, distance));
        }
        return visible;
    }

    private static void draw(List<Visible> visible, EffectFrame frame, Matrix4f viewProjection, float projectionScaleY,
                             float cloudHeight, float viewDistance, Vector4f fogColor, Framebuffer main, int color,
                             int depth, PackWater water) {
        LodDepth.Layer lod = LodDepth.resolve();
        // Water is where the depth with translucents differs from the depth without them: the pack's own
        // pair of depth buffers for the vanilla world, the LOD mod's for its terrain.
        int opaqueDepth = water == null ? 0 : IrisSupport.opaqueDepthTexture();
        boolean lodWater = lod != null && lod.knowsWater();
        if (opaqueDepth <= 0 && !lodWater) water = null;
        boolean mirrored = false;
        for (Visible entry : visible) mirrored |= water != null && entry.mirror() != null;
        CloudLayer.Layer clouds = CloudLayer.resolve();
        FogModel known = EffectFog.model(IrisSupport.packOptions(), lod);
        FogModel fog = ModConfig.effectFogModels() ? known : null;
        FogModel.Env env = FogModel.Env.of(frame.cameraY(), frame.rain(), frame.timeOfDay(),
                lod == null ? null : lod.backend(), lod == null ? 0 : lod.renderDistance());
        // A shader pack's own clouds, unless a cloud mod's layer is already there to be drawn behind.
        PackClouds packClouds = known == null || clouds != null || !ModConfig.effectPackClouds() || packCloudsBroken
                ? null : known.clouds(env, viewDistance);
        wantedPackClouds = packClouds == null ? null : packClouds.kind();
        int[] packTextures = packClouds == null ? null : IrisSupport.packCloudTextures(packClouds.kind());
        logState(visible.size() + " drawn with " + (lod == null ? "vanilla depth only" : "vanilla + LOD depth")
                + (clouds != null ? ", under a cloud layer" : packTextures != null ? ", under the pack's clouds" : "")
                + ", fog: " + (fog == null ? "measured" : fog.name())
                + (!mirrored ? "" : ", mirrored in " + (opaqueDepth > 0 && lodWater ? "vanilla and LOD"
                : lodWater ? "LOD" : "vanilla") + " water"));
        int width = main.textureWidth;
        int height = main.textureHeight;
        List<WorldEffect.Bounds> boxes = new ArrayList<>(visible.size());
        for (Visible entry : visible) boxes.add(entry.effect().bounds());
        Scene scene = new Scene(viewProjection, new Matrix4f(viewProjection).invert(), lod, clouds, cloudHeight,
                (float) frame.cameraY(), fog,
                FogTable.range(frame.cameraX(), frame.cameraZ(), boxes), packTextures != null);

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
            bind(7, GL11C.GL_TEXTURE_2D, clouds == null ? depth : clouds.terrainDepthTexture());
            bind(8, GL11C.GL_TEXTURE_2D, clouds == null ? depth : clouds.depthTexture());
            bind(9, GL11C.GL_TEXTURE_2D, clouds == null ? depth : clouds.colorTexture());
            bind(10, GL11C.GL_TEXTURE_2D, fogTable);
            // A pair that is one texture twice never differs, so nothing is taken for water there.
            bind(11, GL11C.GL_TEXTURE_2D, opaqueDepth > 0 ? opaqueDepth : depth);
            bind(12, GL11C.GL_TEXTURE_2D, lod == null ? depth : lodWater ? lod.surfaceTextureId() : lod.textureId());
            bind(13, GL11C.GL_TEXTURE_2D, lod == null ? depth : lodWater ? lod.opaqueTextureId() : lod.textureId());
            if (fog != null) uploadFogTable(fog, env, scene.fogRange());
            GL30C.glBindVertexArray(vertexArray);
            if (packTextures != null) readPackClouds(packClouds, packTextures, width, height);

            beginProfile();
            // The fog already in the image is measured before anything is drawn over it.
            GL11C.glDisable(GL11C.GL_BLEND);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            for (Visible entry : visible) measureFog(entry, scene, color);
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
            // Reflections first: an effect in front of the water it is mirrored in is drawn over them.
            if (mirrored) {
                for (Visible entry : visible) {
                    if (entry.mirror() != null) {
                        shade(entry, entry.mirror(), water, scene, frame, env, fogColor, projectionScaleY, width, height);
                    }
                }
            }
            for (Visible entry : visible) {
                if (entry.rect() != null) {
                    shade(entry, entry.rect(), null, scene, frame, env, fogColor, projectionScaleY, width, height);
                }
            }
            endProfile();
        } finally {
            saved.restore();
        }
    }

    /**
     * Draws one effect into {@code rect} of the world image, or with {@code water} its reflection in the
     * water there. The world image is bound and blending is set for premultiplied colour.
     */
    private static void shade(Visible entry, EffectCulling.ScreenRect rect, PackWater water, Scene scene,
                              EffectFrame frame, FogModel.Env env, Vector4f fogColor, float projectionScaleY,
                              int width, int height) {
        WorldEffect effect = entry.effect();
        EffectProgram program = program(effect);
        if (program == null) return;
        // Only the pixels the effect's box can cover are shaded at all.
        int x0 = Math.max(0, (int) Math.floor(rect.minX() * width) - 1);
        int y0 = Math.max(0, (int) Math.floor(rect.minY() * height) - 1);
        int x1 = Math.min(width, (int) Math.ceil(rect.maxX() * width) + 1);
        int y1 = Math.min(height, (int) Math.ceil(rect.maxY() * height) + 1);
        if (x1 <= x0 || y1 <= y0) return;
        boolean mirror = water != null;
        // A reflection is rippled and faint, and is marched with half the samples and the finest detail left out.
        int steps = EffectCulling.steps(mirror ? ModConfig.effectSteps() / 2 : ModConfig.effectSteps(),
                Math.max(x1 - x0, y1 - y0));
        int octaves = Math.max(2, EffectCulling.octaves(entry.distance(), projectionScaleY, height) - (mirror ? 1 : 0));
        // A large effect is marched at half resolution and upsampled; a small one is drawn directly, and so
        // is a reflection, which only costs anything on the water it is in.
        boolean half = !mirror && effect.halfResolution()
                && (long) (x1 - x0) * (y1 - y0) > HALF_RESOLUTION_COVERAGE * width * height;
        int targetWidth = half ? (width + 1) / 2 : width;
        int targetHeight = half ? (height + 1) / 2 : height;

        program.use();
        setScene(program, scene, targetWidth, targetHeight);
        if (mirror) {
            program.set("uMirror", 1, (float) ((Math.floorMod(frame.worldTime(), RIPPLE_TICKS) + frame.tickProgress()) / 20.0));
            program.set("uMirrorCamera", (float) (frame.cameraX() - Math.floor(frame.cameraX() / RIPPLE_PERIOD) * RIPPLE_PERIOD),
                    0, (float) (frame.cameraZ() - Math.floor(frame.cameraZ() / RIPPLE_PERIOD) * RIPPLE_PERIOD));
            program.set("uWater", water.base(), water.power(), water.strength());
        }
        program.set("uNoise", 2);
        FogProbe probe = FOG_PROBES.get(effect);
        bind(6, GL11C.GL_TEXTURE_2D, probe.textures[probe.current]);
        program.set("uFogProbe", 6);
        program.set("uFogColor", fogColor.x, fogColor.y, fogColor.z);
        program.set("uSteps", steps);
        program.set("uOctaves", octaves);
        program.set("uPixelSize", (float) (entry.distance() * 2 / (projectionScaleY * height)));
        WorldEffect.Bounds box = effect.bounds();
        program.set("uBoxMin", (float) (box.minX() - frame.cameraX()), (float) (box.minY() - frame.cameraY()),
                (float) (box.minZ() - frame.cameraZ()));
        program.set("uBoxMax", (float) (box.maxX() - frame.cameraX()), (float) (box.maxY() - frame.cameraY()),
                (float) (box.maxZ() - frame.cameraZ()));
        effect.upload(program, frame);
        if (half) {
            drawHalfResolution(scene, width, height, x0, y0, x1, y1);
        } else {
            GL11C.glScissor(x0, y0, x1 - x0, y1 - y0);
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
        }
        if (PROFILE && !mirror) {
            profiledDetail = effect.id() + " " + (x1 - x0) + "x" + (y1 - y0) + " px of " + width + "x" + height
                    + (half ? " at half resolution, " : ", ") + steps + " steps, " + octaves + " octaves"
                    + String.format(", probe rgb %.2f %.2f %.2f visibility %.2f, sky from the horizon up",
                    profiledProbe[0], profiledProbe[1], profiledProbe[2], profiledProbe[3]) + profiledSky()
                    + modelledAt(scene.fog(), env, entry.distance(), box);
        }
    }

    private static void setScene(EffectProgram program, Scene scene, int targetWidth, int targetHeight) {
        LodDepth.Layer lod = scene.lod();
        CloudLayer.Layer clouds = scene.clouds();
        program.set("uSceneDepth", 0);
        program.set("uLodDepth", 1);
        program.set("uTerrainDepth", 7);
        program.set("uCloudDepth", 8);
        program.set("uCloudColor", 9);
        program.set("uOpaqueDepth", 11);
        program.set("uLodSurface", 12);
        program.set("uLodOpaque", 13);
        program.set("uMirror", 0, 0);
        program.set("uSceneInverse", scene.inverse());
        program.set("uLodInverse", lod == null ? scene.inverse() : lod.inverseViewProjection());
        program.set("uLodParams", lod == null ? 0 : 1, lod == null ? 1 : lod.clearDepth(),
                lod != null && lod.zeroToOne() ? 1 : 0);
        // 1: a cloud mod's layer with its own depth, 2: a shader pack's clouds as distance and opacity.
        program.set("uCloudParams", clouds != null ? 1 : scene.packClouds() ? 2 : 0,
                clouds != null && clouds.colorInImage() ? 1 : 0, scene.cloudHeight());
        program.set("uSceneForward", scene.forward());
        FogModel fog = scene.fog();
        FogTable.Range range = scene.fogRange();
        program.set("uFogTable", 10);
        float[] fogColour = fog == null ? null : fog.colour();
        if (fog == null) {
            program.set("uFogTableRange", 0, 0, 0, 0);
        } else {
            program.set("uFogTableRange", 1 / range.maxAlong(), range.minY() - scene.cameraY(),
                    1 / (range.maxY() - range.minY()), fog.measured() ? 1 : 0);
        }
        if (fogColour == null) program.set("uFogTableColor", 0, 0, 0, 0);
        else program.set("uFogTableColor", fogColour[0], fogColour[1], fogColour[2], 1);
        program.set("uViewSize", (float) targetWidth, (float) targetHeight);
    }

    /**
     * Updates an effect's fog probe from the finished world image: how much detail the terrain at the
     * effect's distance still has, and its colour. If the probe shader is unusable the probe keeps its
     * initial "clear".
     */
    private static void measureFog(Visible entry, Scene scene, int color) {
        FogProbe probe = FOG_PROBES.computeIfAbsent(entry.effect(), effect -> createFogProbe());
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
        EffectFog.Columns columns = EffectFog.columns(entry.seen());

        int previous = probe.current;
        probe.current = 1 - previous;
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, probe.framebuffers[probe.current]);
        GL11C.glViewport(0, 0, PROBE_WIDTH, 2);
        bind(5, GL11C.GL_TEXTURE_2D, color);
        bind(6, GL11C.GL_TEXTURE_2D, probe.textures[previous]);
        fogProgram.use();
        setScene(fogProgram, scene, 1, 1);
        fogProgram.set("uSceneColor", 5);
        fogProgram.set("uPrevious", 6);
        fogProgram.set("uColumns", columns.min(), columns.max());
        fogProgram.set("uBand", band.near(), band.far());
        fogProgram.set("uContrast", EffectFog.CONTRAST_GONE, EffectFog.CONTRAST_CLEAR);
        fogProgram.set("uRate", rate);
        // With a model the amount of fog is known and is not measured.
        fogProgram.set("uProbeMode", scene.fog() != null && !scene.fog().measured() ? 1 : 0);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);

        if (PROFILE && profiledFrames == 0) {
            int packBuffer = GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
            GL21C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
            // A row at a time: whatever row length the game left set for reading pixels then does not matter.
            float[] row = new float[PROBE_WIDTH * 4];
            for (int y = 0; y < 2; y++) {
                GL11C.glReadPixels(0, y, PROBE_WIDTH, 1, GL11C.GL_RGBA, GL11C.GL_FLOAT, row);
                System.arraycopy(row, 0, profiledProbe, y * row.length, row.length);
            }
            GL21C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, packBuffer);
        }
    }

    /**
     * For the profile log: the probe's sky colour in each band of elevation, "-" where none was seen, then
     * the colour of each band's bright parts.
     */
    private static String profiledSky() {
        StringBuilder sky = new StringBuilder();
        for (int texel = 1; texel < PROBE_WIDTH * 2; texel++) {
            if (texel == PROBE_WIDTH) sky.append(", bright parts");
            else sky.append(profiledProbe[texel * 4] < 0 ? " -" : String.format(" %.2f/%.2f/%.2f",
                    profiledProbe[texel * 4], profiledProbe[texel * 4 + 1], profiledProbe[texel * 4 + 2]));
        }
        return sky.toString();
    }

    private static FogProbe createFogProbe() {
        FogProbe probe = new FogProbe();
        GL13C.glActiveTexture(GL13C.GL_TEXTURE6);
        resetUnpack();
        // No colour seen yet anywhere, full visibility.
        float[] unseen = new float[PROBE_WIDTH * 2 * 4];
        for (int i = 0; i < unseen.length; i++) unseen[i] = i % 4 == 3 ? 1 : -1;
        for (int i = 0; i < 2; i++) {
            probe.textures[i] = GL11C.glGenTextures();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, probe.textures[i]);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
            // Full floats: at high frame rates each step of the smoothing is smaller than a half float can
            // hold near 1.
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA32F, PROBE_WIDTH, 2, 0, GL11C.GL_RGBA,
                    GL11C.GL_FLOAT, unseen);
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

    /** For the profile log: the modelled fog at the effect's distance, at the foot and the top of its box. */
    private static String modelledAt(FogModel fog, FogModel.Env env, double distance, WorldEffect.Bounds box) {
        if (fog == null) return "";
        float[] foot = new float[2];
        float[] top = new float[2];
        fog.sample(env, distance, box.minY(), foot);
        fog.sample(env, distance, box.maxY(), top);
        return String.format(", %s haze %.2f-%.2f fade %.2f-%.2f", fog.name(), foot[0], top[0], foot[1], top[1]);
    }

    /**
     * Samples the fog model into its texture, on unit 10. The table is kept while the model, the frame's
     * environment and the range are the ones it was built from.
     */
    private static void uploadFogTable(FogModel fog, FogModel.Env env, FogTable.Range range) {
        if (fog.equals(tableModel) && env.equals(tableEnv) && range.equals(tableRange)) return;
        tableModel = fog;
        tableEnv = env;
        tableRange = range;
        GL13C.glActiveTexture(GL13C.GL_TEXTURE10);
        resetUnpack();
        GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, FogTable.WIDTH, FogTable.HEIGHT, GL30C.GL_RG,
                GL11C.GL_FLOAT, FogTable.build(fog, env, range));
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
    private static void drawHalfResolution(Scene scene, int width, int height, int x0, int y0, int x1, int y1) {
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
        setScene(upsample, scene, width, height);
        upsample.set("uLowColor", 3);
        upsample.set("uLowDistance", 4);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
    }

    /**
     * Draws the pack's cloud buffers into a view-sized texture of distance and opacity ({@code pack_clouds.fsh})
     * and binds it where a cloud layer's colour would be. If its shader is unusable the pack's clouds are
     * given up on and nothing is bound.
     */
    private static void readPackClouds(PackClouds clouds, int[] textures, int width, int height) {
        if (packCloudProgram == null) {
            try {
                packCloudProgram = EffectProgram.link("pack_clouds.fsh");
            } catch (RuntimeException e) {
                packCloudsBroken = true;
                LOGGER.error("Shader pack clouds will not hide world effects: their shader is unusable", e);
                return;
            }
        }
        if (packCloudFramebuffer == 0 || width != packCloudWidth || height != packCloudHeight) {
            if (packCloudFramebuffer == 0) {
                packCloudFramebuffer = GL30C.glGenFramebuffers();
                packCloudTexture = GL11C.glGenTextures();
            }
            GL13C.glActiveTexture(GL13C.GL_TEXTURE9);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, packCloudTexture);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA32F, width, height, 0, GL11C.GL_RGBA,
                    GL11C.GL_FLOAT, (ByteBuffer) null);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, packCloudFramebuffer);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D,
                    packCloudTexture, 0);
            if (GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Pack cloud buffer is incomplete");
            }
            packCloudWidth = width;
            packCloudHeight = height;
        }
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, packCloudFramebuffer);
        GL11C.glViewport(0, 0, width, height);
        GL11C.glDisable(GL11C.GL_BLEND);
        GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        bind(3, GL11C.GL_TEXTURE_2D, textures[0]);
        bind(4, GL11C.GL_TEXTURE_2D, textures[1] > 0 ? textures[1] : textures[0]);
        packCloudProgram.use();
        packCloudProgram.set("uPackFirst", 3);
        packCloudProgram.set("uPackSecond", 4);
        packCloudProgram.set("uKind", clouds.kind().ordinal());
        packCloudProgram.set("uPackScale", clouds.uvScale(), clouds.distanceScale());
        packCloudProgram.set("uViewSize", (float) width, (float) height);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
        GL11C.glEnable(GL11C.GL_BLEND);
        bind(9, GL11C.GL_TEXTURE_2D, packCloudTexture);
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

    /**
     * Compiles an effect's program on first use, per shader, as an effect may switch shaders with the config.
     * A shader that fails disables only the effect using it.
     */
    private static EffectProgram program(WorldEffect effect) {
        String shader = effect.shader();
        EffectProgram program = PROGRAMS.get(shader);
        if (program != null) return program;
        try {
            program = EffectProgram.link(shader);
            PROGRAMS.put(shader, program);
            LOGGER.info("World effect '{}' compiled and linked ({})", effect.id(), shader);
            return program;
        } catch (RuntimeException e) {
            BROKEN.add(shader);
            LOGGER.error("World effect '{}' disabled: its shader {} is unusable", effect.id(), shader, e);
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
            fogTable = GL11C.glGenTextures();
            GL13C.glActiveTexture(GL13C.GL_TEXTURE10);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, fogTable);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL, 0);
            resetUnpack();
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RG32F, FogTable.WIDTH, FogTable.HEIGHT, 0, GL30C.GL_RG,
                    GL11C.GL_FLOAT, new float[FogTable.WIDTH * FogTable.HEIGHT * 2]);
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
                GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_2D,
                GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_2D,
                GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_2D};
        private static final int[] BINDINGS = {GL11C.GL_TEXTURE_BINDING_2D, GL11C.GL_TEXTURE_BINDING_2D,
                GL12C.GL_TEXTURE_BINDING_3D, GL11C.GL_TEXTURE_BINDING_2D, GL11C.GL_TEXTURE_BINDING_2D,
                GL11C.GL_TEXTURE_BINDING_2D, GL11C.GL_TEXTURE_BINDING_2D, GL11C.GL_TEXTURE_BINDING_2D,
                GL11C.GL_TEXTURE_BINDING_2D, GL11C.GL_TEXTURE_BINDING_2D, GL11C.GL_TEXTURE_BINDING_2D,
                GL11C.GL_TEXTURE_BINDING_2D, GL11C.GL_TEXTURE_BINDING_2D, GL11C.GL_TEXTURE_BINDING_2D};
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
