#version 330 core

// The void under the Sky Islands: clumps of cloud, each a few large cuboids run into each other, after the cloud
// models that launch the player between the islands but a little translucent, a thin haze around them, and below them a dark void with
// a few fallen islands and a rare nebula. The cuboids sit in the columns of two lattices the view ray walks
// column by column, so every edge is exact; the haze is worked out exactly along the ray.

#include "scene.glsl"

#include "sky_islands_void_shape.glsl"

layout(location = 0) out vec4 fragColor;       // premultiplied colour and opacity
layout(location = 1) out float fragDistance;   // terrain distance, for the half-resolution path

const float PUFF = 8.0;                 // side of one column of a lattice, which holds at most one cuboid
const vec3 PUFF_SHIFT = vec3(4.5, 2.5, 4.5);    // the second lattice against the first; no two faces share a plane
const float PUFF_YOUNG = 0.6;           // share of its full measure a cuboid has when it forms
const float SWELL = 0.07;               // rise of the noise over which it grows to all of it
const float APPEAR = 0.025;             // and over which a new one fades in
const float PUFF_OPACITY = 0.72;        // share of what is behind a cuboid that it hides
const int MAX_PUFFS = 12;               // cuboids gathered along a ray; more only where they fade into the haze
const float CLEAR = 1.0;                // blocks from the camera within which no cuboid is drawn
const float CLEAR_FAR = 5.0;            // and where one is whole
const int MAX_CELLS = 96;               // columns looked at per lattice: the 520 blocks the clumps show to, on a diagonal
const float SUN_FACING = 1.5;           // under a shader pack, the sun's light on a face turned straight to it, against its even share

const float NEBULA_SHADES = 5.0;        // steps of brightness a nebula's clouds are drawn in
const float NEBULA_STARS = 0.03;        // share of a nebula's squares that hold a star
const float ISLE_CELL[ISLES] = float[](4.0, 4.0, 8.0);          // side of one square of a layer's islands

vec3 gathered = vec3(0.0);      // premultiplied colour of what the ray has met, front to back
float through = 1.0;            // how much still shows through it
float first = -1.0;             // where the ray first met something, negative until then

/**
 * A cuboid of cloud the ray enters t blocks along, at `at`: its low corner and size, by which face (see hitBox),
 * its own random and how much of it there is yet, 0..1. Places are in its lattice's blocks.
 */
struct Puff {
    float t;
    vec3 at;
    float shows;
    vec3 low;
    vec3 size;
    vec3 lo;
    vec3 h;
};

/** Where the ray enters the box with corner `low` and the given size, per axis and at all. */
bool hitBox(vec3 low, vec3 size, vec3 origin, vec3 inv, out vec3 lo, out float enter) {
    vec3 a = (low - origin) * inv;
    vec3 b = (low + size - origin) * inv;
    lo = min(a, b);
    vec3 hi = max(a, b);
    enter = max(max(lo.x, lo.y), lo.z);
    return min(min(hi.x, hi.y), hi.z) > max(enter, 0.0);
}

/**
 * Colour of a cuboid of cloud where the ray enters it. The texels are fixed to the lattice, so a cuboid grows
 * over them without dragging them along. Like the
 * launch clouds: white, some cuboids faintly rose or blue, a ragged blush along the foot of the sides and over
 * the underside, lighter texels of half a block, and a block's face shading. A shader pack lights its blocks
 * from where its sun is, so under one the sun's share of the light goes to the faces turned to it.
 */
