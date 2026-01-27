package me.jamino.wynndhrangelimiter.controller;

import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VoxyController implements IRenderDistanceController {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista");

    private boolean initialized = false;
    private int cachedDistance = -1;           // What getRenderDistance() returns (in chunks, to prevent spam)
    private int pendingDistance = -1;          // What we want the distance to be (in chunks)
    private int liveAppliedDistance = -1;      // What we've actually applied to the live system (in chunks)
    private Runnable initCallback;
    private int initCheckTicks = 0;
    private static final int INIT_DELAY_TICKS = 100; // Wait ~5 seconds before first check
    private static final int MAX_INIT_TICKS = 1500;  // Give up after ~75 seconds (Voxy can take 30+ sec)

    // Voxy uses a MUCH larger scale: 1 Voxy section = 32 chunks (observed behavior)
    // This is different from what documentation suggests - empirically determined
    private static final int CHUNK_TO_VOXY_SECTION_RATIO = 32;

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
                    // Convert chunks to Voxy sections (divide by 32)
                    int voxySections = Math.max(2, Math.round(pendingDistance / (float) CHUNK_TO_VOXY_SECTION_RATIO));
                    boolean success = VoxyApiWrapper.setRenderDistance(voxySections);
                    if (success) {
                        liveAppliedDistance = pendingDistance;
                        cachedDistance = pendingDistance;
                        LOGGER.info("Applied pending Voxy render distance to live system: {} chunks → {} sections",
                                pendingDistance, voxySections);
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
            // Log Voxy's current config value for debugging
            int voxySections = VoxyApiWrapper.getRenderDistance();
            int equivalentChunks = voxySections * CHUNK_TO_VOXY_SECTION_RATIO;
            LOGGER.info("Voxy current config: {} sections (equivalent to {} chunks)",
                    voxySections, equivalentChunks);
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
        // Convert Voxy sections back to chunks (multiply by 32)
        int voxySections = VoxyApiWrapper.getRenderDistance();
        return voxySections * CHUNK_TO_VOXY_SECTION_RATIO;
    }

    @Override
    public void setRenderDistance(int distanceInChunks) {
        // Convert chunks to Voxy sections: divide by 32 and round
        // Use Math.round for proper rounding (0.375 → 0, 0.5 → 1, etc.)
        int voxySections = Math.max(1, Math.round(distanceInChunks / (float) CHUNK_TO_VOXY_SECTION_RATIO));

        // Voxy has a hard minimum of 2 sections
        voxySections = Math.max(2, voxySections);

        // Store the chunk distance for comparisons
        pendingDistance = distanceInChunks;
        cachedDistance = distanceInChunks;

        // Always update the config
        int beforeSections = VoxyApiWrapper.getRenderDistance();
        VoxyApiWrapper.setConfigValue(voxySections);
        int afterSections = VoxyApiWrapper.getRenderDistance();
        int effectiveChunks = voxySections * CHUNK_TO_VOXY_SECTION_RATIO;
        LOGGER.info("Voxy: requested {} chunks → setting {} sections (effective: {} chunks)",
                distanceInChunks, voxySections, effectiveChunks);

        // Try to apply to live system if available
        if (VoxyApiWrapper.isSystemAvailable()) {
            boolean success = VoxyApiWrapper.setRenderDistance(voxySections);
            if (success) {
                liveAppliedDistance = distanceInChunks;
                LOGGER.info("Voxy applied: {} sections (effective: {} chunks) [live system]",
                        voxySections, effectiveChunks);
            } else {
                LOGGER.debug("Config updated to {} sections, but failed to apply to live system", voxySections);
            }
        } else {
            LOGGER.debug("Voxy config updated to {} sections (live system unavailable, will retry)", voxySections);
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
