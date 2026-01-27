package me.jamino.wynndhrangelimiter.util;

/**
 * Global state handler for Voxy visibility.
 * This class acts as the "source of truth" for whether Voxy should render.
 * Both VoxyController and the Mixin reference this class.
 */
public class VoxyVisibilityHandler {
    /**
     * Controls whether Voxy renders LODs.
     * true = normal rendering, false = soft hide (no lag, data stays in memory)
     */
    public static volatile boolean voxyVisible = true;
}