vec3 puffColour(Puff puff, vec3 dir, vec3 lit) {
    vec3 lo = puff.lo;
    vec3 h = puff.h;
    bool level = lo.y >= max(lo.x, lo.z);
    bool rising = dir.y > 0.0;
    bool under = level && rising;
    float face = level ? (rising ? 0.74 : 1.0) : (lo.x >= lo.z ? 0.9 : 0.84);
    if (uSkyMatch > 0.5) {
        vec3 normal = level ? vec3(0.0, -sign(dir.y), 0.0)
                : (lo.x >= lo.z ? vec3(-sign(dir.x), 0.0, 0.0) : vec3(0.0, 0.0, -sign(dir.z)));
        lit += sunlight * (SUN_FACING * max(dot(normal, uLightDir), 0.0) - 1.0);
    }
    vec3 texel = floor(clamp(puff.at, puff.low + 0.001, puff.low + puff.size - 0.001) * 2.0);
    vec3 albedo = h.z < 0.62 ? CLOUD_WHITE : (h.z < 0.82 ? CLOUD_ROSE : CLOUD_BLUE);
    if (under) {
        albedo = mix(albedo, CLOUD_BLUSH, 0.7);
    } else if (!level) {
        // The blush is two or three texels high, by the column of texels.
        float along = lo.x >= lo.z ? texel.z : texel.x;
        float foot = hash3(vec3(along, h.xy * 40.0)).x < 0.5 ? 1.0 : 1.5;
        if (puff.at.y - puff.low.y < foot) albedo = mix(albedo, CLOUD_BLUSH, 0.55);
    }
    if (hash3(texel + h * 40.0).x < 0.75) albedo *= 0.955;
    // At night the cloud keeps a little light of its own, so its faces can still be told apart.
    return albedo * face * (lit + NIGHT_LIGHT * uGlow) + VIOLET * (under ? 0.05 : 0.0) * uGlow;
}

/**
 * The most a cuboid measures, by its own random, so that a clump is a mix of shapes: a cube of 6 to 8 blocks,
 * a small cube, a bar along x or along z, a slab, or a post. None is wider than its column.
 */
vec3 puffShape(vec3 r) {
    float side = 6.0 + 2.0 * r.y;
    if (r.x < 0.22) return vec3(side);
    if (r.x < 0.36) return vec3(4.0 + r.y);
    if (r.x < 0.56) return vec3(PUFF, 3.0 + 2.0 * r.y, 3.0 + 2.0 * r.z);
    if (r.x < 0.76) return vec3(3.0 + 2.0 * r.z, 3.0 + 2.0 * r.y, PUFF);
    if (r.x < 0.92) return vec3(side, 3.0 + r.z, 6.0 + 2.0 * r.z);
    return vec3(4.0 + r.z, PUFF, 4.0 + r.y);
}

/**
 * The cuboid in a column of lattice `set`, in that lattice's blocks; false where the column holds none. It is
 * larger the further the column's noise is past COVER, and the noise changes with time, so a cuboid
 * fades in, swells, shrinks and goes again. It grows from a place of its own inside its column, its foot on
 * the level its clump floats at or a block or two above.
 */
bool puffAt(vec2 cell, int set, out vec3 low, out vec3 size, out vec3 h, out float shows) {
    // The index wraps where the pattern repeats, so a cuboid keeps its look across the wrap.
    vec2 column = mod(cell, PERIOD / PUFF);
    vec2 p = ((column + 0.5) * PUFF + (set == 0 ? vec2(0.0) : PUFF_SHIFT.xz)) / PERIOD;
    float past = clumpCoverSteady(p) - COVER;
    if (past < 0.0) return false;
    shows = smoothstep(0.0, APPEAR, past);
    vec3 seed = vec3(column, 3.0 + 17.0 * float(set));
    h = hash3(seed);
    vec3 grow = hash3(seed + 60.0);
    vec3 place = hash3(seed + 91.0);
    size = puffShape(grow) * mix(PUFF_YOUNG, 1.0, clamp(past / SWELL, 0.0, 1.0));
    low.xz = cell * PUFF + (PUFF - size.xz) * place.xz;
    low.y = CELL * step(0.5, clumpLevel(p)) + floor(3.0 * place.y);
    return true;
}

/** A walk along the ray through the columns of one lattice, in the lattice's own blocks. */
struct Walk {
    int set;
    vec3 origin;
    vec3 dir;
    vec3 inv;
    vec2 cell;      // the column the walk is in
    vec2 next;      // where the ray crosses that column's next border, each way
    vec2 delta;
    vec2 stride;
    int left;       // columns the walk may still look at
};

