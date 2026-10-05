package me.jamino.wynndhrangelimiter.compat.voxy;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;

public final class VoxyVersionSupport {
    public static final String VOXY_VERSION = "0.2.16-beta";
    public static final String VOXY_COMMIT = "59b62bee821518e06612e1d5c2c58c487bda761d";
    public static final String MC_VERSION = "1.21.11";

    private VoxyVersionSupport() {}

    public static boolean supported() {
        FabricLoader loader = FabricLoader.getInstance();
        boolean minecraft = loader.getModContainer("minecraft")
                .map(mod -> MC_VERSION.equals(mod.getMetadata().getVersion().getFriendlyString()))
                .orElse(false);
        return minecraft && loader.getModContainer("voxy").map(VoxyVersionSupport::matches).orElse(false);
    }

    private static boolean matches(ModContainer voxy) {
        if (!VOXY_VERSION.equals(voxy.getMetadata().getVersion().getFriendlyString())) return false;
        CustomValue commit = voxy.getMetadata().getCustomValue("commit");
        return commit == null || VOXY_COMMIT.equals(commit.getAsString());
    }
}
