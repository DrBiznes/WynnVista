package me.jamino.wynndhrangelimiter;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Mod Menu's entrypoint: offers the config screen from the mod list. Mod Menu is optional, and this is the only
 * class that uses it; Fabric Loader loads it only when Mod Menu asks for the entrypoint. The screen needs Cloth
 * Config, which is optional too: without it Mod Menu shows no config button and the config file is edited by hand.
 */
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        if (!FabricLoader.getInstance().isModLoaded("cloth-config")) return parent -> null;
        return ConfigScreen::create;
    }
}
