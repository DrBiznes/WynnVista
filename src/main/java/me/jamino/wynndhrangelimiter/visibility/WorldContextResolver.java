package me.jamino.wynndhrangelimiter.visibility;

import me.jamino.wynndhrangelimiter.ModConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.InvalidPathException;

public final class WorldContextResolver {
    public record Resolution(String worldToken, String dimension, MaskMode mode, boolean fixture) {}

    /** Flashback's replay server, compared by name: Flashback is optional and not compiled against. */
    private static final String FLASHBACK_REPLAY_SERVER = "com.moulberry.flashback.playback.ReplayServer";

    private WorldContextResolver() {}

    public static Resolution resolve(MinecraftClient client) {
        if (client.world == null || !ModConfig.maskingEnabled()) {
            return new Resolution("", "", MaskMode.PASSTHROUGH, false);
        }
        String dimension = client.world.getRegistryKey().getValue().toString();
        boolean fixture = isFixture(client);
        boolean recognized = fixture || recognized(client);
        String token = fixture ? fixturePath().toString() : host(client);
        MaskMode mode = client.player == null
                ? (recognized ? MaskMode.NONE : MaskMode.PASSTHROUGH)
                : RegionPolicy.select(recognized, dimension, client.player.getX(), client.player.getZ());
        if (fixture && client.player != null && ModConfig.fixtureOverride() != null) {
            mode = ModConfig.fixtureOverride();
            if (mode == MaskMode.FIXTURE_CUSTOM) RegionPolicy.setFixtureCustomRect(ModConfig.fixtureCustomRect());
        }
        return new Resolution(token, dimension, mode, fixture);
    }

    /**
     * Whether the world is masked and gets world effects: a Wynncraft server, the fixture, or a replay.
     * Other servers and ordinary singleplayer are left alone.
     */
    public static boolean recognized(MinecraftClient client) {
        if (client.getCurrentServerEntry() != null) return RegionPolicy.isWynncraftHost(host(client));
        return isReplay(client) || isFixture(client);
    }

    /**
     * Whether a ReplayMod or Flashback replay is playing. Which server it was recorded on is not asked: anyone
     * watching a replay with WynnVista installed is taken to be watching Wynncraft.
     */
    public static boolean isReplay(MinecraftClient client) {
        // ReplayMod plays from a file, with neither a server entry nor an integrated server.
        if (!client.isIntegratedServerRunning()) {
            return client.getCurrentServerEntry() == null && FabricLoader.getInstance().isModLoaded("replaymod");
        }
        // Flashback plays from an integrated server of its own.
        return client.getServer() != null && FLASHBACK_REPLAY_SERVER.equals(client.getServer().getClass().getName());
    }

    /** The address of the server connected to, which names the world; empty in singleplayer and in replays. */
    public static String host(MinecraftClient client) {
        return client.getCurrentServerEntry() == null ? "" : client.getCurrentServerEntry().address;
    }

    public static boolean isFixture(MinecraftClient client) {
        if (!ModConfig.fixtureEnabled() || !client.isIntegratedServerRunning()
                || client.getServer() == null || fixturePath() == null) return false;
        try {
            Path current = client.getServer().getSavePath(WorldSavePath.ROOT).toRealPath();
            return current.equals(fixturePath().toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    public static Path fixturePath() {
        String raw = ModConfig.fixtureSavePath();
        if (raw == null || raw.isBlank()) return null;
        try {
            return Path.of(raw).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
