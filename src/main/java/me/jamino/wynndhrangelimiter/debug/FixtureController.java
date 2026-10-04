package me.jamino.wynndhrangelimiter.debug;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiWorldLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import me.jamino.wynndhrangelimiter.ModConfig;
import me.jamino.wynndhrangelimiter.visibility.WorldContextResolver;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

public final class FixtureController {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista");
    private static volatile Path startingFixtureRoot;

    private FixtureController() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            Path fixture = WorldContextResolver.fixturePath();
            if (!ModConfig.fixtureEnabled() || fixture == null) return;
            try {
                Path actual = server.getSavePath(WorldSavePath.ROOT).toRealPath();
                if (actual.equals(fixture.toRealPath())) {
                    startingFixtureRoot = actual;
                    LOGGER.info("Designated fixture server starting: {}", actual);
                }
            } catch (IOException e) {
                LOGGER.error("Could not identify starting fixture server", e);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            startingFixtureRoot = null;
            FixtureSelection.clear();
        });
        DhApiEventRegister.on(DhApiWorldLoadEvent.class, new DhApiWorldLoadEvent() {
            @Override
            public void onWorldLoad(DhApiEventParam<EventParam> event) {
                freezeIfDesignatedWorld();
            }
        });
        LOGGER.info("Registered DH fixture world-load handler");
    }

    private static void freezeIfDesignatedWorld() {
        if (!ModConfig.fixtureEnabled()) return;
        Path save = WorldContextResolver.fixturePath();
        if (save == null) return;
        IDhApiWorldProxy proxy = DhApi.Delayed.worldProxy;
        if (proxy == null || !proxy.worldLoaded()) {
            LOGGER.warn("DH world-load event fired before world proxy was ready");
            return;
        }
        try {
            Path fixtureRoot = save.toRealPath();
            if (fixtureRoot.equals(FixtureSelection.selectedRoot())
                    || fixtureRoot.equals(startingFixtureRoot)) {
                proxy.setReadOnly(true);
                LOGGER.info("DH fixture frozen read-only at world load: {}", fixtureRoot);
                return;
            }
            Path expected = fixtureRoot.resolve("data").toRealPath();
            for (IDhApiLevelWrapper level : proxy.getAllLoadedLevelWrappers()) {
                Path actual = level.getDhSaveFolder().toPath().toRealPath();
                if (!expected.equals(actual)) continue;
                proxy.setReadOnly(true);
                LOGGER.info("DH fixture frozen read-only: dimension={}, level={}, folder={}",
                        level.getDimensionName(), level.getDhIdentifier(), actual);
                return;
            }
            LOGGER.warn("DH world-load event did not include designated fixture folder {}", expected);
        } catch (IOException | IllegalStateException e) {
            LOGGER.error("Could not enable DH fixture read-only mode", e);
        }
    }
}
