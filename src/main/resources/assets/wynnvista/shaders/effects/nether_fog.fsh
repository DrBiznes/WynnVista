#version 330 core

// Glowing lava fog in a flat layer, ray-marched in camera-relative world space and cut off at the nearest
// terrain. The look follows the Nether storm of Complementary Reimagined: stretched noise blown in opposite
// directions per octave and raised to a high power, so it breaks into wisps, thickest just above a floor
// altitude and thinning out upward.

#include "scene.glsl"

#include "nether_fog_shape.glsl"

uniform vec3 uLightColor;
uniform vec3 uAmbient;
uniform float uEmission;

layout(location = 0) out vec4 fragColor;       // premultiplied fog colour and opacity
layout(location = 1) out float fragDistance;   // terrain distance the march stopped at, for the half-resolution path

const float EXTINCTION = 0.03;
const float HAZE = 0.35;                // even fog everywhere in the layer
const float BILLOWS = 1.3;              // soft clouds of thicker fog
const float WISPS = 9.0;                // bright streaks, from the noise raised to a high power
const float NEAR_SPACING = 4.0;         // blocks between the sample planes near the camera, at full quality
const float NEAR_DEPTH = 32.0;          // distance up to which they are that close; beyond it the spacing grows with distance
const float PLANES_MIN = 0.55;          // planes a ray crosses at a shallower angle than this cosine are not used
const int MAX_SAMPLES = 72;             // planes visited; 60 at most along a diagonal through the whole depth
const int MAX_OCTAVES = 3;
const float MAX_DEPTH = 380.0;          // blocks of fog marched; nothing shows through more
const float NEAR_CLEAR = 0.3;           // share of the fog left right at the camera, so a player inside can see
const float NEAR_RANGE = 48.0;          // blocks over which it returns to full
const float MAX_OPACITY = 0.93;         // terrain behind the fog never disappears completely

/** Fog density at q, a position relative to the centre of the layer's floor. */
float density(vec3 q, int octaves) {
    vec3 field = fogField(q, octaves);
    return field.x * (HAZE + BILLOWS * smoothstep(0.3, 0.7, field.y) + WISPS * field.z);
}

/** Columns of brighter fog standing over the lava, leaning with the wisps; 0..1. */
float shafts(vec3 q) {
    float n = noise(vec3(q.x + q.y * 0.35, 0.0, q.z) * (4.0 / NOISE_PERIOD) + vec3(uDrift * 4.0, 0.0, 0.0));
    return n * n;
}

/** How many times the spacing of the sample planes has doubled at a distance; the fraction is the way to the next doubling. */
float coarseness(float t) {
    return min(log2(max(t, NEAR_DEPTH) / NEAR_DEPTH), 6.0);
}

