package me.jamino.wynndhrangelimiter.effects;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectCullingTest {
    /** A 70 degree, 16:9 camera at the origin looking down -Z, with a far plane much nearer than the boxes. */
    private static final Matrix4f CAMERA = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 384f);

    @Test
    void boxAheadGetsItsScreenRectangleEvenBeyondTheFarPlane() {
        EffectCulling.ScreenRect rect = EffectCulling.project(CAMERA, -100, -50, -2100, 100, 300, -1900);
        assertNotNull(rect, "the far plane must not cull a ray-marched effect");
        assertTrue(rect.minX() > 0.4f && rect.maxX() < 0.6f, "narrow and centred: " + rect);
        assertTrue(rect.minY() > 0.4f && rect.maxY() < 0.7f, "mostly above the horizon: " + rect);
        assertEquals(1.0f, rect.minX() + rect.maxX(), 1e-4, "symmetric box is centred");
    }

    @Test
    void boxOutsideTheViewIsCulled() {
        assertNull(EffectCulling.project(CAMERA, -100, -50, 1900, 100, 300, 2100), "behind the camera");
        assertNull(EffectCulling.project(CAMERA, 5000, -50, -1100, 5200, 300, -900), "off to the right");
        assertNull(EffectCulling.project(CAMERA, -5200, -50, -1100, -5000, 300, -900), "off to the left");
        assertNull(EffectCulling.project(CAMERA, -100, 3000, -1100, 100, 3300, -900), "far overhead");
        assertNull(EffectCulling.project(CAMERA, -100, -3300, -1100, 100, -3000, -900), "far below");
    }

    @Test
    void boxAroundOrBesideTheCameraCoversTheWholeView() {
        assertSame(EffectCulling.ScreenRect.FULL, EffectCulling.project(CAMERA, -100, -50, -100, 100, 300, 100));
        assertSame(EffectCulling.ScreenRect.FULL, EffectCulling.project(CAMERA, -20, -50, -300, 400, 300, 50),
                "crosses the camera plane but is partly ahead");
    }

    @Test
    void layerBelowTheCameraEndsAtTheHorizon() {
        // The camera stands 80 blocks over the middle of a wide, flat layer and looks level along it.
        EffectCulling.ScreenRect rect = EffectCulling.project(CAMERA, -600, -120, -400, 600, -80, 400);
        assertNotNull(rect);
        assertEquals(0f, rect.minX(), 0f);
        assertEquals(1f, rect.maxX(), 0f);
        assertEquals(0f, rect.minY(), 0f, "it passes under the camera");
        assertTrue(rect.maxY() > 0.3f && rect.maxY() < 0.5f, "its far edge is just below the horizon: " + rect);
        // Looking up, away from it, there is nothing of it in view.
        Matrix4f up = new Matrix4f(CAMERA).rotateX((float) Math.toRadians(-60));
        assertNull(EffectCulling.project(up, -600, -120, -400, 600, -80, 400));
        // Looking straight down it fills the view.
        Matrix4f down = new Matrix4f(CAMERA).rotateX((float) Math.toRadians(90));
        assertSame(EffectCulling.ScreenRect.FULL, EffectCulling.project(down, -600, -120, -400, 600, -80, 400));
    }

    @Test
    void boxCutByTheScreenEdgeIsClamped() {
        EffectCulling.ScreenRect rect = EffectCulling.project(CAMERA, 500, -50, -1100, 3000, 300, -900);
        assertNotNull(rect);
        assertEquals(1.0f, rect.maxX(), 0f);
        assertTrue(rect.minX() > 0.5f && rect.minX() < 1.0f);
    }

    @Test
    void samplesAndOctavesDropWithScreenSizeAndDistance() {
        assertEquals(64, EffectCulling.steps(64, 1080), "a large plume uses the configured quality");
        assertEquals(40, EffectCulling.steps(64, 120));
        assertEquals(20, EffectCulling.steps(64, 12), "never below the floor");
        assertEquals(16, EffectCulling.steps(16, 12), "the floor does not exceed the configured quality");

        float scale = CAMERA.m11();
        assertEquals(4, EffectCulling.octaves(690, scale, 1080), "Ragni at 1080p keeps full detail");
        assertEquals(3, EffectCulling.octaves(3000, scale, 1080));
        assertEquals(2, EffectCulling.octaves(8000, scale, 1080));
        assertTrue(EffectCulling.octaves(690, scale, 480) >= EffectCulling.octaves(690, scale, 240));
    }
}
