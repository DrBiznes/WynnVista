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

class MistwoodsMistTest {
    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void shaderIsBuiltOnTheLevelsThePlacementNames() {
        MistwoodsMist mist = new MistwoodsMist();
        String source = EffectProgram.source(mist.shader());
        assertFalse(source.contains("#include"));
        assertTrue(source.contains("const float DECK = " + (int) MistwoodsMist.DECK_Y + ".0;"));
        assertTrue(source.contains("const float FLOOR = " + (int) MistwoodsMist.FLOOR_Y + ".0;"));
        assertTrue(source.contains("const float RISE_MAX = " + (int) MistwoodsMist.RISE + ".0;"));
        assertTrue(source.contains("const float POUR_TOP = " + (int) MistwoodsMist.POUR_TOP_Y + ".0;"));
        assertTrue(source.contains("const float SKIRT = " + (int) MistwoodsMist.SKIRT + ".0;"));
        assertTrue(source.contains("const float PERIOD = " + (int) MistwoodsMist.NOISE_PERIOD + ".0;"));
        // The falls are a part of their own and the shader leaves them out without leaving out the deck.
        assertTrue(source.contains("uniform float uPours;"));
        assertTrue(source.contains("if (uPours > 0.5)"));
        assertEquals(java.util.List.of(MistwoodsMist.POURS), mist.options());
        assertEquals(MistwoodsMist.MAP, mist.terrainMap());
    }

    @Test
    void movingPatternsWrapWithoutAJump() {
        String source = EffectProgram.source(new MistwoodsMist().shader());
        // Everything the shader multiplies uPhase by is a whole number of noise repeats.
        for (String whole : new String[] {"const vec2 WIND = vec2(-3.0, 1.0);", "const float FLOW_TURNS = 200.0;",
                "const float SURGE = 20.0;", "const float CHURN = 12.0;", "const float POUR_PACE = 5.0;",
                "const float POUR_SPEED = 150.0;", "const float POUR_GROUP = 2.0;", "const float POUR_STREAK = 16.0;"}) {
            assertTrue(source.contains(whole), whole);
        }
        assertEquals(MistwoodsMist.phase(0, 0.5f), MistwoodsMist.phase(MistwoodsMist.CYCLE_TICKS, 0.5f), 1e-7);
        for (long time = -300000; time < 300000; time += 7777) {
            float phase = MistwoodsMist.phase(time, 0);
            assertTrue(phase >= 0 && phase < 1, "time " + time);
        }
        assertEquals(10.5f, MistwoodsMist.wrap(10.5), 1e-4);
        assertEquals(MistwoodsMist.wrap(-1305.25), MistwoodsMist.wrap(-1305.25 + MistwoodsMist.NOISE_PERIOD), 1e-3);
        // No repeat of the noise ends inside the woods, where a pattern that is not a whole number of repeats
        // would jump: they lie between x -2048 and 0 and between z -2048 and 0.
        WorldEffect.Bounds box = new MistwoodsMist().bounds();
        assertTrue(box.minX() > -MistwoodsMist.NOISE_PERIOD && box.maxX() < 0);
        assertTrue(box.minZ() > -MistwoodsMist.NOISE_PERIOD && box.maxZ() < 0);
        // The wind drifts the mist a block a second.
        double blocks = Math.hypot(3, 1) * MistwoodsMist.NOISE_PERIOD;
        assertEquals(1.0, blocks / (MistwoodsMist.CYCLE_TICKS / 20.0), 0.02);
    }

    @Test
    void mapMatchesTheBoxItIsPlacedOn() throws IOException {
        BufferedImage map = map();
        assertEquals(MistwoodsMist.MAP_SIZE_X, map.getWidth());
        assertEquals(2 * MistwoodsMist.MAP_SIZE_Z, map.getHeight(), "two tiles, one under the other");
        assertEquals(0, Math.floorMod(MistwoodsMist.MAP_MIN_X, 16));
        assertEquals(0, Math.floorMod(MistwoodsMist.MAP_MIN_Z, 16));
        // Aldwell is in the woods; Fort Tericen, on Hyloch's side, is not.
        assertEquals(255, flow(map, -1305, -686) >>> 24);
        assertEquals(0, flow(map, -1231, -1033) >>> 24);
        // The wall and castles towards Hyloch end the mist: none in the castle north of Aldwell or on the
        // low ground behind the wall, all of it on the woods' side of both.
        assertEquals(0, flow(map, -1300, -890) >>> 24);
        assertEquals(0, flow(map, -1150, -980) >>> 24);
        assertEquals(255, flow(map, -1300, -850) >>> 24);
        assertEquals(255, flow(map, -1100, -915) >>> 24);
        // East of the last castle the woods run on behind the wall, and so does the mist.
        assertEquals(255, flow(map, -1085, -960) >>> 24);
        // The industrial wall ends it towards Timasca.
        assertEquals(0, flow(map, -1680, -550) >>> 24);
        assertEquals(255, flow(map, -1605, -560) >>> 24);
    }

