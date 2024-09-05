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
    private static final File CONFIG_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "wynn-dh-range-limiter.json");

    private static Config config;

    static {
        loadConfig();
    }

    private static class Config {
        boolean showMessage = true;
        int reducedRenderDistance = 16;
        int originalRenderDistance = 80; // Default value, will be overwritten on first run
    }

    public static boolean shouldShowMessage() {
        return config.showMessage;
    }

    public static int getReducedRenderDistance() {
        return config.reducedRenderDistance;
    }

    public static int getOriginalRenderDistance() {
        return config.originalRenderDistance;
    }

    public static void setOriginalRenderDistance(int distance) {
        config.originalRenderDistance = distance;
        saveConfig();
    }

    private static void loadConfig() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                config = GSON.fromJson(reader, Config.class);
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
                    .setTitle(Text.literal("Wynn DH Range Limiter Config"));

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

            general.addEntry(entryBuilder.startIntSlider(Text.literal("Reduced Render Distance"), config.reducedRenderDistance, 12, 36)
                    .setDefaultValue(16)
                    .setTooltip(Text.literal("Set the reduced render distance for LODs outside Wynncraft area"))
                    .setSaveConsumer(newValue -> {
                        config.reducedRenderDistance = newValue;
                        saveConfig();
                    })
                    .build());

            return builder.build();
        };
    }
}