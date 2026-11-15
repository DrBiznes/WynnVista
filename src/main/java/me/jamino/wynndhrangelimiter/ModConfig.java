package me.jamino.wynndhrangelimiter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.jamino.wynndhrangelimiter.effects.EffectArea;
import me.jamino.wynndhrangelimiter.effects.EffectAreas;
import me.jamino.wynndhrangelimiter.effects.EffectOption;
import me.jamino.wynndhrangelimiter.effects.WorldEffect;
import me.jamino.wynndhrangelimiter.visibility.BlockRect;
import me.jamino.wynndhrangelimiter.visibility.MaskMode;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ModConfig implements ModMenuApi {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("WynnVista.json");
    private static final int SCHEMA_VERSION = 3;
    /** Settings of schema 2 that became effect options, with the key each is kept under now. */
    private static final Map<String, String> LEGACY_OPTIONS = Map.of(
            "smokePlumeStyle", "smoke_plume.style",
            "netherFogStyle", "nether_fog.style",
            "skyIslandsVoidStyle", "sky_islands_void.style");
    /** Each choice as last read from the config, so a frame does not parse the same text again and again. */
    private static final Map<String, Enum<?>> CHOICES = new ConcurrentHashMap<>();
    private static Config config = load();

    private static final class Config {
        int schemaVersion = SCHEMA_VERSION;
        boolean showMessage = true;
        boolean maskingEnabled = true;
        boolean effectsEnabled = true;
        int effectSteps = 64;
        boolean effectFogModels = true;
        boolean effectPackLighting = true;
        boolean effectPackClouds = true;
        boolean effectPackReflections = true;
        /** Per-area switches by {@link EffectArea#id()}; an area that is not listed is on. */
        Map<String, Boolean> effectAreas = new LinkedHashMap<>();
        /** Per-effect switches by {@link WorldEffect#id()}; an effect that is not listed is on. */
        Map<String, Boolean> effects = new LinkedHashMap<>();
        /**
         * Values of the effects' {@link EffectOption}s by {@code <effect id>.<option id>}: true or false for a
         * toggle, the value's name for a choice. An option that is not listed has its fallback.
         */
        Map<String, Object> effectOptions = new LinkedHashMap<>();
        boolean fixtureEnabled = false;
        String fixtureSavePath = "";
        String fixtureOverride = "AUTO";
        String fixtureCustomRect = "";
    }

    private static Config load() {
        Config result = new Config();
        if (!Files.exists(FILE)) {
            save(result);
            return result;
        }
        try {
            JsonObject json = GSON.fromJson(Files.readString(FILE), JsonObject.class);
            if (json != null) {
                if (json.has("showMessage")) result.showMessage = json.get("showMessage").getAsBoolean();
                if (json.has("maskingEnabled")) result.maskingEnabled = json.get("maskingEnabled").getAsBoolean();
                if (json.has("effectsEnabled")) result.effectsEnabled = json.get("effectsEnabled").getAsBoolean();
                if (json.has("effectSteps")) result.effectSteps = json.get("effectSteps").getAsInt();
                if (json.has("effectFogModels")) result.effectFogModels = json.get("effectFogModels").getAsBoolean();
                if (json.has("effectPackLighting")) result.effectPackLighting = json.get("effectPackLighting").getAsBoolean();
                if (json.has("effectPackClouds")) result.effectPackClouds = json.get("effectPackClouds").getAsBoolean();
                if (json.has("effectPackReflections")) result.effectPackReflections = json.get("effectPackReflections").getAsBoolean();
                readSwitches(json, "effectAreas", result.effectAreas);
                readSwitches(json, "effects", result.effects);
                if (json.has("effectOptions") && json.get("effectOptions").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("effectOptions").entrySet()) {
                        if (!entry.getValue().isJsonPrimitive()) continue;
                        JsonPrimitive value = entry.getValue().getAsJsonPrimitive();
                        result.effectOptions.put(entry.getKey(), value.isBoolean() ? value.getAsBoolean() : value.getAsString());
                    }
                }
                LEGACY_OPTIONS.forEach((legacy, key) -> {
                    if (json.has(legacy) && json.get(legacy).isJsonPrimitive()) {
                        result.effectOptions.putIfAbsent(key, json.get(legacy).getAsString());
                    }
                });
                if (json.has("fixtureEnabled")) result.fixtureEnabled = json.get("fixtureEnabled").getAsBoolean();
                if (json.has("fixtureSavePath")) result.fixtureSavePath = json.get("fixtureSavePath").getAsString();
                if (json.has("fixtureOverride")) result.fixtureOverride = json.get("fixtureOverride").getAsString();
                if (json.has("fixtureCustomRect")) result.fixtureCustomRect = json.get("fixtureCustomRect").getAsString();
                int schema = json.has("schemaVersion") ? json.get("schemaVersion").getAsInt() : 0;
                if (schema < SCHEMA_VERSION) {
                    // The file from before masking replaced the distance limits is kept as it was.
                    Path backup = FILE.resolveSibling(FILE.getFileName() + ".bak");
                    if (schema < 2 && !Files.exists(backup)) Files.copy(FILE, backup, StandardCopyOption.COPY_ATTRIBUTES);
                    save(result);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Unable to read WynnVista config; using defaults", e);
        }
        return result;
    }

    private static void readSwitches(JsonObject json, String name, Map<String, Boolean> into) {
        if (!json.has(name) || !json.get(name).isJsonObject()) return;
        json.getAsJsonObject(name).entrySet().forEach(entry -> into.put(entry.getKey(), entry.getValue().getAsBoolean()));
    }

    private static void save(Config value) {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(value), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Unable to save WynnVista config", e);
        }
    }

    private static void save() {
        CHOICES.clear();
        save(config);
    }

    public static boolean shouldShowMessage() { return config.showMessage; }
    public static boolean maskingEnabled() { return config.maskingEnabled; }
    /** Master switch for every world effect. */
    public static boolean effectsEnabled() { return config.effectsEnabled; }
    /** Whether an area's effects are on; the master switch still applies. */
    public static boolean areaEnabled(String id) { return config.effectAreas.getOrDefault(id, true); }
    /** Whether one effect is on; the master switch and its area's switch still apply. */
    public static boolean effectEnabled(String id) { return config.effects.getOrDefault(id, true); }

    /** Whether a part of an effect is on; the effect's own switch still applies. */
    public static boolean effectToggle(String effectId, EffectOption.Toggle option) {
        return config.effectOptions.get(optionKey(effectId, option)) instanceof Boolean value ? value : option.fallback();
    }

    /** The chosen value of one of an effect's choices; a value the option does not know means its fallback. */
    public static <E extends Enum<E>> E effectChoice(String effectId, EffectOption.Choice<E> option) {
        return option.type().cast(CHOICES.computeIfAbsent(optionKey(effectId, option), key -> {
            if (config.effectOptions.get(key) instanceof String name) {
                try {
                    return Enum.valueOf(option.type(), name.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("Invalid {}: {}", key, name);
                }
            }
            return option.fallback();
        }));
    }

    private static String optionKey(String effectId, EffectOption option) {
        return effectId + "." + option.id();
    }

    /** Ray-march samples per pixel for volumetric effects. */
    public static int effectSteps() { return Math.max(16, Math.min(128, config.effectSteps)); }
    /** Whether a shader pack's or LOD mod's fog is worked out from its settings instead of measured. */
    public static boolean effectFogModels() { return config.effectFogModels; }
    /** Whether effects are lit to match the sky and sun path of the shader pack in use. */
    public static boolean effectPackLighting() { return config.effectPackLighting; }
    /** Whether a supported shader pack's own clouds hide the effects behind them. */
    public static boolean effectPackClouds() { return config.effectPackClouds; }
    /** Whether effects are mirrored in the water of a shader pack that draws reflections on it. */
    public static boolean effectPackReflections() { return config.effectPackReflections; }

    public static boolean fixtureEnabled() { return config.fixtureEnabled; }
    public static String fixtureSavePath() { return config.fixtureSavePath; }

    public static MaskMode fixtureOverride() {
        try {
            if ("AUTO".equalsIgnoreCase(config.fixtureOverride)) return null;
            return MaskMode.valueOf(config.fixtureOverride.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid fixtureOverride: {}", config.fixtureOverride);
            return null;
        }
    }

    /** Inclusive block corners "x1,z1,x2,z2" for the fixture-only FIXTURE_CUSTOM override; null if unset or invalid. */
    public static BlockRect fixtureCustomRect() {
        String[] parts = config.fixtureCustomRect.trim().split("\\s*,\\s*");
        if (parts.length != 4) return null;
        try {
            return BlockRect.fromInclusive(Long.parseLong(parts[0]), Long.parseLong(parts[1]),
                    Long.parseLong(parts[2]), Long.parseLong(parts[3]));
        } catch (RuntimeException e) {
            LOGGER.warn("Invalid fixtureCustomRect: {}", config.fixtureCustomRect);
            return null;
        }
    }

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            // Every entry only sets its value; the file is written once when the screen is saved.
            ConfigBuilder builder = ConfigBuilder.create()
                    .setParentScreen(parent).setTitle(Text.literal("WynnVista Config"))
                    .setSavingRunnable(ModConfig::save);
            ConfigEntryBuilder entries = builder.entryBuilder();
            ConfigCategory general = builder.getOrCreateCategory(Text.literal("General"));
            general.addEntry(entries.startBooleanToggle(Text.literal("Show Region Messages"), config.showMessage)
                    .setDefaultValue(true).setSaveConsumer(value -> config.showMessage = value).build());
            general.addEntry(entries.startBooleanToggle(Text.literal("Enable LOD Masking"), config.maskingEnabled)
                    .setDefaultValue(true).setSaveConsumer(value -> config.maskingEnabled = value).build());
            addEffectSettings(builder.getOrCreateCategory(Text.literal("World Effects")), entries);
            for (EffectArea area : EffectAreas.all()) {
                addArea(builder.getOrCreateCategory(Text.literal(area.name())), entries, area);
            }
            ConfigCategory fixture = builder.getOrCreateCategory(Text.literal("Local Fixture"));
            fixture.addEntry(entries.startBooleanToggle(Text.literal("Enable Fixture"), config.fixtureEnabled)
                    .setDefaultValue(false).setSaveConsumer(value -> config.fixtureEnabled = value).build());
            fixture.addEntry(entries.startStrField(Text.literal("Fixture Save Path"), config.fixtureSavePath)
                    .setDefaultValue("").setSaveConsumer(value -> config.fixtureSavePath = value).build());
            fixture.addEntry(entries.startStrField(Text.literal("Fixture Override"), config.fixtureOverride)
                    .setDefaultValue("AUTO")
                    .setTooltip(Text.literal("AUTO, MAIN, LIGHT, VOID_OUTER, NONE, or PASSTHROUGH"))
                    .setSaveConsumer(value -> config.fixtureOverride = value).build());
            return builder.build();
        };
    }

    /** The settings shared by every world effect, wherever it is. */
    private static void addEffectSettings(ConfigCategory effects, ConfigEntryBuilder entries) {
        effects.addEntry(entries.startBooleanToggle(Text.literal("Enable World Effects"), config.effectsEnabled)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Master switch for the effects of every area. Each area has a tab of its "
                        + "own with a switch for each of its effects"))
                .setSaveConsumer(value -> config.effectsEnabled = value).build());
        effects.addEntry(entries.startIntSlider(Text.literal("World Effect Quality"), effectSteps(), 16, 128)
                .setDefaultValue(64)
                .setTooltip(Text.literal("Samples per pixel for volumetric effects; lower is faster"))
                .setSaveConsumer(value -> config.effectSteps = value).build());
        effects.addEntry(entries.startBooleanToggle(Text.literal("Follow Fog Settings"), config.effectFogModels)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Put effects in the same fog as distant terrain, worked out from the "
                        + "settings of the shader pack (Complementary, BSL, Photon) or of Distant Horizons / "
                        + "Voxy. Off: the fog is only measured from the picture"))
                .setSaveConsumer(value -> config.effectFogModels = value).build());
        effects.addEntry(entries.startBooleanToggle(Text.literal("Match Shader Pack Lighting"),
                        config.effectPackLighting)
                .setDefaultValue(true)
                .setTooltip(Text.literal("With a shader pack, light effects from the pack's sun and moon and "
                        + "match their brightness and tint to the sky the pack draws. Off: their own lighting"))
                .setSaveConsumer(value -> config.effectPackLighting = value).build());
        effects.addEntry(entries.startBooleanToggle(Text.literal("Shader Pack Clouds Hide Effects"),
                        config.effectPackClouds)
                .setDefaultValue(true)
                .setTooltip(Text.literal("Draw effects behind the clouds of the shader pack in use "
                        + "(Complementary, BSL, Photon). Off: effects are drawn over the pack's clouds"))
                .setSaveConsumer(value -> config.effectPackClouds = value).build());
        effects.addEntry(entries.startBooleanToggle(Text.literal("Shader Pack Water Reflections"),
                        config.effectPackReflections)
                .setDefaultValue(true)
                .setTooltip(Text.literal("With a shader pack whose water reflects, show effects in that "
                        + "water too. Off: water mirrors the sky and terrain but not the effects"))
                .setSaveConsumer(value -> config.effectPackReflections = value).build());
    }

    /**
     * One area's tab: its own switch, then a group for each of its effects holding the effect's switch and an
     * entry for each of its options. Everything on it comes from the area and its effects.
     */
    @SuppressWarnings("rawtypes")
    private static void addArea(ConfigCategory tab, ConfigEntryBuilder entries, EffectArea area) {
        tab.addEntry(entries.startBooleanToggle(Text.literal("Enable " + area.name() + " Effects"),
                        areaEnabled(area.id()))
                .setDefaultValue(true)
                .setTooltip(Text.literal(area.description() + ". Off: none of the effects below are drawn"))
                .setSaveConsumer(value -> config.effectAreas.put(area.id(), value)).build());
        for (WorldEffect effect : area.effects()) {
            List<AbstractConfigListEntry> group = new ArrayList<>();
            group.add(entries.startBooleanToggle(Text.literal("Enabled"), effectEnabled(effect.id()))
                    .setDefaultValue(true)
                    .setTooltip(Text.literal(effect.description()))
                    .setSaveConsumer(value -> config.effects.put(effect.id(), value)).build());
            for (EffectOption option : effect.options()) group.add(optionEntry(entries, effect, option));
            tab.addEntry(entries.startSubCategory(Text.literal(effect.name()), group)
                    .setExpanded(true)
                    .setTooltip(Text.literal(effect.description())).build());
        }
    }

    private static AbstractConfigListEntry<?> optionEntry(ConfigEntryBuilder entries, WorldEffect effect,
                                                           EffectOption option) {
        String key = optionKey(effect.id(), option);
        return switch (option) {
            case EffectOption.Toggle toggle -> entries
                    .startBooleanToggle(Text.literal(toggle.name()), effectToggle(effect.id(), toggle))
                    .setDefaultValue(toggle.fallback())
                    .setTooltip(Text.literal(toggle.description()))
                    .setSaveConsumer(value -> config.effectOptions.put(key, value)).build();
            case EffectOption.Choice<?> choice -> choiceEntry(entries, effect, choice, key);
        };
    }

    private static <E extends Enum<E>> AbstractConfigListEntry<?> choiceEntry(ConfigEntryBuilder entries,
                                                                              WorldEffect effect,
                                                                              EffectOption.Choice<E> choice,
                                                                              String key) {
        return entries.startEnumSelector(Text.literal(choice.name()), choice.type(), effectChoice(effect.id(), choice))
                .setDefaultValue(choice.fallback())
                .setEnumNameProvider(value -> Text.literal(choice.label().apply(choice.type().cast(value))))
                .setTooltip(Text.literal(choice.description()))
                .setSaveConsumer(value -> config.effectOptions.put(key, value.name())).build();
    }
}