/** The walk through lattice `set` that begins `from` blocks along the ray. */
Walk walkFrom(int set, vec3 dir, float from) {
    vec3 safe = mix(dir, vec3(1.0e-7), lessThan(abs(dir), vec3(1.0e-7)));
    Walk walk;
    walk.set = set;
    // y 0 is the foot of the lowest clumps, and the whole lattice drifts as vanilla clouds do.
    walk.origin = vec3(uNoiseOrigin.x + uCloudPhase * PERIOD, CELL - uLevels.y, uNoiseOrigin.y);
    if (set != 0) walk.origin -= PUFF_SHIFT;
    walk.dir = dir;
    walk.inv = 1.0 / safe;
    walk.stride = sign(safe.xz);
    vec2 start = (walk.origin.xz + safe.xz * from) / PUFF;
    walk.cell = floor(start);
    walk.next = from + (walk.cell + max(walk.stride, 0.0) - start) * PUFF * walk.inv.xz;
    walk.delta = abs(walk.inv.xz) * PUFF;
    walk.left = MAX_CELLS;
    return walk;
}

/**
 * Carries a walk on to the next cuboid the ray passes through before `to` and returns it; its t is INF where
 * there is none. A cuboid stays inside its column, so a lattice's cuboids are met in their order along the ray.
 */
Puff nextPuff(inout Walk walk, float to) {
    Puff puff = Puff(INF, vec3(0.0), 0.0, vec3(0.0), vec3(1.0), vec3(0.0), vec3(0.0));
    while (walk.left > 0) {
        vec2 cell = walk.cell;
        walk.left = min(walk.next.x, walk.next.y) < to ? walk.left - 1 : 0;
        if (walk.next.x < walk.next.y) {
            walk.next.x += walk.delta.x;
            walk.cell.x += walk.stride.x;
        } else {
            walk.next.y += walk.delta.y;
            walk.cell.y += walk.stride.y;
        }
        float enter;
        if (puffAt(cell, walk.set, puff.low, puff.size, puff.h, puff.shows)
                && hitBox(puff.low, puff.size, walk.origin, walk.inv, puff.lo, enter) && enter < to) {
            puff.t = max(enter, 0.0);
            puff.at = walk.origin + walk.dir * puff.t;
            return puff;
        }
    }
    return puff;
}

/**
 * Gathers the cloud along the ray between `from` and `to`. A clump is a few large cuboids that run into each
 * other, as the launch clouds are modelled: two lattices of 8-block columns, the second half a column across
 * and 2.5 blocks above the first, each column holding at most one cuboid, so a cuboid of one lattice sits
 * across the seams of the other. A cuboid is a little translucent and flat, as the cubes of the blocky smoke
 * plume are: it hides the same share of what is behind it however the ray passes through it, so where cuboids
 * overlap the cloud is denser, and three in a row are opaque. The two walks are merged front to back.
 */
void cloudSea(vec3 dir, float from, float to, vec3 lit) {
    Walk one = walkFrom(0, dir, from);
    Walk two = walkFrom(1, dir, from);
    Puff a = nextPuff(one, to);
    Puff b = nextPuff(two, to);
    for (int i = 0; i < MAX_PUFFS; i++) {
        bool second = b.t < a.t;
        Puff puff = a;
        if (second) puff = b;
        float t = puff.t;
        if (t >= to) return;
        // A cuboid fades out just in front of the camera instead of filling the view with one face.
        float alpha = PUFF_OPACITY * puff.shows * smoothstep(CLEAR, CLEAR_FAR, t) * (1.0 - smoothstep(CELLS_NEAR, CELLS_FAR, t));
        gathered += through * alpha * puffColour(puff, dir, lit);
        through *= 1.0 - alpha;
        if (first < 0.0 && alpha > 0.0) first = t;
        if (through < 0.03) return;
        if (second) b = nextPuff(two, to);
        else a = nextPuff(one, to);
    }
}

