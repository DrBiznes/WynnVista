package me.jamino.wynndhrangelimiter.effects;

import java.util.Locale;

/**
 * The fog of each supported shader pack and LOD mod, rebuilt from that pack's or mod's own code and read
 * from its own settings. Only the overworld is modelled, outdoors: cave, underwater and blindness fog are
 * left out, as are the per-biome weather variations of the packs.
 *
 * <p>No pack code is included; each model is the pack's published formula written out again, and is tied
 * to the pack version named on it.
 */
public final class FogModels {
    private FogModels() {}

    /** The model for a shader pack, recognised by the options it defines and, failing that, its name. */
    public static FogModel forPack(EffectFog.PackOptions pack) {
        String name = pack.name().toLowerCase(Locale.ROOT);
        if (pack.defines("BORDER_FOG_DISTANCE_OVERWORLD") && pack.defines("ATM_FOG_DISTANCE")) return Complementary.of(pack);
        if (pack.defines("AIR_FOG_MIE_DENSITY_NOON") || name.contains("photon")) return Photon.of(pack);
        if (pack.defines("FOG_DENSITY_NIGHT") && pack.defines("FAR_VANILLA_FOG") || name.contains("bsl")) return Bsl.of(pack);
        return new UnknownPack();
    }

    /**
     * Complementary Reimagined / Unbound r5.x with Distant Horizons or Voxy ({@code lib/atmospherics/fog/mainFog.glsl}):
     * an atmospheric fog that is nearly at its full strength within a few hundred blocks and thins with
     * altitude, then a late, steep fade into the sky at the LOD border.
     */
    public record Complementary(boolean atmospheric, float atmDistance, float atmAltitude, float atmStrength,
                                boolean border, float borderStrength, float borderDensity) implements FogModel {
        /** The pack takes this off Voxy's distance, as Voxy's terrain ends short of the configured one. */
        static final float VOXY_MARGIN = 256;
        /** Blocks above its altitude setting over which the fog thins out, with a LOD mod. */
        private static final double THINNING = 90;

        static Complementary of(EffectFog.PackOptions pack) {
            return new Complementary(pack.enabled("ATMOSPHERIC_FOG", true),
                    Math.max(1, number(pack, "ATM_FOG_DISTANCE", 100)), number(pack, "ATM_FOG_ALTITUDE", 63),
                    number(pack, "ATMOSPHERIC_FOG_DENSITY", 1) * number(pack, "ATM_FOG_MULT", 0.95f),
                    pack.enabled("BORDER_FOG", true) && pack.enabled("BORDER_FOG_OVERWORLD", true),
                    number(pack, "BORDER_FOG_DISTANCE_OVERWORLD", 3),
                    clamp(number(pack, "BORDER_FOG_DENSITY_OVERWORLD", 1)));
        }

        @Override public String name() { return "Complementary"; }

        @Override
        public void sample(Env env, double along, double y, float[] out) {
            if (atmospheric) {
                double clear = 1 - env.rain();
                double length = Math.hypot(along, y - env.cameraY());
                double fog = 1 - Math.pow(2, -Math.max(length - 40, 0) * (0.4 + 0.4 * env.rain()) / atmDistance);
                double altitude = thinning(y) * 0.9 + 0.1;
                // Far terrain is fogged whatever its height.
                altitude = lerp(altitude, 1, fog * fog * fog * fog);
                fog *= atmStrength - 0.1 - 0.15 * clear;
                double camera = thinning(env.cameraY() + 0.25 * THINNING);
                altitude = lerp(altitude, 1, 0.8 * camera);
                altitude *= 1 - 0.5 * camera * clear;
                out[0] = clamp((float) (fog * altitude));
            }
            if (border && env.lodRenderDistance() > 0) {
                float margin = env.backend() == LodDepth.Backend.VOXY ? VOXY_MARGIN : 0;
                out[1] = EffectFog.borderFog(env.lodRenderDistance() - margin, borderStrength, 4, borderDensity,
                        false, 0, along, y - env.cameraY());
            }
        }

        private double thinning(double altitude) {
            double above = Math.max(0, Math.min(THINNING, altitude - (atmAltitude + 0.1)));
            return (1 - above / THINNING) * (1 - above / THINNING);
        }
    }

