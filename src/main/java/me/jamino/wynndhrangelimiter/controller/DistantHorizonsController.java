package me.jamino.wynndhrangelimiter.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DistantHorizonsController implements IRenderDistanceController {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista");
    private boolean initialized = false;
    private Runnable initCallback;

    @Override
    public void initialize(Runnable callback) {
        this.initCallback = callback;
        DhApiWrapper.registerInitEvent(this::onDhInitialized);
    }

    private void onDhInitialized() {
        LOGGER.info("Distant Horizons initialized. Mod version: " + DhApiWrapper.getModVersion());
        initialized = true;
        if (initCallback != null) {
            initCallback.run();
        }
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public String getModName() {
        return "Distant Horizons";
    }

    @Override
    public int getRenderDistance() {
        return DhApiWrapper.getRenderDistance();
    }

    @Override
    public void setRenderDistance(int distance) {
        DhApiWrapper.setRenderDistance(distance);
    }

    /**
     * Inner class to isolate DH class loading.
     * This class is only loaded when its methods are called,
     * preventing ClassNotFoundException when DH isn't installed.
     */
    private static class DhApiWrapper {
        static void registerInitEvent(Runnable callback) {
            com.seibel.distanthorizons.api.methods.events.DhApiEventRegister.on(
                com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent.class,
                new com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent() {
                    @Override
                    public void afterDistantHorizonsInit(
                            com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam<Void> event) {
                        callback.run();
                    }
                }
            );
        }

        static String getModVersion() {
            return com.seibel.distanthorizons.api.DhApi.getModVersion();
        }

        static int getRenderDistance() {
            return com.seibel.distanthorizons.api.DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue();
        }

        static void setRenderDistance(int distance) {
            com.seibel.distanthorizons.api.DhApi.Delayed.configs.graphics().chunkRenderDistance().setValue(distance);
        }
    }
}
