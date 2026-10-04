package me.jamino.wynndhrangelimiter.visibility;

public record BlockRect(long minX, long maxX, long minZ, long maxZ) {
    public BlockRect {
        if (minX >= maxX || minZ >= maxZ) {
            throw new IllegalArgumentException("Empty or inverted block rectangle");
        }
    }

    public static BlockRect fromInclusive(long x1, long z1, long x2, long z2) {
        return new BlockRect(Math.min(x1, x2), Math.addExact(Math.max(x1, x2), 1),
                Math.min(z1, z2), Math.addExact(Math.max(z1, z2), 1));
    }

    public boolean contains(double x, double z) {
        return Double.isFinite(x) && Double.isFinite(z)
                && x >= minX && x < maxX && z >= minZ && z < maxZ;
    }

    public boolean intersects(BlockRect other) {
        return minX < other.maxX && other.minX < maxX
                && minZ < other.maxZ && other.minZ < maxZ;
    }

    public boolean contains(BlockRect other) {
        return minX <= other.minX && maxX >= other.maxX
                && minZ <= other.minZ && maxZ >= other.maxZ;
    }
}
