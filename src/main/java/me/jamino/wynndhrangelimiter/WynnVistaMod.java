package me.jamino.wynndhrangelimiter;

import me.jamino.wynndhrangelimiter.controller.DistantHorizonsController;
import me.jamino.wynndhrangelimiter.controller.IRenderDistanceController;
import me.jamino.wynndhrangelimiter.controller.VoxyController;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WynnVistaMod {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista");

    private IRenderDistanceController controller;
    private boolean isOnServer = false;
    private int originalRenderDistance;
    private long joinTimestamp = 0;

    void initialize() {
        // Detect which LOD mod is installed
        boolean hasDH = FabricLoader.getInstance().isModLoaded("distanthorizons");
        boolean hasVoxy = FabricLoader.getInstance().isModLoaded("voxy");

        if (hasDH) {
            LOGGER.info("Distant Horizons detected, using DH controller");
            controller = new DistantHorizonsController();
        } else if (hasVoxy) {
            LOGGER.info("Voxy detected, using Voxy controller");
            controller = new VoxyController();
        } else {
            LOGGER.warn("No supported LOD mod detected (Distant Horizons or Voxy). WynnVista will be disabled.");
            return;
        }

        controller.initialize(this::onControllerInitialized);
    }

    private void onControllerInitialized() {
        LOGGER.info("{} initialized successfully", controller.getModName());
        int currentDistance = controller.getRenderDistance();
        boolean wasFirstRun = ModConfig.isFirstRun();
        // Only capture on first run, otherwise use the saved value from config
        originalRenderDistance = ModConfig.initializeMaxRenderDistance(currentDistance);
        if (wasFirstRun) {
            LOGGER.info("First run - captured max render distance: {}", originalRenderDistance);
        } else {
            LOGGER.info("Using saved max render distance: {}", originalRenderDistance);
        }

        // Check if we're already on a server (player joined before controller initialized)
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null && isOnServer) {
            LOGGER.info("Controller initialized while on server - applying initial render distance");
            checkAndUpdateRenderDistance(client, true);
        }
    }

    void onPlayerJoin(MinecraftClient client) {
        // Always track whether we're on a server, even if controller isn't ready
        isOnServer = client.getCurrentServerEntry() != null && !client.isIntegratedServerRunning();
        LOGGER.info("Player joined " + (isOnServer ? "a server" : "singleplayer"));

        if (isOnServer) {
            joinTimestamp = System.currentTimeMillis();
        }

        // Only update render distance if controller is ready
        if (controller == null || !controller.isInitialized()) {
            LOGGER.info("Controller not ready yet, will update render distance when initialized");
            return;
        }

        if (isOnServer) {
            checkAndUpdateRenderDistance(client, true);
        }
    }

    void onPlayerDisconnect() {
        resetState();
    }

    void onClientTick(MinecraftClient client) {
        if (controller == null) return;

        // Always tick the controller (needed for Voxy's delayed initialization)
        controller.tick();

        // Only check render distance if initialized and on server
        if (isOnServer && client.player != null && controller.isInitialized()) {
            checkAndUpdateRenderDistance(client, false);
        }
    }

    private void checkAndUpdateRenderDistance(MinecraftClient client, boolean initialCheck) {
        if (client.player == null) {
            LOGGER.warn("Player is null, skipping render distance update");
            return;
        }

        double x = client.player.getX();
        double z = client.player.getZ();
        boolean withinWynnRange = x >= -2512 && x <= 1553 && z >= -5774 && z <= -207;

        // Use config values directly so changes in GUI take effect immediately
        int maxDistance = ModConfig.getMaxRenderDistance();
        int targetRenderDistance = withinWynnRange ?
                (maxDistance > 0 ? maxDistance : originalRenderDistance) : ModConfig.getReducedRenderDistance();

        if (controller.getRenderDistance() != targetRenderDistance) {
            controller.setRenderDistance(targetRenderDistance);

            // Suppress messages for the first 1 second after joining
            if (!initialCheck && ModConfig.shouldShowMessage() && System.currentTimeMillis() - joinTimestamp > 1000) {
                client.player.sendMessage(Text.literal(withinWynnRange ? "The Fog lifts." : "The Fog descends."), false);
            }
        }
    }

    private void resetState() {
        isOnServer = false;
        if (controller != null && controller.isInitialized()) {
            int maxDistance = ModConfig.getMaxRenderDistance();
            int restoreDistance = maxDistance > 0 ? maxDistance : originalRenderDistance;
            controller.onDisconnect(restoreDistance);
            LOGGER.info("Reset mod state and restored render distance to {}", restoreDistance);
        }
    }
}
