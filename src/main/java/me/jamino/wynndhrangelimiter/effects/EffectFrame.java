package me.jamino.wynndhrangelimiter.effects;

/** What an effect may read about the frame being drawn. */
public record EffectFrame(double cameraX, double cameraY, double cameraZ,
                          long worldTime, long timeOfDay, float tickProgress, float rain) {}
