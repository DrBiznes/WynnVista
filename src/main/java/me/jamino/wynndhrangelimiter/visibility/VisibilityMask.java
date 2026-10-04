package me.jamino.wynndhrangelimiter.visibility;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public record VisibilityMask(MaskMode mode, List<BlockRect> rectangles) {
    public enum Classification { OUTSIDE, INSIDE, INTERSECTING }

    public VisibilityMask {
        rectangles = List.copyOf(rectangles);
        if (rectangles.size() > 8) {
            throw new IllegalArgumentException("At most eight mask rectangles are supported");
        }
        if (mode == MaskMode.NONE && !rectangles.isEmpty()) {
            throw new IllegalArgumentException("NONE must have an empty mask");
        }
    }

    public Classification classify(long minX, long minZ, long width) {
        if (width <= 0) {
            throw new IllegalArgumentException("Section width must be positive");
        }
        return classify(new BlockRect(minX, Math.addExact(minX, width),
                minZ, Math.addExact(minZ, width)));
    }

    public Classification classify(BlockRect section) {
        if (mode == MaskMode.PASSTHROUGH) return Classification.INSIDE;
        if (mode == MaskMode.NONE) return Classification.OUTSIDE;

        TreeSet<Long> xs = new TreeSet<>(List.of(section.minX(), section.maxX()));
        TreeSet<Long> zs = new TreeSet<>(List.of(section.minZ(), section.maxZ()));
        boolean intersects = false;
        for (BlockRect allowed : rectangles) {
            if (!allowed.intersects(section)) continue;
            intersects = true;
            xs.add(Math.max(section.minX(), allowed.minX()));
            xs.add(Math.min(section.maxX(), allowed.maxX()));
            zs.add(Math.max(section.minZ(), allowed.minZ()));
            zs.add(Math.min(section.maxZ(), allowed.maxZ()));
        }
        if (!intersects) return Classification.OUTSIDE;

        List<Long> xCuts = new ArrayList<>(xs);
        List<Long> zCuts = new ArrayList<>(zs);
        for (int x = 0; x < xCuts.size() - 1; x++) {
            for (int z = 0; z < zCuts.size() - 1; z++) {
                BlockRect cell = new BlockRect(xCuts.get(x), xCuts.get(x + 1),
                        zCuts.get(z), zCuts.get(z + 1));
                if (rectangles.stream().noneMatch(allowed -> allowed.contains(cell))) {
                    return Classification.INTERSECTING;
                }
            }
        }
        return Classification.INSIDE;
    }
}
