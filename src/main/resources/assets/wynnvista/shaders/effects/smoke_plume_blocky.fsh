#version 330 core

// Blocky smoke column in the manner of the Better Clouds mod: the plume is built from flat-shaded, translucent
// cubes whose overlap adds up to the opacity. The cubes sit on lattices that rise with the smoke; the ray
// walks each lattice cell by cell, so every cube edge is exact. Only the technique follows that mod, no code.

#include "scene.glsl"

#include "plume_shape.glsl"

uniform vec3 uLightDir;
uniform vec3 uLightColor;
uniform vec3 uAmbient;
uniform float uGlow;
uniform float uPixelSize;     // blocks one pixel spans at the plume's distance

layout(location = 0) out vec4 fragColor;       // premultiplied smoke colour and opacity
layout(location = 1) out float fragDistance;   // terrain distance, as the other effects write it

// The smoke leaves the vent as many small cubes and ends as few large ones: four lattices, each twice as
// coarse as the one below, each owning a band of heights. NOISE_PERIOD is a whole number of cells of each.
const int LEVELS = 4;
const float CELL[LEVELS] = float[](2.0, 4.0, 8.0, 16.0);         // lattice spacing in blocks
const float BAND[LEVELS] = float[](0.0, 24.0, 72.0, 200.0);      // height above the vent where a level takes over
const float BLEND = 1.5;                // cells of the coarser lattice, each way, over which two levels swap
const int MAX_CELLS = 80;               // more than a ray can cross inside one level's box
const float CUBE_OPACITY = 0.28;        // of a full-size cube of the coarsest level
const float MIN_SIZE = 0.25;            // smaller cubes are left out rather than drawn as specks
const float MIN_PIXELS = 1.5;           // a lattice whose cells are smaller than this on screen is not used

vec3 hash3(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}

/**
 * Walks one level's lattice front to back along the ray and composites its cubes. The level draws cubes whose
 * centres are between bottom and top (heights above the vent); a negative fade means that end is not faded.
 * front receives what had been gathered at the first cube behind the cloud layer; it is negative until then.
 */
void marchLevel(int level, vec3 dir, float limit, vec3 ambient, float bottom, float bottomFade, float top,
                float topFade, float cloudAt, inout vec3 colour, inout float transmittance, inout float firstHit,
                inout vec4 front) {
    float size = CELL[level];
    // The part of the column this level can occupy, relative to the vent, a cell wider for cubes on its edge.
    float y0 = max(bottom - max(bottomFade, 0.0), 0.0);
    float y1 = min(top + max(topFade, 0.0), uShape.x);
    float u0 = y0 / uShape.x;
    float u1 = y1 / uShape.x;
    float radius = mix(uShape.y, uShape.z, pow(u1, 0.85)) + size;
    vec2 axis0 = uDrift * u0 * u0;
    vec2 axis1 = uDrift * u1 * u1;
    vec3 boxMin = vec3(min(axis0.x, axis1.x) - radius, y0 - size, min(axis0.y, axis1.y) - radius);
    vec3 boxMax = vec3(max(axis0.x, axis1.x) + radius, y1 + size, max(axis0.y, axis1.y) + radius);

    vec3 safe = mix(dir, vec3(1.0e-7), lessThan(abs(dir), vec3(1.0e-7)));
    vec3 a = (boxMin + uVent) / safe;
    vec3 b = (boxMax + uVent) / safe;
    vec3 lo = min(a, b);
    vec3 hi = max(a, b);
    float from = max(max(lo.x, lo.y), max(lo.z, 0.0));
    float to = min(min(min(hi.x, hi.y), hi.z), limit);
    if (to <= from) return;

    // Lattice space: one unit per cell, origin at the vent, sliding upward with the smoke.
    vec3 lift = vec3(0.0, uScroll, 0.0);
    vec3 origin = (-uVent - lift) / size;
    vec3 inv = size / safe;
    vec3 stride = sign(safe);
    vec3 start = origin + safe * from / size;
    vec3 cell = floor(start);
    vec3 next = from + (cell + max(stride, 0.0) - start) * inv;
    vec3 delta = abs(inv);
    // Small cubes are fainter, so that a given thickness of smoke is about as opaque at every level.
    float opacity = 1.0 - pow(1.0 - CUBE_OPACITY, pow(size / CELL[LEVELS - 1], 0.3));

    // Detail finer than a cube only shuffles which cells are filled, so two octaves are enough.
    int octaves = min(uOctaves, 2);
    for (int i = 0; i < MAX_CELLS; i++) {
        vec3 q = (cell + 0.5) * size + lift;
        float weight = 1.0;
        if (bottomFade >= 0.0) weight *= smoothstep(bottom - bottomFade, bottom + bottomFade, q.y);
        if (topFade >= 0.0) weight *= 1.0 - smoothstep(top - topFade, top + topFade, q.y);
        float scale = weight > 0.0 ? clamp(density(q, octaves) * 2.2, 0.0, 1.0) * weight : 0.0;
        if (scale >= MIN_SIZE) {
            // The cell index wraps where the rising pattern repeats, so a cube keeps its look across the wrap.
            vec3 h = hash3(vec3(cell.x, mod(cell.y, NOISE_PERIOD / size), cell.z) + float(level) * 17.0);
            vec3 centre = cell + 0.5 + (h - 0.5) * (1.0 - scale);
            a = (centre - 0.5 * scale - origin) * inv;
            b = (centre + 0.5 * scale - origin) * inv;
            lo = min(a, b);
            hi = max(a, b);
            float enter = max(max(lo.x, lo.y), max(lo.z, 0.0));
            float leave = min(min(hi.x, hi.y), hi.z);
            if (leave > enter && enter < limit) {
                if (firstHit < 0.0) firstHit = enter;
                if (front.a < 0.0 && enter >= cloudAt) front = vec4(colour, 1.0 - transmittance);
                vec4 c = column(q);
                float u = c.y;
                // One flat colour per cube: brighter on the side of the column that faces the light,
                // darker ash low down, and a little variation from cube to cube.
                float side = dot(c.zw, uLightDir.xz) * c.x / max(length(c.zw), 1.0e-3);
                float shade = clamp(0.5 + 0.5 * side + 0.3 * uLightDir.y, 0.0, 1.0);
                vec3 albedo = mix(vec3(0.66, 0.65, 0.64), vec3(0.97, 0.97, 0.98), smoothstep(0.0, 0.4, u));
                albedo *= 0.8 + 0.2 * h.x;
                // The face the ray enters by is shaded as a block's would be: top brightest, underside darkest.
                float face = lo.y >= max(lo.x, lo.z) ? (stride.y < 0.0 ? 1.0 : 0.7) : (lo.x >= lo.z ? 0.82 : 0.9);
                vec3 lit = albedo * face * (ambient * mix(0.85, 1.0, u) * (0.55 + 0.2 * shade)
                        + uLightColor * mix(0.22, 0.6, shade));
                lit += vec3(1.0, 0.34, 0.07) * uGlow * exp(-q.y / 20.0);
                // Cubes fade out just in front of the camera instead of filling the view with one face.
                float nearFade = smoothstep(size, size * 3.0, length(centre - origin) * size);
                float alpha = opacity * mix(0.55, 1.0, scale) * nearFade;
                colour += transmittance * alpha * lit;
                transmittance *= 1.0 - alpha;
                if (transmittance < 0.03) return;
            }
        }
        float exit = min(next.x, min(next.y, next.z));
        if (exit >= to) return;
        vec3 axis = step(next.xyz, next.yzx) * step(next.xyz, next.zxy);
        next += axis * delta;
        cell += axis * stride;
    }
}

