package me.jamino.wynndhrangelimiter.effects;

/**
 * Where a shader pack keeps what it knows about the clouds it drew this frame, and how to read it. Packs
 * march their clouds straight into the image and write no depth for them, but each one stores the distance
 * to the cloud on every pixel for its own later passes. Those buffers are read when the frame is finished, or
 * copied earlier where a pack reuses them. {@code pack_clouds.fsh} turns them into a cloud layer the effects
 * are drawn behind.
 *
 * @param kind          which pack's layout the buffers have
 * @param uvScale       share of each buffer that covers the view; under 1 when the pack renders at a
 *                      reduced resolution
 * @param distanceScale blocks that a stored distance of 1 stands for; 1 when the pack stores blocks
 */
public record PackClouds(Kind kind, float uvScale, float distanceScale) {
    /** The packs whose cloud buffers are understood, with the colour buffers ({@code colortexN}) they use. */
    public enum Kind {
        /**
         * Photon v1.3: {@code colortex11} is the cloud image, with how much shows through it in alpha, and
         * {@code colortex12.x} the distance to it in blocks.
         */
        PHOTON(11, 12),
        /**
         * BSL v10.1: {@code colortex4.r} is the distance to the first sample where the cloud is more than
         * half opaque, as a share of the cloud range; 1 where there is none.
         */
        BSL(4, -1),
        /**
         * Complementary r5.x: {@code colortex5.a} is the square root of the distance to the cloud as a share
         * of the pack's render distance; 1 where there is none. The pack's later passes keep something else
         * there, so it is copied as soon as the clouds have been drawn.
         */
        COMPLEMENTARY(5, -1);

        private final int first;
        private final int second;

        Kind(int first, int second) {
            this.first = first;
            this.second = second;
        }

        /** Index of the pack's colour buffer holding the cloud data. */
        public int first() { return first; }

        /** Index of a second buffer it is spread over, or -1. */
        public int second() { return second; }

        /** True when the buffer only holds the clouds between the pack's deferred and composite passes. */
        public boolean afterDeferred() { return this == COMPLEMENTARY; }
    }

    /** Iris gives a pack this far plane for Distant Horizons: the LOD distance and 512 blocks, times root two. */
    public static float dhFarPlane(float lodRenderDistance) {
        return (float) ((lodRenderDistance + 512) * Math.sqrt(2));
    }
}
