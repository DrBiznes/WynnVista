#version 330 core

// Blocky lava fog in the manner of the Better Clouds mod: the billows and wisps of the layer are flat-shaded,
// translucent slabs on a fixed lattice, swelling and shrinking as the noise drifts through them, over an even
// haze that is constant within each lattice cell. Only the technique follows that mod, no code.

#include "scene.glsl"

#include "nether_fog_shape.glsl"

uniform vec3 uLightColor;
uniform vec3 uAmbient;
uniform float uEmission;
uniform float uPixelSize;     // blocks one pixel spans at the fog's distance

layout(location = 0) out vec4 fragColor;       // premultiplied fog colour and opacity
layout(location = 1) out float fragDistance;   // terrain distance, as the other effects write it

// Small slabs close to the lava, larger ones above; each lattice owns a band of heights above the floor.
const int LEVELS = 2;
const vec3 CELL[LEVELS] = vec3[](vec3(6.0, 3.0, 6.0), vec3(12.0, 6.0, 12.0));
const float BAND[LEVELS + 1] = float[](0.0, 36.0, 1.0e4);    // a whole number of cells of both lattices
const int MAX_CELLS = 144;              // cells walked per level
const int MAX_OCTAVES = 2;              // detail finer than a slab only shuffles which cells are filled
const float MAX_DEPTH = 380.0;          // blocks of fog marched; nothing shows through more
const float HAZE = 0.0105;              // extinction per block of the even fog between the slabs
const float BILLOWS = 0.9;              // slab size from the soft noise
const float WISPS = 6.0;                // slab size from the sharp noise
const float CUBE_OPACITY = 0.2;         // of a full-size slab of the coarsest level
const float MIN_SIZE = 0.25;            // smaller slabs are left out rather than drawn as specks
const float MIN_PIXELS = 1.5;           // a lattice whose cells are lower than this on screen is not used
const float NEAR_CLEAR = 0.3;           // share of the haze left right at the camera, so a player inside can see
const float NEAR_RANGE = 48.0;          // blocks over which it returns to full, and slabs to their full size
const float NEAR_EMPTY = 10.0;          // no slabs closer than this
const float MAX_OPACITY = 0.93;         // terrain behind the fog never disappears completely

vec3 hash3(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}

