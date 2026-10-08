#version 330 core

// The void under the Sky Islands: clumps of cloud built from whole cubes, after the cloud models that launch
// the player between the islands, a thin haze around them, and below them a dark void with a rare nebula.
// The cubes sit on a lattice the view ray walks cell by cell, as in the blocky smoke plume, so every edge is
// exact; the haze is worked out exactly along the ray.

#include "scene.glsl"

uniform vec3 uLevels;           // x: where the void begins, y: the top of the lowest layer of cubes, both camera-relative
uniform vec2 uNoiseOrigin;      // the camera's place within one repeat of the noise pattern, in blocks
uniform float uPhase;           // 0..1 over the time after which the moving patterns repeat
uniform vec3 uLightColor;
uniform vec3 uAmbient;
uniform float uGlow;            // 1 at night, less by day

layout(location = 0) out vec4 fragColor;       // premultiplied colour and opacity
layout(location = 1) out float fragDistance;   // terrain distance, for the half-resolution path

const float PERIOD = 2048.0;            // blocks per repeat of the noise; one of its cells is 64 blocks at scale 1

const float CELL = 4.0;                 // lattice spacing and side of the largest cube; PERIOD is a whole number of them
const int LAYERS = 4;                   // lattice layers a clump can reach into
const int STACK = 3;                    // cubes a clump can stand high
const float COVER = 0.60;               // noise above which a column holds a cube; higher is fewer and smaller clumps
const float COVER_STEP = 0.05;          // how much higher that is for each cube above the first
const float SWELL = 0.07;               // rise of the noise over which a cube grows from its smallest to a whole cell
const float SMALLEST = 0.5;             // side of the smallest cube, in cells
const float CRUMB_MARGIN = 0.035;       // a cell this far short of holding a cube holds crumbs: small cubes around the large
const float CRUMB_HANG = 0.03;          // and crumbs hang under a column this far past holding one
const float CRUMBS_NEAR = 110.0;        // blocks from the camera at which the crumbs start to fade
const float CRUMBS_FAR = 170.0;         // and where they are gone
const float CELLS_NEAR = 220.0;         // the same for the cubes, which give way to the haze
const float CELLS_FAR = 520.0;
const int MAX_CELLS = 96;               // lattice cells walked; a ray that needs more is deep in the haze by then

const float HAZE_HEIGHT = 8.0;          // blocks above the cloud over which the haze thins to 1/e
const float HAZE_SINK = 10.0;           // and below it, where it takes the colour of the depths
const float HAZE_DENSITY = 0.011;       // extinction per block at its level
const float HAZE_MAX = 0.95;
const float EDGE = 96.0;                // blocks from the sides of the box over which everything fades out

const float NEBULA_DEPTH = 220.0;       // how far below where the void begins its nebulae appear to lie
const float NEBULA_CELL = 4.0;          // side of one square of a nebula, at its depth
const float NEBULA_RARE = 0.64;         // noise above which a place holds a nebula; higher is fewer
const float NEBULA_SHADES = 5.0;        // steps of brightness a nebula's clouds are drawn in
const float NEBULA_STARS = 0.03;        // share of a nebula's squares that hold a star
const float NEBULA_WARP = 0.02;         // how far a nebula's pattern is bent, in noise repeats
const float NEBULA_STRENGTH = 0.9;      // its brightness at night, before the dark in front of it
const float MURK = 0.0035;              // extinction per block of that dark
const float CRYSTAL_CELL = 4.0;         // side of one square that may hold a crystal's glint
const float CRYSTAL_SHARE = 0.004;      // share of them that do

const vec3 VIOLET = vec3(0.46, 0.17, 0.95);
const vec3 DARK = vec3(0.012, 0.009, 0.024);

vec3 gathered = vec3(0.0);      // premultiplied colour of what the ray has met, front to back
float through = 1.0;            // how much still shows through it
float first = -1.0;             // where the ray first met something, negative until then

vec3 hash3(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}

