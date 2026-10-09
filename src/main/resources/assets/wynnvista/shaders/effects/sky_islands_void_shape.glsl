// Shared by both styles of the Sky Islands void: where the cloud clumps, the nebulae and the fallen islands
// are, the haze among the clouds, the light and the colours. Include after scene.glsl.

uniform vec3 uLevels;           // x: where the void begins, y: the top of the lowest layer of cloud, both camera-relative
uniform vec2 uNoiseOrigin;      // the camera's place within one repeat of the noise pattern, in blocks
uniform float uPhase;           // 0..1 over the time after which the moving patterns repeat
uniform float uCloudPhase;      // the same for the cloud, which moves and changes more slowly, over a longer time
uniform vec3 uLightDir;         // unit direction towards the sun or moon
uniform vec3 uLightColor;
uniform vec3 uAmbient;
uniform float uGlow;            // 1 at night, less by day

const float PERIOD = 2048.0;            // blocks per repeat of the noise; one of its cells is 64 blocks at scale 1

const float CELL = 4.0;                 // blocks between the two levels a clump can float at
const int LAYERS = 4;                   // CELLs above the top of the lowest that cloud can reach
const float COVER = 0.60;               // noise above which a place holds cloud; higher is fewer and smaller clumps
const float CRUMB_MARGIN = 0.035;       // noise this far short of that still holds the thin rim of a soft cloud
const float CHURN = 8.0;                // noise repeats the clumps change through per cycle of uCloudPhase; a whole number
const float GATHER = 4.0;               // and the groups of clumps; a whole number
const float CELLS_NEAR = 220.0;         // blocks from the camera at which the clumps start to give way to the haze
const float CELLS_FAR = 520.0;          // and where they are gone

const float HAZE_HEIGHT = 8.0;          // blocks above the cloud over which the haze thins to 1/e
const float HAZE_SINK = 10.0;           // and below it, where it takes the colour of the depths
const float HAZE_DENSITY = 0.011;       // extinction per block at its level
const float HAZE_MAX = 0.95;
const float EDGE = 96.0;                // blocks from the sides of the box over which everything fades out

const float NEBULA_DEPTH = 220.0;       // how far below where the void begins its nebulae appear to lie
const float NEBULA_CELL = 4.0;          // side of one square that may hold a star, at that depth
const float NEBULA_RARE = 0.52;        // noise above which a place holds a nebula; higher is fewer
const float NEBULA_WARP = 0.02;         // how far a nebula's pattern is bent, in noise repeats
const float NEBULA_STRENGTH = 0.9;      // its brightness at night, before the dark in front of it
const float MURK = 0.0035;              // extinction per block of that dark
const int ISLES = 3;                    // layers of fallen islands
const float ISLE_DEPTH[ISLES] = float[](60.0, 130.0, 210.0);    // blocks below where the void begins, all above the nebulae
const float ISLE_SHOWS[ISLES] = float[](1.0, 0.7, 0.45);        // how much of a layer shows, before the dark in front of it
const float ISLE_COVER = 0.70;          // noise above which a layer holds land; higher is fewer islands
const float ISLE_THICK = 0.3;           // an island's thickness as a share of its layer's depth
const float CRYSTAL_CELL = 4.0;         // side of one square that may hold a crystal's glint
const float CRYSTAL_SHARE = 0.004;      // share of them that do

const vec3 VIOLET = vec3(0.46, 0.17, 0.95);
const vec3 DARK = vec3(0.012, 0.009, 0.024);
const vec3 STONE = vec3(0.30, 0.29, 0.37);
// The cloud, after the launch clouds: white, some of it faintly rose or blue, and a blush along its foot.
const vec3 CLOUD_WHITE = vec3(0.97, 0.97, 0.99);
const vec3 CLOUD_ROSE = vec3(0.98, 0.91, 0.95);
const vec3 CLOUD_BLUE = vec3(0.89, 0.94, 1.0);
const vec3 CLOUD_BLUSH = vec3(0.96, 0.80, 0.90);
const vec3 NIGHT_LIGHT = vec3(0.11, 0.10, 0.17);    // light of its own the cloud keeps at night, times uGlow

vec3 sunlight = vec3(0.0);      // the sun's or moon's share of the light the cloud is lit by

vec3 hash3(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}

/**
 * How much cloud stands at p, a place in noise repeats: cloud where it is above COVER, and taller the further
 * past. It has peaks about 16 blocks apart, each a clump, and a slow part that gathers the clumps into groups.
 * Both change with time, the clumps faster than the groups, so the cloud swells, thins and forms anew.
 */
float clumpCover(vec2 p) {
    return 0.62 * noise(vec3(p * 4.0, 0.37 + CHURN * uCloudPhase)) + 0.38 * noise(vec3(p, 0.71 + GATHER * uCloudPhase));
}

/**
 * noise() at xy and z, with the blend between the two slices of z done here. The texture filter blends in 256
 * steps from one texel to the next, which is not seen across space but jerks whatever follows one value over
 * time, such as the size of a cuboid. Two lookups.
 */
