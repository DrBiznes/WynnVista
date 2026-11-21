package me.jamino.wynndhrangelimiter.effects;

import java.util.List;

/**
 * Every world effect, grouped by the part of the map it belongs to. This is the one place an effect or an area
 * is registered: the renderer draws what is listed here and the config screen builds a tab from each area.
 *
 * <p>To add an effect, implement {@link WorldEffect} and add it to its area's list. To add an area, add an
 * {@link EffectArea} here. Neither needs a change to the renderer or the config code.
 */
public final class EffectAreas {
    public static final EffectArea WYNN_PLAINS = new EffectArea("wynn_plains", "Wynn Plains",
            "Effects of the Wynn Plains: Mount Wynn and the Roots of Corruption",
            List.of(new SmokePlume(), new NetherFog()));

    public static final EffectArea SKY_ISLANDS = new EffectArea("sky_islands", "Sky Islands",
            "Effects of the Sky Islands: the void under them and what rises through their air",
            List.of(new SkyIslandsVoid(), new SkyIslandsAir()));

    public static final EffectArea FRUMA = new EffectArea("fruma", "Fruma",
            "Effects of Fruma: the mist of the Mistwoods",
            List.of(new MistwoodsMist()));

    private static final List<EffectArea> AREAS = List.of(WYNN_PLAINS, SKY_ISLANDS, FRUMA);

    private EffectAreas() {}

    /** Every area, in draw order and in the order of their config tabs. */
    public static List<EffectArea> all() {
        return AREAS;
    }
}