/** Haze between its level and h blocks from it (negative below), for an even climb of one block per block. */
float hazeColumn(float h) {
    return h >= 0.0 ? HAZE_HEIGHT * (1.0 - exp(-h / HAZE_HEIGHT)) : -HAZE_SINK * (1.0 - exp(h / HAZE_SINK));
}

/**
 * Optical depth of the haze along a straight piece of ray of length len that starts a blocks above the
 * haze's level and ends b above it. The haze is densest at its level and thins exponentially above and below.
 */
float hazeDepth(float a, float b, float len) {
    float rise = abs(b - a);
    if (rise > 1.0e-3) return HAZE_DENSITY * len * abs(hazeColumn(b) - hazeColumn(a)) / rise;
    return HAZE_DENSITY * len * exp(a >= 0.0 ? -a / HAZE_HEIGHT : a / HAZE_SINK);
}

/** Where the ray enters the cube with corner `low` and side `size` (lattice units), per axis and at all. */
bool hitCube(vec3 low, float size, vec3 origin, vec3 inv, out vec3 lo, out float enter) {
    vec3 a = (low - origin) * inv;
    vec3 b = (low + size - origin) * inv;
    lo = min(a, b);
    vec3 hi = max(a, b);
    enter = max(max(lo.x, lo.y), lo.z);
    return min(min(hi.x, hi.y), hi.z) > max(enter, 0.0);
}

/**
 * Colour of a cloud cube where the ray enters it. `local` is that point within the cube, 0..1 each way, `lo`
 * says by which face, `h` is the cube's own random. Like the launch clouds: white, some cubes faintly rose or
 * blue, a blush along the foot of the sides and over the underside, lighter texels, and a block's face shading.
 */
vec3 cubeColour(vec3 local, vec3 lo, bool rising, vec3 h, vec3 lit) {
    bool level = lo.y >= max(lo.x, lo.z);
    bool under = level && rising;
    float face = level ? (rising ? 0.74 : 1.0) : (lo.x >= lo.z ? 0.9 : 0.84);
    vec3 albedo = h.z < 0.62 ? vec3(0.97, 0.97, 0.99) : (h.z < 0.82 ? vec3(0.98, 0.91, 0.95) : vec3(0.89, 0.94, 1.0));
    if (under) albedo = mix(albedo, vec3(0.96, 0.80, 0.90), 0.7);
    else if (!level && local.y < 0.3125) albedo = mix(albedo, vec3(0.96, 0.80, 0.90), 0.55);
    vec3 texel = floor(clamp(local, 0.001, 0.999) * 8.0);
    if (hash3(texel + h * 40.0).x < 0.75) albedo *= 0.955;
    // At night the cubes keep a little light of their own, so their faces can still be told apart.
    return albedo * face * (lit + vec3(0.11, 0.10, 0.17) * uGlow) + VIOLET * (under ? 0.05 : 0.0) * uGlow;
}

/**
 * x: the noise value of a column of the lattice, which decides how many cubes stand there. It has peaks about
 * 16 blocks apart, each a clump, and a slow part that gathers the clumps into groups. y: the layer the
 * column's clump begins on, so that clumps float at two heights.
 */
vec2 clump(vec2 column) {
    vec2 p = (column + 0.5) * CELL / PERIOD;
    float n = 0.62 * noise(vec3(p * 4.0, 0.37)) + 0.38 * noise(vec3(p, 0.71));
    return vec2(n, step(0.5, noise(vec3(p * 2.0, 0.13))));
}

/**
 * Walks the cloud lattice along the ray between `from` and `to` and gathers its cubes. A cell holds at most
 * one cube, as in the blocky smoke plume: larger the further the column's noise is past what the cell needs,
 * so a clump has whole cells in its middle and half-size cubes at its rim, and placed at random inside its
 * cell, except that a cube above another rests on the floor of its cell. A cell that falls just short of
 * holding a cube holds crumbs: up to eight cubes of a quarter cell to half a cell that rest on the cube below,
 * and under a clump they hang from it. The whole lattice drifts as vanilla clouds do.
 */
