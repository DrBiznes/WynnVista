package me.jamino.wynndhrangelimiter.effects;

import me.jamino.wynndhrangelimiter.visibility.MaskMode;
import me.jamino.wynndhrangelimiter.visibility.RegionPolicy;
import me.jamino.wynndhrangelimiter.visibility.VisibilitySnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoidDepthsTest {
    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void everyStyleHasASurroundingShaderBuiltOnTheSkyIslandsOne() {
        for (SkyIslandsVoid.Style style : SkyIslandsVoid.Style.values()) {
            String surround = EffectProgram.source(style.surroundShader());
            assertTrue(surround.startsWith("#version 330 core"), style.name());
            assertEquals(1, surround.split("#version", -1).length - 1, style.name() + ": one #version line");
            assertTrue(surround.indexOf("#define SURROUND") < surround.indexOf("uniform vec3 uLevels;"), style.name());
            assertFalse(surround.contains("#include"), style.name());
            // The whole Sky Islands shader is in it, with the dome over it.
            String own = EffectProgram.source(style.shader());
            assertTrue(surround.contains(own.substring(own.indexOf("\n") + 1)), style.name());
            assertTrue(surround.contains("vec4 dome(vec3 dir)"), style.name());
            assertTrue(surround.contains("vec3 nebulaLight(vec2 at, vec2 run, float seen, out float nebula) {"), style.name());
            assertTrue(surround.contains("fragColor = surround(fragColor, dir, fragDistance);"), style.name());
        }
    }

    @Test
    void skyIslandsShadersLeaveTheSurroundOut() {
        for (SkyIslandsVoid.Style style : SkyIslandsVoid.Style.values()) {
            String own = EffectProgram.source(style.shader());
            assertFalse(own.contains("#define SURROUND"), style.name());
            // Every use of it is behind the switch.
            String unguarded = own.replaceAll("(?s)#ifdef SURROUND.*?#(else|endif)", "");
            assertFalse(unguarded.contains("= surround(") || unguarded.contains("dome(dir)"), style.name());
            assertTrue(own.contains("= surround(") && own.contains("dome(dir)"), style.name() + ": the switch is in the source");
        }
    }

    @Test
    void coversTheVoidRegionFromBelowTheCloudToAboveTheBuildLimit() {
        VoidDepths effect = new VoidDepths();
        WorldEffect.Bounds box = effect.bounds();
        assertEquals(RegionPolicy.VOID_OUTER.minX(), box.minX());
        assertEquals(RegionPolicy.VOID_OUTER.maxX(), box.maxX());
        assertEquals(RegionPolicy.VOID_OUTER.minZ(), box.minZ());
        assertEquals(RegionPolicy.VOID_OUTER.maxZ(), box.maxZ());
        assertEquals(VoidDepths.ABYSS_Y, box.minY());
        assertTrue(VoidDepths.ABYSS_Y < VoidDepths.CLOUD_Y && VoidDepths.CLOUD_Y < 100, "the cloud is under the islands");
        assertTrue(box.maxY() > 320, "the dome is inside the box from anywhere the player can be");
        assertTrue(RegionPolicy.VOID_OUTER.contains(effect.anchorX(), effect.anchorZ()));
    }

    @Test
    void shownOnlyWithTheVoidRegionsTerrain() {
        VoidDepths effect = new VoidDepths();
        for (MaskMode mode : new MaskMode[] {MaskMode.MAIN, MaskMode.LIGHT, MaskMode.VOID_OUTER}) {
            VisibilitySnapshot snapshot = new VisibilitySnapshot("play.wynncraft.com", OVERWORLD, mode,
                    RegionPolicy.mask(mode), 1);
            assertEquals(mode == MaskMode.VOID_OUTER,
                    EffectRegion.shows(snapshot, true, OVERWORLD, 14000, -4000, effect.anchorX(), effect.anchorZ()),
                    mode.name());
        }
        // And the Sky Islands void is not shown there.
        SkyIslandsVoid islands = new SkyIslandsVoid();
        VisibilitySnapshot outer = new VisibilitySnapshot("play.wynncraft.com", OVERWORLD, MaskMode.VOID_OUTER,
                RegionPolicy.mask(MaskMode.VOID_OUTER), 1);
        assertFalse(EffectRegion.shows(outer, true, OVERWORLD, 14000, -4000, islands.anchorX(), islands.anchorZ()));
    }

    @Test
    void surroundsTheCameraFromInsideTheRegionOnly() {
        VoidDepths effect = new VoidDepths();
        assertEquals(1f, VoidDepths.skirt(14000, -4000));
        assertEquals(0f, VoidDepths.skirt(VoidDepths.MIN_X - 10, -4000));
        assertEquals(0f, VoidDepths.skirt(14000, VoidDepths.SOUTH_Z));
        assertEquals(0.5f, VoidDepths.skirt(VoidDepths.MIN_X + SkyIslandsVoid.EDGE / 2, -4000), 1e-6);
        WorldEffect.Bounds view = effect.viewBounds(14000, 150, -4000);
        assertEquals(VoidDepths.MIN_X - SkyIslandsVoid.SKIRT, view.minX());
        assertEquals(VoidDepths.SOUTH_Z + SkyIslandsVoid.SKIRT, view.maxZ());
        assertEquals(VoidDepths.TOP_Y, view.maxY());
        assertEquals(effect.bounds(), effect.viewBounds(VoidDepths.MIN_X - 200, 150, -4000));
        assertTrue(effect.inRange(14000, 150, -4000));
        assertTrue(effect.inRange(VoidDepths.MAX_X + 600, 150, -4000));
        assertFalse(effect.inRange(VoidDepths.MAX_X + 700, 150, -4000));
    }

    @Test
    void blockyStyleReadsTheSameBakeAsTheSkyIslands() {
        // The bake is drawn without SURROUND, so the cloud's cover must not depend on it.
        String shape = EffectProgram.source("sky_islands_void_shape.glsl");
        String cover = shape.substring(shape.indexOf("const float COVER"), shape.indexOf('\n', shape.indexOf("const float COVER")));
        String blocky = EffectProgram.source(SkyIslandsVoid.Style.BLOCKY.surroundShader());
        assertTrue(blocky.contains("uniform sampler2D uBake;"));
        assertFalse(shape.substring(0, shape.indexOf(cover)).contains("#ifdef SURROUND"), "COVER is the same either way");
        assertFalse(new VoidDepths().reflects());
    }
}