    @Test
    void wallsLowerThanTheDeckHoldItDown() throws IOException {
        BufferedImage map = map();
        // Right behind the industrial wall the deck's top is held at about the wall's height, well under the
        // deck's own, and it is free again some 60 blocks into the woods.
        int atWall = falls(map, -1622, -560) >>> 24;
        assertTrue(atWall > 70 && atWall < MistwoodsMist.DECK_Y - 10, "held at " + atWall);
        int inland = falls(map, -1575, -560) >>> 24;
        assertTrue(inland > MistwoodsMist.DECK_Y + MistwoodsMist.RISE - 8, "held at " + inland);
        // Nothing holds it at Aldwell, and a castle as tall as the deck holds it at about the deck's height.
        assertEquals(255, falls(map, -1305, -686) >>> 24);
        assertTrue((falls(map, -1300, -860) >>> 24) > MistwoodsMist.DECK_Y - 5);
        String source = EffectProgram.source(new MistwoodsMist().shader());
        assertTrue(source.contains("top = min(top, held - HELD_SWELL * (2.0 - 2.0 * swell));"));
        // Inside a giant trunk nothing flows and nothing is nearer.
        for (int[] trunk : new int[][] {{-1389, -563}, {-1170, -725}}) {
            int texel = flow(map, trunk[0], trunk[1]);
            assertEquals(128, texel >> 16 & 0xFF);
            assertEquals(128, texel >> 8 & 0xFF);
            assertEquals(0, texel & 0xFF);
        }
        // In the open woods the mist drifts west, down the valley, far from anything in its way.
        int open = flow(map, -1300, -600);
        assertTrue((open >> 16 & 0xFF) < 100, "the flow's x is below 128: towards the west");
        assertEquals(255, open & 0xFF);
    }

    @Test
    void mapHoldsTheLedgesMistPoursFrom() throws IOException {
        BufferedImage map = map();
        // The open column beside a ledge of the giant tree north-west of Aldwell: the lip's height, no distance
        // to it, and a foot below it and above the deck.
        int lip = falls(map, -1381, -561);
        assertEquals(171, lip >> 16 & 0xFF);
        assertEquals(0, lip >> 8 & 0xFF);
        assertEquals(155, lip & 0xFF);
        // Far from any ledge there is no lip, and the nearest is more than 127 blocks away.
        int none = falls(map, -1500, -900);
        assertEquals(0, none >> 16 & 0xFF);
        assertEquals(255, none >> 8 & 0xFF);
        // Every lip stands clear of the deck and inside the box.
        for (int z = 0; z < MistwoodsMist.MAP_SIZE_Z; z++) {
            for (int x = 0; x < MistwoodsMist.MAP_SIZE_X; x++) {
                int texel = map.getRGB(x, z + MistwoodsMist.MAP_SIZE_Z);
                if ((texel >> 8 & 0xFF) != 0) continue;
                int height = texel >> 16 & 0xFF;
                assertTrue(height > MistwoodsMist.DECK_Y + 8 && height < MistwoodsMist.POUR_TOP_Y, x + " " + z);
            }
        }
    }

    @Test
    void boxHoldsTheDeckAndTheFalls() {
        WorldEffect.Bounds box = new MistwoodsMist().bounds();
        assertEquals(MistwoodsMist.FLOOR_Y, box.minY());
        assertEquals(MistwoodsMist.POUR_TOP_Y, box.maxY());
        assertTrue(box.maxY() > MistwoodsMist.DECK_Y + MistwoodsMist.RISE);
        // The mist runs on past the map, as far south as the wall to the wastelands (about z -425).
        assertEquals(MistwoodsMist.MAP_MIN_Z + MistwoodsMist.MAP_SIZE_Z + MistwoodsMist.SKIRT, box.maxZ());
        assertTrue(box.maxZ() > -425);
        assertEquals(MistwoodsMist.MAP_MIN_X - MistwoodsMist.SKIRT, box.minX());
    }

