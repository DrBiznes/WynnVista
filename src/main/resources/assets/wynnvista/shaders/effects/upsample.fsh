#version 330 core

// Composites an effect that was ray-marched at reduced resolution. Each pixel compares its own terrain
// distance with the distance each low-resolution neighbour was marched to: where they agree the result is
// smoothly interpolated, and at terrain edges the best-matching neighbour is used alone, so smoke neither
// leaks over nearer terrain nor leaves a halo behind it.

#include "scene.glsl"

uniform sampler2D uLowColor;
uniform sampler2D uLowDistance;

out vec4 fragColor;

void main() {
    vec2 uv = gl_FragCoord.xy / uViewSize;
    float here = min(sceneDistance(uv, uv * 2.0 - 1.0), 1.0e5);
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
    fragColor = agree ? blended : best;
    if (fragColor.a < 0.002) discard;
}
