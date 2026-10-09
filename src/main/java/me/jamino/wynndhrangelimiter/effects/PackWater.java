package me.jamino.wynndhrangelimiter.effects;

import java.util.Locale;

/**
 * How much of what is mirrored in it a shader pack's water shows, so that an effect's reflection is as strong
 * as the sky's and the terrain's beside it. Each pack has its own curve from a view straight down, where
 * water reflects little, to a grazing view, where it is a mirror:
 * {@code strength * (base + (1 - base) * (1 - cosine)^power)}, with the cosine of the angle between the view
 * and the water's normal. The curves are each pack's own, written out again from the version named.
 *
 * @param base     share reflected looking straight down
 * @param power    how late the rise towards a grazing view comes
 * @param strength the pack's own multiplier on the whole
 */
public record PackWater(float base, float power, float strength) {
    /** Schlick's approximation for water; Photon v1.3 and any pack that is not recognised. */
    static final PackWater PHYSICAL = new PackWater(0.02f, 5, 1);

    /**
     * The water of a shader pack, recognised as its fog is ({@link FogModels#forPack}); null when the pack's
     * reflections are switched off, as then nothing else is mirrored in its water either.
     */
    public static PackWater forPack(EffectFog.PackOptions pack) {
        String name = pack.name().toLowerCase(Locale.ROOT);
        if (pack.defines("BORDER_FOG_DISTANCE_OVERWORLD") && pack.defines("ATM_FOG_DISTANCE")) {
            // Complementary r5.x, gbuffers_water: (fresnel^3 * 0.85 + 0.15) * FRESNEL_MULTIPLIER, no more than 1.
            if (number(pack, "WATER_REFLECT_QUALITY", 2) < 0) return null;
            return new PackWater(0.15f, 3, Math.max(0, Math.min(1, number(pack, "FRESNEL_MULTIPLIER", 1))));
        }
        if (pack.defines("AIR_FOG_MIE_DENSITY_NOON") || name.contains("photon")) {
            return pack.enabled("ENVIRONMENT_REFLECTIONS", true) || pack.enabled("SKY_REFLECTIONS", true)
                    ? PHYSICAL : null;
        }
        if (pack.defines("FOG_DENSITY_NIGHT") && pack.defines("FAR_VANILLA_FOG") || name.contains("bsl")) {
            // BSL v10.1, gbuffers_water: fresnel^5 * 0.98 + 0.02.
            return number(pack, "REFLECTION", 2) <= 0 ? null : PHYSICAL;
        }
        return PHYSICAL;
    }

    /** Share of a reflection that is seen, for the cosine of the angle between the view and the water's normal. */
    public float reflectance(double cosine) {
        double grazing = 1 - Math.max(0, Math.min(1, cosine));
        return (float) (strength * (base + (1 - base) * Math.pow(grazing, power)));
    }

    /** A value option of the pack, which unlike the fog options may be negative. */
    private static float number(EffectFog.PackOptions pack, String option, float fallback) {
        String value = pack.value(option);
        if (value == null) return fallback;
        try {
            float parsed = Float.parseFloat(value.trim());
            return Float.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
