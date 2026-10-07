package me.jamino.wynndhrangelimiter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.jamino.wynndhrangelimiter.effects.WorldEffect;
import me.jamino.wynndhrangelimiter.effects.WorldEffects;
import me.jamino.wynndhrangelimiter.visibility.BlockRect;
import me.jamino.wynndhrangelimiter.visibility.MaskMode;
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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class ModConfig implements ModMenuApi {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("WynnVista.json");
    private static Config config = load();

    private static final class Config {
        int schemaVersion = 2;
        boolean showMessage = true;
        boolean maskingEnabled = true;
        boolean effectsEnabled = true;
        int effectSteps = 64;
        /** Per-effect switches by {@link WorldEffect#id()}; an effect that is not listed is on. */
        Map<String, Boolean> effects = new LinkedHashMap<>();
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
                if (json.has("effects") && json.get("effects").isJsonObject()) {
                    json.getAsJsonObject("effects").entrySet().forEach(entry ->
                            result.effects.put(entry.getKey(), entry.getValue().getAsBoolean()));
                }
                if (json.has("fixtureEnabled")) result.fixtureEnabled = json.get("fixtureEnabled").getAsBoolean();
                if (json.has("fixtureSavePath")) result.fixtureSavePath = json.get("fixtureSavePath").getAsString();
                if (json.has("fixtureOverride")) result.fixtureOverride = json.get("fixtureOverride").getAsString();
                if (json.has("fixtureCustomRect")) result.fixtureCustomRect = json.get("fixtureCustomRect").getAsString();
                if (!json.has("schemaVersion") || json.get("schemaVersion").getAsInt() < 2) {
                    Path backup = FILE.resolveSibling(FILE.getFileName() + ".bak");
                    if (!Files.exists(backup)) Files.copy(FILE, backup, StandardCopyOption.COPY_ATTRIBUTES);
                    save(result);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Unable to read WynnVista config; using defaults", e);
        }
        return result;
    }

    private static void save(Config value) {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(value), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Unable to save WynnVista config", e);
        }
    }

    private static void save() { save(config); }

    public static boolean shouldShowMessage() { return config.showMessage; }
    public static boolean maskingEnabled() { return config.maskingEnabled; }
    /** Master switch for every world effect. */
    public static boolean effectsEnabled() { return config.effectsEnabled; }
    /** Whether one effect is on; the master switch still applies. */
    public static boolean effectEnabled(String id) { return config.effects.getOrDefault(id, true); }
    /** Ray-march samples per pixel for volumetric effects. */
    public static int effectSteps() { return Math.max(16, Math.min(128, config.effectSteps)); }
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
            ConfigBuilder builder = ConfigBuilder.create()
                    .setParentScreen(parent).setTitle(Text.literal("WynnVista Config"));
            ConfigCategory general = builder.getOrCreateCategory(Text.literal("General"));
            ConfigEntryBuilder entries = builder.entryBuilder();
            general.addEntry(entries.startBooleanToggle(Text.literal("Show Region Messages"), config.showMessage)
                    .setDefaultValue(true).setSaveConsumer(value -> {
                        config.showMessage = value;
                        save();
                    }).build());
            general.addEntry(entries.startBooleanToggle(Text.literal("Enable LOD Masking"), config.maskingEnabled)
                    .setDefaultValue(true).setSaveConsumer(value -> {
                        config.maskingEnabled = value;
                        save();
                    }).build());
            ConfigCategory effects = builder.getOrCreateCategory(Text.literal("World Effects"));
            effects.addEntry(entries.startBooleanToggle(Text.literal("Enable World Effects"), config.effectsEnabled)
                    .setDefaultValue(true)
                    .setTooltip(Text.literal("Master switch for every effect below"))
                    .setSaveConsumer(value -> {
                        config.effectsEnabled = value;
                        save();
                    }).build());
            effects.addEntry(entries.startIntSlider(Text.literal("World Effect Quality"), effectSteps(), 16, 128)
                    .setDefaultValue(64)
                    .setTooltip(Text.literal("Samples per pixel for volumetric effects; lower is faster"))
                    .setSaveConsumer(value -> {
                        config.effectSteps = value;
                        save();
                    }).build());
            for (WorldEffect effect : WorldEffects.all()) {
                effects.addEntry(entries.startBooleanToggle(Text.literal(effect.name()), effectEnabled(effect.id()))
                        .setDefaultValue(true)
                        .setTooltip(Text.literal(effect.description()))
                        .setSaveConsumer(value -> {
                            config.effects.put(effect.id(), value);
                            save();
                        }).build());
            }
            ConfigCategory fixture = builder.getOrCreateCategory(Text.literal("Local Fixture"));
            fixture.addEntry(entries.startBooleanToggle(Text.literal("Enable Fixture"), config.fixtureEnabled)
                    .setDefaultValue(false).setSaveConsumer(value -> {
                        config.fixtureEnabled = value;
                        save();
                    }).build());
            fixture.addEntry(entries.startStrField(Text.literal("Fixture Save Path"), config.fixtureSavePath)
                    .setDefaultValue("").setSaveConsumer(value -> {
                        config.fixtureSavePath = value;
                        save();
                    }).build());
            fixture.addEntry(entries.startStrField(Text.literal("Fixture Override"), config.fixtureOverride)
                    .setDefaultValue("AUTO")
                    .setTooltip(Text.literal("AUTO, MAIN, LIGHT, VOID_OUTER, NONE, or PASSTHROUGH"))
                    .setSaveConsumer(value -> {
                        config.fixtureOverride = value;
                        save();
                    }).build());
            return builder.build();
        };
    }
}
