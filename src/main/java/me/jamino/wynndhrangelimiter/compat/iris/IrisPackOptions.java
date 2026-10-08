package me.jamino.wynndhrangelimiter.compat.iris;

import me.jamino.wynndhrangelimiter.effects.EffectFog;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.option.OptionSet;
import net.irisshaders.iris.shaderpack.option.values.OptionValues;

/**
 * Reads the active shader pack's option values. These are Iris internals, not its API, so they are kept in
 * this class alone: {@link IrisSupport#packOptions()} catches the linkage error of a build that lacks them.
 */
final class IrisPackOptions {
    private static ShaderPack pack;
    private static EffectFog.PackOptions options;

    private IrisPackOptions() {}

    /** Null when Iris has no pack loaded. Changing an option reloads the pack, so one reading per pack holds. */
    static EffectFog.PackOptions current() {
        ShaderPack current = Iris.getCurrentPack().orElse(null);
        if (current == null) {
            pack = null;
            options = null;
            return null;
        }
        if (current != pack) {
            String name = String.valueOf(Iris.getCurrentPackName());
            OptionValues values = current.getShaderPackOptions().getOptionValues();
            OptionSet set = values.getOptionSet();
            pack = current;
            options = new EffectFog.PackOptions() {
                @Override public String name() { return name; }

                @Override
                public boolean defines(String option) {
                    return set.getStringOptions().containsKey(option) || set.getBooleanOptions().containsKey(option);
                }

                @Override
                public String value(String option) {
                    return set.getStringOptions().containsKey(option) ? values.getStringValueOrDefault(option) : null;
                }

                @Override
                public boolean enabled(String option, boolean fallback) {
                    return set.getBooleanOptions().containsKey(option) ? values.getBooleanValueOrDefault(option) : fallback;
                }
            };
        }
        return options;
    }
}
