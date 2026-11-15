package me.jamino.wynndhrangelimiter.effects;

import me.jamino.wynndhrangelimiter.ModConfig;
import me.jamino.wynndhrangelimiter.visibility.BlockRect;
import me.jamino.wynndhrangelimiter.visibility.RegionPolicy;

/**
 * The void of the Void and Outer Void: the Sky Islands void's clouds, nebulae and fallen islands, but around the
 * player rather than under land. The islands there float in the void itself, so the haze rises among them, the
 * floor runs to the horizon on every side and above the horizon the void closes over the sky. Placement and
 * levels; the look is the Sky Islands void's shaders with {@code SURROUND} (see
 * {@code sky_islands_void_shape.glsl}), in the style chosen for that void.
 */
public final class VoidDepths implements WorldEffect {
    public static final String ID = "void_depths";

    /** The region itself: the effect covers all of it, and is only drawn while its LOD terrain is. */
    public static final BlockRect AREA = RegionPolicy.VOID_OUTER;
    public static final double MIN_X = AREA.minX();
    public static final double MAX_X = AREA.maxX();
    public static final double NORTH_Z = AREA.minZ();
    public static final double SOUTH_Z = AREA.maxZ();

    /**
     * The islands float at about y 100 to 170. The lowest layer of cloud has its top well below them, so the
     * cloud lies deep under them, and the void begins below that. The haze is densest above the cloud, at
     * {@link #HAZE_Y}, where it was when the cloud was 32 blocks higher (y 72), and thins to 1/e every 48 blocks
     * above, so at the islands it is still there; below its level it thins over 10 blocks, so it does not bury
     * the cloud.
     */
    public static final double CLOUD_Y = 40;
    public static final double ABYSS_Y = -8;
    public static final double HAZE_Y = 76;

    /** Above the islands and the build limit: from inside, the dome above the horizon is in the box. */
    public static final double TOP_Y = 400;

    /** Blocks from the region, along the ground, beyond which nothing is drawn. */
    public static final double REACH = 640;

    private static final Bounds BOUNDS = new Bounds(MIN_X, ABYSS_Y, NORTH_Z, MAX_X, TOP_Y, SOUTH_Z);

    @Override public String id() { return ID; }
    @Override public String name() { return "Void and Outer Void"; }
    @Override public String description() {
        return "Clouds, nebulae and a dark void all around the floating islands of the Void and Outer Void";
    }
    @Override public String shader() { return ModConfig.skyIslandsVoidStyle().surroundShader(); }
    /** As the Sky Islands void: half resolution would blur the edges of the blocky style's cuboids. */
    @Override public boolean halfResolution() { return ModConfig.skyIslandsVoidStyle() != SkyIslandsVoid.Style.BLOCKY; }
    /** The cloud is the Sky Islands void's, so is its bake; each effect draws its own copy. */
    @Override public Bake bake() {
        return ModConfig.skyIslandsVoidStyle() == SkyIslandsVoid.Style.BLOCKY ? SkyIslandsVoid.COVER : null;
    }
    @Override public double anchorX() { return (MIN_X + MAX_X) / 2.0; }
    @Override public double anchorZ() { return (NORTH_Z + SOUTH_Z) / 2.0; }
    @Override public Bounds bounds() { return BOUNDS; }
    @Override public boolean reflects() { return false; }
    @Override public double maxViewDistance() { return REACH; }

    @Override public boolean inRange(double x, double y, double z) {
        return BOUNDS.groundDistance(x, z) <= REACH;
    }

    /** From inside the region the floor and the haze run on to the horizon, as under the Sky Islands. */
    @Override public Bounds viewBounds(double x, double y, double z) {
        double push = SkyIslandsVoid.SKIRT * skirt(x, z);
        return push <= 0 ? BOUNDS : new Bounds(MIN_X - push, ABYSS_Y, NORTH_Z - push, MAX_X + push, TOP_Y, SOUTH_Z + push);
    }

    /** How much of the skirt, and of the dome, shows from a camera at x, z. */
    public static float skirt(double x, double z) {
        return SkyIslandsVoid.skirt(x, z, MIN_X, NORTH_Z, MAX_X, SOUTH_Z);
    }

    @Override
    public void upload(EffectProgram program, EffectFrame frame) {
        SkyIslandsVoid.upload(program, frame, MIN_X, NORTH_Z, MAX_X, SOUTH_Z, ABYSS_Y, CLOUD_Y, HAZE_Y);
    }
}
