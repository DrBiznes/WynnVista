package me.jamino.wynndhrangelimiter.effects;

/**
 * The glowing lava fog over the Roots of Corruption: placement, shape and lighting rules. It is a layer
 * that starts a little below ground level and fills the spikes above it; the Nether portal's pit lies below its floor,
 * so the world event fought down there stays clear.
 */
public final class NetherFog implements WorldEffect {
    public static final String ID = "nether_fog";

    /**
     * The corrupted ground around the portal is roughly an ellipse. Inside the core radii the fog has its
     * full density and height; over the next {@link #SPREAD} blocks it thins out and sinks, so it has no
     * visible border. The layer's floor is below ground level (about y 85) and above the pit's floor
     * (near y 50); nothing is drawn below it.
     */
    public static final double CENTER_X = 254;
    public static final double CENTER_Z = -1300;
    public static final float CORE_X = 140;
    public static final float CORE_Z = 90;
    public static final float SPREAD = 160;
    public static final float RADIUS_X = CORE_X + SPREAD;
    public static final float RADIUS_Z = CORE_Z + SPREAD;
    public static final double FLOOR_Y = 67;
    public static final float THICKNESS = 130;

    /** The Nether portal, and the distance from it at which its purple glow in the fog has faded out. */
    public static final double PORTAL_X = 342;
    public static final double PORTAL_Z = -1292;
    public static final float PORTAL_GLOW_RADIUS = 75;

    /** Blocks the noise pattern drifts per second along each axis, and the distance after which it repeats. */
    public static final double DRIFT_SPEED = 1.2;
    public static final double NOISE_PERIOD = 640;

    private static final Bounds BOUNDS = new Bounds(CENTER_X - RADIUS_X, FLOOR_Y, CENTER_Z - RADIUS_Z,
            CENTER_X + RADIUS_X, FLOOR_Y + THICKNESS, CENTER_Z + RADIUS_Z);

    @Override public String id() { return ID; }
    @Override public String name() { return "Roots of Corruption Lava Fog"; }
    @Override public String description() { return "Glowing lava fog over the Roots of Corruption, around the Nether portal"; }
    @Override public String shader() { return "nether_fog.fsh"; }
    @Override public double anchorX() { return CENTER_X; }
    @Override public double anchorZ() { return CENTER_Z; }
    @Override public Bounds bounds() { return BOUNDS; }

    /** Beyond this the layer is a thin sliver hidden behind the canyon walls around it. */
    @Override public double maxViewDistance() { return 2000; }

    @Override
    public void upload(EffectProgram program, EffectFrame frame) {
        SmokePlume.Lighting light = SmokePlume.lighting(frame.timeOfDay(), frame.rain());
        program.set("uOrigin", (float) (CENTER_X - frame.cameraX()), (float) (FLOOR_Y - frame.cameraY()),
                (float) (CENTER_Z - frame.cameraZ()));
        program.set("uExtent", RADIUS_X, RADIUS_Z);
        program.set("uCore", CORE_X, CORE_Z);
        program.set("uThickness", THICKNESS);
        program.set("uPortal", (float) (PORTAL_X - CENTER_X), (float) (PORTAL_Z - CENTER_Z), PORTAL_GLOW_RADIUS);
        program.set("uDrift", drift(frame.worldTime(), frame.tickProgress()));
        program.set("uLightColor", light.red(), light.green(), light.blue());
        program.set("uAmbient", light.ambientRed(), light.ambientGreen(), light.ambientBlue());
        program.set("uEmission", emission(light.glow()));
    }

    /**
     * Phase of the drifting noise pattern in repeats, 0..1. The shader moves each octave by a whole multiple
     * of it, so the wrap from 1 to 0 is invisible.
     */
    public static float drift(long worldTime, float tickProgress) {
        double seconds = (Math.floorMod(worldTime, 32000L) + tickProgress) / 20.0;
        return (float) ((seconds * DRIFT_SPEED / NOISE_PERIOD) % 1.0);
    }

    /**
     * Strength of the fog's own lava glow for the plume's night glow factor (1 at night, 0.1 by day): daylight
     * washes the glow out but never hides it.
     */
    public static float emission(float nightGlow) {
        return 0.65f + 0.35f * Math.max(0, Math.min(1, nightGlow));
    }
}
