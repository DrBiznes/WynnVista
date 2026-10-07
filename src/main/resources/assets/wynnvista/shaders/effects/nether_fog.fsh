#version 330 core

// Glowing lava fog in a flat layer, ray-marched in camera-relative world space and cut off at the nearest
// terrain. The look follows the Nether storm of Complementary Reimagined: stretched noise blown in opposite
// directions per octave and raised to a high power, so it breaks into wisps, thickest just above a floor
// altitude and thinning out upward.

#include "scene.glsl"

uniform vec3 uOrigin;         // centre of the layer's floor, camera-relative
uniform vec2 uExtent;         // radii along x and z of the ellipse where the fog ends
uniform vec2 uCore;           // radii of the ellipse inside which it has its full density and height
uniform float uThickness;
uniform vec3 uPortal;         // xy: the Nether portal's x and z from the layer's centre, z: reach of its glow
uniform float uDrift;         // phase of the drifting noise, 0..1
uniform vec3 uLightColor;
uniform vec3 uAmbient;
uniform float uEmission;

layout(location = 0) out vec4 fragColor;       // premultiplied fog colour and opacity
layout(location = 1) out float fragDistance;   // terrain distance the march stopped at, for the half-resolution path

const float NOISE_PERIOD = 640.0;       // blocks per noise texture repeat at the base octave
const float EXTINCTION = 0.03;
const float HAZE = 0.35;                // even fog everywhere in the layer
const float BILLOWS = 1.3;              // soft clouds of thicker fog
const float WISPS = 9.0;                // bright streaks, from the noise raised to a high power
const float RISE = 10.0;                // blocks over which the underside thickens
const float UNEVEN = 14.0;              // how far the underside is lifted above the floor in places
const int MAX_STEPS = 40;               // the fog is smooth; more samples than this do not show
const int MAX_OCTAVES = 3;
const float MAX_DEPTH = 380.0;          // blocks of fog marched; nothing shows through more
const float NEAR_CLEAR = 0.3;           // share of the fog left right at the camera, so a player inside can see
const float NEAR_RANGE = 48.0;          // blocks over which it returns to full
const float MAX_OPACITY = 0.93;         // terrain behind the fog never disappears completely

/** Fog density at q, a position relative to the centre of the layer's floor. */
float density(vec3 q, int octaves) {
    if (q.y <= 0.0 || q.y >= uThickness) return 0.0;
    float outer = length(q.xz / uExtent);
    if (outer >= 1.0) return 0.0;

    // Wisps are stretched vertically and lean along x.
    vec3 p = vec3(q.x, (q.y + q.x * 0.35) * 0.5, q.z) / NOISE_PERIOD;
    vec3 wind = vec3(uDrift);
    float first = 0.0;
    float soft = 0.0;
    float sharp = 0.0;
    float weight = 0.5;
    float total = 0.0;
    for (int i = 0; i < octaves; i++) {
        float n = noise(p + wind);
        if (i == 0) first = n;
        soft += weight * n;
        total += weight;
        weight *= 0.5;
        n *= n;
        n *= n;
        sharp += n * n;
        p *= 2.0;
        wind *= -2.0;
    }

    // Outside the core the fog disperses: 0 at the core's rim, 1 where it ends. It thins slowly at first and
    // its top sinks, so from outside it is a haze that gathers towards the middle, not a wall. The coarsest
    // noise shifts the fade and lifts the underside in places (never below the floor).
    float core = length(q.xz / uCore);
    float away = core <= 1.0 ? 0.0 : (core - 1.0) / (core - outer);
    float edge = 1.0 - smoothstep(0.0, 1.0, away + (first - 0.5) * 0.7 * min(away * 4.0, 1.0));
    float top = uThickness * mix(0.4, 1.0, edge);
    float rise = smoothstep(0.0, RISE, q.y - first * UNEVEN);
    float fall = max(1.0 - q.y / top, 0.0);
    float shape = edge * edge * edge * rise * fall * fall;
    return shape * (HAZE + BILLOWS * smoothstep(0.3, 0.7, soft / total) + WISPS * sharp / float(octaves));
}

/** Columns of brighter fog standing over the lava, leaning with the wisps; 0..1. */
float shafts(vec3 q) {
    float n = noise(vec3(q.x + q.y * 0.35, 0.0, q.z) * (4.0 / NOISE_PERIOD) + vec3(uDrift * 4.0, 0.0, 0.0));
    return n * n;
}

void main() {
    vec2 uv = gl_FragCoord.xy / uViewSize;
    vec2 ndc = uv * 2.0 - 1.0;
    vec3 dir = viewRay(ndc);

    // Pixels without fog write zero rather than discarding: the half-resolution path needs their distance.
    fragColor = vec4(0.0);
    fragDistance = sceneDistance(uv, ndc);
    vec2 span = boxSpan(dir);
    span.y = min(span.y, fragDistance);
    if (span.y <= span.x) return;
    span.y = min(span.y, span.x + MAX_DEPTH);

    // The sky lights the fog by day; at night almost all of its colour is the lava's own glow.
    vec3 ambient = mix(uAmbient, uFogColor, 0.45);
    vec3 scattered = vec3(0.48, 0.19, 0.16) * (ambient * 0.5 + uLightColor * 0.3);
    int steps = min(uSteps, MAX_STEPS);
    int octaves = min(uOctaves, MAX_OCTAVES);
    float dt = max((span.y - span.x) / float(steps), 1.0);
    float t = span.x + dt * dither(gl_FragCoord.xy);
    vec3 colour = vec3(0.0);
    float transmittance = 1.0;
    float firstHit = -1.0;
    for (int i = 0; i < steps; i++) {
        if (t >= span.y || transmittance < 0.03) break;
        vec3 q = dir * t - uOrigin;
        float d = density(q, octaves) * mix(NEAR_CLEAR, 1.0, smoothstep(4.0, NEAR_RANGE, t));
        if (d > 0.003) {
            if (firstHit < 0.0) firstHit = t;
            // Ember red near the lava, a dark blood red higher up, and brighter where the wisps are thick.
            vec3 glow = mix(vec3(0.85, 0.24, 0.06), vec3(0.45, 0.05, 0.03), smoothstep(0.0, 0.9, q.y / uThickness));
            // Around the portal the fog glows with its purple instead, strongest low down.
            float portal = 1.0 - min(length(vec3(q.x - uPortal.x, q.y * 0.6, q.z - uPortal.y)) / uPortal.z, 1.0);
            glow = mix(glow, vec3(0.60, 0.16, 0.95), portal * portal * (3.0 - 2.0 * portal));
            // Shafts are finer than a pixel far away, where their average is used instead.
            glow *= uOctaves > 2 && transmittance > 0.15 ? 0.6 + 1.3 * shafts(q) : 0.95;
            vec3 lit = glow * uEmission * (0.7 + 0.5 * smoothstep(0.5, 3.0, d)) + scattered;
            float alpha = 1.0 - exp(-d * EXTINCTION * dt);
            colour += transmittance * alpha * lit;
            transmittance *= 1.0 - alpha;
        }
        t += dt;
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