void cloudSea(vec3 dir, float from, float to, vec3 lit) {
    vec3 safe = mix(dir, vec3(1.0e-7), lessThan(abs(dir), vec3(1.0e-7)));
    // Lattice space: one unit per cell, layer 0 the lowest a clump begins on.
    vec3 origin = vec3(uNoiseOrigin.x + uPhase * PERIOD, CELL - uLevels.y, uNoiseOrigin.y) / CELL;
    vec3 inv = CELL / safe;
    vec3 stride = sign(safe);
    vec3 start = origin + safe * from / CELL;
    vec3 cell = floor(start);
    vec3 next = from + (cell + max(stride, 0.0) - start) * inv;
    vec3 delta = abs(inv);
    float at = from;
    for (int i = 0; i < MAX_CELLS; i++) {
        float layer = cell.y;
        if (layer >= -1.0 && layer <= float(LAYERS)) {
            // The index wraps where the pattern repeats, so a cube keeps its look across the wrap.
            vec2 column = mod(cell.xz, PERIOD / CELL);
            vec2 c = clump(column);
            float rank = layer - c.y;       // which cube of its clump this cell would hold, from 0 up
            float need = COVER + COVER_STEP * max(rank, 0.0);
            vec3 low = cell;
            float size = 1.0;
            vec3 h = vec3(0.0);
            vec3 lo = vec3(0.0);
            float enter = INF;
            float fade = 1.0;
            if (rank >= 0.0 && rank < float(STACK) && c.x >= need) {
                h = hash3(vec3(column, layer) + 3.0);
                size = mix(SMALLEST, 1.0, clamp((c.x - need) / SWELL, 0.0, 1.0));
                vec3 place = hash3(vec3(column, layer) + 60.0);
                if (rank > 0.0) place.y = 0.0;
                low = cell + (1.0 - size) * place;
                if (!hitCube(low, size, origin, inv, lo, enter)) enter = INF;
            } else if (at < CRUMBS_FAR && rank >= -1.0 && rank <= float(STACK)
                    && c.x >= (rank < 0.0 ? COVER + CRUMB_HANG : need - CRUMB_MARGIN)) {
                fade = 1.0 - smoothstep(CRUMBS_NEAR, CRUMBS_FAR, at);
                for (int k = 0; k < 8; k++) {
                    vec3 sub = vec3(k & 1, (k >> 1) & 1, k >> 2);
                    vec3 seed = vec3(column, layer) * 2.0 + sub + 31.0;
                    vec3 r = hash3(seed);
                    bool resting = sub.y == (rank < 0.0 ? 1.0 : 0.0);
                    if (r.x >= (resting ? 0.6 : 0.18)) continue;
                    float side = 0.5 * mix(0.55, 1.0, r.y);
                    vec3 place = hash3(seed + 57.0);
                    if (resting) place.y = rank < 0.0 ? 1.0 : 0.0;
                    vec3 corner = cell + sub * 0.5 + (0.5 - side) * place;
                    vec3 faces;
                    float reached;
                    if (hitCube(corner, side, origin, inv, faces, reached) && reached < enter) {
                        enter = reached;
                        lo = faces;
                        low = corner;
                        size = side;
                        h = r;
                    }
                }
            }
            float t = max(enter, 0.0);
            if (t < to) {
                vec3 local = (origin + dir * t / CELL - low) / size;
                // Cubes fade out just in front of the camera instead of filling the view with one face.
                float alpha = fade * smoothstep(1.0, 5.0, t) * (1.0 - smoothstep(CELLS_NEAR, CELLS_FAR, t));
                gathered += through * alpha * cubeColour(local, lo, dir.y > 0.0, h, lit);
                through *= 1.0 - alpha;
                if (first < 0.0 && alpha > 0.0) first = t;
                if (through < 0.03) return;
            }
        }
        at = min(next.x, min(next.y, next.z));
        if (at >= to) return;
        vec3 axis = step(next.xyz, next.yzx) * step(next.xyz, next.zxy);
        next += axis * delta;
        cell += axis * stride;
    }
}

