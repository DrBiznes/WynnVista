package me.jamino.wynndhrangelimiter;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.text.Text;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WynndhrangelimiterClient implements ClientModInitializer
{
    private static final Logger LOGGER = LoggerFactory.getLogger("wynndhrangelimiter");
    private static final int MIN_X = -2512;
    private static final int MAX_X = 1553;
    private static final int MIN_Z = -5774;
    private static final int MAX_Z = -207;
    private static final double CHECK_DISTANCE = 100.0;

    private boolean lastEnabledState = true;
    private double lastCheckedX = Double.MAX_VALUE;
    private double lastCheckedZ = Double.MAX_VALUE;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing Wynn DH Range Limiter");

        DhApiEventRegister.on(DhApiAfterDhInitEvent.class, new DhApiAfterDhInitEvent() {
            @Override
            public void afterDistantHorizonsInit(DhApiEventParam<Void> event) {
                onAfterDhInit();
            }
        });

        // Register the movement event listener
        ClientPlayConnectionEvents.JOIN.register(this::onPlayerJoin);
    }

    private void onAfterDhInit() {
        LOGGER.info("Distant Horizons initialized. Mod version: " + DhApi.getModVersion());
        int currentRenderDistance = DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue();
        if (ModConfig.getOriginalRenderDistance() == 80) {
            ModConfig.setOriginalRenderDistance(currentRenderDistance);
        }
    }

    private void onPlayerJoin(ClientPlayNetworkHandler handler, PacketSender sender, MinecraftClient client) {
        // Initialize the last checked position when the player joins a world
        if (client.player != null) {
            lastCheckedX = client.player.getX();
            lastCheckedZ = client.player.getZ();
        }

        // Register the movement check event
        ClientTickEvents.START_CLIENT_TICK.register(this::checkPlayerMovement);
    }

    private void checkPlayerMovement(MinecraftClient client) {
        if (client.player != null) {
            double x = client.player.getX();
            double z = client.player.getZ();

            double distanceMoved = Math.sqrt(Math.pow(x - lastCheckedX, 2) + Math.pow(z - lastCheckedZ, 2));
            if (distanceMoved >= CHECK_DISTANCE) {
                checkPlayerPosition(client, x, z);
                lastCheckedX = x;
                lastCheckedZ = z;
            }
        }
    }

    private void checkPlayerPosition(MinecraftClient client, double x, double z) {
        boolean withinRange = x >= MIN_X && x <= MAX_X && z >= MIN_Z && z <= MAX_Z;

        if (withinRange != lastEnabledState) {
            lastEnabledState = withinRange;
            if (withinRange) {
                enableMod(client);
            } else {
                disableMod(client);
            }
        }
    }

    private void disableMod(MinecraftClient client) {
        DhApi.Delayed.configs.graphics().chunkRenderDistance().setValue(ModConfig.getReducedRenderDistance());
        if (ModConfig.shouldShowMessage()) {
            client.player.sendMessage(Text.literal("The Fog descends."), false);
        }
    }

    private void enableMod(MinecraftClient client) {
        DhApi.Delayed.configs.graphics().chunkRenderDistance().setValue(ModConfig.getOriginalRenderDistance());
        if (ModConfig.shouldShowMessage()) {
            client.player.sendMessage(Text.literal("The Fog lifts."), false);
        }
    }
}