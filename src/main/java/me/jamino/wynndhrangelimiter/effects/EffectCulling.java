package me.jamino.wynndhrangelimiter.effects;

import org.joml.Matrix4fc;
import org.joml.Vector4f;

/** Pure view-frustum culling and level-of-detail rules for world effects. */
public final class EffectCulling {
    /** Part of the view an effect can touch, in 0..1 with the origin at the bottom left. */
    public record ScreenRect(float minX, float minY, float maxX, float maxY) {
        public static final ScreenRect FULL = new ScreenRect(0, 0, 1, 1);
    }

    private EffectCulling() {}

    /** Clip-space w nearer than which a point counts as behind the camera. */
    private static final float NEAR = 0.05f;

    /**
     * Projects a camera-relative box. Returns null when the box is entirely outside the view, and otherwise
     * the screen rectangle of the part of it in front of the camera: the whole view when the camera is inside
     * it, less when it only reaches behind the camera (a layer below the camera ends at the horizon).
     * The far plane is ignored: effects are ray-marched and are not limited by it.
     */
    public static ScreenRect project(Matrix4fc viewProjection, float minX, float minY, float minZ,
                                     float maxX, float maxY, float maxZ) {
        Vector4f[] corners = new Vector4f[8];
        for (int corner = 0; corner < 8; corner++) {
            corners[corner] = new Vector4f((corner & 1) == 0 ? minX : maxX, (corner & 2) == 0 ? minY : maxY,
                    (corner & 4) == 0 ? minZ : maxZ, 1).mul(viewProjection);
        }
        // The part in front of the camera is bounded by the corners there and by where the edges that cross
        // the camera's plane meet it.
        float[] bounds = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int corner = 0; corner < 8; corner++) {
            Vector4f from = corners[corner];
            if (from.w > NEAR) include(bounds, from.x, from.y, from.w);
            for (int axis = 1; axis < 8; axis <<= 1) {
                if ((corner & axis) != 0) continue;
                Vector4f to = corners[corner | axis];
                if (from.w > NEAR == to.w > NEAR) continue;
                float t = (NEAR - from.w) / (to.w - from.w);
                include(bounds, from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t, NEAR);
            }
        }
        // Nothing in front of the camera, or all of that off one side of the view.
        if (bounds[0] > 1 || bounds[1] > 1 || bounds[2] < -1 || bounds[3] < -1) return null;
        ScreenRect rect = new ScreenRect(unit(bounds[0]), unit(bounds[1]), unit(bounds[2]), unit(bounds[3]));
        return rect.equals(ScreenRect.FULL) ? ScreenRect.FULL : rect;
    }

    private static void include(float[] bounds, float x, float y, float w) {
        bounds[0] = Math.min(bounds[0], x / w);
        bounds[1] = Math.min(bounds[1], y / w);
        bounds[2] = Math.max(bounds[2], x / w);
        bounds[3] = Math.max(bounds[3], y / w);
    }

    /** Fewer samples when the effect covers few pixels: each sample then already spans less than a pixel. */
    public static int steps(int configured, float coveredPixels) {
        return Math.max(Math.min(configured, 20), Math.min(configured, Math.round(coveredPixels / 3)));
    }

    /**
     * Noise octaves worth sampling at a distance. The finest of four octaves has 5-block detail; once a pixel
     * covers more than that the octave only adds cost and shimmer.
     *
     * @param projectionScaleY the projection matrix's m11, i.e. {@code 1 / tan(fov / 2)}
     */
    public static int octaves(double distance, float projectionScaleY, int viewHeight) {
        double blocksPerPixel = distance * 2 / (projectionScaleY * viewHeight);
        return blocksPerPixel > 8 ? 2 : blocksPerPixel > 3 ? 3 : 4;
    }

    private static float unit(float ndc) {
        return Math.max(0, Math.min(1, ndc * 0.5f + 0.5f));
    }
}
