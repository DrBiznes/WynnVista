package me.jamino.wynndhrangelimiter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
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
import java.util.Locale;

public final class ModConfig implements ModMenuApi {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("WynnVista.json");
    private static Config config = load();

    private static final class Config {
        int schemaVersion = 2;
        boolean showMessage = true;
        boolean maskingEnabled = true;
        boolean fixtureEnabled = false;
        String fixtureSavePath = "";
        String fixtureOverride = "AUTO";
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
                if (json.has("fixtureEnabled")) result.fixtureEnabled = json.get("fixtureEnabled").getAsBoolean();
                if (json.has("fixtureSavePath")) result.fixtureSavePath = json.get("fixtureSavePath").getAsString();
                if (json.has("fixtureOverride")) result.fixtureOverride = json.get("fixtureOverride").getAsString();
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
