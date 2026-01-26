package me.jamino.wynndhrangelimiter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class ModConfig implements ModMenuApi {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "WynnVista.json");

    private static Config config;

    static {
        loadConfig();
    }

    private static class Config {
        boolean showMessage = true;
        int reducedRenderDistance = 16;
        int maxRenderDistance = -1; // -1 indicates not yet initialized (first run)
    }

    public static boolean shouldShowMessage() {
        return config.showMessage;
    }

    public static int getReducedRenderDistance() {
        return config.reducedRenderDistance;
    }

    public static int getMaxRenderDistance() {
        return config.maxRenderDistance;
    }

    /**
     * Called on mod initialization to capture the LOD mod's render distance.
     * Only captures on first run (when maxRenderDistance is -1).
     *
     * @param distance The current render distance from the LOD mod
     * @return The max render distance to use (either captured or previously saved)
     */
    public static int initializeMaxRenderDistance(int distance) {
        if (config.maxRenderDistance == -1) {
            // First run - capture the current distance
            config.maxRenderDistance = distance;
            saveConfig();
        }
        return config.maxRenderDistance;
    }

    /**
     * @return true if this is the first run (maxRenderDistance not yet set)
     */
    public static boolean isFirstRun() {
        return config.maxRenderDistance == -1;
    }

    // Legacy method - kept for compatibility but now uses the new field
    public static void setOriginalRenderDistance(int distance) {
        if (config.maxRenderDistance != distance) {
            config.maxRenderDistance = distance;
            saveConfig();
        }
    }

    private static void loadConfig() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                // First, read raw JSON to check for old field
                com.google.gson.JsonObject jsonObj = GSON.fromJson(reader, com.google.gson.JsonObject.class);
                if (jsonObj != null) {
                    config = new Config();

                    // Load standard fields
                    if (jsonObj.has("showMessage")) {
                        config.showMessage = jsonObj.get("showMessage").getAsBoolean();
                    }
                    if (jsonObj.has("reducedRenderDistance")) {
                        config.reducedRenderDistance = jsonObj.get("reducedRenderDistance").getAsInt();
                    }

                    // Handle migration: check for old field name first
                    if (jsonObj.has("maxRenderDistance")) {
                        config.maxRenderDistance = jsonObj.get("maxRenderDistance").getAsInt();
                    } else if (jsonObj.has("originalRenderDistance")) {
                        // Migrate from old config format
                        config.maxRenderDistance = jsonObj.get("originalRenderDistance").getAsInt();
                        saveConfig(); // Save with new field name
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (config == null) {
            config = new Config();
            saveConfig();
        }
    }

    private static void saveConfig() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(config, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            ConfigBuilder builder = ConfigBuilder.create()
                    .setParentScreen(parent)
                    .setTitle(Text.literal("WynnVista Config"));

            ConfigCategory general = builder.getOrCreateCategory(Text.literal("General"));

            ConfigEntryBuilder entryBuilder = builder.entryBuilder();

            general.addEntry(entryBuilder.startBooleanToggle(Text.literal("Show In-Game Messages"), config.showMessage)
                    .setDefaultValue(true)
                    .setTooltip(Text.literal("Toggle whether to show messages when entering/leaving the Wynncraft area"))
                    .setSaveConsumer(newValue -> {
                        config.showMessage = newValue;
                        saveConfig();
                    })
                    .build());

            general.addEntry(entryBuilder.startIntSlider(Text.literal("Max Render Distance"),
                            config.maxRenderDistance > 0 ? config.maxRenderDistance : 80, 16, 256)
                    .setDefaultValue(80)
                    .setTooltip(Text.literal("Your preferred LOD render distance inside the Wynncraft area"))
                    .setSaveConsumer(newValue -> {
                        config.maxRenderDistance = newValue;
                        saveConfig();
                    })
                    .build());

            general.addEntry(entryBuilder.startIntSlider(Text.literal("Reduced Render Distance"), config.reducedRenderDistance, 12, 64)
                    .setDefaultValue(16)
                    .setTooltip(Text.literal("Reduced render distance for LODs outside Wynncraft area"))
                    .setSaveConsumer(newValue -> {
                        config.reducedRenderDistance = newValue;
                        saveConfig();
                    })
                    .build());

            return builder.build();
        };
    }
}
