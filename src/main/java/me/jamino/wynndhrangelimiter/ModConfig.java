package me.jamino.wynndhrangelimiter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import me.jamino.wynndhrangelimiter.effects.EffectArea;
import me.jamino.wynndhrangelimiter.effects.EffectAreas;
import me.jamino.wynndhrangelimiter.effects.EffectOption;
import me.jamino.wynndhrangelimiter.effects.WorldEffect;
import me.jamino.wynndhrangelimiter.visibility.BlockRect;
import me.jamino.wynndhrangelimiter.visibility.MaskMode;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WynnVista's settings: the config file and what the rest of the mod reads from it. This class uses neither Mod
 * Menu nor Cloth Config, which are both optional: without them the file is edited by hand. The config screen is
 * {@link ConfigScreen}, opened through {@link ModMenuIntegration}.
 */
public final class ModConfig {
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
    static Config config = load();

    static final class Config {
        int schemaVersion = SCHEMA_VERSION;
        boolean showMessage = false;
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

    /** Writes the settings as they are now to the config file. */
    static void save() {
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

    static String optionKey(String effectId, EffectOption option) {
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

    private ModConfig() {}
}
