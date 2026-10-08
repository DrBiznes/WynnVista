#version 330 core

// The void under the Sky Islands: a sea of blocky cloud in the manner of Minecraft's own clouds, dimmer
// sheets of it sunk below, a thin haze around them, and far below a dark abyss holding a nebula. Nothing is
// marched: the cloud is a few flat sheets of square cells met by the view ray, and the haze is worked out
// exactly along it.

#include "scene.glsl"

uniform vec3 uLevels;           // x: the abyss, y: the lowest terrace of cloud, both camera-relative
uniform vec2 uNoiseOrigin;      // the camera's place within one repeat of the noise pattern, in blocks
uniform float uPhase;           // 0..1 over the time after which the moving patterns repeat
uniform vec3 uLightColor;
uniform vec3 uAmbient;
uniform float uGlow;            // 1 at night, less by day

layout(location = 0) out vec4 fragColor;       // premultiplied colour and opacity
layout(location = 1) out float fragDistance;   // terrain distance, for the half-resolution path

const float PERIOD = 2048.0;            // blocks per repeat of the noise; one of its cells is 64 blocks at scale 1

const int SHEETS = 5;                   // cloud sheets: sunken ones first, then terraces, each standing on the one below
const int SUNKEN = 2;                   // how many of them lie below the terraces
const float SHEET_GAP = 4.0;            // blocks between terraces
const float SUNKEN_GAP = 12.0;          // blocks between sunken sheets, and from the upper one to the lowest terrace
const float CELL = 4.0;                 // side of a terrace's cloud cell
const float SUNKEN_CELL = 8.0;          // and of a sunken sheet's
const float SUNKEN_COVER = 0.5;         // noise above which a sunken sheet has a cell
const float COVER = 0.47;               // noise above which the lowest sheet has a cell; lower is more cloud
const float COVER_STEP = 0.075;         // how much higher that is for each sheet above
const float CLOUD_ALPHA = 0.82;         // opacity of one sheet, as of a vanilla cloud
const float CELLS_NEAR = 220.0;         // blocks from the camera at which the cells start to give way to the haze
const float CELLS_FAR = 520.0;          // and where they are gone: far cells are smaller than a pixel

const float HAZE_HEIGHT = 8.0;          // blocks above the cloud over which the haze thins to 1/e
const float HAZE_SINK = 24.0;           // and below it, where it takes the abyss's colour
const float HAZE_DENSITY = 0.011;       // extinction per block at its level
const float HAZE_MAX = 0.95;
const float EDGE = 96.0;               // blocks from the sides of the box over which everything fades out

const float NEBULA_DEPTH = 220.0;       // how far below the abyss plane its nebula appears to lie
const float NEBULA_CELL = 8.0;          // side of one square of the nebula, at its depth
const float NEBULA_WARP = 0.02;         // how far its pattern is bent, in noise repeats
const float NEBULA_STRENGTH = 0.62;     // its brightness at night
const float STAR_SHARE = 0.045;         // share of those squares that hold a star

const vec3 VIOLET = vec3(0.46, 0.17, 0.95);

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
 * Whether sheet `sheet` (0 is the deepest) holds cloud over `rel`, a camera-relative position. One noise value
 * per cell; a higher terrace needs a higher value, so it stands on the one below. The terraces drift together
 * as vanilla clouds do, the sunken sheets their own ways.
 */
float cloudCell(vec2 rel, int sheet) {
    bool sunken = sheet < SUNKEN;
    float size = sunken ? SUNKEN_CELL : CELL;
    vec2 drift = sunken ? vec2(0.0, sheet == 0 ? 0.0 : uPhase * PERIOD) : vec2(uPhase * PERIOD, 0.0);
    vec2 p = (floor((rel + uNoiseOrigin + drift) / size) + 0.5) * size / PERIOD;
    if (sunken) {
        float seed = 0.13 + 0.4 * float(sheet);
        return step(SUNKEN_COVER, 0.65 * noise(vec3(p * 2.0, seed)) + 0.35 * noise(vec3(p * 4.0, seed + 0.27)));
    }
    float n = 0.65 * noise(vec3(p * 2.0, 0.37)) + 0.35 * noise(vec3(p * 4.0, 0.71));
    return step(COVER + COVER_STEP * float(sheet - SUNKEN), n);
}

/** How much of one nebula cloud there is for noise value n: none below `from`, all of it 0.3 above. */
float nebulaCloud(float n, float from) {
    return smoothstep(from, from + 0.3, n);
}

/**
 * The abyss where the ray meets its plane, t along the ray: almost black, with a nebula and stars. Both are
 * looked up far below the plane, so they barely move as the camera does. The nebula is built the way the
 * night nebula of Complementary Reimagined is: clouds of three colours from noise bent by more noise, each
 * drifting its own way with a slowly changing threshold, in square pixels, and brighter stars inside them.
 */
