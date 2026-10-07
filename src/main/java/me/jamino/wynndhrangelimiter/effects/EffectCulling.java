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

    /**
     * Projects a camera-relative box. Returns null when the box is entirely outside the view, the whole view
     * when it reaches behind the camera (the camera is in or beside it), and otherwise its screen rectangle.
     * The far plane is ignored: effects are ray-marched and are not limited by it.
     */
    public static ScreenRect project(Matrix4fc viewProjection, float minX, float minY, float minZ,
                                     float maxX, float maxY, float maxZ) {
        Vector4f clip = new Vector4f();
        boolean allLeft = true, allRight = true, allBelow = true, allAbove = true, allBehind = true;
        boolean anyBehind = false;
        float x0 = 1, y0 = 1, x1 = -1, y1 = -1;
        for (int corner = 0; corner < 8; corner++) {
            clip.set((corner & 1) == 0 ? minX : maxX, (corner & 2) == 0 ? minY : maxY,
                    (corner & 4) == 0 ? minZ : maxZ, 1).mul(viewProjection);
            allLeft &= clip.x < -clip.w;
            allRight &= clip.x > clip.w;
            allBelow &= clip.y < -clip.w;
            allAbove &= clip.y > clip.w;
            if (clip.w <= 0.05f) {
                anyBehind = true;
                continue;
            }
            allBehind = false;
            x0 = Math.min(x0, clip.x / clip.w);
            x1 = Math.max(x1, clip.x / clip.w);
            y0 = Math.min(y0, clip.y / clip.w);
            y1 = Math.max(y1, clip.y / clip.w);
        }
        if (allLeft || allRight || allBelow || allAbove || allBehind) return null;
        if (anyBehind) return ScreenRect.FULL;
        return new ScreenRect(unit(x0), unit(y0), unit(x1), unit(y1));
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
