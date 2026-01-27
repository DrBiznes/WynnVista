package me.jamino.wynndhrangelimiter.controller;

import net.minecraft.client.MinecraftClient;

public interface IRenderDistanceController {

    /**
     * Initialize the controller and register any necessary event listeners.
     * @param callback Called when the LOD mod has fully initialized
     */
    void initialize(Runnable callback);

    /**
     * @return true if the LOD mod has fully initialized and is ready to use
     */
    boolean isInitialized();

    /**
     * @return The name of the LOD mod this controller manages (for logging)
     */
    String getModName();

    /**
     * Get the current render distance setting.
     * @return The current render distance in chunks
     */
    int getRenderDistance();

    /**
     * Set the render distance.
     * @param distance The new render distance in chunks
     */
    void setRenderDistance(int distance);

    /**
     * Called when the player disconnects - allows controller to perform cleanup.
     * @param originalDistance The original distance to restore
     */
    default void onDisconnect(int originalDistance) {
        setRenderDistance(originalDistance);
    }

    /**
     * Called every client tick. Used for controllers that need periodic updates
     * (e.g., waiting for delayed initialization).
     */
    default void tick() {
        // Default implementation does nothing
    }
}