vec3 abyss(vec3 dir, float t) {
    vec2 xz = dir.xz * t * (uLevels.x - NEBULA_DEPTH) / uLevels.x + uNoiseOrigin;
    vec2 pixel = floor(xz / NEBULA_CELL);
    vec2 centre = (pixel + 0.5) * NEBULA_CELL / PERIOD;
    float turn = 6.2832 * noise(vec3(centre, uPhase));
    vec2 bent = centre + NEBULA_WARP * vec2(cos(turn), sin(turn));
    float tide = 0.04 * sin(6.2832 * uPhase * 8.0);
    float violet = nebulaCloud(fbm(vec3(bent * 2.0 + uPhase * vec2(1.0, 0.0), 0.21), 3), 0.42 + tide);
    float blue = nebulaCloud(fbm(vec3(bent + uPhase * vec2(0.0, -1.0) + 0.43, 0.57), 3), 0.48 - tide);
    float rose = nebulaCloud(fbm(vec3(bent * 3.0 + uPhase * vec2(-1.0, 1.0) + 0.81, 0.89), 3), 0.56);
    vec3 nebula = VIOLET * 0.55 * violet + vec3(0.12, 0.30, 0.85) * 0.45 * blue;
    nebula = mix(nebula, vec3(0.90, 0.38, 0.85) * 0.6, rose * rose * 0.7);

    // A star fills its square. Inside the nebula it is brighter; far away it would be smaller than a pixel.
    float chosen = textureLod(uNoise, vec3((pixel + 0.5) / NOISE_SIZE, 9.5 / NOISE_SIZE), 0.0).r;
    if (chosen < STAR_SHARE) {
        float twinkle = 0.6 + 0.4 * sin(6.2832 * (uPhase * 160.0 + chosen / STAR_SHARE));
        float star = twinkle * (0.35 + 1.3 * max(violet, blue)) * (1.0 - smoothstep(250.0, 550.0, t));
        nebula += vec3(0.62, 0.52, 0.88) * star;
    }
    return vec3(0.014, 0.011, 0.028) + nebula * NEBULA_STRENGTH * (0.55 + 0.45 * uGlow);
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

    // By day the cloud is lit by the sky and sun; the abyss lends it a trace of violet, more at night. Cloud
    // that lies deeper is dimmer and takes more of that colour, so the sea goes over into the abyss.
    vec3 ambient = skyLight(uAmbient);
    vec3 pale = vec3(0.92, 0.91, 0.96) * (ambient * 0.9 + uLightColor * sunTint * 0.35) * skyGain
            + VIOLET * (0.01 + 0.035 * uGlow);
    vec3 dusk = pale * vec3(0.42, 0.36, 0.70) + VIOLET * (0.03 + 0.07 * uGlow);

    // The sheets, nearest first.
    vec3 colour = vec3(0.0);
    float through = 1.0;
    float first = -1.0;
    if (abs(dir.y) > 1.0e-4) {
        for (int i = 0; i < SHEETS; i++) {
            int sheet = dir.y < 0.0 ? SHEETS - 1 - i : i;
            float height = sheet < SUNKEN ? -SUNKEN_GAP * float(SUNKEN - sheet) : SHEET_GAP * float(sheet - SUNKEN);
            float t = (uLevels.y + height) / dir.y;
            if (t <= span.x || t >= end || t >= CELLS_FAR) continue;
            if (cloudCell(dir.xz * t, sheet) <= 0.0) continue;
            vec3 tint;
            float alpha;
            if (sheet < SUNKEN) {
                tint = mix(pale, dusk, sheet == 0 ? 0.85 : 0.55);
                alpha = sheet == 0 ? 0.5 : 0.62;
            } else {
                // Seen from above a higher terrace is brighter, as a block's top is.
                tint = mix(pale, dusk, sheet == SUNKEN ? 0.18 : 0.0) * (1.0 - 0.07 * float(SHEETS - 1 - sheet));
                alpha = CLOUD_ALPHA;
            }
            // From below every sheet is in shade.
            if (dir.y > 0.0) tint *= 0.72;
            alpha *= 1.0 - smoothstep(CELLS_NEAR, CELLS_FAR, t);
            colour += through * alpha * tint;
            through *= 1.0 - alpha;
            if (first < 0.0) first = t;
        }
    }

    // The abyss is the floor of the box: it is seen where the ray leaves through it before meeting terrain.
    float floorAt = dir.y < 0.0 ? uLevels.x / dir.y : INF;
    if (floorAt > 0.0 && floorAt <= end * 1.0001 + 0.01) {
        colour += through * abyss(dir, floorAt);
        through = 0.0;
        if (first < 0.0) first = floorAt;
    }

    // The haze lies around the middle terrace and is laid over the rest: towards the horizon it is all there
    // is. What lies below its level has the abyss's colour, so the nebula is seen through a veil of it.
    float level = uLevels.y + SHEET_GAP;
    float start = dir.y * span.x - level;
    float stop = dir.y * end - level;
    float high = max(start, stop);
    float low = min(start, stop);
    float above = high - low > 1.0e-3 ? (max(high, 0.0) - max(low, 0.0)) / (high - low) : (low > 0.0 ? 1.0 : 0.0);
    float reach = end - span.x;
    float pall = hazeDepth(max(low, 0.0), max(high, 0.0), reach * above);
    float veil = hazeDepth(min(low, 0.0), min(high, 0.0), reach * (1.0 - above));
    float haze = min(1.0 - exp(-pall - veil), HAZE_MAX);
    vec3 hazeColour = (pale * 0.94 * pall + mix(pale, dusk, 0.7) * veil) / max(pall + veil, 1.0e-6);
    colour = hazeColour * haze + colour * (1.0 - haze);
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
