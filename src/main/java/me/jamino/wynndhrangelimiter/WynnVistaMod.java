package me.jamino.wynndhrangelimiter;

import me.jamino.wynndhrangelimiter.debug.FixtureController;
import me.jamino.wynndhrangelimiter.debug.VoxyFixtureController;
import me.jamino.wynndhrangelimiter.compat.dh.DhVersionSupport;
import me.jamino.wynndhrangelimiter.compat.voxy.VoxyVersionSupport;
import me.jamino.wynndhrangelimiter.visibility.MaskMode;
import me.jamino.wynndhrangelimiter.visibility.VisibilityService;
import me.jamino.wynndhrangelimiter.visibility.VisibilitySnapshot;
import me.jamino.wynndhrangelimiter.visibility.WorldContextResolver;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WynnVistaMod {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista");
    private static boolean dhSupported;
    private static boolean voxySupported;

    void initialize() {
        boolean dh = FabricLoader.getInstance().isModLoaded("distanthorizons");
        boolean voxy = FabricLoader.getInstance().isModLoaded("voxy");
        dhSupported = dh && DhVersionSupport.supported();
        LOGGER.info("LOD backends detected: Distant Horizons={}, Voxy={}", dh, voxy);
        if (dhSupported) {
            LOGGER.info("DH {} / Minecraft {}: stock terrain masking enabled (exact when a verified shader path is active; otherwise conservative)",
                    DhVersionSupport.DH_VERSION, DhVersionSupport.MC_VERSION);
            FixtureController.register();
        } else if (dh) {
            LOGGER.warn("DH binary is unsupported for spatial masking; its renderer remains unchanged");
        }
        voxySupported = voxy && VoxyVersionSupport.supported();
        if (voxySupported) {
            LOGGER.info("Voxy {} / Minecraft {}: terrain masking enabled (exact for the stock pipeline and Iris shader-pack pipelines)",
                    VoxyVersionSupport.VOXY_VERSION, VoxyVersionSupport.MC_VERSION);
            VoxyFixtureController.register();
        } else if (voxy) {
            LOGGER.warn("Voxy binary is unsupported for spatial masking; its renderer remains unchanged");
        }
        if (dhSupported && voxySupported) {
            LOGGER.warn("Distant Horizons and Voxy are both present; simultaneous operation is untested");
        }
    }

    void onPlayerJoin(MinecraftClient client) {
        VisibilityService.reset();
        refresh(client);
    }

    void onPlayerDisconnect() {
        VisibilityService.reset();
    }

    void onClientTick(MinecraftClient client) {
        refresh(client);
    }

    public static VisibilitySnapshot refreshForRender(MinecraftClient client) {
        WorldContextResolver.Resolution context = WorldContextResolver.resolve(client);
        VisibilitySnapshot before = VisibilityService.current();
        VisibilitySnapshot after = VisibilityService.publish(
                context.worldToken(), context.dimension(), context.mode());
        if (before.revision() != after.revision()) {
            LOGGER.info("Visibility revision {}: {} in {} (fixture={}, token={})",
                    after.revision(), after.mode(), after.dimension(), context.fixture(), after.worldToken());
            if ((dhSupported || voxySupported) && ModConfig.shouldShowMessage() && client.player != null
                    && before.mode() != after.mode() && after.mode() != MaskMode.PASSTHROUGH) {
                client.player.sendMessage(Text.literal("WynnVista: " + after.mode()), true);
            }
        }
        return after;
    }

    private void refresh(MinecraftClient client) {
        refreshForRender(client);
    }
}
