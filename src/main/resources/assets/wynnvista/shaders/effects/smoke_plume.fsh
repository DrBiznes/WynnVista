#version 330 core

// Volumetric smoke column, ray-marched in camera-relative world space and cut off at the nearest terrain.

#include "scene.glsl"

uniform vec3 uVent;           // crater vent, camera-relative
uniform vec3 uShape;          // x: column height, y: radius at the vent, z: radius at the top
uniform vec2 uDrift;          // how far the top has blown downwind
uniform float uScroll;        // blocks the noise pattern has risen
uniform vec3 uLightDir;
uniform vec3 uLightColor;
uniform vec3 uAmbient;
uniform float uGlow;

layout(location = 0) out vec4 fragColor;       // premultiplied smoke colour and opacity
layout(location = 1) out float fragDistance;   // terrain distance the march stopped at, for the half-resolution path

const float NOISE_PERIOD = 1280.0;      // blocks per noise texture repeat at the base octave
const float EXTINCTION = 0.05;
const int COARSE_STEPS = 32;

/**
 * Position inside the bent, widening column: x = distance from the axis as a fraction of the radius,
 * y = height as a fraction of the column, zw = offset from the axis. x >= 1 means outside.
 */
vec4 column(vec3 q) {
    float u = q.y / uShape.x;
    if (u <= 0.0 || u >= 1.0) return vec4(2.0, u, 0.0, 0.0);
    vec2 off = q.xz - uDrift * u * u;
    return vec4(length(off) / mix(uShape.y, uShape.z, pow(u, 0.85)), u, off);
}

/** Smoke density at q, a position relative to the vent. */
float density(vec3 q, int octaves) {
    vec4 c = column(q);
    float r = c.x;
    float u = c.y;
    if (r >= 1.0) return 0.0;

    // Smoke survives where the noise beats a threshold that rises towards the edge and with height,
    // so the outline is lumpy and the top breaks into drifting puffs instead of ending in a cap.
    float threshold = r * r * 0.62 + u * 0.16 + smoothstep(0.55, 1.0, u) * 0.5 - 0.02;
    if (threshold >= 1.0) return 0.0;

    // The pattern lives in column-local space and scrolls upward, so billows follow the bend of the column.
    // Billows are small in the narrow throat and grow with the column, as eddies do in a real plume.
    vec3 s = vec3(c.z, q.y - uScroll, c.w) / NOISE_PERIOD;
    float wide = smoothstep(0.04, 0.4, u);
    float n = 0.0;
    if (wide < 1.0) n += (1.0 - wide) * fbm(s * 4.0, octaves);
    if (wide > 0.0) n += wide * fbm(s + vec3(0.5), octaves);

    float d = smoothstep(0.0, 0.26, n - threshold);
    return d * smoothstep(0.0, 0.025, u) * mix(1.0, 0.4, u);
}

void main() {
    vec2 uv = gl_FragCoord.xy / uViewSize;
    vec2 ndc = uv * 2.0 - 1.0;
    vec3 dir = viewRay(ndc);

    // Pixels without smoke write zero rather than discarding: the half-resolution path needs their distance.
    fragColor = vec4(0.0);
    fragDistance = sceneDistance(uv, ndc);
    vec2 span = boxSpan(dir);
    span.y = min(span.y, fragDistance);
    if (span.y <= span.x) return;

    // Cheap pass without noise: find where the ray is actually inside the column, so pixels that only
    // cross the bounding box cost almost nothing and every expensive sample lands where smoke can be.
    float coarse = (span.y - span.x) / float(COARSE_STEPS);
    float enter = -1.0;
    float leave = -1.0;
    for (int i = 0; i < COARSE_STEPS; i++) {
        float t = span.x + (float(i) + 0.5) * coarse;
        if (column(dir * t - uVent).x < 1.0) {
            if (enter < 0.0) enter = t;
            leave = t;
        }
    }
    if (enter < 0.0) return;
    float t0 = max(span.x, enter - coarse);
    float t1 = min(span.y, leave + coarse);

    // The sky around the plume lights it as much as the sun does, which also carries sunset and weather colours.
    vec3 ambient = mix(uAmbient, uFogColor, 0.45);
    int lightOctaves = min(uOctaves, 2);
    float dt = max((t1 - t0) / float(uSteps), 1.5);
    float t = t0 + dt * dither(gl_FragCoord.xy);
    vec3 colour = vec3(0.0);
    float transmittance = 1.0;
    float firstHit = -1.0;
    float shade = 1.0;
    for (int i = 0; i < uSteps; i++) {
        if (t >= t1 || transmittance < 0.03) break;
        vec3 q = dir * t - uVent;
        float d = density(q, uOctaves);
        if (d > 0.003) {
            if (firstHit < 0.0) firstHit = t;
            float u = q.y / uShape.x;
            // Self-shadowing from one sample towards the light. Deep inside, where little of this sample
            // reaches the eye, the previous sample's shadow is reused.
            if (transmittance > 0.3) shade = exp(-density(q + uLightDir * 36.0, lightOctaves) * 2.4);
            vec3 albedo = mix(vec3(0.30, 0.28, 0.27), vec3(0.80, 0.80, 0.82), smoothstep(0.0, 0.55, u));
            vec3 lit = albedo * (ambient * mix(0.7, 1.0, u) * (0.6 + 0.4 * shade) + uLightColor * shade);
            lit += vec3(1.0, 0.34, 0.07) * uGlow * exp(-q.y / 20.0);
            float alpha = 1.0 - exp(-d * EXTINCTION * dt);
            colour += transmittance * alpha * lit;
            transmittance *= 1.0 - alpha;
        }
        t += dt;
    }

    float alpha = 1.0 - transmittance;
    if (alpha < 0.004) return;
    // Aerial perspective: distant smoke sinks into the horizon colour like the terrain around it.
    float haze = 1.0 - exp(-max(firstHit, 0.0) * 0.00022);
    colour = mix(colour, uFogColor * alpha, haze);
    fragColor = vec4(colour, alpha);
}
