package me.jamino.wynndhrangelimiter.effects;

/**
 * What an effect may read about the frame being drawn.
 *
 * @param packLighting    true while a shader pack renders the world and effects are lit to match it
 * @param sunPathRotation degrees by which that pack tilts the sun's path out of the east-west plane
 */
public record EffectFrame(double cameraX, double cameraY, double cameraZ,
                          long worldTime, long timeOfDay, float tickProgress, float rain,
                          boolean packLighting, float sunPathRotation) {}
