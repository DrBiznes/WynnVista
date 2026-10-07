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
const int MAX_STEPS = 40;               // the fog is smooth; more samples than this do not show
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
            // Brighter where the wisps are thick.
            vec3 glow = fogGlow(q);
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
