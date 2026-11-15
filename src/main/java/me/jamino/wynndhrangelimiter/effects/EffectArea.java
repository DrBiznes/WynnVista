package me.jamino.wynndhrangelimiter.effects;

import java.util.List;

/**
 * The world effects of one part of the map. An area has its own tab on the config screen, with a switch for the
 * whole area and, for each of its effects, the effect's switch and {@link WorldEffect#options() options}.
 *
 * @param id      stable key used in the config file
 * @param name    title of the area's config tab
 * @param effects the area's effects, in draw order
 */
public record EffectArea(String id, String name, String description, List<WorldEffect> effects) {
    public EffectArea {
        effects = List.copyOf(effects);
    }
}
