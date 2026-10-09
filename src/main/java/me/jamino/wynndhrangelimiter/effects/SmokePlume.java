package me.jamino.wynndhrangelimiter.effects;

import me.jamino.wynndhrangelimiter.ModConfig;

import java.util.List;

/**
 * A smoke column rising from a volcano's crater: placement, shape and lighting rules. The column is Mount
 * Wynn's; a smaller volcano has the same one scaled down to its crater.
 */
public final class SmokePlume implements WorldEffect {
    public static final String ID = "smoke_plume";
    /** The three plumes of the Volcanic Isles share one config toggle. */
    public static final String VOLCANIC_ISLES_ID = "volcanic_isles_plumes";

    /** Peak of Mount Wynn; the smoke starts just below the rim, inside the crater. */
    public static final double PEAK_X = -183;
    public static final double PEAK_Y = 205;
    public static final double PEAK_Z = -1964;
    public static final double VENT_DEPTH = 12;
    public static final double VENT_Y = PEAK_Y - VENT_DEPTH;

    /**
     * Column height, radius at the vent and at the top, and how far the top has blown downwind (east, slightly
     * south), at Mount Wynn's size. Every length of a scaled plume is its scale times these.
     */
    public static final float HEIGHT = 520;
    public static final float VENT_RADIUS = 14;
    public static final float TOP_RADIUS = 170;
    public static final float DRIFT_X = 150;
    public static final float DRIFT_Z = 70;

    /** Blocks the noise pattern climbs per second at Mount Wynn's size, and the distance after which it repeats exactly. */
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

    public static final SmokePlume MOUNT_WYNN = new SmokePlume(ID, "Mount Wynn Smoke Plume",
            "Smoke rising from the crater of Mount Wynn", PEAK_X, PEAK_Y, PEAK_Z, 1);

    /** The Volcanic Isles' three volcanoes, each by the centre of its crater and the crater's diameter. */
    public static final List<SmokePlume> VOLCANIC_ISLES = List.of(
            volcanicIsle(-1032, 135, -3666, 15),
            volcanicIsle(-826, 95, -3663, 18),
            volcanicIsle(-860, 84, -3768, 10));

    private final String id;
    private final String name;
    private final String description;
    private final double peakX;
    private final double ventY;
    private final double peakZ;
    private final float scale;
    private final Bounds bounds;

    /** @param scale size of the whole plume as a share of Mount Wynn's */
    private SmokePlume(String id, String name, String description, double peakX, double peakY, double peakZ,
                       float scale) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.peakX = peakX;
        this.ventY = peakY - VENT_DEPTH * scale;
        this.peakZ = peakZ;
        this.scale = scale;
        this.bounds = new Bounds(peakX - TOP_RADIUS * scale, ventY, peakZ - TOP_RADIUS * scale,
                peakX + (DRIFT_X + TOP_RADIUS) * scale, ventY + HEIGHT * scale,
                peakZ + (DRIFT_Z + TOP_RADIUS) * scale);
    }

    private static SmokePlume volcanicIsle(double x, double y, double z, float craterDiameter) {
        return new SmokePlume(VOLCANIC_ISLES_ID, "Volcanic Isles Smoke Plumes",
                "Smoke rising from the three volcanoes of the Volcanic Isles", x, y, z, scaleFor(craterDiameter));
    }

    /** The scale at which the smoke leaves the vent as wide as a crater of this diameter. */
    public static float scaleFor(float craterDiameter) {
        return craterDiameter / (2 * VENT_RADIUS);
    }

    public float scale() { return scale; }

    /** Light arriving at the plume: a unit direction towards the light, its colour, the sky ambient and the crater glow. */
    public record Lighting(float dirX, float dirY, float dirZ,
                           float red, float green, float blue,
                           float ambientRed, float ambientGreen, float ambientBlue,
                           float glow) {}

    @Override public String id() { return id; }
    @Override public String name() { return name; }
    @Override public String description() { return description; }
    @Override public String shader() { return ModConfig.smokePlumeStyle().shader(); }
    /** Half resolution would blur the cube edges the blocky style is made of. */
    @Override public boolean halfResolution() { return ModConfig.smokePlumeStyle() != Style.BLOCKY; }
    @Override public double anchorX() { return peakX; }
    @Override public double anchorZ() { return peakZ; }
    @Override public Bounds bounds() { return bounds; }

    /**
     * Beyond this the plume is a few pixels wide; the main map is at most about 4,300 blocks from Mount
     * Wynn's peak.
     */
    @Override public double maxViewDistance() { return 9000 * scale; }

    @Override
    public void upload(EffectProgram program, EffectFrame frame) {
        Lighting light = lighting(frame);
        program.set("uSkyMatch", frame.packLighting() ? 1f : 0f);
        program.set("uVent", (float) (peakX - frame.cameraX()), (float) (ventY - frame.cameraY()),
                (float) (peakZ - frame.cameraZ()));
        program.set("uScale", scale);
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
        return lighting(timeOfDay, rain, false, 0);
    }

    /** The light of a frame: under a shader pack it comes from where that pack puts its sun and moon. */
    public static Lighting lighting(EffectFrame frame) {
        return lighting(frame.timeOfDay(), frame.rain(), frame.packLighting(), frame.sunPathRotation());
    }

    /**
     * @param packSun         true to follow a shader pack's sun path instead of the fixed one above
     * @param sunPathRotation that pack's {@code sunPathRotation} in degrees: the path is tilted about the
     *                        east-west axis, towards the north at noon for a positive value
     */
    public static Lighting lighting(long timeOfDay, float rain, boolean packSun, float sunPathRotation) {
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
        if (packSun) {
            // Iris places the sun at RotY(-90) RotZ(rotation) RotX(sky angle) of straight up; the moon is opposite.
            double tilt = Math.toRadians(sunPathRotation);
            dirY = Math.max((float) (Math.abs(sunY) * Math.cos(tilt)), 0.12f);
            dirZ = (float) (-Math.abs(sunY) * Math.sin(tilt));
        }
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

    /**
     * Brightness of a middling part of an effect under this light, to compare with the sky a shader pack
     * has drawn: three quarters of the sky light and somewhat under half of the sun or moon. The fog probe
     * keeps it beside each band of sky it saw, and a sky that has gone out of view is scaled by how much it
     * has changed since.
     */
    public static float skyReference(Lighting light) {
        return 0.75f * luminance(light.ambientRed(), light.ambientGreen(), light.ambientBlue())
                + 0.45f * luminance(light.red(), light.green(), light.blue());
    }

    private static float luminance(float red, float green, float blue) {
        return 0.2126f * red + 0.7152f * green + 0.0722f * blue;
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
