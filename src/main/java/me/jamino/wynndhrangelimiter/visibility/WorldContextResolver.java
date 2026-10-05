package me.jamino.wynndhrangelimiter.visibility;

import me.jamino.wynndhrangelimiter.ModConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.InvalidPathException;

public final class WorldContextResolver {
    public record Resolution(String worldToken, String dimension, MaskMode mode, boolean fixture) {}

    private WorldContextResolver() {}

    public static Resolution resolve(MinecraftClient client) {
        if (client.world == null || !ModConfig.maskingEnabled()) {
            return new Resolution("", "", MaskMode.PASSTHROUGH, false);
        }
        String dimension = client.world.getRegistryKey().getValue().toString();
        boolean fixture = isFixture(client);
        String host = client.getCurrentServerEntry() == null ? "" : client.getCurrentServerEntry().address;
        boolean recognized = fixture || RegionPolicy.isWynncraftHost(host);
        String token = fixture ? fixturePath().toString() : host;
        MaskMode mode = client.player == null
                ? (recognized ? MaskMode.NONE : MaskMode.PASSTHROUGH)
                : RegionPolicy.select(recognized, dimension, client.player.getX(), client.player.getZ());
        if (fixture && client.player != null && ModConfig.fixtureOverride() != null) {
            mode = ModConfig.fixtureOverride();
            if (mode == MaskMode.FIXTURE_CUSTOM) RegionPolicy.setFixtureCustomRect(ModConfig.fixtureCustomRect());
        }
        return new Resolution(token, dimension, mode, fixture);
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
