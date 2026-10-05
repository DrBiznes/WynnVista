package me.jamino.wynndhrangelimiter.debug;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.jamino.wynndhrangelimiter.ModConfig;
import me.jamino.wynndhrangelimiter.visibility.WorldContextResolver;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Freezes Voxy ingestion for the one designated local fixture save, so the vanilla superflat chunks
 * cannot overwrite the copied Wynncraft LODs. The flag is changed in memory only and restored when the
 * fixture server stops; ordinary worlds and servers never see it.
 */
public final class VoxyFixtureController {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista");
    private static boolean frozen;
    private static boolean previousIngest;

    private VoxyFixtureController() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            Path fixture = WorldContextResolver.fixturePath();
            if (!ModConfig.fixtureEnabled() || fixture == null) return;
            try {
                Path actual = server.getSavePath(WorldSavePath.ROOT).toRealPath();
                if (!actual.equals(fixture.toRealPath())) return;
                previousIngest = VoxyConfig.CONFIG.ingestEnabled;
                VoxyConfig.CONFIG.ingestEnabled = false;
                frozen = true;
                LOGGER.info("Voxy fixture ingestion frozen before world start (was {}): {}", previousIngest, actual);
            } catch (IOException e) {
                LOGGER.error("Could not identify starting Voxy fixture server", e);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (!frozen) return;
            VoxyConfig.CONFIG.ingestEnabled = previousIngest;
            frozen = false;
            FixtureSelection.clear();
            LOGGER.info("Voxy fixture ingestion restored in memory to {}", previousIngest);
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!frozen || client.world == null || client.getServer() == null) return;
            try {
                String id = WorldIdentifier.of(client.world).getWorldId();
                LOGGER.info("Voxy fixture world: id={}, dimension={}, storage={}", id,
                        client.world.getRegistryKey().getValue(),
                        client.getServer().getSavePath(WorldSavePath.ROOT).resolve("voxy").resolve(id).resolve("storage"));
            } catch (RuntimeException e) {
                LOGGER.warn("Could not resolve Voxy fixture world identifier", e);
            }
        });
        LOGGER.info("Registered Voxy fixture ingestion freeze");
    }
}
