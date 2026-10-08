#version 330 core

// Reads what the fog in the world image looks like, into a 1x1 target that is smoothed over time.
//
// Mode 0, when nothing is known about the fog (an unrecognised shader pack, WynnIris' post-process fog, no LOD
// fog model): measures it at an effect's distance. Terrain that fog has swallowed has lost its detail and has
// the fog's colour. Effects apply the result with throughFog().
//
// Mode 1, when a FogModel says how much fog there is: only the fog's colour is wanted, and it is taken from
// the sky just above the horizon in the effect's direction, which is what distant terrain fades into.

#include "scene.glsl"

uniform sampler2D uSceneColor;
uniform sampler2D uPrevious;  // last frame's result
uniform vec2 uColumns;        // horizontal part of the view to search, 0..1
uniform vec2 uBand;           // terrain distances that say something about the effect's distance
uniform vec2 uContrast;       // terrain detail at which it counts as swallowed, and as fully visible
uniform float uRate;          // share of this frame's measurement blended in; 1 replaces the old value
uniform int uProbeMode;       // 0: terrain detail and colour, 1: colour of the sky at the horizon

out vec4 fragColor;           // mode 0: rgb colour of terrain at the effect's distance, a: how much detail it keeps
                              // mode 1: rgb colour of the horizon sky, negative while none was seen, a: 1

const int COLUMNS = 32;
const int ROWS = 24;
const float ENOUGH = 8.0;     // terrain samples for a full-weight measurement

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

const int ELEVATIONS = 8;
const float HIGHEST = 0.1;    // sine of the highest elevation sampled, about 6 degrees

/** Average colour of the sky just above the horizon across the searched columns. */
void horizon() {
    vec3 colour = vec3(0.0);
    float count = 0.0;
    for (int x = 0; x < COLUMNS; x++) {
        float ndcX = mix(uColumns.x, uColumns.y, (float(x) + 0.5) / float(COLUMNS)) * 2.0 - 1.0;
        vec2 heading = viewRay(vec2(ndcX, 0.0)).xz;
        if (dot(heading, heading) < 1.0e-4) continue;
        heading = normalize(heading);
        for (int e = 0; e < ELEVATIONS; e++) {
            float up = (float(e) + 0.5) / float(ELEVATIONS) * HIGHEST;
            vec4 clip = uSceneForward * vec4(vec3(heading.x, 0.0, heading.y) * sqrt(1.0 - up * up) + vec3(0.0, up, 0.0), 0.0);
            if (clip.w <= 0.0) continue;
            vec2 ndc = clip.xy / clip.w;
            if (abs(ndc.x) >= 1.0 || abs(ndc.y) >= 1.0) continue;
            vec2 uv = ndc * 0.5 + 0.5;
            // Terrain standing above the horizon is not sky.
            if (sceneDistance(uv, ndc) < 0.5 * INF) continue;
            colour += texture(uSceneColor, uv).rgb;
            count += 1.0;
        }
    }
    vec4 previous = texelFetch(uPrevious, ivec2(0), 0);
    bool fresh = uRate >= 1.0 || previous.r < 0.0;
    if (count < 0.5) {
        fragColor = uRate >= 1.0 ? vec4(-1.0, -1.0, -1.0, 1.0) : vec4(previous.rgb, 1.0);
        return;
    }
    fragColor = vec4(fresh ? colour / count : mix(previous.rgb, colour / count, uRate), 1.0);
}

void main() {
    if (uProbeMode == 1) {
        horizon();
        return;
    }
    vec2 size = vec2(textureSize(uSceneColor, 0));
    // Far enough apart to step over anti-aliasing, near enough to stay on the same hillside.
    vec2 offset = max(3.0, size.y / 180.0) / size;
    vec3 colour = vec3(0.0);
    float detail = 0.0;
    float count = 0.0;
    for (int x = 0; x < COLUMNS; x++) {
        for (int y = 0; y < ROWS; y++) {
            vec2 uv = vec2(mix(uColumns.x, uColumns.y, (float(x) + 0.5) / float(COLUMNS)),
                    (float(y) + 0.5) / float(ROWS));
            vec2 right = uv + vec2(offset.x, 0.0);
            vec2 up = uv + vec2(0.0, offset.y);
            // All three on terrain at the right distance: a skyline is not terrain detail.
            if (!inBand(uv) || !inBand(right) || !inBand(up)) continue;
            vec3 here = texture(uSceneColor, uv).rgb;
            float c = max(contrast(here, texture(uSceneColor, right).rgb),
                    contrast(here, texture(uSceneColor, up).rgb));
            // Fourth powers: the result follows the strongest detail, so water, snow and clouds, which
            // are flat in any weather, do not read as fog.
            detail += c * c * c * c;
            colour += here;
            count += 1.0;
        }
    }
    vec4 previous = texelFetch(uPrevious, ivec2(0), 0);
    // A value left by the other mode may hold no colour.
    previous.rgb = max(previous.rgb, 0.0);
    // Without terrain at the right distance the previous value stands (or, on a fresh start, "clear").
    if (count < 0.5) {
        fragColor = uRate >= 1.0 ? vec4(0.0, 0.0, 0.0, 1.0) : previous;
        return;
    }
    float visible = clamp((sqrt(sqrt(detail / count)) - uContrast.x) / (uContrast.y - uContrast.x), 0.0, 1.0);
    vec4 measured = vec4(colour / count, visible);
    fragColor = mix(previous, measured, uRate >= 1.0 ? 1.0 : uRate * min(count / ENOUGH, 1.0));
}
