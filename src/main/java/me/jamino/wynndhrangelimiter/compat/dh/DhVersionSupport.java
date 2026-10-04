package me.jamino.wynndhrangelimiter.compat.dh;

import net.fabricmc.loader.api.FabricLoader;

public final class DhVersionSupport {
    public static final String DH_VERSION = "3.3.3";
    public static final String MC_VERSION = "1.21.11";

    private DhVersionSupport() {}

    public static boolean supported() {
        FabricLoader loader = FabricLoader.getInstance();
        return loader.getModContainer("minecraft")
                .map(mod -> MC_VERSION.equals(mod.getMetadata().getVersion().getFriendlyString()))
                .orElse(false)
                && loader.getModContainer("distanthorizons")
                .map(mod -> DH_VERSION.equals(mod.getMetadata().getVersion().getFriendlyString()))
                .orElse(false);
    }
}
