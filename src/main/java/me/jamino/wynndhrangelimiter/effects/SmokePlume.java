package me.jamino.wynndhrangelimiter.effects;

import me.jamino.wynndhrangelimiter.ModConfig;

/** The smoke column rising from Mount Wynn's crater: placement, shape and lighting rules. */
public final class SmokePlume implements WorldEffect {
    public static final String ID = "smoke_plume";

    /** Peak of Mount Wynn; the smoke starts just below the rim, inside the crater. */
    public static final double PEAK_X = -183;
    public static final double PEAK_Y = 205;
    public static final double PEAK_Z = -1964;
    public static final double VENT_Y = PEAK_Y - 12;

    /** Column height, radius at the vent and at the top, and how far the top has blown downwind (east, slightly south). */
    public static final float HEIGHT = 520;
    public static final float VENT_RADIUS = 14;
    public static final float TOP_RADIUS = 170;
    public static final float DRIFT_X = 150;
    public static final float DRIFT_Z = 70;

    /** Blocks the noise pattern climbs per second, and the distance after which it repeats exactly. */
    public static final double RISE_SPEED = 4.8;
    public static final double SCROLL_PERIOD = 1280;

    /** How the plume is drawn; both styles share its placement, shape and lighting. */
    public enum Style {
        REALISTIC("Realistic", "smoke_plume.fsh"),
        /** Translucent cubes in the manner of the Better Clouds mod. */
        BLOCKY("Blocky", "smoke_plume_blocky.fsh");

        private final String label;
        private final String shader;

        Style(String label, String shader) {
            this.label = label;
            this.shader = shader;
        }

        public String label() { return label; }
        public String shader() { return shader; }
    }

    private static final Bounds BOUNDS = new Bounds(PEAK_X - TOP_RADIUS, VENT_Y, PEAK_Z - TOP_RADIUS,
            PEAK_X + DRIFT_X + TOP_RADIUS, VENT_Y + HEIGHT, PEAK_Z + DRIFT_Z + TOP_RADIUS);

    /** Light arriving at the plume: a unit direction towards the light, its colour, the sky ambient and the crater glow. */
    public record Lighting(float dirX, float dirY, float dirZ,
                           float red, float green, float blue,
                           float ambientRed, float ambientGreen, float ambientBlue,
                           float glow) {}

    @Override public String id() { return ID; }
    @Override public String name() { return "Mount Wynn Smoke Plume"; }
    @Override public String description() { return "Smoke rising from the crater of Mount Wynn"; }
    @Override public String shader() { return ModConfig.smokePlumeStyle().shader(); }
    /** Half resolution would blur the cube edges the blocky style is made of. */
    @Override public boolean halfResolution() { return ModConfig.smokePlumeStyle() != Style.BLOCKY; }
    @Override public double anchorX() { return PEAK_X; }
    @Override public double anchorZ() { return PEAK_Z; }
    @Override public Bounds bounds() { return BOUNDS; }

    /** Beyond this the plume is a few pixels wide; the main map is at most about 4,300 blocks from the peak. */
    @Override public double maxViewDistance() { return 9000; }

    @Override
    public void upload(EffectProgram program, EffectFrame frame) {
        Lighting light = lighting(frame.timeOfDay(), frame.rain());
        program.set("uVent", (float) (PEAK_X - frame.cameraX()), (float) (VENT_Y - frame.cameraY()),
                (float) (PEAK_Z - frame.cameraZ()));
        program.set("uShape", HEIGHT, VENT_RADIUS, TOP_RADIUS);
        program.set("uDrift", DRIFT_X, DRIFT_Z);
        program.set("uScroll", scroll(frame.worldTime(), frame.tickProgress()));
        program.set("uLightDir", light.dirX(), light.dirY(), light.dirZ());
        program.set("uLightColor", light.red(), light.green(), light.blue());
        program.set("uAmbient", light.ambientRed(), light.ambientGreen(), light.ambientBlue());
        program.set("uGlow", light.glow());
    }

    /** Vertical offset of the rising noise pattern, wrapped where the pattern repeats so it never jumps. */
    public static float scroll(long worldTime, float tickProgress) {
        double seconds = (Math.floorMod(worldTime, 32000L) + tickProgress) / 20.0;
        return (float) ((seconds * RISE_SPEED) % SCROLL_PERIOD);
    }

    /**
     * Sun or moon light for a time of day in ticks (0 = sunrise, 6000 = noon). The sun rises in the east (+X)
     * and sets in the west, as in vanilla.
     */
    public static Lighting lighting(long timeOfDay, float rain) {
        double angle = Math.floorMod(timeOfDay, 24000L) / 24000.0 * Math.PI * 2;
        float sunX = (float) Math.cos(angle);
        float sunY = (float) Math.sin(angle);
        float day = smoothstep(-0.3f, 0.1f, sunY);
        float high = smoothstep(0.0f, 0.4f, Math.abs(sunY));
        boolean sunUp = sunY > -0.05f;
        // Keep the light off the horizon so the plume is never lit exactly edge-on.
        float dirX = sunUp ? sunX : -sunX;
        float dirY = Math.max(Math.abs(sunY), 0.12f);
        float dirZ = 0.25f;
        float length = (float) Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        float dim = 1 - 0.55f * clamp(rain);
        float sun = day * dim;
        float moon = (1 - day) * 0.16f;
        float red = sun * lerp(1.00f, 0.92f, high) + moon * 0.55f;
        float green = sun * lerp(0.58f, 0.90f, high) + moon * 0.68f;
        float blue = sun * lerp(0.36f, 0.86f, high) + moon * 1.00f;
        float ambient = lerp(0.08f, 0.62f, day) * dim;
        return new Lighting(dirX / length, dirY / length, dirZ / length, red, green, blue,
                ambient * 0.92f, ambient * 0.97f, ambient * lerp(1.45f, 1.10f, day),
                lerp(1.0f, 0.1f, day));
    }

    private static float clamp(float value) {
        return Math.max(0, Math.min(1, value));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp((x - edge0) / (edge1 - edge0));
        return t * t * (3 - 2 * t);
    }
}