/** Walks one level's lattice front to back between two distances along the ray and composites its fog. */
void marchLevel(int level, vec3 dir, float from, float to, vec3 bottomTop, vec3 scattered,
                inout vec3 colour, inout float transmittance, inout float firstHit) {
    vec3 size = CELL[level];
    vec3 safe = mix(dir, vec3(1.0e-7), lessThan(abs(dir), vec3(1.0e-7)));
    // The ray inside this level's band of heights.
    float ya = (bottomTop.x + uOrigin.y) / safe.y;
    float yb = (bottomTop.y + uOrigin.y) / safe.y;
    from = max(from, min(ya, yb));
    to = min(to, max(ya, yb));
    if (to <= from) return;

    // Lattice space: one unit per cell, origin at the centre of the layer's floor.
    vec3 origin = -uOrigin / size;
    vec3 inv = size / safe;
    vec3 stride = sign(safe);
    vec3 start = origin + safe * from / size;
    vec3 cell = floor(start);
    vec3 next = from + (cell + max(stride, 0.0) - start) * inv;
    vec3 delta = abs(inv);
    // Small slabs are fainter, so that a given depth of fog is about as opaque at every level.
    float opacity = 1.0 - pow(1.0 - CUBE_OPACITY, pow(size.y / CELL[LEVELS - 1].y, 0.3));
    int octaves = min(uOctaves, MAX_OCTAVES);

    float t = from;
    for (int i = 0; i < MAX_CELLS; i++) {
        float exit = min(min(next.x, min(next.y, next.z)), to);
        vec3 q = (cell + 0.5) * size;
        vec3 field = fogField(q, octaves);
        if (field.x > 0.0) {
            vec3 glow = fogGlow(q) * uEmission;
            float strength = BILLOWS * smoothstep(0.3, 0.7, field.y) + WISPS * field.z;
            // Slabs shrink to nothing around the camera, leaving a clearing to see through.
            float reach = smoothstep(NEAR_EMPTY, NEAR_RANGE, length(q + uOrigin));
            float scale = clamp(sqrt(field.x) * strength, 0.0, 1.0) * reach;
            if (scale >= MIN_SIZE) {
                vec3 h = hash3(cell + float(level) * 17.0);
                vec3 centre = cell + 0.5 + (h - 0.5) * (1.0 - scale);
                vec3 a = (centre - 0.5 * scale - origin) * inv;
                vec3 b = (centre + 0.5 * scale - origin) * inv;
                vec3 lo = min(a, b);
                vec3 hi = max(a, b);
                float enter = max(max(lo.x, lo.y), max(lo.z, 0.0));
                float leave = min(min(hi.x, hi.y), hi.z);
                if (leave > enter && enter < to) {
                    if (firstHit < 0.0) firstHit = enter;
                    // One flat colour per slab, brighter where the fog is thick, shaded by the face the ray
                    // enters by as a block's would be: top brightest, underside darkest.
                    float face = lo.y >= max(lo.x, lo.z) ? (stride.y < 0.0 ? 1.0 : 0.7) : (lo.x >= lo.z ? 0.82 : 0.9);
                    vec3 lit = glow * face * (0.65 + 0.5 * h.x + 0.4 * smoothstep(0.5, 1.5, strength)) + scattered;
                    float alpha = opacity * mix(0.55, 1.0, scale);
                    colour += transmittance * alpha * lit;
                    transmittance *= 1.0 - alpha;
                }
            }
            // The even haze, constant within the cell, over the length of ray inside it.
            float thin = mix(NEAR_CLEAR, 1.0, smoothstep(4.0, NEAR_RANGE, t));
            float alpha = 1.0 - exp(-field.x * HAZE * thin * (exit - t));
            if (alpha > 0.0005 && firstHit < 0.0) firstHit = t;
            colour += transmittance * alpha * (glow * 0.67 + scattered);
            transmittance *= 1.0 - alpha;
            if (transmittance < 0.03) return;
        }
        if (exit >= to) return;
        t = exit;
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
    span.y = min(span.y, fragDistance);
    if (span.y <= span.x) return;
    span.y = min(span.y, span.x + MAX_DEPTH);

    // From far away the finer lattice is smaller than a pixel; the coarser one takes its place.
    int first = 0;
    while (first < LEVELS - 1 && CELL[first].y < MIN_PIXELS * uPixelSize) first++;

    // The sky lights the fog by day; at night almost all of its colour is the lava's own glow.
    vec3 ambient = mix(uAmbient, uFogColor, 0.45);
    vec3 scattered = vec3(0.48, 0.19, 0.16) * (ambient * 0.5 + uLightColor * 0.3);
    vec3 colour = vec3(0.0);
    float transmittance = 1.0;
    float firstHit = -1.0;
    // Levels are stacked by height, so the order the ray meets them in is the order of its climb or descent.
    for (int k = 0; k < LEVELS; k++) {
        int level = dir.y >= 0.0 ? k : LEVELS - 1 - k;
        if (level < first || transmittance < 0.03) continue;
        marchLevel(level, dir, span.x, span.y, vec3(level == first ? 0.0 : BAND[level], BAND[level + 1], 0.0),
                scattered, colour, transmittance, firstHit);
    }

    float alpha = 1.0 - transmittance;
    if (alpha < 0.004) return;
    if (alpha > MAX_OPACITY) {
        colour *= MAX_OPACITY / alpha;
        alpha = MAX_OPACITY;
    }
    // Aerial perspective: distant fog sinks into the horizon colour like the terrain around it.
    float haze = 1.0 - exp(-max(firstHit, 0.0) * 0.00022);
    colour = mix(colour, uFogColor * alpha, haze);
    // Fog from a shader pack that has swallowed the canyon swallows this too.
    fragColor = vec4(throughFog(colour, alpha), alpha);
}