    /**
     * BSL v10.1 ({@code lib/atmospherics/fog.glsl}): a fog that grows with distance, is denser at night and
     * in rain and thins above a set height, plus an optional linear fade over the last part of the LOD
     * distance, which the pack leaves off in the overworld by default.
     */
    public record Bsl(float density, float lodDensity, float nightDensity, float weatherDensity,
                      boolean heightFog, float heightY, float heightFalloff,
                      boolean border, boolean borderAlongGround, float borderDensity) implements FogModel {
        static Bsl of(EffectFog.PackOptions pack) {
            int far = Math.round(number(pack, "FAR_VANILLA_FOG", 2));
            return new Bsl(number(pack, "FOG_DENSITY", 1), number(pack, "FOG_DENSITY_LOD", 1),
                    Math.max(1, number(pack, "FOG_DENSITY_NIGHT", 4)), number(pack, "FOG_DENSITY_WEATHER", 1.5f),
                    pack.enabled("FOG_HEIGHT", true), number(pack, "FOG_HEIGHT_Y", 62),
                    number(pack, "FOG_HEIGHT_FALLOFF", 7), far == 1 || far == 3,
                    Math.round(number(pack, "FAR_VANILLA_FOG_STYLE", 0)) == 1, number(pack, "FOG_DENSITY_VANILLA", 1));
        }

        @Override public String name() { return "BSL"; }

        @Override
        public void sample(Env env, double along, double y, float[] out) {
            double length = Math.hypot(along, y - env.cameraY());
            double sunVisibility = Math.max(0, Math.min(1, env.sunY() * 2 + 0.5));
            double clearDay = sunVisibility * (1 - env.rain());
            double fog = length * density / 1024;
            if (env.backend() != null) fog *= lodDensity;
            fog *= lerp(1, weatherDensity, env.rain()) / lerp(1.0 / nightDensity, 1, clearDay);
            double dampen = 0.3 * env.rain() + 0.5;
            fog = Math.min(fog, (fog - dampen) * 0.25 + dampen);
            if (heightFog) fog *= Math.pow(2, -Math.max(y - heightY, 0) / Math.pow(2, heightFalloff));
            out[0] = clamp((float) (1 - Math.exp(-2 * Math.pow(Math.max(fog, 0), 0.35 * clearDay + 1.25))));
            if (border && env.lodRenderDistance() > 0) {
                double width = 0.4 * Math.sqrt(borderDensity) * env.lodRenderDistance();
                double reach = borderAlongGround ? along : length;
                out[1] = width <= 0 ? 0 : clamp((float) (1 - (env.lodRenderDistance() - reach) / width));
            }
        }
    }