/** The noise value of one square of a layer of fallen islands; land where it is above ISLE_COVER. */
float isle(vec2 xz, int layer) {
    float size = ISLE_CELL[layer];
    return isleField((floor(xz / size) + 0.5) * size / PERIOD, layer);
}

/**
 * The few islands that fell, t along the ray where it leaves the box: xyz is an island's colour and w how
 * much of it shows, 0 where the ray meets none. They lie in layers ever further down, so they slide against
 * each other as the camera moves, and a deeper layer shows less through the dark, the last one barely. A layer
 * is land where its noise is high, which is seldom; the same noise read further down the ray with a higher
 * threshold is the island's darker side, so it narrows downward.
 */
vec4 fallenIsles(vec3 dir, float t, vec3 lit) {
    float slope = -1.0 / dir.y;
    vec2 at = dir.xz * t + uNoiseOrigin;
    vec2 run = dir.xz * slope;
    for (int k = 0; k < ISLES; k++) {
        float depth = ISLE_DEPTH[k];
        float shows = ISLE_SHOWS[k] * exp(-MURK * depth * slope);
        // Daylight reaches less far down than the eye does.
        vec3 stone = STONE * (lit * 0.5 * exp(-depth / 200.0) + 0.06);
        float land = isle(at + run * depth, k);
        if (land >= ISLE_COVER) return vec4(stone * (land >= ISLE_COVER + 0.03 ? 1.0 : 0.82), shows);
        if (isle(at + run * depth * (1.0 + ISLE_THICK), k) >= ISLE_COVER + 0.04) return vec4(stone * 0.5, shows);
    }
    return vec4(0.0);
}

/** A cloud's density for noise value n, none below `from` and all 0.3 above, in the steps of pixel art. */
float nebulaCloud(float n, float from) {
    return floor(smoothstep(from, from + 0.3, n) * NEBULA_SHADES + 0.5) / NEBULA_SHADES;
}

/**
 * The void where the ray leaves the box through its floor, t along the ray: dark, and in a few places a
 * nebula. Where one is comes from a slow noise field that changes with time, so nebulae are rare and fade in
 * and out, and everything about them drifts slowly across the void as one. A second field gives the place its
 * shades, so no two look alike and one changes shade across its width. Inside, two clouds of four noise octaves bent
 * by more noise, a third colour along bright filaments, and stars, all in square pixels and a few steps of
 * brightness. It is looked up far below the plane, so it barely moves as the camera does and dims at a slant.
 * A rare square outside the nebulae holds the glint of a crystal. In front of all of it lie a few fallen islands.
 */
