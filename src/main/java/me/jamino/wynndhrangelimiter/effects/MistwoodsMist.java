package me.jamino.wynndhrangelimiter.effects;

import me.jamino.wynndhrangelimiter.ModConfig;

import java.util.List;

/**
 * The canopy mist of the Mistwoods: a deck of mist that fills the woods to above their lower canopy and drifts
 * down the valley around the giant trees, Aldwell and the citadel's wall, and falls of mist from the ledges of
 * the giant trees. Placement and timing rules; the look is in the shader.
 *
 * <p>Inside the woods the server already fogs the view: the biome there has a fog colour and a fog end distance
 * of its own, which the game draws, and WynnIris draws again after a shader pack. That fog is on the screen,
 * not in the world, and is gone one step outside the woods; this effect is the same mist seen from anywhere.
 * For a camera inside that fog it takes the fog's colour and leaves everything past the fog's end to it.
 */
public final class MistwoodsMist implements WorldEffect {
    public static final String ID = "mistwoods_mist";

    /**
     * The area's map, {@code scripts/mask_tool.py reduce-mistwoods} of {@code masks/mistwoods.wvmask}: two
     * tiles of this size, one under the other.
     */
    public static final String MAP = "mistwoods.png";
    public static final int MAP_MIN_X = -1728;
    public static final int MAP_MIN_Z = -1040;
    public static final int MAP_SIZE_X = 816;
    public static final int MAP_SIZE_Z = 576;

    /**
     * The woods lie in a basin with its floor at about y 50. Their lower canopy, the gathering trees and the
     * lower boughs of the giant ones, ends at about y 100; the crowns of the giant trees begin at about y 128.
     * The deck's top is in the gap between them and can stand {@link #RISE} blocks higher where it heaps up.
     */
    public static final double DECK_Y = 102;
    public static final double FLOOR_Y = 36;
    public static final double RISE = 24;

    /** No ledge that spills mist is higher than this; the box ends here while the falls are drawn. */
    public static final double POUR_TOP_Y = 180;

    /**
     * Blocks past the map's sides the mist runs on for, thinning out. The map ends at z -465 in the south, some
     * 40 blocks short of the wall to the wastelands, which could not be recorded.
     */
    public static final double SKIRT = 56;

    /** Blocks from the box, along the ground, beyond which the mist is not drawn. */
    public static final double REACH = 1400;

    /** Blocks per repeat of the noise, and the ticks after which the moving patterns repeat. */
    public static final double NOISE_PERIOD = 2048;
    public static final long CYCLE_TICKS = 128000;

    /**
     * A fog end nearer than this many blocks is a biome's own fog, the Mistwoods' among them; the game's
     * default is 1024.
     */
    public static final float FOG_NEAR = 600;

    /** Seconds over which the mist takes on that fog, or lets go of it, as the camera crosses the woods' edge. */
    public static final double FOG_EASE = 0.8;

    public static final EffectOption.Toggle POURS = new EffectOption.Toggle("pours", "Mistfalls",
            "Mist that gathers on the ledges of the giant trees and now and then spills over them into the "
                    + "mist below");

    /** From the woods' floor to the highest ledge; the deck itself ends at {@link #DECK_Y} plus {@link #RISE}. */
    private static final Bounds BOUNDS = new Bounds(MAP_MIN_X - SKIRT, FLOOR_Y, MAP_MIN_Z - SKIRT,
            MAP_MIN_X + MAP_SIZE_X + SKIRT, POUR_TOP_Y, MAP_MIN_Z + MAP_SIZE_Z + SKIRT);

    /** How far the camera is in a biome's fog, 0..1, and when that was last worked out. */
    private float inFog;
    private long inFogAt;

    @Override public String id() { return ID; }
    @Override public String name() { return "Mistwoods Canopy Mist"; }
    @Override public String description() {
        return "A deck of mist over the Mistwoods that drifts around the giant trees, Aldwell and the citadel's wall";
    }
    @Override public List<EffectOption> options() { return List.of(POURS); }
    @Override public String shader() { return "mistwoods_mist.fsh"; }
    @Override public String terrainMap() { return MAP; }
    /** The woods' water lies under the deck. */
    @Override public boolean reflects() { return false; }
    /** Aldwell. */
    @Override public double anchorX() { return -1305; }
    @Override public double anchorZ() { return -686; }
    @Override public Bounds bounds() { return BOUNDS; }
    @Override public double maxViewDistance() { return REACH; }

    /** From the box, not from Aldwell: the woods are 800 blocks across. */
    @Override public boolean inRange(double x, double y, double z) {
        return BOUNDS.groundDistance(x, z) <= REACH;
    }

    @Override
    public void upload(EffectProgram program, EffectFrame frame) {
        SmokePlume.Lighting light = SmokePlume.lighting(frame);
        long now = System.nanoTime();
        inFog = ease(inFog, fogTarget(frame.fogEnd()), inFogAt == 0 ? Double.MAX_VALUE : (now - inFogAt) / 1.0e9);
        inFogAt = now;
        program.set("uSkyMatch", frame.packLighting() ? 1f : 0f);
        program.set("uMap", (float) (MAP_MIN_X - frame.cameraX()), (float) (MAP_MIN_Z - frame.cameraZ()), MAP_SIZE_X,
                MAP_SIZE_Z);
        program.set("uCameraY", (float) frame.cameraY());
        program.set("uNoiseOrigin", wrap(frame.cameraX()), wrap(frame.cameraZ()));
        program.set("uPhase", phase(frame.worldTime(), frame.tickProgress()));
        program.set("uLightDir", light.dirX(), light.dirY(), light.dirZ());
        program.set("uLightColor", light.red(), light.green(), light.blue());
        program.set("uAmbient", light.ambientRed(), light.ambientGreen(), light.ambientBlue());
        program.set("uGlow", Math.max(0, Math.min(1, light.glow())));
        program.set("uBiomeFog", inFog, frame.fogStart(), Math.max(frame.fogEnd(), frame.fogStart() + 1));
        program.set("uPours", ModConfig.effectToggle(ID, POURS) ? 1f : 0f);
    }

    /** 1 where a fog end distance is a biome's own fog, 0 where it is the game's default. */
    public static float fogTarget(float fogEnd) {
        return fogEnd < FOG_NEAR ? 1 : 0;
    }

    /** `current` moved towards `target` as far as `seconds` allow: all but 1/e of the way in {@link #FOG_EASE}. */
    public static float ease(float current, float target, double seconds) {
        if (seconds <= 0) return current;
        return (float) (target + (current - target) * Math.exp(-seconds / FOG_EASE));
    }

    /**
     * A world coordinate within one repeat of the noise, so the patterns stay fixed to the world while the
     * shader works with small numbers.
     */
    public static float wrap(double coordinate) {
        return (float) (coordinate - Math.floor(coordinate / NOISE_PERIOD) * NOISE_PERIOD);
    }

    /**
     * Phase of the moving patterns, 0..1. The shader moves each pattern by a whole multiple of it, so the
     * wrap from 1 to 0 is invisible.
     */
    public static float phase(long worldTime, float tickProgress) {
        return (float) ((Math.floorMod(worldTime, CYCLE_TICKS) + tickProgress) / CYCLE_TICKS);
    }
}
