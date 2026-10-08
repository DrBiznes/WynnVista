package me.jamino.wynndhrangelimiter.effects;

/**
 * The void under the Sky Islands: clumps of cloud cubes the islands' spikes dip into and, below them, a dark
 * void with a rare nebula. Placement and timing rules; the look is in the shader.
 */
public final class SkyIslandsVoid implements WorldEffect {
    public static final String ID = "sky_islands_void";

    /**
     * The area's terrain map, {@code scripts/mask_tool.py reduce} of {@code masks/sky_islands.wvmask}. This
     * effect does not read it; the updrafts and motes do.
     */
    public static final String MAP = "sky_islands.png";
    public static final int MAP_MIN_X = 704;
    public static final int MAP_MIN_Z = -5008;
    public static final int MAP_SIZE_X = 832;
    public static final int MAP_SIZE_Z = 640;

    /**
     * The void is larger than the map: it runs on south between the islands towards the Raiders' bases, east
     * past the coast to where the world ends, and there is a separate piece of it under the Colossus, west of
     * the map's south-west corner. This effect does not need the map, so its box takes all of that in, with
     * room to fade out where no land hides its edge.
     */
    public static final int MIN_X = 560;
    public static final int MAX_X = 1808;
    public static final int NORTH_Z = -5056;
    public static final int SOUTH_Z = -4192;

    /**
     * The world there ends at y 0 and the longest spikes reach down to y 1. The lowest layer of cloud cubes
     * has its top among the lowest spikes; the void begins at a plane below the cloud and below the world,
     * where nothing can be in front of it but the cloud.
     */
    public static final double CLOUD_Y = 4;
    public static final double ABYSS_Y = -40;
    public static final double TOP_Y = 256;

    /** Blocks per repeat of the noise, and the ticks after which the moving patterns repeat. */
    public static final double NOISE_PERIOD = 2048;
    public static final long CYCLE_TICKS = 32000;

    private static final Bounds BOUNDS = new Bounds(MIN_X, ABYSS_Y, NORTH_Z, MAX_X, TOP_Y, SOUTH_Z);

    @Override public String id() { return ID; }
    @Override public String name() { return "Sky Islands Void"; }
    @Override public String description() { return "Clumps of cloud cubes over a dark void with a rare nebula under the Sky Islands"; }
    @Override public String shader() { return "sky_islands_void.fsh"; }
    /** Half resolution would blur the edges of the cloud cubes. */
    @Override public boolean halfResolution() { return false; }
    @Override public double anchorX() { return (MIN_X + MAX_X) / 2.0; }
    @Override public double anchorZ() { return (NORTH_Z + SOUTH_Z) / 2.0; }
    @Override public Bounds bounds() { return BOUNDS; }

    /** The void lies below the rim of the land around it and cannot be seen from much further away. */
    @Override public double maxViewDistance() { return 1500; }

    @Override
    public void upload(EffectProgram program, EffectFrame frame) {
        SmokePlume.Lighting light = SmokePlume.lighting(frame);
        program.set("uSkyMatch", frame.packLighting() ? 1 : 0, SmokePlume.skyReference(light));
        program.set("uLevels", (float) (ABYSS_Y - frame.cameraY()), (float) (CLOUD_Y - frame.cameraY()), 0);
        program.set("uNoiseOrigin", wrap(frame.cameraX()), wrap(frame.cameraZ()));
        program.set("uPhase", phase(frame.worldTime(), frame.tickProgress()));
        program.set("uLightColor", light.red(), light.green(), light.blue());
        program.set("uAmbient", light.ambientRed(), light.ambientGreen(), light.ambientBlue());
        program.set("uGlow", Math.max(0, Math.min(1, light.glow())));
    }

    /**
     * A world coordinate within one repeat of the noise, so the patterns stay fixed to the world while the
     * shader works with small numbers.
     */
    public static float wrap(double coordinate) {
        return (float) (coordinate - Math.floor(coordinate / NOISE_PERIOD) * NOISE_PERIOD);
    }

    /**
     * Phase of the moving patterns, 0..1. The shader moves each pattern by a whole multiple of it, so the
     * wrap from 1 to 0 is invisible.
     */
    public static float phase(long worldTime, float tickProgress) {
        return (float) ((Math.floorMod(worldTime, CYCLE_TICKS) + tickProgress) / CYCLE_TICKS);
    }
}
