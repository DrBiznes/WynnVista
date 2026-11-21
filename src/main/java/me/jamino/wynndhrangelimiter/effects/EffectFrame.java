package me.jamino.wynndhrangelimiter.effects;

/**
 * What an effect may read about the frame being drawn.
 *
 * @param packLighting    true while a shader pack renders the world and effects are lit to match it
 * @param sunPathRotation degrees by which that pack tilts the sun's path out of the east-west plane
 * @param fogStart        blocks from the camera at which the fog of the place it is in begins: the biome's, or
 *                        the game's default of 0
 * @param fogEnd          blocks at which that fog hides everything: the biome's, or the game's default of 1024
 */
public record EffectFrame(double cameraX, double cameraY, double cameraZ,
                          long worldTime, long timeOfDay, float tickProgress, float rain,
                          boolean packLighting, float sunPathRotation, float fogStart, float fogEnd) {}
