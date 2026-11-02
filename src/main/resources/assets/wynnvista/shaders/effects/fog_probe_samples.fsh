#version 330 core

// The fog probe's samples of the world image, one per pixel so that the GPU reads them all at once instead of
// a few threads reading them one after another; fog_probe.fsh adds them up (layout in fog_probe_layout.glsl).
//
// Sky rows: the colour of the world image at one heading across the searched columns and one elevation, where
// that is sky, with alpha 0. Measurement rows: where all three of a pixel and the pixels a little to its right
// and above it are terrain at the effect's distance, the pixel's colour and in alpha the fourth power of its
// contrast with the other two. Nothing is measured while a FogModel knows the fog (mode 1).

#include "scene.glsl"

#include "fog_probe_layout.glsl"

uniform sampler2D uSceneColor;
uniform vec2 uColumns;        // horizontal part of the view to search, 0..1
uniform vec2 uBand;           // terrain distances that say something about the effect's distance
uniform int uProbeMode;       // 0: measure the fog, 1: a model knows it

layout(location = 0) out vec4 fragColor;

const vec4 NONE = vec4(-1.0);

bool inBand(vec2 uv) {
    float d = sceneDistance(uv, uv * 2.0 - 1.0);
    return d >= uBand.x && d <= uBand.y;
}

/** Difference of two colours relative to their brightness, so a dark night scene counts like a bright one. */
float contrast(vec3 a, vec3 b) {
    vec3 difference = abs(a - b);
    vec3 brightest = max(a, b);
    return max(difference.r, max(difference.g, difference.b))
            / (max(brightest.r, max(brightest.g, brightest.b)) + 0.04);
}

/** The sky at column x of the searched columns, at elevation e of the given band. */
vec4 skySample(int x, int band, int e) {
    float ndcX = mix(uColumns.x, uColumns.y, (float(x) + 0.5) / float(COLUMNS)) * 2.0 - 1.0;
    vec2 heading = viewRay(vec2(ndcX, 0.0)).xz;
    if (dot(heading, heading) < 1.0e-4) return NONE;
    heading = normalize(heading);
    float up = (float(band) + (float(e) + 0.5) / float(ELEVATIONS)) / float(SKY_BANDS);
    vec4 clip = uSceneForward * vec4(vec3(heading.x, 0.0, heading.y) * sqrt(1.0 - up * up) + vec3(0.0, up, 0.0), 0.0);
    if (clip.w <= 0.0) return NONE;
    vec2 ndc = clip.xy / clip.w;
    if (abs(ndc.x) >= 1.0 || abs(ndc.y) >= 1.0) return NONE;
    vec2 uv = ndc * 0.5 + 0.5;
    // Terrain standing above the horizon is not sky.
    if (sceneDistance(uv, ndc) < 0.5 * INF) return NONE;
    return vec4(texture(uSceneColor, uv).rgb, 0.0);
}

/** The terrain detail at column x and row y of the measurement's grid. */
vec4 measureSample(int x, int y) {
    if (uProbeMode == 1) return NONE;
    vec2 size = vec2(textureSize(uSceneColor, 0));
    // Far enough apart to step over anti-aliasing, near enough to stay on the same hillside.
    vec2 offset = max(3.0, size.y / 180.0) / size;
    vec2 uv = vec2(mix(uColumns.x, uColumns.y, (float(x) + 0.5) / float(COLUMNS)), (float(y) + 0.5) / float(ROWS));
    vec2 right = uv + vec2(offset.x, 0.0);
    vec2 up = uv + vec2(0.0, offset.y);
    // All three on terrain at the right distance: a skyline is not terrain detail.
    if (!inBand(uv) || !inBand(right) || !inBand(up)) return NONE;
    vec3 here = texture(uSceneColor, uv).rgb;
    float c = max(contrast(here, texture(uSceneColor, right).rgb), contrast(here, texture(uSceneColor, up).rgb));
    // Fourth powers: the result follows the strongest detail, so water, snow and clouds, which are flat in any
    // weather, do not read as fog.
    return vec4(here, c * c * c * c);
}

void main() {
    int x = int(gl_FragCoord.x);
    int row = int(gl_FragCoord.y);
    fragColor = row < SKY_ROWS ? skySample(x, row / ELEVATIONS, row % ELEVATIONS) : measureSample(x, row - SKY_ROWS);
}
