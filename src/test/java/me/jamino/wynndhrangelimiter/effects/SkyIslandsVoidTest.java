package me.jamino.wynndhrangelimiter.effects;

import me.jamino.wynndhrangelimiter.visibility.MaskMode;
import me.jamino.wynndhrangelimiter.visibility.RegionPolicy;
import me.jamino.wynndhrangelimiter.visibility.VisibilitySnapshot;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkyIslandsVoidTest {
    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void everyStyleHasAShaderBuiltOnTheSharedPlacement() {
        for (SkyIslandsVoid.Style style : SkyIslandsVoid.Style.values()) {
            String source = EffectProgram.source(style.shader());
            assertTrue(source.contains("float clumpCover(vec2 p)"), style.name());
            assertTrue(source.contains("vec4 hazeAlong(vec3 dir, float from, float to, vec3 pale, vec3 dusk)"), style.name());
            assertTrue(source.contains("vec3 depths(vec3 dir, float t, vec3 lit)"), style.name());
            assertFalse(source.contains("#include"), style.name());
        }
        String blocky = EffectProgram.source(SkyIslandsVoid.Style.BLOCKY.shader());
        assertTrue(blocky.contains("void cloudSea(vec3 dir, float from, float to, vec3 lit)"));
        String realistic = EffectProgram.source(SkyIslandsVoid.Style.REALISTIC.shader());
        assertTrue(realistic.contains("void clouds(vec3 dir, float from, float to, vec3 lit)"));
    }

    @Test
    void terrainMapMatchesTheBoxItIsPlacedOn() throws IOException {
        try (InputStream in = SkyIslandsVoid.class.getResourceAsStream(
                "/assets/wynnvista/textures/effects/" + SkyIslandsVoid.MAP)) {
            assertNotNull(in, "the reduced map is shipped with the mod");
            BufferedImage map = ImageIO.read(in);
            assertEquals(SkyIslandsVoid.MAP_SIZE_X, map.getWidth());
            assertEquals(SkyIslandsVoid.MAP_SIZE_Z, map.getHeight());
            // The middle of the widest gap between the islands: no block (green 255), nothing lit from below (red 0), and
            // some way from the nearest island (blue, quarter blocks).
            int voidTexel = texel(map, 1313, -4393);
            assertEquals(0, voidTexel >> 16 & 0xFF);
            assertEquals(255, voidTexel >> 8 & 0xFF);
            assertTrue((voidTexel & 0xFF) > 80);
            // The land around the area is filled to the bottom of the world and faces no void above y 1.
            int land = texel(map, 720, -5000);
            assertEquals(1, land >> 16 & 0xFF);
            assertEquals(0, land >> 8 & 0xFF);
            assertEquals(0, land & 0xFF);
        }
    }

    @Test
    void terrainMapMarksWhereWaterFallsIntoTheVoid() throws IOException {
        try (InputStream in = SkyIslandsVoid.class.getResourceAsStream(
                "/assets/wynnvista/textures/effects/" + SkyIslandsVoid.MAP)) {
            BufferedImage map = ImageIO.read(in);
            // Sky Falls: one of its columns of water, and open void far from any.
            assertEquals(255, texel(map, 1416, -4578) >>> 24);
            assertEquals(0, texel(map, 1313, -4393) >>> 24);
        }
    }

    @Test
    void airEffectIsDrawnOnlyAroundTheCameraAndItsPlacesAreOnTheMap() {
        SkyIslandsAir air = new SkyIslandsAir();
        String source = EffectProgram.source(air.shader());
        assertTrue(source.contains("const float RANGE = " + (int) SkyIslandsAir.RANGE + ".0;"));
        assertFalse(source.contains("#include"));
        assertFalse(air.halfResolution());
        assertEquals(SkyIslandsVoid.MAP, air.terrainMap());
        double[][] places = {{SkyIslandsAir.WIND_X, SkyIslandsAir.WIND_Z}, {SkyIslandsAir.STARS_X, SkyIslandsAir.STARS_Z},
                {SkyIslandsAir.SPARKLES_X, SkyIslandsAir.SPARKLES_Z}};
        for (double[] place : places) {
            float x = SkyIslandsAir.onMapX(place[0]);
            float z = SkyIslandsAir.onMapZ(place[1]);
            assertTrue(x > 0 && x < SkyIslandsVoid.MAP_SIZE_X && z > 0 && z < SkyIslandsVoid.MAP_SIZE_Z);
        }
        // From a corner of the map the effect is still within its view distance.
        assertTrue(Math.hypot(SkyIslandsVoid.MAP_SIZE_X, SkyIslandsVoid.MAP_SIZE_Z) / 2 < air.maxViewDistance());
        // It is in range from the map's corner and from just outside it or above it, and from no further.
        int east = SkyIslandsVoid.MAP_MIN_X + SkyIslandsVoid.MAP_SIZE_X;
        assertTrue(air.inRange(east, 60, SkyIslandsVoid.MAP_MIN_Z));
        assertTrue(air.inRange(east + 40, 60, -4700));
        assertFalse(air.inRange(east + 65, 60, -4700));
        assertTrue(air.inRange(1300, SkyIslandsAir.TOP_Y + 60, -4700));
        assertFalse(air.inRange(1300, SkyIslandsAir.TOP_Y + 65, -4700));
    }

    @Test
    void voidIsInRangeFromItsBoxAndTheLandBesideItOnly() {
        SkyIslandsVoid effect = new SkyIslandsVoid();
        assertTrue(effect.inRange(1300, 400, -4600), "from any height over it");
        assertTrue(effect.inRange(SkyIslandsVoid.MIN_X - 600, 150, -4600), "from the Canyon of the Lost");
        assertTrue(effect.inRange(1300, 150, SkyIslandsVoid.NORTH_Z - 600), "from Molten Heights");
        assertFalse(effect.inRange(SkyIslandsVoid.MIN_X - 650, 150, -4600));
        assertFalse(effect.inRange(SkyIslandsVoid.MIN_X - 500, 150, SkyIslandsVoid.NORTH_Z - 500), "measured to the corner");
        // The clouds show from as far as the shaders draw them.
        for (SkyIslandsVoid.Style style : SkyIslandsVoid.Style.values()) {
            assertTrue(EffectProgram.source(style.shader()).contains("const float CELLS_FAR = 520.0;"), style.name());
        }
        assertTrue(SkyIslandsVoid.REACH > 520);
    }

    private static int texel(BufferedImage map, int x, int z) {
        return map.getRGB(x - SkyIslandsVoid.MAP_MIN_X, z - SkyIslandsVoid.MAP_MIN_Z);
    }

    @Test
    void boxCoversTheVoidFromTheAbyssToTheTopOfTheHaze() {
        WorldEffect.Bounds box = new SkyIslandsVoid().bounds();
        assertEquals(SkyIslandsVoid.ABYSS_Y, box.minY(), "the abyss is the floor of the box");
        // The 16 blocks above CLOUD_Y that cloud can reach, and eight times the haze's 8 blocks over its level.
        assertTrue(box.maxY() >= SkyIslandsVoid.CLOUD_Y + 4 * 4 && box.maxY() >= SkyIslandsVoid.CLOUD_Y + 4 + 8 * 8);
        assertTrue(box.maxY() < 100, "the islands above are not part of it: looking up from them culls it");
        assertTrue(SkyIslandsVoid.ABYSS_Y < 0 && SkyIslandsVoid.CLOUD_Y > 0, "the world's lowest blocks are at y 0");
        // The corners of the Sky Islands the effect was asked for.
        assertTrue(box.minX() <= 776 && box.maxX() >= 1468 && box.minZ() <= -4928 && box.maxZ() >= -4427);
        // The void is larger than the terrain map on every side.
        assertTrue(box.minX() < SkyIslandsVoid.MAP_MIN_X && box.minZ() < SkyIslandsVoid.MAP_MIN_Z);
        assertTrue(box.maxX() > SkyIslandsVoid.MAP_MIN_X + SkyIslandsVoid.MAP_SIZE_X);
        assertTrue(box.maxZ() > SkyIslandsVoid.MAP_MIN_Z + SkyIslandsVoid.MAP_SIZE_Z);
        assertEquals(0, SkyIslandsVoid.MAP_MIN_X % 16);
        assertEquals(0, Math.floorMod(SkyIslandsVoid.MAP_MIN_Z, 16));
    }

    @Test
    void shownWithTheMainMapTerrainAroundIt() {
        SkyIslandsVoid effect = new SkyIslandsVoid();
        VisibilitySnapshot main = new VisibilitySnapshot("play.wynncraft.com", OVERWORLD, MaskMode.MAIN,
                RegionPolicy.mask(MaskMode.MAIN), 1);
        VisibilitySnapshot light = new VisibilitySnapshot("play.wynncraft.com", OVERWORLD, MaskMode.LIGHT,
                RegionPolicy.mask(MaskMode.LIGHT), 1);
        assertTrue(EffectRegion.shows(main, true, OVERWORLD, 1050, -4600, effect.anchorX(), effect.anchorZ()));
        assertFalse(EffectRegion.shows(light, true, OVERWORLD, 1050, -4600, effect.anchorX(), effect.anchorZ()));
    }

    @Test
    void patternsStayFixedToTheWorldAndRepeatWithoutAJump() {
        assertEquals(10.5f, SkyIslandsVoid.wrap(10.5), 1e-4);
        assertEquals(SkyIslandsVoid.wrap(-4690.25), SkyIslandsVoid.wrap(-4690.25 + SkyIslandsVoid.NOISE_PERIOD), 1e-3);
        assertTrue(SkyIslandsVoid.wrap(-0.5) >= 0 && SkyIslandsVoid.wrap(-0.5) < SkyIslandsVoid.NOISE_PERIOD);
        assertEquals(SkyIslandsVoid.phase(0, 0.5f), SkyIslandsVoid.phase(SkyIslandsVoid.CYCLE_TICKS, 0.5f), 1e-7);
        for (long time = -40000; time < 40000; time += 777) {
            float phase = SkyIslandsVoid.phase(time, 0);
            assertTrue(phase >= 0 && phase < 1, "time " + time);
        }
    }
}
