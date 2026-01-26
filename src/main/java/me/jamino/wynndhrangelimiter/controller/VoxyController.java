package me.jamino.wynndhrangelimiter.controller;

import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VoxyController implements IRenderDistanceController {
    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista");
    private boolean initialized = false;
    private int cachedDistance = -1;
    private boolean hasLoggedDebugInfo = false;

    @Override
    public void initialize(Runnable callback) {
        // Voxy doesn't have an initialization event like DH
        // We consider it initialized immediately and rely on null checks when accessing
        initialized = true;
        LOGGER.info("Voxy controller initialized");
        callback.run();
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
        // Return cached value if we've set one, to prevent spam when system isn't available
        if (cachedDistance > 0) {
            return cachedDistance;
        }
        return VoxyApiWrapper.getRenderDistance();
    }

    @Override
    public void setRenderDistance(int distance) {
        if (distance != cachedDistance) {
            cachedDistance = distance;
            boolean success = VoxyApiWrapper.setRenderDistance(distance, !hasLoggedDebugInfo);
            hasLoggedDebugInfo = true;
            if (success) {
                LOGGER.info("Voxy render distance set to {}", distance);
            } else {
                LOGGER.warn("Failed to set Voxy render distance to {} - check debug logs above", distance);
            }
        }
    }

    /**
     * Inner class to isolate Voxy class loading.
     * This class is only loaded when its methods are called,
     * preventing ClassNotFoundException when Voxy isn't installed.
     */
    private static class VoxyApiWrapper {
        private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista");

        static int getRenderDistance() {
            return me.cortex.voxy.client.config.VoxyConfig.CONFIG.sectionRenderDistance;
        }

        static boolean setRenderDistance(int distance, boolean logDebug) {
            if (logDebug) {
                LOGGER.info("=== Voxy Debug Info ===");
                LOGGER.info("Attempting to set render distance to: {}", distance);
                LOGGER.info("Current VoxyConfig.CONFIG.sectionRenderDistance: {}",
                    me.cortex.voxy.client.config.VoxyConfig.CONFIG.sectionRenderDistance);
            }

            // Always update the config value
            me.cortex.voxy.client.config.VoxyConfig.CONFIG.sectionRenderDistance = distance;
            if (logDebug) {
                LOGGER.info("Updated VoxyConfig.CONFIG.sectionRenderDistance to: {}", distance);
            }

            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                if (logDebug) LOGGER.warn("MinecraftClient is null");
                return false;
            }

            if (client.worldRenderer == null) {
                if (logDebug) LOGGER.warn("worldRenderer is null");
                return false;
            }

            if (logDebug) {
                LOGGER.info("worldRenderer class: {}", client.worldRenderer.getClass().getName());
                LOGGER.info("worldRenderer interfaces: ");
                for (Class<?> iface : client.worldRenderer.getClass().getInterfaces()) {
                    LOGGER.info("  - {}", iface.getName());
                }
                LOGGER.info("worldRenderer superclass: {}", client.worldRenderer.getClass().getSuperclass().getName());

                // Check all interfaces including from superclasses
                LOGGER.info("All implemented interfaces (including inherited):");
                Class<?> currentClass = client.worldRenderer.getClass();
                while (currentClass != null) {
                    for (Class<?> iface : currentClass.getInterfaces()) {
                        LOGGER.info("  - {} (from {})", iface.getName(), currentClass.getSimpleName());
                    }
                    currentClass = currentClass.getSuperclass();
                }
            }

            // Check if it implements IGetVoxyRenderSystem
            boolean isVoxyRenderer = client.worldRenderer instanceof me.cortex.voxy.client.core.IGetVoxyRenderSystem;
            if (logDebug) {
                LOGGER.info("worldRenderer instanceof IGetVoxyRenderSystem: {}", isVoxyRenderer);
            }

            if (isVoxyRenderer) {
                me.cortex.voxy.client.core.IGetVoxyRenderSystem voxyAccessor =
                    (me.cortex.voxy.client.core.IGetVoxyRenderSystem) client.worldRenderer;
                me.cortex.voxy.client.core.VoxyRenderSystem system = voxyAccessor.getVoxyRenderSystem();

                if (logDebug) {
                    LOGGER.info("VoxyRenderSystem is null: {}", (system == null));
                }

                if (system != null) {
                    if (logDebug) {
                        LOGGER.info("VoxyRenderSystem class: {}", system.getClass().getName());
                    }
                    system.setRenderDistance(distance);
                    if (logDebug) {
                        LOGGER.info("Successfully called system.setRenderDistance({})", distance);
                        LOGGER.info("=== End Voxy Debug Info ===");
                    }
                    return true;
                } else {
                    if (logDebug) LOGGER.warn("VoxyRenderSystem is null - Voxy may be disabled in settings");
                }
            } else {
                if (logDebug) {
                    LOGGER.warn("worldRenderer does not implement IGetVoxyRenderSystem");
                    LOGGER.warn("This may indicate a Voxy API version mismatch");

                    // Try to find if there's a similar interface with a different package
                    try {
                        Class<?> voxyInterface = Class.forName("me.cortex.voxy.client.core.IGetVoxyRenderSystem");
                        LOGGER.info("IGetVoxyRenderSystem class loaded successfully: {}", voxyInterface.getName());
                    } catch (ClassNotFoundException e) {
                        LOGGER.error("IGetVoxyRenderSystem class not found! Voxy API may have changed.", e);
                    }
                }
            }

            if (logDebug) {
                LOGGER.info("=== End Voxy Debug Info ===");
            }
            return false;
        }
    }
}