    /**
     * Photon v1.3 ({@code include/fog/overworld/raymarched.glsl}, {@code include/weather/fog.glsl},
     * {@code include/fog/simple_fog.glsl}): air that scatters light, thinning by half every few blocks above
     * a starting height, with a blue haze and a mist whose density follows the time of day and the rain; and
     * an early, soft fade along the ground towards the LOD border, lifted above the horizon.
     *
     * <p>An approximation: the pack marches this fog with a cloud-like noise and per-colour extinction, and
     * varies it with biome and a slow random weather. Here it is one density, in temperate air of average
     * humidity.
     */
    public record Photon(float intensity, float seaLevel, float rayleighStart, float rayleighHalfLife,
                         float mieStart, float mieHalfLife, float rayleigh, float rayleighRain,
                         float mieMorning, float mieNoon, float mieEvening, float mieMidnight, float mieBlueHour,
                         float mieRain, boolean border) implements FogModel {
        private static final int STEPS = 8;
        /** The pack's random humidity and temperature average to this factor on the blue haze. */
        private static final double HUMID = 1.25;
        /** And to this much mist, in units of the noon density. */
        private static final double HUMID_MIST = 8 * (0.5 / 0.8) * (0.5 / 0.8);
        static final float BORDER_STRENGTH = (float) (2.4 * Math.log(2));

        static Photon of(EffectFog.PackOptions pack) {
            return new Photon(number(pack, "OVERWORLD_FOG_INTENSITY", 1), number(pack, "SEA_LEVEL", 63),
                    number(pack, "AIR_FOG_RAYLEIGH_FALLOFF_START", 30),
                    Math.max(0.01f, number(pack, "AIR_FOG_RAYLEIGH_FALLOFF_HALF_LIFE", 30)),
                    number(pack, "AIR_FOG_MIE_FALLOFF_START", 7),
                    Math.max(0.01f, number(pack, "AIR_FOG_MIE_FALLOFF_HALF_LIFE", 7)),
                    brightness(pack, "", 0.31f, 0.67f, 1) * number(pack, "AIR_FOG_RAYLEIGH_DENSITY", 0.0005f),
                    brightness(pack, "_RAIN", 0.31f, 0.67f, 1) * number(pack, "AIR_FOG_RAYLEIGH_DENSITY_RAIN", 0.0005f),
                    number(pack, "AIR_FOG_MIE_DENSITY_MORNING", 0.007f), number(pack, "AIR_FOG_MIE_DENSITY_NOON", 0.0001f),
                    number(pack, "AIR_FOG_MIE_DENSITY_EVENING", 0.005f), number(pack, "AIR_FOG_MIE_DENSITY_MIDNIGHT", 0.005f),
                    number(pack, "AIR_FOG_MIE_DENSITY_BLUE_HOUR", 0.002f), number(pack, "AIR_FOG_MIE_DENSITY_RAIN", 0.03f),
                    pack.enabled("BORDER_FOG", true));
        }

        /** How strongly a haze of the pack's colour setting dims what is behind it, as one number. */
        private static float brightness(EffectFog.PackOptions pack, String suffix, float r, float g, float b) {
            return (float) (0.2126 * Math.pow(number(pack, "AIR_FOG_RAYLEIGH_R" + suffix, r), 2.2)
                    + 0.7152 * Math.pow(number(pack, "AIR_FOG_RAYLEIGH_G" + suffix, g), 2.2)
                    + 0.0722 * Math.pow(number(pack, "AIR_FOG_RAYLEIGH_B" + suffix, b), 2.2));
        }

        @Override public String name() { return "Photon"; }

        @Override
        public void sample(Env env, double along, double y, float[] out) {
            double rise = y - env.cameraY();
            double length = Math.hypot(along, rise);
            double marched = env.lodRenderDistance() > 0 ? Math.min(length, env.lodRenderDistance()) : length;
            double haze = 0;
            double mist = 0;
            for (int i = 0; i < STEPS; i++) {
                double height = env.cameraY() + rise * ((i + 0.5) / STEPS) * (length > 0 ? marched / length : 0);
                // Nothing below the sea: the pack fades its fog out over the 24 blocks under sea level.
                double under = Math.max(0, Math.min(1, (height - (seaLevel - 24)) / 24));
                haze += under * Math.pow(2, Math.min((rayleighStart + seaLevel - height) / rayleighHalfLife, 0));
                mist += under * Math.pow(2, Math.min((mieStart + seaLevel - height) / mieHalfLife, 0));
            }
            double step = marched / STEPS * 0.5 * intensity;
            out[0] = clamp((float) (1 - Math.exp(-(haze * step * hazeDensity(env) + mist * step * mistDensity(env)))));
            if (border && env.lodRenderDistance() > 0) {
                out[1] = EffectFog.borderFog(env.lodRenderDistance(), BORDER_STRENGTH, 2, 1, true, 0.75f, along, rise);
            }
        }

        private double hazeDensity(Env env) {
            return lerp(rayleigh * HUMID, rayleighRain, env.rain());
        }

        /** Thick in the morning and evening, almost absent at noon, thickest in rain. */
        double mistDensity(Env env) {
            double fade = env.sunY() < 0.18 ? 0.37 + 1.2 * Math.max(0, -env.sunY()) : 1.7;
            double edge = Math.max(0, Math.min(1, 1 - fade * Math.abs(env.sunY() - 0.18)));
            edge *= edge;
            double blueHour = Math.max(0, (Math.exp(-190 * square(env.sunY() + 0.07283)) - 0.05) / 0.95);
            double mist = (env.sunX() > 0 ? mieMorning : mieEvening) * edge
                    + (env.sunY() > 0 ? mieNoon : mieMidnight) * (1 - edge) + mieBlueHour * blueHour;
            return lerp(mist + HUMID_MIST * mieNoon, mieRain, env.rain());
        }
    }

    /**
     * A shader pack that is not recognised. Its fog is still measured from the image by the fog probe; all
     * that is assumed is a fade at the LOD border like Complementary's default one.
     */
    public record UnknownPack() implements FogModel {
        @Override public String name() { return "unrecognised pack, measured"; }
        @Override public boolean measured() { return true; }

        @Override
        public void sample(Env env, double along, double y, float[] out) {
            if (env.lodRenderDistance() <= 0) return;
            float margin = env.backend() == LodDepth.Backend.VOXY ? Complementary.VOXY_MARGIN : 0;
            out[1] = EffectFog.borderFog(env.lodRenderDistance() - margin, 3, 4, 1, false, 0, along, y - env.cameraY());
        }
    }

