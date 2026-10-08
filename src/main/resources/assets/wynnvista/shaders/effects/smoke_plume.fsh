#version 330 core

// Volumetric smoke column, ray-marched in camera-relative world space and cut off at the nearest terrain.

#include "scene.glsl"

#include "plume_shape.glsl"

uniform vec3 uLightDir;
uniform vec3 uLightColor;
uniform vec3 uAmbient;
uniform float uGlow;

layout(location = 0) out vec4 fragColor;       // premultiplied smoke colour and opacity
layout(location = 1) out float fragDistance;   // terrain distance the march stopped at, for the half-resolution path

const float EXTINCTION = 0.05;
const int COARSE_STEPS = 32;

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
    // What the march had gathered when it reached the cloud layer; negative until then.
    vec4 cloud;
    float cloudAt = cloudDistance(uv, ndc, dir, cloud);
    vec4 front = vec4(-1.0);
    for (int i = 0; i < uSteps; i++) {
        if (t >= t1 || transmittance < 0.03) break;
        if (front.a < 0.0 && t >= cloudAt) front = vec4(colour, 1.0 - transmittance);
        vec3 q = dir * t - uVent;
        float d = density(q, uOctaves);
        if (d > 0.003) {
            if (firstHit < 0.0) firstHit = t;
            float u = q.y / uShape.x;
            // Self-shadowing from one sample towards the light. Deep inside, where little of this sample
            // reaches the eye, the previous sample's shadow is reused.
            if (transmittance > 0.3) shade = exp(-density(q + uLightDir * 36.0, lightOctaves) * 1.6);
            // Pale ash scatters light many times over, so the shaded side stays a light grey-blue rather
            // than going dark: the sun term keeps a floor and the sky fills in the rest.
            vec3 albedo = mix(vec3(0.66, 0.65, 0.64), vec3(0.97, 0.97, 0.98), smoothstep(0.0, 0.4, u));
            vec3 lit = albedo * (ambient * mix(0.85, 1.0, u) * (0.55 + 0.2 * shade)
                    + uLightColor * mix(0.22, 0.6, shade));
            lit += vec3(1.0, 0.34, 0.07) * uGlow * exp(-q.y / 20.0);
            float alpha = 1.0 - exp(-d * EXTINCTION * dt);
            colour += transmittance * alpha * lit;
            transmittance *= 1.0 - alpha;
        }
        t += dt;
    }

    float alpha = 1.0 - transmittance;
    if (alpha < 0.004) return;
    if (front.a < 0.0) front = vec4(colour, alpha);
    // Aerial perspective: distant smoke sinks into the horizon colour like the terrain around it.
    float haze = 1.0 - exp(-max(firstHit, 0.0) * 0.00022);
    // That, the fog that has swallowed the mountain, and any cloud the smoke is behind.
    fragColor = underClouds(vec4(colour, alpha), front, haze, cloud, dir * max(firstHit, 0.0));
}
