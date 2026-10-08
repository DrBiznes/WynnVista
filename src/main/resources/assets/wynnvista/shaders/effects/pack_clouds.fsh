#version 330 core

// Turns a shader pack's own cloud buffers (PackClouds) into the cloud layer world effects are drawn behind:
// the distance to the pack's cloud on each pixel, and how much it hides.

uniform sampler2D uPackFirst;
uniform sampler2D uPackSecond;
uniform int uKind;            // 0: Photon, 1: BSL, 2: Complementary
uniform vec2 uPackScale;      // x: share of the pack's buffers that covers the view, y: blocks per unit of stored distance
uniform vec2 uViewSize;

out vec4 fragColor;           // r: distance to the cloud in blocks, a: its opacity, 0 where there is none

// BSL and Complementary only record where a cloud is, not how thick. A recorded cloud hides this much.
const float SOLID = 0.85;
const float NONE = 0.999;

void main() {
    vec2 uv = gl_FragCoord.xy / uViewSize * uPackScale.x;
    float opacity = 0.0;
    float reach = 0.0;
    if (uKind == 0) {
        opacity = 1.0 - clamp(textureLod(uPackFirst, uv, 0.0).a, 0.0, 1.0);
        // The nearest of the four texels around the pixel, as the pack itself reads it.
        ivec2 size = textureSize(uPackSecond, 0);
        ivec2 base = ivec2(floor(uv * vec2(size) - 0.5));
        reach = 1.0e6;
        for (int i = 0; i < 4; i++) {
            ivec2 texel = clamp(base + ivec2(i & 1, i >> 1), ivec2(0), size - 1);
            reach = min(reach, texelFetch(uPackSecond, texel, 0).x);
        }
        // A buffer the pack never drew clouds into holds zeroes, not "no cloud".
        if (reach <= 0.0 || reach > 1.0e5) opacity = 0.0;
    } else {
        // A yes-or-no cloud per pixel: its share of a small neighbourhood softens the edge.
        ivec2 size = textureSize(uPackFirst, 0);
        ivec2 centre = ivec2(uv * vec2(size));
        float covered = 0.0;
        float nearest = 1.0;
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                ivec2 texel = clamp(centre + ivec2(x, y) * 2, ivec2(0), size - 1);
                // Complementary keeps an unrelated value in the last pixel of this buffer.
                if (uKind == 2 && texel == size - 1) continue;
                vec4 stored = texelFetch(uPackFirst, texel, 0);
                float value = uKind == 1 ? stored.r : stored.a;
                if (value < NONE) {
                    covered += 1.0;
                    nearest = min(nearest, value);
                }
            }
        }
        opacity = covered / 9.0 * SOLID;
        reach = (uKind == 1 ? nearest : nearest * nearest) * uPackScale.y;
    }
    fragColor = vec4(reach, 0.0, 0.0, opacity);
}
