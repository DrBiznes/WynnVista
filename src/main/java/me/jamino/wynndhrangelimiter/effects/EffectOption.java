package me.jamino.wynndhrangelimiter.effects;

import java.util.function.Function;

/**
 * One setting of a {@link WorldEffect} beyond its own on/off switch: a part of it that can be left out, or a
 * choice of how it is drawn. An effect lists its options in {@link WorldEffect#options()}; the config screen
 * builds an entry from each and the config file keeps its value under {@code <effect id>.<option id>}, so an
 * effect gains a setting without any change to the config code.
 */
public sealed interface EffectOption permits EffectOption.Toggle, EffectOption.Choice {
    /** Stable key within the effect, used in the config file. */
    String id();

    /** Label and tooltip of this option's config entry. */
    String name();

    String description();

    /** A part of an effect that is drawn or left out. */
    record Toggle(String id, String name, String description, boolean fallback) implements EffectOption {
        /** A part that is drawn unless switched off. */
        public Toggle(String id, String name, String description) {
            this(id, name, description, true);
        }
    }

    /** One of an enum's values; a value the config file does not name, or names wrongly, is the fallback. */
    record Choice<E extends Enum<E>>(String id, String name, String description, Class<E> type, E fallback,
                                     Function<E, String> label) implements EffectOption {}
}
