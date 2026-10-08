package me.jamino.wynndhrangelimiter.effects;

import java.util.List;

/**
 * A {@link FogModel} sampled into a small table over distance along the ground and world height, which the
 * effect shaders read as a texture. Every model's maths therefore stays in Java, and the shaders need no
 * per-pack code.
 */
public final class FogTable {
    public static final int WIDTH = 96;
    public static final int HEIGHT = 32;
    private static final double ALONG_STEP = 64;
    private static final double MARGIN = 16;

    /** The part of the world the table covers: ground distances 0..maxAlong and world heights minY..maxY. */
    public record Range(float maxAlong, float minY, float maxY) {}

    private FogTable() {}

    /**
     * A range holding every box, seen from a camera at the given place. The far end is rounded up, so
     * walking does not change it every frame.
     */
    public static Range range(double cameraX, double cameraZ, List<WorldEffect.Bounds> boxes) {
        double along = 0;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (WorldEffect.Bounds box : boxes) {
            double dx = Math.max(Math.abs(box.minX() - cameraX), Math.abs(box.maxX() - cameraX));
            double dz = Math.max(Math.abs(box.minZ() - cameraZ), Math.abs(box.maxZ() - cameraZ));
            along = Math.max(along, Math.hypot(dx, dz));
            minY = Math.min(minY, box.minY());
            maxY = Math.max(maxY, box.maxY());
        }
        if (boxes.isEmpty()) return new Range((float) ALONG_STEP, 0, 1);
        return new Range((float) (Math.ceil(along / ALONG_STEP) * ALONG_STEP + ALONG_STEP),
                (float) (minY - MARGIN), (float) (maxY + MARGIN));
    }

    /**
     * Two floats per cell, row by row from the lowest height: the haze and the fade of
     * {@link FogModel#sample}, each at the centre of its cell.
     */
    public static float[] build(FogModel model, FogModel.Env env, Range range) {
        float[] table = new float[WIDTH * HEIGHT * 2];
        float[] out = new float[2];
        for (int row = 0; row < HEIGHT; row++) {
            double y = range.minY() + (row + 0.5) / HEIGHT * (range.maxY() - range.minY());
            for (int column = 0; column < WIDTH; column++) {
                double along = (column + 0.5) / WIDTH * range.maxAlong();
                out[0] = 0;
                out[1] = 0;
                model.sample(env, along, y, out);
                int index = (row * WIDTH + column) * 2;
                table[index] = unit(out[0]);
                table[index + 1] = unit(out[1]);
            }
        }
        return table;
    }

    private static float unit(float value) {
        return Float.isNaN(value) ? 0 : Math.max(0, Math.min(1, value));
    }
}
