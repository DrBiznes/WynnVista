#version 330 core

// Composites an effect that was ray-marched at reduced resolution. Each pixel compares its own terrain
// distance with the distance each low-resolution neighbour was marched to: where they agree the result is
// smoothly interpolated, and at terrain edges the best-matching neighbour is used alone, so smoke neither
// leaks over nearer terrain nor leaves a halo behind it.

#include "scene.glsl"

uniform sampler2D uLowColor;
uniform sampler2D uLowDistance;

out vec4 fragColor;

/** The effect on this pixel as the low-resolution marches around it have it, for terrain `here` blocks away. */
vec4 marched(vec2 uv, float here) {
    ivec2 size = textureSize(uLowColor, 0);
    vec2 p = uv * vec2(size) - 0.5;
    ivec2 base = ivec2(floor(p));
    vec2 f = fract(p);

    vec4 blended = vec4(0.0);
    vec4 best = vec4(0.0);
    float bestDifference = INF;
    bool agree = true;
    for (int i = 0; i < 4; i++) {
        ivec2 o = ivec2(i & 1, i >> 1);
        ivec2 texel = clamp(base + o, ivec2(0), size - 1);
        vec4 colour = texelFetch(uLowColor, texel, 0);
        float difference = abs(min(texelFetch(uLowDistance, texel, 0).r, 1.0e5) - here);
        if (difference < bestDifference) {
            bestDifference = difference;
            best = colour;
        }
        if (difference > 0.08 * here + 1.0) agree = false;
        blended += colour * (o.x == 0 ? 1.0 - f.x : f.x) * (o.y == 0 ? 1.0 - f.y : f.y);
    }
    return agree ? blended : best;
}

void main() {
    vec2 uv = gl_FragCoord.xy / uViewSize;
    vec2 ndc = uv * 2.0 - 1.0;
    float near;
    float far;
    float nearShare;
    // Along a terrain edge that a shader pack resolves over frames, each of the two surfaces on the pixel
    // takes the marches that match it, mixed by their shares of the pixel (sceneEdge() in scene.glsl).
    if (uEdges < 0.5) {
        fragColor = marched(uv, min(sceneDistance(uv, ndc), 1.0e5));
    } else if (sceneEdge(uv, ndc, near, far, nearShare)) {
        fragColor = mix(marched(uv, min(far, 1.0e5)), marched(uv, min(near, 1.0e5)), nearShare);
    } else {
        fragColor = marched(uv, min(near, 1.0e5));
    }
    if (fragColor.a < 0.002) discard;
}