void main() {
    vec2 uv = gl_FragCoord.xy / uViewSize;
    vec2 ndc = uv * 2.0 - 1.0;
    vec3 dir = viewRay(ndc);

    fragColor = vec4(0.0);
    fragDistance = sceneDistance(uv, ndc);
    vec2 span = boxSpan(dir);
    float limit = min(span.y, fragDistance);
    if (limit <= span.x) return;

    // From far away the finest lattices are smaller than a pixel; the first one that is not takes their place.
    int first = 0;
    while (first < LEVELS - 1 && CELL[first] < MIN_PIXELS * uPixelSize) first++;

    vec3 ambient = mix(uAmbient, uFogColor, 0.45);
    vec3 colour = vec3(0.0);
    float transmittance = 1.0;
    float firstHit = -1.0;
    vec4 cloud;
    float cloudAt = cloudDistance(uv, ndc, dir, cloud);
    vec4 front = vec4(-1.0);
    // Levels are stacked by height, so the order the ray meets them in is the order of its climb or descent.
    for (int k = 0; k < LEVELS; k++) {
        int level = dir.y >= 0.0 ? k : LEVELS - 1 - k;
        if (level < first || transmittance < 0.03) continue;
        bool lowest = level == first;
        bool highest = level == LEVELS - 1;
        marchLevel(level, dir, limit, ambient,
                lowest ? 0.0 : BAND[level], lowest ? -1.0 : CELL[level] * BLEND,
                highest ? uShape.x : BAND[level + 1], highest ? -1.0 : CELL[level + 1] * BLEND,
                cloudAt, colour, transmittance, firstHit, front);
    }

    float alpha = 1.0 - transmittance;
    if (alpha < 0.004) return;
    if (front.a < 0.0) front = vec4(colour, alpha);
    // Aerial perspective: distant smoke sinks into the horizon colour like the terrain around it.
    float haze = 1.0 - exp(-max(firstHit, 0.0) * 0.00022);
    // That, the fog that has swallowed the mountain, and any cloud the smoke is behind.
    fragColor = underClouds(vec4(colour, alpha), front, haze, cloud, dir * max(firstHit, 0.0));
}