/** The colours a nebula can have, all near each other: purple, violet, indigo and two blues, for t in 0..1. */
vec3 nebulaHue(float t) {
    const vec3 HUES[5] = vec3[](vec3(0.40, 0.13, 0.78), vec3(0.50, 0.20, 0.95), vec3(0.27, 0.19, 0.88),
            vec3(0.16, 0.30, 0.92), vec3(0.26, 0.46, 0.95));
    float x = clamp(t, 0.0, 0.999) * 4.0;
    int i = int(x);
    return mix(HUES[i], HUES[i + 1], x - float(i));
}

/** A cloud's density for noise value n, none below `from` and all 0.3 above, in the steps of pixel art. */
float nebulaCloud(float n, float from) {
    return floor(smoothstep(from, from + 0.3, n) * NEBULA_SHADES + 0.5) / NEBULA_SHADES;
}

/**
 * The void where the ray leaves the box through its floor, t along the ray: dark, and in a few places a
 * nebula. Where one is comes from a slow noise field that drifts across the void and changes with time, so
 * nebulae are rare, wander, and come and go. A second field that drifts with it gives the place its colours,
 * so no two look alike and one changes colour across its width. Inside, two clouds of four noise octaves bent
 * by more noise, a third colour along bright filaments, and stars, all in square pixels and a few steps of
 * brightness. It is looked up far below the plane, so it barely moves as the camera does and dims at a slant.
 * A rare square outside the nebulae holds the glint of a crystal.
 */
vec3 depths(vec3 dir, float t) {
    float slope = -1.0 / dir.y;         // blocks of ray per block of descent
    vec2 at = dir.xz * (t + slope * NEBULA_DEPTH) + uNoiseOrigin;
    float seen = exp(-MURK * NEBULA_DEPTH * slope) * (0.55 + 0.45 * uGlow);
    vec3 colour = DARK;
    vec2 pixel = floor(at / NEBULA_CELL);
    vec2 centre = (pixel + 0.5) * NEBULA_CELL / PERIOD;
    vec2 adrift = centre + uPhase * vec2(2.0, 1.0);
    float nebula = smoothstep(NEBULA_RARE, NEBULA_RARE + 0.12, noise(vec3(adrift, uPhase * 2.0)));
    if (nebula > 0.0) {
        float tone = (noise(vec3(adrift * 2.0 + 0.19, 0.45)) - 0.3) / 0.4;
        float turn = 6.2832 * noise(vec3(centre * 2.0, uPhase * 4.0));
        vec2 bent = centre + NEBULA_WARP * vec2(cos(turn), sin(turn));
        float one = nebulaCloud(fbm(vec3(bent * 2.0 + uPhase * vec2(1.0, 0.0), 0.21), 4), 0.42);
        float two = nebulaCloud(fbm(vec3(bent * 3.0 + uPhase * vec2(0.0, -1.0) + 0.43, 0.57), 4), 0.47);
        float thread = 1.0 - abs(2.0 * noise(vec3(bent * 8.0 + uPhase * vec2(-1.0, 1.0), 0.89)) - 1.0);
        thread = step(0.86, thread) * max(one, two);
        vec3 glow = nebulaHue(tone) * 0.55 * one + nebulaHue(fract(tone + 0.3)) * 0.45 * two;
        glow = mix(glow, mix(nebulaHue(fract(tone + 0.6)), vec3(0.86, 0.82, 1.0), 0.4) * 0.75, thread * 0.8);
        // A star fills its square and is brighter inside the clouds.
        vec3 random = hash3(vec3(mod(pixel, PERIOD / NEBULA_CELL), 2.0));
        if (random.x < NEBULA_STARS) {
            float twinkle = 0.6 + 0.4 * sin(6.2832 * (uPhase * 160.0 + random.y));
            glow += vec3(0.74, 0.72, 0.98) * twinkle * (0.25 + 0.75 * max(one, two));
        }
        colour += glow * nebula * NEBULA_STRENGTH * seen;
    }
    vec2 square = floor(at / CRYSTAL_CELL);
    if (textureLod(uNoise, vec3((square + 0.5) / NOISE_SIZE, 9.5 / NOISE_SIZE), 0.0).r < CRYSTAL_SHARE) {
        float pulse = 0.6 + 0.4 * sin(6.2832 * (uPhase * 160.0 + hash3(vec3(square, 1.0)).x));
        colour += vec3(0.62, 0.42, 0.95) * 0.5 * pulse * seen * (1.0 - nebula);
    }
    return colour;
}

