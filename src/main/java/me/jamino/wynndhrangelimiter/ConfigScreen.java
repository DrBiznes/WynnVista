package me.jamino.wynndhrangelimiter;

import me.jamino.wynndhrangelimiter.effects.EffectArea;
import me.jamino.wynndhrangelimiter.effects.EffectAreas;
import me.jamino.wynndhrangelimiter.effects.EffectOption;
import me.jamino.wynndhrangelimiter.effects.WorldEffect;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * The config screen, built with Cloth Config. Only loaded when a screen is asked for and Cloth Config is
 * installed (see {@link ModMenuIntegration}); nothing else in the mod may refer to this class.
 */
final class ConfigScreen {
    private ConfigScreen() {}

    static Screen create(Screen parent) {
        // Every entry only sets its value; the file is written once when the screen is saved.
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent).setTitle(Text.literal("WynnVista Config"))
                .setSavingRunnable(ModConfig::save);
        ConfigEntryBuilder entries = builder.entryBuilder();
        ConfigCategory general = builder.getOrCreateCategory(Text.literal("General"));
        general.addEntry(entries.startBooleanToggle(Text.literal("Show Region Messages"), ModConfig.config.showMessage)
                .setDefaultValue(false)
                .setTooltip(Text.literal("Says in chat \"The Fog lifts.\" when you enter a region whose LOD terrain is shown, and "
                        + "\"The Fog descends.\" when you leave it")).setSaveConsumer(value -> ModConfig.config.showMessage = value).build());
        general.addEntry(entries.startBooleanToggle(Text.literal("Enable LOD Masking"), ModConfig.config.maskingEnabled)
                .setDefaultValue(true).setSaveConsumer(value -> ModConfig.config.maskingEnabled = value).build());
        addEffectSettings(builder.getOrCreateCategory(Text.literal("World Effects")), entries);
        for (EffectArea area : EffectAreas.all()) {
            addArea(builder.getOrCreateCategory(Text.literal(area.name())), entries, area);
        }
        ConfigCategory fixture = builder.getOrCreateCategory(Text.literal("Local Fixture"));
        fixture.addEntry(entries.startBooleanToggle(Text.literal("Enable Fixture"), ModConfig.config.fixtureEnabled)
                .setDefaultValue(false).setSaveConsumer(value -> ModConfig.config.fixtureEnabled = value).build());
        fixture.addEntry(entries.startStrField(Text.literal("Fixture Save Path"), ModConfig.config.fixtureSavePath)
                .setDefaultValue("").setSaveConsumer(value -> ModConfig.config.fixtureSavePath = value).build());
        fixture.addEntry(entries.startStrField(Text.literal("Fixture Override"), ModConfig.config.fixtureOverride)
                .setDefaultValue("AUTO")
                .setTooltip(Text.literal("AUTO, MAIN, LIGHT, VOID_OUTER, NONE, or PASSTHROUGH"))
                .setSaveConsumer(value -> ModConfig.config.fixtureOverride = value).build());
        return builder.build();
    }

    /** The settings shared by every world effect, wherever it is. */
    private static void addEffectSettings(ConfigCategory effects, ConfigEntryBuilder entries) {
        effects.addEntry(entries.startBooleanToggle(Text.literal("Enable World Effects"), ModConfig.config.effectsEnabled)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Master switch for the effects of every area. Each area has a tab of its "
                        + "own with a switch for each of its effects"))
                .setSaveConsumer(value -> ModConfig.config.effectsEnabled = value).build());
        effects.addEntry(entries.startIntSlider(Text.literal("World Effect Quality"), ModConfig.effectSteps(), 16, 128)
                .setDefaultValue(64)
                .setTooltip(Text.literal("Samples per pixel for volumetric effects; lower is faster"))
                .setSaveConsumer(value -> ModConfig.config.effectSteps = value).build());
        effects.addEntry(entries.startBooleanToggle(Text.literal("Follow Fog Settings"), ModConfig.config.effectFogModels)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Put effects in the same fog as distant terrain, worked out from the "
                        + "settings of the shader pack (Complementary, BSL, Photon) or of Distant Horizons / "
                        + "Voxy. Off: the fog is only measured from the picture"))
                .setSaveConsumer(value -> ModConfig.config.effectFogModels = value).build());
        effects.addEntry(entries.startBooleanToggle(Text.literal("Match Shader Pack Lighting"),
                        ModConfig.config.effectPackLighting)
                .setDefaultValue(true)
                .setTooltip(Text.literal("With a shader pack, light effects from the pack's sun and moon and "
                        + "match their brightness and tint to the sky the pack draws. Off: their own lighting"))
                .setSaveConsumer(value -> ModConfig.config.effectPackLighting = value).build());
        effects.addEntry(entries.startBooleanToggle(Text.literal("Shader Pack Clouds Hide Effects"),
                        ModConfig.config.effectPackClouds)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Draw effects behind the clouds of the shader pack in use "
                        + "(Complementary, BSL, Photon). Off: effects are drawn over the pack's clouds"))
                .setSaveConsumer(value -> ModConfig.config.effectPackClouds = value).build());
        effects.addEntry(entries.startBooleanToggle(Text.literal("Steady Effect Edges"),
                        ModConfig.config.effectPackEdges)
                .setDefaultValue(true)
                .setTooltip(Text.literal("With Wynn-Photon, keep effects from shimmering along the edges of "
                        + "blocks in front of them. Other shader packs do not provide what this needs and are "
                        + "not affected. Off: effects are cut off by each frame's depth alone"))
                .setSaveConsumer(value -> ModConfig.config.effectPackEdges = value).build());
        effects.addEntry(entries.startBooleanToggle(Text.literal("Shader Pack Water Reflections"),
                        ModConfig.config.effectPackReflections)
                .setDefaultValue(true)
                .setTooltip(Text.literal("With a shader pack whose water reflects, show effects in that "
                        + "water too. Off: water mirrors the sky and terrain but not the effects"))
                .setSaveConsumer(value -> ModConfig.config.effectPackReflections = value).build());
    }

    /**
     * One area's tab: its own switch, then a group for each of its effects holding the effect's switch and an
     * entry for each of its options. Everything on it comes from the area and its effects.
     */
    @SuppressWarnings("rawtypes")
    private static void addArea(ConfigCategory tab, ConfigEntryBuilder entries, EffectArea area) {
        tab.addEntry(entries.startBooleanToggle(Text.literal("Enable " + area.name() + " Effects"),
                        ModConfig.areaEnabled(area.id()))
                .setDefaultValue(true)
                .setTooltip(Text.literal(area.description() + ". Off: none of the effects below are drawn"))
                .setSaveConsumer(value -> ModConfig.config.effectAreas.put(area.id(), value)).build());
        for (WorldEffect effect : area.effects()) {
            List<AbstractConfigListEntry> group = new ArrayList<>();
            group.add(entries.startBooleanToggle(Text.literal("Enabled"), ModConfig.effectEnabled(effect.id()))
                    .setDefaultValue(true)
                    .setTooltip(Text.literal(effect.description()))
                    .setSaveConsumer(value -> ModConfig.config.effects.put(effect.id(), value)).build());
            for (EffectOption option : effect.options()) group.add(optionEntry(entries, effect, option));
            tab.addEntry(entries.startSubCategory(Text.literal(effect.name()), group)
                    .setExpanded(true)
                    .setTooltip(Text.literal(effect.description())).build());
        }
    }

    private static AbstractConfigListEntry<?> optionEntry(ConfigEntryBuilder entries, WorldEffect effect,
                                                           EffectOption option) {
        String key = ModConfig.optionKey(effect.id(), option);
        return switch (option) {
            case EffectOption.Toggle toggle -> entries
                    .startBooleanToggle(Text.literal(toggle.name()), ModConfig.effectToggle(effect.id(), toggle))
                    .setDefaultValue(toggle.fallback())
                    .setTooltip(Text.literal(toggle.description()))
                    .setSaveConsumer(value -> ModConfig.config.effectOptions.put(key, value)).build();
            case EffectOption.Choice<?> choice -> choiceEntry(entries, effect, choice, key);
        };
    }

    private static <E extends Enum<E>> AbstractConfigListEntry<?> choiceEntry(ConfigEntryBuilder entries,
                                                                              WorldEffect effect,
                                                                              EffectOption.Choice<E> choice,
                                                                              String key) {
        return entries.startEnumSelector(Text.literal(choice.name()), choice.type(), ModConfig.effectChoice(effect.id(), choice))
                .setDefaultValue(choice.fallback())
                .setEnumNameProvider(value -> Text.literal(choice.label().apply(choice.type().cast(value))))
                .setTooltip(Text.literal(choice.description()))
                .setSaveConsumer(value -> ModConfig.config.effectOptions.put(key, value.name())).build();
    }
}
