package me.jamino.wynndhrangelimiter;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WynnVistaMod {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista");
    private boolean isOnServer = false;
    private int originalRenderDistance;
    private boolean hasInitialized = false;
    private long joinTimestamp = 0;  // Tracks the time when the player joined

    void initialize() {
        DhApiEventRegister.on(DhApiAfterDhInitEvent.class, new DhApiAfterDhInitEvent() {
            @Override
            public void afterDistantHorizonsInit(DhApiEventParam<Void> event) {
                onAfterDhInit();
            }
        });
    }

    private void onAfterDhInit() {
        LOGGER.info("Distant Horizons initialized. Mod version: " + DhApi.getModVersion());
        originalRenderDistance = DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue();
        ModConfig.setOriginalRenderDistance(originalRenderDistance);
        hasInitialized = true;
    }

    void onPlayerJoin(MinecraftClient client) {
        if (!hasInitialized) return;

        isOnServer = client.getCurrentServerEntry() != null && !client.isIntegratedServerRunning();
        LOGGER.info("Player joined " + (isOnServer ? "a server" : "singleplayer"));
        if (isOnServer) {
            joinTimestamp = System.currentTimeMillis();  // Record the time when the player joins
            checkAndUpdateRenderDistance(client, true);  // Perform an initial check without sending messages
        }
    }

    void onPlayerDisconnect() {
        resetState();
    }

    void onClientTick(MinecraftClient client) {
        if (isOnServer && client.player != null && hasInitialized) {
            checkAndUpdateRenderDistance(client, false);  // Normal checks after joining
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

        int targetRenderDistance = withinWynnRange ?
                originalRenderDistance : ModConfig.getReducedRenderDistance();

        if (DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue() != targetRenderDistance) {
            DhApi.Delayed.configs.graphics().chunkRenderDistance().setValue(targetRenderDistance);

            // Suppress messages for the first 1 second after joining
            if (!initialCheck && ModConfig.shouldShowMessage() && System.currentTimeMillis() - joinTimestamp > 1000) {
                client.player.sendMessage(Text.literal(withinWynnRange ? "The Fog lifts." : "The Fog descends."), false);
            }
        }
    }

    private void resetState() {
        isOnServer = false;
        DhApi.Delayed.configs.graphics().chunkRenderDistance().setValue(originalRenderDistance);
        LOGGER.info("Reset mod state and restored original render distance");
    }
}
