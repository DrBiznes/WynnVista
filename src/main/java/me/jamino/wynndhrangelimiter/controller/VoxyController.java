package me.jamino.wynndhrangelimiter.controller;

import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VoxyController implements IRenderDistanceController {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista");

    private boolean initialized = false;
    private int cachedDistance = -1;           // What getRenderDistance() returns (to prevent spam)
    private int pendingDistance = -1;          // What we want the distance to be
    private int liveAppliedDistance = -1;      // What we've actually applied to the live system
    private Runnable initCallback;
    private int initCheckTicks = 0;
    private static final int INIT_DELAY_TICKS = 100; // Wait ~5 seconds before first check
    private static final int MAX_INIT_TICKS = 1500;  // Give up after ~75 seconds (Voxy can take 30+ sec)

    @Override
    public void initialize(Runnable callback) {
        this.initCallback = callback;
        // Don't mark as initialized yet - we'll do that once VoxyRenderSystem is available
        LOGGER.info("Voxy controller waiting for VoxyRenderSystem to become available...");
    }

    /**
     * Called every tick to check if Voxy is ready.
     * This should be called from the main mod's tick handler.
     */
    public void tick() {
        if (initialized) {
            // Already initialized, check if we have a pending distance that hasn't been applied to live system
            if (pendingDistance > 0 && pendingDistance != liveAppliedDistance) {
                if (VoxyApiWrapper.isSystemAvailable()) {
                    boolean success = VoxyApiWrapper.setRenderDistance(pendingDistance);
                    if (success) {
                        liveAppliedDistance = pendingDistance;
                        cachedDistance = pendingDistance;
                        LOGGER.info("Applied pending Voxy render distance to live system: {}", pendingDistance);
                    }
                }
            }
            return;
        }

        initCheckTicks++;

        // Wait before first check to let Voxy fully initialize
        if (initCheckTicks < INIT_DELAY_TICKS) {
            return;
        }

        // Check every 20 ticks (~1 second) after initial delay
        if ((initCheckTicks - INIT_DELAY_TICKS) % 20 != 0) {
            return;
        }

        if (initCheckTicks > MAX_INIT_TICKS) {
            LOGGER.warn("Voxy system not available after {} ticks, initializing anyway with config-only mode", initCheckTicks);
            completeInitialization(false);
            return;
        }

        // Check if system is available
        if (VoxyApiWrapper.isSystemAvailable()) {
            LOGGER.info("VoxyRenderSystem became available after {} ticks", initCheckTicks);
            completeInitialization(true);
        }
    }

    private void completeInitialization(boolean systemAvailable) {
        initialized = true;
        if (systemAvailable) {
            LOGGER.info("Voxy controller fully initialized with live system access");
        } else {
            LOGGER.info("Voxy controller initialized in config-only mode (changes require restart)");
        }
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
        return "Voxy";
    }

    @Override
    public int getRenderDistance() {
        if (cachedDistance > 0) {
            return cachedDistance;
        }
        return VoxyApiWrapper.getRenderDistance();
    }

    @Override
    public void setRenderDistance(int distance) {
        pendingDistance = distance;
        // Update cache for getRenderDistance() to prevent message spam
        cachedDistance = distance;

        // Always update the config
        VoxyApiWrapper.setConfigValue(distance);

        // Try to apply to live system if available
        if (VoxyApiWrapper.isSystemAvailable()) {
            boolean success = VoxyApiWrapper.setRenderDistance(distance);
            if (success) {
                liveAppliedDistance = distance;
                LOGGER.info("Voxy render distance set to {} (live system)", distance);
            } else {
                LOGGER.debug("Config updated to {}, but failed to apply to live system", distance);
            }
        } else {
            LOGGER.debug("Voxy config updated to {} (live system unavailable, will retry)", distance);
        }
    }

    /**
     * Inner class to isolate Voxy class loading.
     */
    private static class VoxyApiWrapper {
        private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista");

        static int getRenderDistance() {
            try {
                return me.cortex.voxy.client.config.VoxyConfig.CONFIG.sectionRenderDistance;
            } catch (Exception e) {
                LOGGER.error("Failed to get Voxy render distance", e);
                return 32; // Default fallback
            }
        }

        static void setConfigValue(int distance) {
            try {
                me.cortex.voxy.client.config.VoxyConfig.CONFIG.sectionRenderDistance = distance;
            } catch (Exception e) {
                LOGGER.error("Failed to set Voxy config value", e);
            }
        }

        static boolean isSystemAvailable() {
            try {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client == null || client.worldRenderer == null) {
                    return false;
                }

                if (client.worldRenderer instanceof me.cortex.voxy.client.core.IGetVoxyRenderSystem voxyAccessor) {
                    me.cortex.voxy.client.core.VoxyRenderSystem system = voxyAccessor.getVoxyRenderSystem();
                    return system != null;
                }
                return false;
            } catch (Exception e) {
                return false;
            }
        }

        static boolean setRenderDistance(int distance) {
            try {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client == null || client.worldRenderer == null) {
                    return false;
                }

                if (client.worldRenderer instanceof me.cortex.voxy.client.core.IGetVoxyRenderSystem voxyAccessor) {
                    me.cortex.voxy.client.core.VoxyRenderSystem system = voxyAccessor.getVoxyRenderSystem();
                    if (system != null) {
                        // Update config first
                        me.cortex.voxy.client.config.VoxyConfig.CONFIG.sectionRenderDistance = distance;
                        // Then update live system
                        system.setRenderDistance(distance);
                        return true;
                    }
                }
                return false;
            } catch (Exception e) {
                LOGGER.error("Failed to set Voxy render distance", e);
                return false;
            }
        }
    }
}