    @Test
    void inRangeFromTheWoodsAndTheLandAroundThem() {
        MistwoodsMist mist = new MistwoodsMist();
        assertTrue(mist.inRange(-1305, 60, -686), "Aldwell");
        assertTrue(mist.inRange(-1231, 150, -1033), "Fort Tericen");
        assertTrue(mist.inRange(-1620, 250, -830), "the citadel");
        assertTrue(mist.inRange(MistwoodsMist.MAP_MIN_X - 1300, 150, -700));
        assertFalse(mist.inRange(MistwoodsMist.MAP_MIN_X - MistwoodsMist.SKIRT - MistwoodsMist.REACH - 5, 150, -700));
        assertTrue(mist.maxViewDistance() >= MistwoodsMist.REACH);
    }

    @Test
    void shownWithTheMainMapTerrainAroundIt() {
        MistwoodsMist mist = new MistwoodsMist();
        VisibilitySnapshot main = new VisibilitySnapshot("play.wynncraft.com", OVERWORLD, MaskMode.MAIN,
                RegionPolicy.mask(MaskMode.MAIN), 1);
        VisibilitySnapshot light = new VisibilitySnapshot("play.wynncraft.com", OVERWORLD, MaskMode.LIGHT,
                RegionPolicy.mask(MaskMode.LIGHT), 1);
        assertTrue(EffectRegion.shows(main, true, OVERWORLD, -1231, -1033, mist.anchorX(), mist.anchorZ()));
        assertFalse(EffectRegion.shows(light, true, OVERWORLD, -1231, -1033, mist.anchorX(), mist.anchorZ()));
        // The anchor is on the map.
        assertTrue(mist.anchorX() > MistwoodsMist.MAP_MIN_X
                && mist.anchorX() < MistwoodsMist.MAP_MIN_X + MistwoodsMist.MAP_SIZE_X);
        assertTrue(mist.anchorZ() > MistwoodsMist.MAP_MIN_Z
                && mist.anchorZ() < MistwoodsMist.MAP_MIN_Z + MistwoodsMist.MAP_SIZE_Z);
    }

    @Test
    void takesOnABiomesFogAndLetsGoOfIt() {
        // The game's default fog ends at 1024 blocks; a biome with a fog of its own ends it far nearer.
        assertEquals(0f, MistwoodsMist.fogTarget(1024));
        assertEquals(1f, MistwoodsMist.fogTarget(96));
        assertEquals(1f, MistwoodsMist.fogTarget(MistwoodsMist.FOG_NEAR - 1));
        assertEquals(0f, MistwoodsMist.fogTarget(MistwoodsMist.FOG_NEAR));
        // It comes in evenly, not in one frame, and gets there.
        assertEquals(0.3f, MistwoodsMist.ease(0.3f, 1, 0));
        assertEquals(1 - Math.exp(-1), MistwoodsMist.ease(0, 1, MistwoodsMist.FOG_EASE), 1e-6);
        assertEquals(1f, MistwoodsMist.ease(0, 1, 60), 1e-6);
        assertEquals(1f, MistwoodsMist.ease(0, 1, Double.MAX_VALUE));
        assertEquals(0f, MistwoodsMist.ease(1, 0, Double.MAX_VALUE));
        String source = EffectProgram.source(new MistwoodsMist().shader());
        assertTrue(source.contains("uniform vec3 uBiomeFog;"));
        assertTrue(source.contains("return 1.0 - uBiomeFog.x * hidden;"));
    }

    private static BufferedImage map() throws IOException {
        try (InputStream in = MistwoodsMist.class.getResourceAsStream(
                "/assets/wynnvista/textures/effects/" + MistwoodsMist.MAP)) {
            assertNotNull(in, "the map is shipped with the mod");
            return ImageIO.read(in);
        }
    }

    /** The first tile's texel of a block column: red and green the flow, blue the way to an obstacle, alpha the woods. */
    private static int flow(BufferedImage map, int x, int z) {
        return map.getRGB(x - MistwoodsMist.MAP_MIN_X, z - MistwoodsMist.MAP_MIN_Z);
    }

    /** The second tile's: red a lip's height, green the way to the nearest lip, blue where its fall ends. */
    private static int falls(BufferedImage map, int x, int z) {
        return map.getRGB(x - MistwoodsMist.MAP_MIN_X, z - MistwoodsMist.MAP_MIN_Z + MistwoodsMist.MAP_SIZE_Z);
    }
}