void main() {
    vec2 uv = gl_FragCoord.xy / uViewSize;
    vec2 ndc = uv * 2.0 - 1.0;
    vec3 dir = viewRay(ndc);

    // Pixels without the effect write zero rather than discarding: the half-resolution path needs their distance.
    fragColor = vec4(0.0);
    fragDistance = sceneDistance(uv, ndc);
    vec2 span = boxSpan(dir);
    float end = min(span.y, fragDistance);
    if (end <= span.x) return;

    // By day the cloud is lit by the sky and sun; the haze below it is a dim violet.
    vec3 ambient = skyLight(uAmbient);
    vec3 lit = (ambient * 0.9 + uLightColor * sunTint * 0.35) * skyGain;
    vec3 pale = vec3(0.92, 0.91, 0.96) * lit + VIOLET * (0.01 + 0.035 * uGlow);
    vec3 dusk = pale * vec3(0.42, 0.36, 0.70) + VIOLET * (0.03 + 0.07 * uGlow);

    // The cubes, within the slab of heights that can hold them: a cell of crumbs below and above the clumps.
    float solid = end;
    if (abs(dir.y) > 1.0e-4) {
        float a = (uLevels.y - 2.0 * CELL) / dir.y;
        float b = (uLevels.y + float(LAYERS) * CELL) / dir.y;
        float from = max(min(a, b), span.x);
        float to = min(max(a, b), end);
        if (to > from) cloudSea(dir, from, to, lit);
        if (through < 0.03) solid = first;
    }

    // The void is seen where the ray leaves the box through its floor before meeting terrain.
    float floorAt = dir.y < 0.0 ? uLevels.x / dir.y : INF;
    if (through >= 0.03 && floorAt > 0.0 && floorAt <= end * 1.0001 + 0.01) {
        gathered += through * depths(dir, floorAt);
        through = 0.0;
        if (first < 0.0) first = floorAt;
    }

    // The haze lies among the clumps and is laid over the rest, up to the first solid cube: towards the horizon
    // it is all there is. What lies below its level is dim, and the void is seen through a veil of it.
    float level = uLevels.y + CELL;
    float start = dir.y * span.x - level;
    float stop = dir.y * solid - level;
    float high = max(start, stop);
    float low = min(start, stop);
    float above = high - low > 1.0e-3 ? (max(high, 0.0) - max(low, 0.0)) / (high - low) : (low > 0.0 ? 1.0 : 0.0);
    float reach = solid - span.x;
    float pall = hazeDepth(max(low, 0.0), max(high, 0.0), reach * above);
    float veil = hazeDepth(min(low, 0.0), min(high, 0.0), reach * (1.0 - above));
    float haze = min(1.0 - exp(-pall - veil), HAZE_MAX);
    vec3 hazeColour = (pale * 0.94 * pall + dusk * 0.6 * veil) / max(pall + veil, 1.0e-6);
    vec3 colour = hazeColour * haze + gathered * (1.0 - haze);
    float alpha = 1.0 - through * (1.0 - haze);
    if (alpha < 0.004) return;
    if (first < 0.0) first = abs(dir.y) > 1.0e-4 ? clamp(level / dir.y, span.x, end) : span.x;

    vec4 cloud;
    float cloudAt = cloudDistance(uv, ndc, dir, cloud);
    vec4 whole = vec4(colour, alpha);
    // Aerial perspective, a shader pack's fog, and any cloud between the camera and the void.
    float distant = 1.0 - exp(-first * 0.00022);
    fragColor = underClouds(whole, cloudAt < first ? vec4(0.0) : whole, distant, cloud, dir * first);
    // Towards the sides of its box the effect fades out, so it has no edge where no land hides one.
    vec2 inside = min(dir.xz * first - uBoxMin.xz, uBoxMax.xz - dir.xz * first);
    fragColor *= smoothstep(0.0, EDGE, min(inside.x, inside.y));
}
