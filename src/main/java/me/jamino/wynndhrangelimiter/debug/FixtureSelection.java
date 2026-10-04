package me.jamino.wynndhrangelimiter.debug;

import me.jamino.wynndhrangelimiter.ModConfig;
import me.jamino.wynndhrangelimiter.visibility.WorldContextResolver;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

public final class FixtureSelection {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista");
    private static volatile Path selectedRoot;

    private FixtureSelection() {}

    public static void onSaveSelected(MinecraftClient client, String name) {
        selectedRoot = null;
        Path fixture = WorldContextResolver.fixturePath();
        if (!ModConfig.fixtureEnabled() || fixture == null) return;
        try {
            Path selected = client.getLevelStorage().resolve(name).toRealPath();
            if (selected.equals(fixture.toRealPath())) {
                selectedRoot = selected;
                LOGGER.info("Designated DH fixture selected before world start: {}", selected);
            }
        } catch (IOException e) {
            LOGGER.error("Could not identify selected fixture save", e);
        }
    }

    public static Path selectedRoot() { return selectedRoot; }
    public static void clear() { selectedRoot = null; }
}
