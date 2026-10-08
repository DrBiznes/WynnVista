package me.jamino.wynndhrangelimiter.effects;

/**
 * What rises through the air of the Sky Islands near the camera: streaks of updraft in the gaps between the
 * islands, violet motes under them, and motes of light at two named places. Placement rules; the look is in
 * the shader.
 */
public final class SkyIslandsAir implements WorldEffect {
    public static final String ID = "sky_islands_air";

    /** Blocks from the camera within which the shader draws; the box below is that much larger than the map. */
    public static final double RANGE = 64;
    public static final double TOP_Y = 210;

    /** Windwalker Temple: the updrafts around it are stronger and reach higher. */
    public static final double WIND_X = 1358;
    public static final double WIND_Z = -4745;
    public static final float WIND_REACH = 80;

    /** Astraulus' Tower: star motes around it, from below its banner to well above. */
    public static final double STARS_X = 1206;
    public static final double STARS_Y = 126;
    public static final double STARS_Z = -4921;
    public static final float STARS_REACH = 40;

    /** Wybel Island: pastel sparkles over it. */
    public static final double SPARKLES_X = 1310;
    public static final double SPARKLES_Y = 66;
    public static final double SPARKLES_Z = -4684;
    public static final float SPARKLES_REACH = 45;

    private static final Bounds BOUNDS = new Bounds(SkyIslandsVoid.MAP_MIN_X, 0, SkyIslandsVoid.MAP_MIN_Z,
            SkyIslandsVoid.MAP_MIN_X + SkyIslandsVoid.MAP_SIZE_X, TOP_Y,
            SkyIslandsVoid.MAP_MIN_Z + SkyIslandsVoid.MAP_SIZE_Z);

    @Override public String id() { return ID; }
    @Override public String name() { return "Sky Islands Updrafts and Motes"; }
    @Override public String description() {
        return "Streaks of rising wind between the Sky Islands, violet motes under them, star motes at Astraulus' "
                + "Tower and sparkles on Wybel Island";
    }
    @Override public String shader() { return "sky_islands_air.fsh"; }
    @Override public String terrainMap() { return SkyIslandsVoid.MAP; }
    /** The lines are a fraction of a block wide and would be lost at half resolution. */
    @Override public boolean halfResolution() { return false; }
    @Override public double anchorX() { return SkyIslandsVoid.MAP_MIN_X + SkyIslandsVoid.MAP_SIZE_X / 2.0; }
    @Override public double anchorZ() { return SkyIslandsVoid.MAP_MIN_Z + SkyIslandsVoid.MAP_SIZE_Z / 2.0; }
    @Override public Bounds bounds() { return BOUNDS; }

    /** Only drawn near the camera, so only from inside the area or just outside its corners. */
    @Override public double maxViewDistance() {
        return Math.hypot(SkyIslandsVoid.MAP_SIZE_X, SkyIslandsVoid.MAP_SIZE_Z) / 2 + RANGE;
    }

    @Override
    public void upload(EffectProgram program, EffectFrame frame) {
        SmokePlume.Lighting light = SmokePlume.lighting(frame);
        program.set("uSkyMatch", frame.packLighting() ? 1 : 0, SmokePlume.skyReference(light));
        program.set("uMap", (float) (SkyIslandsVoid.MAP_MIN_X - frame.cameraX()),
                (float) (SkyIslandsVoid.MAP_MIN_Z - frame.cameraZ()), SkyIslandsVoid.MAP_SIZE_X,
                SkyIslandsVoid.MAP_SIZE_Z);
        program.set("uCameraY", (float) frame.cameraY());
        program.set("uPhase", SkyIslandsVoid.phase(frame.worldTime(), frame.tickProgress()));
        program.set("uLightColor", light.red(), light.green(), light.blue());
        program.set("uAmbient", light.ambientRed(), light.ambientGreen(), light.ambientBlue());
        program.set("uGlow", Math.max(0, Math.min(1, light.glow())));
        program.set("uWind", onMapX(WIND_X), onMapZ(WIND_Z), WIND_REACH);
        program.set("uStars", onMapX(STARS_X), onMapZ(STARS_Z), (float) STARS_Y, STARS_REACH);
        program.set("uSparkles", onMapX(SPARKLES_X), onMapZ(SPARKLES_Z), (float) SPARKLES_Y, SPARKLES_REACH);
    }

    /** A world x as blocks from the terrain map's corner, the coordinates the shader works in. */
    public static float onMapX(double x) {
        return (float) (x - SkyIslandsVoid.MAP_MIN_X);
    }

    public static float onMapZ(double z) {
        return (float) (z - SkyIslandsVoid.MAP_MIN_Z);
    }
}
