package me.jamino.wynndhrangelimiter.effects;

import me.jamino.wynndhrangelimiter.ModConfig;

/**
 * The void under the Sky Islands: clumps of cloud the islands' spikes dip into and, below them, a dark
 * void with a rare nebula. Placement and timing rules; the look is in the shaders.
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
     * The world there ends at y 0 and the longest spikes reach down to y 1. The lowest layer of cloud cuboids
     * has its top among the lowest spikes; the void begins at a plane below the cloud and below the world,
     * where nothing can be in front of it but the cloud. No cloud reaches above y 20 and the haze around
     * them is densest at y 8 and thins to 1/e every 8 blocks: above the top of the box less than half a
     * percent of it is left along any ray, so the box ends there and not at the islands' tops.
     */
    public static final double CLOUD_Y = 4;
    public static final double ABYSS_Y = -40;
    public static final double TOP_Y = 72;

    /**
     * Blocks from the box, along the ground, beyond which the void is not drawn. The clumps are gone 520
     * blocks from the camera, and from further out only the haze could show over the rim of the land.
     */
    public static final double REACH = 640;

    /** Blocks inside the box over which things fade out towards its sides. */
    public static final double EDGE = 96;

    /**
     * Blocks past the box the floor and the haze run on for a camera inside it. Seen from low down, a ray
     * near the horizon leaves through a side of the box long before it meets the floor; this far out the
     * floor and the haze close the view to the horizon, and what is left is under a pixel to a few.
     */
    public static final double SKIRT = 32000;

    /** Blocks per repeat of the noise, and the ticks after which the moving patterns repeat. */
    public static final double NOISE_PERIOD = 2048;
    public static final long CYCLE_TICKS = 32000;

    /**
     * The same for the cloud, which drifts one repeat of the noise in that time: 0.32 blocks per second, half
     * the pace of vanilla clouds.
     */
    public static final long CLOUD_CYCLE_TICKS = 4 * CYCLE_TICKS;

    /**
     * Columns, each way, of the two lattices the blocky style's cuboids sit in, per repeat of the noise: one
     * every 8 blocks ({@code PUFF} in {@code sky_islands_void_lattice.glsl}).
     */
    public static final int COLUMNS = (int) (NOISE_PERIOD / 8);

    /**
     * The blocky style's bake: the cloud cover of every column of both lattices, worked out once a frame instead
     * of in every column every view ray walks through.
     */
    public static final Bake COVER = new Bake("sky_islands_void_bake.fsh", COLUMNS, COLUMNS);

    /** How the void is drawn; both styles share where its clouds, nebulae and fallen islands are. */
    public enum Style {
        /** Soft ray-marched clouds over smooth nebulae. */
        REALISTIC("Realistic", "sky_islands_void.fsh"),
        /** Clouds of a few large cuboids each, after the launch clouds, over pixel-art nebulae. */
        BLOCKY("Blocky", "sky_islands_void_blocky.fsh");

        private final String label;
        private final String shader;

        Style(String label, String shader) {
            this.label = label;
            this.shader = shader;
        }

        public String label() { return label; }
        public String shader() { return shader; }
    }

    private static final Bounds BOUNDS = new Bounds(MIN_X, ABYSS_Y, NORTH_Z, MAX_X, TOP_Y, SOUTH_Z);

    @Override public String id() { return ID; }
    @Override public String name() { return "Sky Islands Void"; }
    @Override public String description() { return "Clumps of cloud over a dark void with a rare nebula under the Sky Islands"; }
    @Override public String shader() { return ModConfig.skyIslandsVoidStyle().shader(); }
    /** Half resolution would blur the edges of the cloud cuboids the blocky style is made of. */
    @Override public boolean halfResolution() { return ModConfig.skyIslandsVoidStyle() != Style.BLOCKY; }
    @Override public Bake bake() { return ModConfig.skyIslandsVoidStyle() == Style.BLOCKY ? COVER : null; }
    @Override public double anchorX() { return (MIN_X + MAX_X) / 2.0; }
    @Override public double anchorZ() { return (NORTH_Z + SOUTH_Z) / 2.0; }
    @Override public Bounds bounds() { return BOUNDS; }
    /** The void lies under the land, with no water it could be mirrored in. */
    @Override public boolean reflects() { return false; }

    /** The void lies below the rim of the land around it and cannot be seen from much further away. */
    @Override public double maxViewDistance() { return REACH; }

    /** From the box, not its middle: the box is wider than the void can be seen from. */
    @Override public boolean inRange(double x, double y, double z) {
        return BOUNDS.groundDistance(x, z) <= REACH;
    }

    /** From inside the box the floor and the haze run on towards the horizon, past the box's sides. */
    @Override public Bounds viewBounds(double x, double y, double z) {
        double push = SKIRT * skirt(x, z);
        return push <= 0 ? BOUNDS : new Bounds(MIN_X - push, ABYSS_Y, NORTH_Z - push, MAX_X + push, TOP_Y, SOUTH_Z + push);
    }

    /**
     * How much of the skirt shows from a camera at x, z: none from outside the box, all of it from further
     * inside than {@link #EDGE}, so it comes and goes without a jump as the camera crosses a side.
     */
    public static float skirt(double x, double z) {
        double inside = Math.min(Math.min(x - MIN_X, MAX_X - x), Math.min(z - NORTH_Z, SOUTH_Z - z));
        double t = Math.max(0, Math.min(1, inside / EDGE));
        return (float) (t * t * (3 - 2 * t));
    }

    @Override
    public void upload(EffectProgram program, EffectFrame frame) {
        SmokePlume.Lighting light = SmokePlume.lighting(frame);
        program.set("uSkyMatch", frame.packLighting() ? 1f : 0f);
        program.set("uLevels", (float) (ABYSS_Y - frame.cameraY()), (float) (CLOUD_Y - frame.cameraY()), 0);
        program.set("uAreaMin", (float) (MIN_X - frame.cameraX()), (float) (NORTH_Z - frame.cameraZ()));
        program.set("uAreaMax", (float) (MAX_X - frame.cameraX()), (float) (SOUTH_Z - frame.cameraZ()));
        program.set("uSkirt", skirt(frame.cameraX(), frame.cameraZ()));
        program.set("uNoiseOrigin", wrap(frame.cameraX()), wrap(frame.cameraZ()));
        program.set("uPhase", phase(frame.worldTime(), frame.tickProgress()));
        program.set("uCloudPhase", cloudPhase(frame.worldTime(), frame.tickProgress()));
        program.set("uLightDir", light.dirX(), light.dirY(), light.dirZ());
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

    /** Phase of the cloud's drift and change, 0..1, used the same way. */
    public static float cloudPhase(long worldTime, float tickProgress) {
        return (float) ((Math.floorMod(worldTime, CLOUD_CYCLE_TICKS) + tickProgress) / CLOUD_CYCLE_TICKS);
    }
}
