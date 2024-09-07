package me.jamino.wynndhrangelimiter;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WynndhrangelimiterClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynndhrangelimiter");
    private static final WynnVistaMod MOD = new WynnVistaMod();

    @Override
    public void onInitializeClient() {
        LOGGER.info("Initializing Wynn DH Range Limiter");

        MOD.initialize();

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            LOGGER.info("Player JOIN event triggered");
            MOD.onPlayerJoin(client);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LOGGER.info("Player DISCONNECT event triggered");
            MOD.onPlayerDisconnect();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            MOD.onClientTick(client);
        });
    }
}
