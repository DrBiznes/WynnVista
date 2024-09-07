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
    private static final Logger LOGGER = LoggerFactory.getLogger("wynndhrangelimiter");
    private boolean isOnServer = false;
    private int originalRenderDistance;

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
    }

    void onPlayerJoin(MinecraftClient client) {
        isOnServer = client.getCurrentServerEntry() != null && !client.isIntegratedServerRunning();
        LOGGER.info("Player joined " + (isOnServer ? "a server" : "singleplayer"));
        if (isOnServer) {
            checkAndUpdateRenderDistance(client);
        }
    }

    void onPlayerDisconnect() {
        resetState();
    }

    void onClientTick(MinecraftClient client) {
        if (isOnServer && client.player != null) {
            checkAndUpdateRenderDistance(client);
        }
    }

    private void checkAndUpdateRenderDistance(MinecraftClient client) {
        double x = client.player.getX();
        double z = client.player.getZ();
        boolean withinWynnRange = x >= -2512 && x <= 1553 && z >= -5774 && z <= -207;

        int targetRenderDistance = withinWynnRange ?
                originalRenderDistance : ModConfig.getReducedRenderDistance();

        if (DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue() != targetRenderDistance) {
            DhApi.Delayed.configs.graphics().chunkRenderDistance().setValue(targetRenderDistance);
            if (ModConfig.shouldShowMessage()) {
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
