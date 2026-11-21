package me.jamino.wynndhrangelimiter.effects;

import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code -Dwynnvista.effects.profile=true} logs the GPU time of the effect pass and of each part of it: the fog
 * probes, and each effect's bake, reflection and shading. A timestamp is written after each part; the frame's
 * timestamps are read back at the start of the next frame, which waits for them, as is fine for a diagnostic run.
 * It also logs the time from the start of the world's own rendering to the effect pass: the game, the LODs and
 * all of a shader pack's passes but its last, which is what to compare two versions of a pack by.
 * Render thread only.
 */
final class EffectProfile {
    static final boolean ENABLED = Boolean.getBoolean("wynnvista.effects.profile");
    private static final Logger LOGGER = LoggerFactory.getLogger("wynnvista-effects");
    private static final int FRAMES = 100;
    private static final int MAX_MARKS = 32;

    private static int[] queries;
    /** The part each timestamp ends, the first of a frame none. */
    private static final String[] LABELS = new String[MAX_MARKS];
    private static int marks;
    private static int pending;
    private static int frames;
    private static long totalNanos;
    private static int worldQuery;
    /** When the world's rendering started: in the frame being drawn, and in the one waiting to be read back. */
    private static long worldStart;
    private static long pendingWorldStart;
    private static long worldNanos;
    /** Per part: GPU nanoseconds and the frames it was drawn in, over the frames so far. */
    private static final Map<String, long[]> PARTS = new LinkedHashMap<>();
    /** Per part: what it drew in the last frame it was drawn in. */
    private static final Map<String, String> DETAILS = new LinkedHashMap<>();

    private EffectProfile() {}

    /** Whether this frame is the first of a batch: the one in which slow readings, such as the probes', are taken. */
    static boolean sampling() {
        return ENABLED && frames == 0;
    }

    /** Where the world's own rendering starts, before the game draws anything of it. */
    static void world() {
        if (!ENABLED) return;
        if (worldQuery == 0) worldQuery = GL15C.glGenQueries();
        GL33C.glQueryCounter(worldQuery, GL33C.GL_TIMESTAMP);
    }

    /** Reads back the last frame and starts this one. */
    static void begin() {
        if (!ENABLED) return;
        if (queries == null) {
            queries = new int[MAX_MARKS];
            GL15C.glGenQueries(queries);
        }
        if (pending > 1) collect();
        if (worldQuery != 0) worldStart = GL33C.glGetQueryObjecti64(worldQuery, GL15C.GL_QUERY_RESULT);
        pending = 0;
        marks = 0;
        mark(null);
    }

    /** Ends the part {@code label} here: it is the GPU work since the previous mark. */
    static void mark(String label) {
        if (!ENABLED || marks == MAX_MARKS) return;
        LABELS[marks] = label;
        GL33C.glQueryCounter(queries[marks++], GL33C.GL_TIMESTAMP);
    }

    static void end() {
        if (!ENABLED) return;
        pending = marks;
        pendingWorldStart = worldStart;
    }

    static void detail(String label, String detail) {
        if (ENABLED) DETAILS.put(label, detail);
    }

    private static void collect() {
        long previous = GL33C.glGetQueryObjecti64(queries[0], GL15C.GL_QUERY_RESULT);
        long start = previous;
        for (int i = 1; i < pending; i++) {
            long now = GL33C.glGetQueryObjecti64(queries[i], GL15C.GL_QUERY_RESULT);
            long[] part = PARTS.computeIfAbsent(LABELS[i], label -> new long[2]);
            part[0] += now - previous;
            part[1]++;
            previous = now;
        }
        totalNanos += previous - start;
        if (pendingWorldStart != 0) worldNanos += start - pendingWorldStart;
        if (++frames < FRAMES) return;
        StringBuilder log = new StringBuilder(String.format("World effects GPU time: %.3f ms/frame over %d frames",
                totalNanos / 1.0e6 / FRAMES, FRAMES));
        log.append(String.format("%n  before the effects (game, LODs, shader pack): %.3f ms", worldNanos / 1.0e6 / FRAMES));
        for (Map.Entry<String, long[]> part : PARTS.entrySet()) {
            long[] time = part.getValue();
            // Per frame it was drawn in, so a part that was in view only some of the time is not understated.
            log.append(String.format("%n  %s: %.3f ms in %d frames", part.getKey(), time[0] / 1.0e6 / time[1], time[1]));
            String detail = DETAILS.get(part.getKey());
            if (detail != null) log.append(" (").append(detail).append(')');
        }
        LOGGER.info(log.toString());
        frames = 0;
        totalNanos = 0;
        worldNanos = 0;
        PARTS.clear();
        DETAILS.clear();
    }
}