float noiseOver(vec2 xy, float z) {
    vec2 q = xy * NOISE_SIZE;
    vec2 f = fract(q);
    vec2 uv = (floor(q) + f * f * (3.0 - 2.0 * f) + 0.5) / NOISE_SIZE;
    z *= NOISE_SIZE;
    float slice = floor(z);
    float a = textureLod(uNoise, vec3(uv, (slice + 0.5) / NOISE_SIZE), 0.0).r;
    float b = textureLod(uNoise, vec3(uv, (slice + 1.5) / NOISE_SIZE), 0.0).r;
    return mix(a, b, z - slice);
}

/** clumpCover for what must change evenly with time; it differs from it by less than the filter's steps and a little in its timing. */
float clumpCoverSteady(vec2 p) {
    return 0.62 * noiseOver(p * 4.0, 0.37 + CHURN * uCloudPhase) + 0.38 * noiseOver(p, 0.71 + GATHER * uCloudPhase);
}

/** Above 0.5 where the clump at p begins one layer higher, so that clumps float at two heights. */
float clumpLevel(vec2 p) {
    return noise(vec3(p * 2.0, 0.13));
}

/** The noise value of a layer of fallen islands at p, a place in noise repeats; land where it is above ISLE_COVER. */
float isleField(vec2 p, int layer) {
    float seed = 0.11 + 0.19 * float(layer);
    return 0.7 * noise(vec3(p * 2.0, seed)) + 0.3 * noise(vec3(p * 6.0, seed + 0.31));
}

/** The colours a nebula can have, all near each other: purple, violet, indigo and two blues, for t in 0..1. */
vec3 nebulaHue(float t) {
    const vec3 HUES[5] = vec3[](vec3(0.40, 0.13, 0.78), vec3(0.50, 0.20, 0.95), vec3(0.27, 0.19, 0.88),
            vec3(0.16, 0.30, 0.92), vec3(0.26, 0.46, 0.95));
    float x = clamp(t, 0.0, 0.999) * 4.0;
    int i = int(x);
    return mix(HUES[i], HUES[i + 1], x - float(i));
}

/**
 * The light the cloud is lit by: the sky and the sun or moon by day, little at night. Also the colour of the
 * haze at and above its level, and the dim violet it has below.
 */
vec3 cloudLight(out vec3 pale, out vec3 dusk) {
    // The cloud lies below the camera: it is lit by the sky at the horizon.
    vec3 ambient = skyLight(uAmbient, uLightColor, 0.0);
    sunlight = sunLight * 0.35;
    vec3 lit = ambient * 0.9 + sunlight;
    pale = vec3(0.92, 0.91, 0.96) * lit + VIOLET * (0.01 + 0.035 * uGlow);
    dusk = pale * vec3(0.42, 0.36, 0.70) + VIOLET * (0.03 + 0.07 * uGlow);
    return lit;
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

/**
 * The haze along the ray between `from` and `to`: its colour, and in w how much of what is behind it hides.
 * What lies below its level is dim.
 */
vec4 hazeAlong(vec3 dir, float from, float to, vec3 pale, vec3 dusk) {
    float level = uLevels.y + CELL;
    float start = dir.y * from - level;
    float stop = dir.y * to - level;
    float high = max(start, stop);
    float low = min(start, stop);
    float above = high - low > 1.0e-3 ? (max(high, 0.0) - max(low, 0.0)) / (high - low) : (low > 0.0 ? 1.0 : 0.0);
    float reach = to - from;
    float pall = hazeDepth(max(low, 0.0), max(high, 0.0), reach * above);
    float veil = hazeDepth(min(low, 0.0), min(high, 0.0), reach * (1.0 - above));
    float haze = min(1.0 - exp(-pall - veil), HAZE_MAX);
    return vec4((pale * 0.94 * pall + dusk * 0.6 * veil) / max(pall + veil, 1.0e-6), haze);
}

/** Where a ray that met nothing but haze is taken to have met it: at the haze's level. */
float hazeFront(vec3 dir, float from, float to) {
    return abs(dir.y) > 1.0e-4 ? clamp((uLevels.y + CELL) / dir.y, from, to) : from;
}

/**
 * The finished pixel: `whole` is the premultiplied colour and opacity of everything the ray met, the first of
 * it `first` blocks along the ray. Aerial perspective, a shader pack's fog and any cloud between the camera
 * and the void are put on it, and towards the sides of its box the effect fades out, so it has no edge where
 * no land hides one.
 */
vec4 finished(vec4 whole, float first, vec2 uv, vec2 ndc, vec3 dir) {
    vec4 cloud;
    float cloudAt = cloudDistance(uv, ndc, dir, cloud);
    float distant = 1.0 - exp(-first * 0.00022);
    vec4 result = underClouds(whole, cloudAt < first ? vec4(0.0) : whole, distant, cloud, dir * first);
    vec2 inside = min(dir.xz * first - uBoxMin.xz, uBoxMax.xz - dir.xz * first);
    return result * smoothstep(0.0, EDGE, min(inside.x, inside.y));
}
