package me.jamino.wynndhrangelimiter.debug;

import me.jamino.wynndhrangelimiter.mixin.client.InvokerCreateWorldScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.world.Difficulty;
import net.minecraft.world.gen.WorldPresets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the empty superflat fixture save without the GUI. Active only when the system property
 * {@code wynnvista.fixture.createWorld} names a save; a fixed seed keeps the world identifier stable.
 */
public final class FixtureWorldCreator {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista");
    private static final String SEED = "wynnvista-fixture";
    private static boolean opened;
    private static boolean confirmed;

    private FixtureWorldCreator() {}

    public static boolean requested() {
        return System.getProperty("wynnvista.fixture.createWorld") != null;
    }

    /** Called every client tick while a world is requested; returns true once creation has been started. */
    public static boolean tick(MinecraftClient client) {
        String name = System.getProperty("wynnvista.fixture.createWorld");
        if (name == null || confirmed) return confirmed;
        if (!opened && client.currentScreen instanceof TitleScreen) {
            opened = true;
            LOGGER.info("Opening world creation for fixture save: {}", name);
            CreateWorldScreen.show(client, () -> {});
            return false;
        }
        if (opened && client.currentScreen instanceof CreateWorldScreen screen) {
            WorldCreator creator = screen.getWorldCreator();
            creator.setWorldName(name);
            creator.setGameMode(WorldCreator.Mode.CREATIVE);
            creator.setCheatsEnabled(true);
            creator.setDifficulty(Difficulty.PEACEFUL);
            creator.setGenerateStructures(false);
            creator.setBonusChestEnabled(false);
            creator.setSeed(SEED);
            WorldCreator.WorldType flat = creator.getNormalWorldTypes().stream()
                    .filter(type -> type.preset() != null && type.preset().matchesKey(WorldPresets.FLAT))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Superflat preset not offered"));
            creator.setWorldType(flat);
            confirmed = true;
            LOGGER.info("Creating superflat fixture save '{}' (seed '{}')", creator.getWorldDirectoryName(), SEED);
            ((InvokerCreateWorldScreen) screen).wynnvista$createLevel();
        }
        return confirmed;
    }
}