vec3 depths(vec3 dir, float t, vec3 lit) {
    float slope = -1.0 / dir.y;         // blocks of ray per block of descent
    vec2 still = dir.xz * (t + slope * NEBULA_DEPTH) + uNoiseOrigin;
    float seen = exp(-MURK * NEBULA_DEPTH * slope) * (0.55 + 0.45 * uGlow);
    vec3 colour = DARK;
    // The squares drift with the nebulae, so a nebula slides as a whole and nothing in it crawls or flickers.
    vec2 at = still + uPhase * PERIOD * vec2(0.0, 1.0);
    vec2 pixel = floor(at / NEBULA_CELL);
    vec2 centre = (pixel + 0.5) * NEBULA_CELL / PERIOD;
    float nebula = smoothstep(NEBULA_RARE, NEBULA_RARE + 0.12, noise(vec3(centre, uPhase)));
    if (nebula > 0.0) {
        float tone = (noise(vec3(centre * 2.0 + 0.19, 0.45)) - 0.3) / 0.4;
        float turn = 6.2832 * noise(vec3(centre * 2.0, 0.77));
        vec2 bent = centre + NEBULA_WARP * vec2(cos(turn), sin(turn));
        float one = nebulaCloud(fbm(vec3(bent * 2.0, 0.21), 4), 0.42);
        float two = nebulaCloud(fbm(vec3(bent * 3.0 + 0.43, 0.57), 4), 0.47);
        float thread = 1.0 - abs(2.0 * noise(vec3(bent * 8.0, 0.89)) - 1.0);
        thread = step(0.86, thread) * max(one, two);
        vec3 glow = nebulaHue(tone) * 0.55 * one + nebulaHue(fract(tone + 0.3)) * 0.45 * two;
        glow = mix(glow, mix(nebulaHue(fract(tone + 0.6)), vec3(0.86, 0.82, 1.0), 0.4) * 0.75, thread * 0.8);
        // A star fills its square and is brighter inside the clouds.
        vec3 random = hash3(vec3(mod(pixel, PERIOD / NEBULA_CELL), 2.0));
        if (random.x < NEBULA_STARS) {
            float twinkle = 0.6 + 0.4 * sin(6.2832 * (uPhase * 64.0 + random.y));
            glow += vec3(0.74, 0.72, 0.98) * twinkle * (0.25 + 0.75 * max(one, two));
        }
        colour += glow * nebula * NEBULA_STRENGTH * seen;
    }
    vec2 square = floor(still / CRYSTAL_CELL);
    if (textureLod(uNoise, vec3((square + 0.5) / NOISE_SIZE, 9.5 / NOISE_SIZE), 0.0).r < CRYSTAL_SHARE) {
        float pulse = 0.6 + 0.4 * sin(6.2832 * (uPhase * 160.0 + hash3(vec3(square, 1.0)).x));
        colour += vec3(0.62, 0.42, 0.95) * 0.5 * pulse * seen * (1.0 - nebula);
    }
    // The islands are above all of that and fade into it.
    vec4 fallen = fallenIsles(dir, t, lit);
    return mix(colour, fallen.rgb, fallen.a);
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
    vec3 pale;
    vec3 dusk;
    vec3 lit = cloudLight(pale, dusk);

    // The cloud, within the slab of heights that can hold it.
    if (abs(dir.y) > 1.0e-4) {
        float a = (uLevels.y - CELL) / dir.y;
        float b = (uLevels.y + float(LAYERS) * CELL) / dir.y;
        float from = max(min(a, b), span.x);
        // No cloud shows beyond CELLS_FAR, so the lattices are not walked there.
        float to = min(min(max(a, b), end), CELLS_FAR);
        if (to > from) cloudSea(dir, from, to, lit);
    }
    // The haze in front of the cloud is laid over it; without cloud, all of the haze is in front.
    float front = first < 0.0 ? end : first;

    // Through the cloud show the haze behind it and, where the ray leaves the box through its floor before
    // meeting terrain, the void.
    if (through >= 0.03) {
        vec4 behind = vec4(0.0);
        float floorAt = dir.y < 0.0 ? uLevels.x / dir.y : INF;
        if (floorAt > 0.0 && floorAt <= end * 1.0001 + 0.01) {
            behind = vec4(depths(dir, floorAt, lit), 1.0);
            if (first < 0.0) first = floorAt;
        }
        if (front < end) {
            vec4 beyond = hazeAlong(dir, front, end, pale, dusk);
            behind = vec4(beyond.rgb * beyond.a + behind.rgb * (1.0 - beyond.a), 1.0 - (1.0 - behind.a) * (1.0 - beyond.a));
        }
        gathered += through * behind.rgb;
        through *= 1.0 - behind.a;
    }

    // Towards the horizon the haze is all there is, and the void is seen through a veil of it.
    vec4 haze = hazeAlong(dir, span.x, front, pale, dusk);
    vec3 colour = haze.rgb * haze.a + gathered * (1.0 - haze.a);
    float alpha = 1.0 - through * (1.0 - haze.a);
    if (alpha < 0.004) return;
    if (first < 0.0) first = hazeFront(dir, span.x, end);
    fragColor = finished(vec4(colour, alpha), first, uv, ndc, dir);
}