    /**
     * Distant Horizons' own fog ({@code shaders/fog/gl/fog.frag}), without a shader pack: a "far" fog over a
     * share of the LOD distance and an optional height fog, mixed in one of its ways. Distances are shares
     * of the LOD render distance and heights shares of the world height, as in DH. DH gives the height fog
     * the far fog's thickness limits and density, which is followed here.
     *
     * @param falloff       0 linear, 1 exponential, 2 exponential squared
     * @param mixMode       DH's {@code EDhApiHeightFogMixMode} value; 0 measures the far fog along the view
     *                      ray, 1 along the ground, and neither has a height fog
     */
    public record DistantHorizons(int falloff, float start, float end, float min, float max, float density,
                                  int heightFalloff, int mixMode, boolean heightFromCamera, boolean heightUp,
                                  boolean heightDown, float heightBase, float heightStart, float heightEnd,
                                  float renderDistance, float worldHeight,
                                  float red, float green, float blue) implements FogModel {
        @Override public String name() { return "Distant Horizons' fog"; }
        @Override public float[] colour() { return new float[] {red, green, blue}; }

        @Override
        public void sample(Env env, double along, double y, float[] out) {
            if (renderDistance <= 0) return;
            double rise = y - env.cameraY();
            double reach = (mixMode == 0 ? Math.hypot(along, rise) : along) / renderDistance;
            double far = thickness(falloff, reach, start, end);
            double fog = far;
            if (mixMode > 1) {
                double offset = heightFromCamera ? rise : y - heightBase;
                double depth = heightUp && heightDown ? Math.abs(offset) : heightDown ? -offset : heightUp ? offset : 0;
                double height = thickness(heightFalloff, depth / Math.max(worldHeight, 1), heightStart, heightEnd);
                fog = switch (mixMode) {
                    case 2 -> Math.max(far, height);
                    case 3 -> far + height;
                    case 4 -> far * height;
                    case 5 -> 1 - (1 - far) * (1 - height);
                    case 6 -> far + Math.max(far, height);
                    case 7 -> far + far * height;
                    case 8 -> far + 1 - (1 - far) * (1 - height);
                    case 9 -> far * 0.5 + height * 0.5;
                    default -> far;
                };
            }
            out[0] = clamp((float) fog);
        }

        private double thickness(int type, double x, float from, float to) {
            double t = (x - from) / Math.max(to - from, 1e-6);
            if (type == 0) return min + (max - min) * Math.max(0, Math.min(1, t));
            t = Math.max(t, 0) * density;
            return min + (max - min) - (max - min) / Math.exp(type == 1 ? t : t * t);
        }
    }

    /**
     * Voxy's own fog ({@code shaders/post/blit_texture_depth_cutout.frag}), without a shader pack and with
     * its "environmental fog" option on: the game's own distance fog, carried on over the LOD terrain but no
     * thicker than it is at the far corner of the vanilla render distance.
     *
     * @param scale  fog per block of distance: one over the game's fog length
     * @param offset fog at the camera, negative while the fog starts some way off
     * @param limit  the most fog LOD terrain gets
     * @param alpha  strength of the game's fog colour
     */
    public record Voxy(float scale, float offset, float limit, float alpha,
                       float red, float green, float blue) implements FogModel {
        /**
         * @param start         where the game's environmental fog begins, in blocks
         * @param end           and where it is complete
         * @param viewDistance  the vanilla render distance in blocks
         * @return null when Voxy draws no fog with these values
         */
        public static Voxy of(float start, float end, float viewDistance, float red, float green, float blue,
                              float alpha) {
            if (Math.abs(end - start) <= 1 || alpha <= 0) return null;
            float scale = 1 / (end - start);
            float offset = -start * scale;
            float corner = (float) (Math.max(viewDistance, 320) * Math.sqrt(3));
            return new Voxy(scale, offset, clamp(corner * scale + offset), alpha, red, green, blue);
        }

        @Override public String name() { return "Voxy's fog"; }
        @Override public float[] colour() { return new float[] {red, green, blue}; }

        @Override
        public void sample(Env env, double along, double y, float[] out) {
            double length = Math.hypot(along, y - env.cameraY());
            out[0] = (float) (Math.max(0, Math.min(limit, length * scale + offset)) * alpha);
        }
    }

    private static float number(EffectFog.PackOptions pack, String option, float fallback) {
        String value = pack.value(option);
        if (value == null) return fallback;
        try {
            float parsed = Float.parseFloat(value.trim());
            return Float.isFinite(parsed) && parsed >= 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float clamp(float value) {
        return Math.max(0, Math.min(1, value));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double square(double value) {
        return value * value;
    }
}