/** One pixel, marched up to sceneDistance(); main() is in effect_main.glsl. */
void effect() {
    vec2 uv = gl_FragCoord.xy / uViewSize;
    vec2 ndc = uv * 2.0 - 1.0;
    vec3 dir;

    // Pixels without fog write zero rather than discarding: the half-resolution path needs their distance.
    fragColor = vec4(0.0);
    fragDistance = beginRay(uv, ndc, dir);
    vec2 span = boxSpan(dir);
    span.y = min(span.y, fragDistance);
    if (span.y <= span.x) return;
    span.y = min(span.y, span.x + MAX_DEPTH);

    // The sky lights the fog by day; at night almost all of its colour is the lava's own glow.
    vec3 ambient = skyLight(uAmbient, uLightColor, dir.y);
    vec3 scattered = vec3(0.48, 0.19, 0.16) * (ambient * 0.5 + sunLight * 0.3);
    int octaves = min(uOctaves, MAX_OCTAVES);
    vec3 colour = vec3(0.0);
    float transmittance = 1.0;
    float firstHit = -1.0;
    // The part of the march in front of the cloud layer.
    vec4 cloud;
    float cloudAt = cloudDistance(uv, ndc, dir, cloud);
    vec4 front = vec4(0.0);

    // The fog has detail finer than the march can afford to step, so where the samples fall shows in the
    // picture. They are taken where the ray crosses planes fixed in the world, a whole number of spacings
    // from the layer's centre along one axis: what a sample finds then stays where it is in the world as the
    // camera moves, where samples spaced from the camera or offset per pixel left a pattern that stayed on
    // the screen while the fog moved behind it. The planes used are those the ray crosses most squarely;
    // towards a diagonal a second set is marched too and the two are weighted, so there is no seam where one
    // set takes over from the other.
    vec3 from = rayOrigin - uOrigin;
    vec3 squarely = abs(dir);
    vec3 share = max(squarely - max(min(squarely.x, min(squarely.y, squarely.z)), PLANES_MIN), 0.0);
    share *= share;
    float shares = share.x + share.y + share.z;
    // Straight along a diagonal of all three axes no set stands out; any will do.
    if (shares < 1.0e-8) {
        share = vec3(1.0, 0.0, 0.0);
        shares = 1.0;
    }
    float fine = NEAR_SPACING * (uSteps >= 32 ? 1.0 : 2.0);
    float begin = fine * float(1 << int(coarseness(span.x)));
    // Per set of planes: its weight, the planes crossed per block of ray, the ray's start across them, the
    // next plane and the distance along the ray to it.
    float weight[2] = float[](0.0, 0.0);
    float rate[2] = float[](1.0, 1.0);
    float base[2] = float[](0.0, 0.0);
    float plane[2] = float[](0.0, 0.0);
    float next[2] = float[](INF, INF);
    int sets = 0;
    for (int axis = 0; axis < 3; axis++) {
        if (share[axis] <= 0.0 || sets == 2) continue;
        weight[sets] = share[axis] / shares;
        rate[sets] = squarely[axis];
        base[sets] = sign(dir[axis]) * from[axis];
        // The first sample is on the last plane before the fog begins: its slice of the ray reaches into it.
        plane[sets] = floor((base[sets] + rate[sets] * span.x) / begin) * begin;
        next[sets] = (plane[sets] - base[sets]) / rate[sets];
        sets++;
    }

    for (int i = 0; i < MAX_SAMPLES; i++) {
        int set = next[1] < next[0] ? 1 : 0;
        float centre = next[set];
        if (centre >= 0.5 * INF || transmittance < 0.03) break;
        // Far away only every second plane is used, then every fourth and so on. The planes in between are
        // not dropped at some distance, where they would pop in as the camera came closer: the slice of the
        // ray each stands for narrows to nothing as the spacing nears its next doubling, and its neighbours'
        // slices widen to make up for it.
        float coarse = coarseness(max(centre, 0.0));
        float spacing = fine * float(1 << int(coarse));
        float number = plane[set] / spacing;
        float fade = fract(coarse);
        float slice = fract(number) != 0.0 ? 0.0 : spacing * (mod(number, 2.0) < 0.5 ? 1.0 + fade : 1.0 - fade);
        plane[set] = (floor(number) + 1.0) * spacing;
        next[set] = (plane[set] - base[set]) / rate[set];
        if (centre - spacing / rate[set] >= span.y) {
            next[set] = INF;
            continue;
        }
        // The slice ends at the terrain and begins where the fog does, and a plane outside them is sampled
        // at the end of its slice inside them: fog in front of terrain then has no steps where a plane
        // passes into the ground.
        float reach = 0.5 * slice / rate[set];
        float dt = min(centre + reach, span.y) - max(centre - reach, span.x);
        if (dt <= 0.0) continue;
        dt *= weight[set];
        float t = clamp(centre, span.x, span.y);
        gather(front, colour, transmittance, t, cloudAt);
        vec3 q = from + dir * t;
        float d = density(q, octaves) * mix(NEAR_CLEAR, 1.0, smoothstep(4.0, NEAR_RANGE, t));
        if (d > 0.003) {
            if (firstHit < 0.0) firstHit = t;
            // Brighter where the wisps are thick.
            vec3 glow = fogGlow(q);
            // Shafts are finer than a pixel far away, where their average is used instead.
            glow *= uOctaves > 2 && transmittance > 0.15 ? 0.6 + 1.3 * shafts(q) : 0.95;
            vec3 lit = glow * uEmission * (0.7 + 0.5 * smoothstep(0.5, 3.0, d)) + scattered;
            float alpha = 1.0 - exp(-d * EXTINCTION * dt);
            colour += transmittance * alpha * lit;
            transmittance *= 1.0 - alpha;
        }
    }

    float alpha = 1.0 - transmittance;
    if (alpha < 0.004) return;
    gather(front, colour, transmittance, INF, cloudAt);
    if (alpha > MAX_OPACITY) {
        front *= MAX_OPACITY / alpha;
        colour *= MAX_OPACITY / alpha;
        alpha = MAX_OPACITY;
    }
    // Aerial perspective: distant fog sinks into the horizon colour like the terrain around it.
    float haze = 1.0 - exp(-max(firstHit, 0.0) * 0.00022);
    // That, a shader pack's fog that has swallowed the canyon, and any cloud the fog is behind.
    fragColor = underClouds(vec4(colour, alpha), front, haze, cloud, rayOrigin + dir * max(firstHit, 0.0));
}

#include "effect_main.glsl"
